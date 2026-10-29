package com.routegenie.ui.design

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PlatformLeaks
import com.routegenie.ui.EndpointDetailsPanel
import com.routegenie.ui.EndpointJTree
import com.routegenie.ui.EndpointTreeModel
import com.routegenie.ui.EndpointTreeRenderer
import com.routegenie.ui.model.EndpointIndex
import com.routegenie.ui.model.EndpointTree
import com.routegenie.ui.model.GroupMode
import org.junit.Assert.fail
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import javax.swing.JComponent

/**
 * Leak and speed gates of the design system. Leaks: components created below a disposable are collectable after it is disposed,
 * and a loop of create and dispose does not grow the heap. Speed: painting a window over a 50,000 row model and swapping that
 * model in stay within the 16 ms render budget, and painting a method badge allocates (almost) nothing.
 */
class DesignLeakAndSpeedTest : BasePlatformTestCase() {
    private fun buildAll(parent: Disposable): List<JComponent> = listOf(
        EmptyState(RouteGenieIcons.EMPTY_ROUTES, "title", "hint"),
        LoadingState(parent, "scanning"),
        LoadingRow(parent, "scanning"),
        SkeletonRows(),
        SectionHeader("header", Chip("1")),
        KeyValueGrid().also { it.addRow("label") },
        StatusPill(200, "OK"),
        Chip("tag", Palette.warning),
        Divider.horizontal(),
        EndpointDetailsPanel(project),
    )

    fun testComponentsAreCollectedAfterTheirDisposableIsDisposed() {
        val refs = ArrayList<Pair<String, WeakReference<JComponent>>>()
        val parent = Disposer.newDisposable("design leak")
        buildAll(parent).forEach { refs += it.javaClass.simpleName to WeakReference(it) }
        Disposer.dispose(parent)
        // The test thread is the EDT: queued invokeLater work (an animated icon stopping itself) holds components until it runs.
        UIUtil.dispatchAllInvocationEvents()
        refs.forEach { (name, ref) -> LeakProbe.assertCollected("design component $name", ref) }
    }

    fun testNoDisposedDesignComponentStaysReachableFromTheIdeRoots() {
        val parent = Disposer.newDisposable("design leak hunter")
        buildAll(parent)
        Disposer.dispose(parent)
        UIUtil.dispatchAllInvocationEvents()
        PlatformLeaks.assertNoneReachable("disposed LoadingRow", LoadingRow::class.java)
        PlatformLeaks.assertNoneReachable("disposed LoadingState", LoadingState::class.java)
    }

    fun testCreateAndDisposeInALoopDoesNotGrowTheHeap() {
        LeakProbe.assertNoHeapGrowth("design components", maxGrowthBytes = HEAP_GROWTH_LIMIT, warmups = 30, iterations = 200) {
            val parent = Disposer.newDisposable("design loop")
            buildAll(parent)
            Disposer.dispose(parent)
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    private fun endpoints(count: Int): List<Endpoint> = (0 until count).map { i ->
        val method = HttpMethod.entries[i % HttpMethod.entries.size]
        Endpoint(
            method, "/svc${i % 97}/resource$i/{id}", Framework.FASTAPI, Handler("handler$i", "app.m${i % 50}.handler$i"),
            SourceRef("app/m${i % 50}.py", i, i % 400 + 1), tags = if (i % 3 == 0) listOf("tag${i % 7}", "other") else emptyList(), deprecated = i % 11 == 0,
        )
    }

    fun testPaintingAWindowOverFiftyThousandRowsStaysWithinTheRenderBudget() {
        val index = EndpointIndex.build(endpoints(50_000))
        val tree = EndpointTree.build(index.endpoints, GroupMode.NONE)
        assertEquals(50_000, tree.count)
        val jtree = EndpointJTree().apply {
            isRootVisible = false
            cellRenderer = EndpointTreeRenderer()
        }
        val swap = PerfBudget.measure("design-tree-model-swap-50k", Limits.BUDGET_FILTER_KEYSTROKE_MS) {
            jtree.model = EndpointTreeModel(tree.root)
        }
        if (swap.medianMs > swap.budgetMs) {
            val hot = hotFrames { jtree.model = EndpointTreeModel(tree.root) }
            fail("design-tree-model-swap-50k: median ${swap.medianMs} ms exceeds ${swap.budgetMs} ms. Where the time goes:\n$hot")
        }
        val scroll = JBScrollPane(jtree)
        scroll.setBounds(0, 0, 640, 800)
        Snapshots.layoutAll(scroll)
        Snapshots.layoutAll(scroll)
        jtree.setSelectionRow(10)
        val image = BufferedImage(640, 800, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        // Paint only: the image and the layout are test scaffolding, not window cost.
        val result = PerfBudget.measure("design-tree-paint-50k", Limits.BUDGET_FILTER_KEYSTROKE_MS, warmups = PAINT_WARMUPS, runs = PAINT_RUNS) {
            scroll.paint(g)
        }
        // The same tree with a renderer that only appends text is what the platform costs without the design system.
        jtree.cellRenderer = PlainRenderer()
        val plain = PerfBudget.measure("design-tree-paint-50k-plain-renderer", Limits.BUDGET_FILTER_KEYSTROKE_MS, warmups = PAINT_WARMUPS, runs = PAINT_RUNS) {
            scroll.paint(g)
        }
        jtree.cellRenderer = EndpointTreeRenderer()
        println("design-tree-paint-50k median ${result.medianMs} ms, plain text renderer ${plain.medianMs} ms (budget ${Limits.BUDGET_FILTER_KEYSTROKE_MS} ms)")
        if (result.medianMs > result.budgetMs) {
            val hot = hotFrames { repeat(PAINT_RUNS) { scroll.paint(g) } }
            fail("design-tree-paint-50k: median ${result.medianMs} ms exceeds ${result.budgetMs} ms (plain renderer ${plain.medianMs} ms). Where the time goes:\n$hot")
        }
        g.dispose()
    }

    /** Only the text of an endpoint, the cheapest renderer a tree of this kind can have. */
    private class PlainRenderer : com.intellij.ui.ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as? com.routegenie.ui.model.Leaf ?: return
            append(node.endpoint.method.name)
            append(node.endpoint.path)
        }
    }

    /** Samples the calling thread's stack while [block] runs and lists the most frequent frames; used only to explain a failure. */
    private fun hotFrames(block: () -> Unit): String {
        val target = Thread.currentThread()
        val counts = HashMap<String, Int>()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                target.stackTrace.take(HOT_DEPTH).forEach { counts.merge("${it.className}.${it.methodName}", 1, Int::plus) }
                Thread.sleep(1)
            }
        }
        sampler.start()
        repeat(HOT_RUNS) { block() }
        running.set(false)
        sampler.join()
        return counts.entries.sortedByDescending { it.value }.take(HOT_LINES).joinToString("\n") { "${it.value} ${it.key}" }
    }

    /**
     * The same Java2D calls without any design code (plain color, plain font, no cache lookups) are the floor: the JDK itself
     * allocates for rounded fills and text. The badge may add at most [ALLOWED_EXTRA_BYTES] on top of that.
     */
    fun testPaintingABadgeAllocatesNoMoreThanThePlainJdkCalls() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val image = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        val icons = HttpMethod.entries.flatMap { m -> BadgeState.entries.map { MethodBadge.of(m, it) } }
        val plainFont = DesignTokens.Type.badge()
        val plainColor = java.awt.Color.GRAY
        val labels = HttpMethod.entries.map { it.name }
        val thread = Thread.currentThread().id
        val calls = 30_000

        fun measure(block: (Int) -> Unit): Double {
            repeat(calls) { block(it) }
            val before = bean.getThreadAllocatedBytes(thread)
            repeat(calls) { block(it) }
            return (bean.getThreadAllocatedBytes(thread) - before).toDouble() / calls
        }

        val badge = measure { icons[it % icons.size].paintIcon(null, g, 4, 4) }
        val floor = measure {
            val oldAa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING)
            val oldFont = g.font
            val oldColor = g.color
            val oldComposite = g.composite
            val oldStroke = g.stroke
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = plainColor
            g.composite = DesignTokens.State.TINT
            g.fillRoundRect(4, 4, 54, 16, 4, 4)
            g.composite = oldComposite
            g.font = plainFont
            g.color = plainColor
            val fm = g.fontMetrics
            val label = labels[it % labels.size]
            g.drawString(label, 4 + (54 - fm.stringWidth(label)) / 2, 16)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, oldAa)
            g.font = oldFont
            g.color = oldColor
            g.composite = oldComposite
            g.stroke = oldStroke
        }
        g.dispose()
        File(Snapshots.dir, "badge-allocation.txt").writeText("bytes per badge paint: $badge, plain JDK calls: $floor, allowed extra: $ALLOWED_EXTRA_BYTES", Charsets.US_ASCII)
        assertTrue("a badge paint allocated $badge bytes per call, the plain JDK calls $floor", badge <= floor + ALLOWED_EXTRA_BYTES)
    }

    private companion object {
        const val PAINT_WARMUPS = 40
        const val PAINT_RUNS = 21
        const val HOT_DEPTH = 30
        const val HOT_RUNS = 3
        const val HOT_LINES = 20
        const val HEAP_GROWTH_LIMIT = 2L * 1024 * 1024

        /** Fonts, colors, strokes and composites are cached; a small allowance covers the boxed rendering hint reads and metrics lookups. */
        const val ALLOWED_EXTRA_BYTES = 48
    }
}
