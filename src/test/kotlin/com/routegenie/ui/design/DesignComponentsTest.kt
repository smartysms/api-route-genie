package com.routegenie.ui.design

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.routegenie.model.HttpMethod
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import javax.swing.JComponent

/** Every reusable component constructs, measures and paints in both modes, and says something different for different input. */
class DesignComponentsTest : BasePlatformTestCase() {
    private val originalDark = !JBColor.isBright()
    private val owner = Disposer.newDisposable("design components")
    private var iconsWereOn = true

    override fun setUp() {
        super.setUp()
        // The platform starts a JVM without a display (every CI runner) with icons switched off, and then every icon reports the 1x1
        // placeholder instead of its image. The IDE switches them on at startup, so a test that looks at icons has to do the same.
        iconsWereOn = !GraphicsEnvironment.isHeadless()
        IconLoader.activate()
    }

    override fun tearDown() {
        try {
            if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
            JBColor.setDark(originalDark)
            if (!iconsWereOn) IconLoader.deactivate()
        } finally {
            super.tearDown()
        }
    }

    private fun paint(component: JComponent, w: Int, h: Int, scale: Double = 1.0): BufferedImage = Snapshots.render(component, w, h, scale)

    private fun forBothModes(block: (Boolean) -> Unit) {
        for (dark in listOf(false, true)) {
            JBColor.setDark(dark)
            block(dark)
        }
    }

    fun testMethodBadgesPaintForEveryVerbAndState() {
        forBothModes { dark ->
            for (method in HttpMethod.entries) for (state in BadgeState.entries) {
                val icon = MethodBadge.of(method, state)
                assertTrue(icon.iconWidth > icon.iconHeight)
                val holder = JBLabel(icon)
                val image = paint(holder, icon.iconWidth + 8, icon.iconHeight + 8)
                assertTrue("$method $state dark=$dark painted nothing", Snapshots.inkPixels(image) > 20)
            }
        }
    }

    fun testBadgesAreSharedInstancesSoRowsAllocateNothing() {
        assertSame(MethodBadge.of(HttpMethod.GET), MethodBadge.of(HttpMethod.GET))
        assertNotSame(MethodBadge.of(HttpMethod.GET), MethodBadge.of(HttpMethod.POST))
        assertNotSame(MethodBadge.of(HttpMethod.GET), MethodBadge.of(HttpMethod.GET, BadgeState.SELECTED))
    }

    fun testAnyIsAnOutlineAndTheOthersAreFilled() {
        JBColor.setDark(false)
        fun ink(method: HttpMethod): Int {
            val icon = MethodBadge.of(method)
            return Snapshots.inkPixels(paint(JBLabel(icon), icon.iconWidth + 8, icon.iconHeight + 8))
        }
        assertTrue("a tinted badge covers more pixels than an outline", ink(HttpMethod.GET) > ink(HttpMethod.ANY))
    }

    fun testStatusPillTextsAndToneByClass() {
        forBothModes {
            val pills = listOf(100 to "Continue", 200 to "OK", 302 to "Found", 404 to "Not Found", 503 to "Unavailable")
            val sizes = pills.map { (code, text) ->
                val pill = StatusPill(code, text)
                val size = pill.preferredSize
                assertTrue(size.width > size.height)
                assertTrue(Snapshots.inkPixels(paint(pill, size.width + 4, size.height + 4)) > 30)
                size.width
            }
            assertTrue("longer text must make a wider pill", sizes[4] > sizes[1])
        }
        val pill = StatusPill(200, "OK")
        val before = pill.preferredSize.width
        pill.setStatus(200, "A much longer reason phrase")
        assertTrue(pill.preferredSize.width > before)
    }

    fun testChipSizeGrowsWithTextAndNeutralDiffersFromTone() {
        JBColor.setDark(false)
        val short = Chip("a")
        val long = Chip("a long tag")
        assertTrue(long.preferredSize.width > short.preferredSize.width)
        assertEquals(short.preferredSize.height, long.preferredSize.height)
        val neutral = paint(Chip("tag"), 80, 24)
        val tinted = paint(Chip("tag", Palette.danger), 80, 24)
        var differing = 0
        for (y in 0 until neutral.height) for (x in 0 until neutral.width) if (neutral.getRGB(x, y) != tinted.getRGB(x, y)) differing++
        assertTrue("a toned chip must look different from a neutral one", differing > 50)
    }

    fun testKeyValueGridSkipsHiddenRowsAndAlignsTheValueColumn() {
        val grid = KeyValueGrid()
        val first = grid.addRow("Handler")
        val second = grid.addRow("A much longer label")
        (first.value as JBLabel).text = "one"
        (second.value as JBLabel).text = "two"
        val both = grid.preferredSize.height
        second.isVisible = false
        assertTrue("a hidden row must not take space", grid.preferredSize.height < both)
        second.isVisible = true
        paint(grid, 400, both + 10)
        assertEquals("values start in one column", first.value.x, second.value.x)
        assertTrue(first.value.x >= second.label.width)
    }

    fun testSectionHeaderAndDividerHaveSaneGeometry() {
        val header = SectionHeader("Title", Chip("3"))
        assertEquals("Title", header.title)
        header.title = "Other"
        assertEquals("Other", header.title)
        assertTrue(header.preferredSize.height > 0)
        val horizontal = Divider.horizontal()
        val vertical = Divider.vertical()
        assertTrue(horizontal.preferredSize.height >= 1 && horizontal.preferredSize.width == 0)
        assertTrue(vertical.preferredSize.width >= 1 && vertical.preferredSize.height == 0)
        assertTrue(Snapshots.inkPixels(paint(header, 300, header.preferredSize.height)) > 20)
    }

    fun testEmptyAndLoadingStatesPaintAndDispose() {
        forBothModes {
            val empty = EmptyState(RouteGenieIcons.EMPTY_ROUTES, "Nothing here", "A hint that is long enough to need a second line so wrapping is exercised properly.")
            assertTrue(Snapshots.inkPixels(paint(empty, 420, 260)) > 200)
            val loading = LoadingState(owner, "Scanning")
            assertTrue(Snapshots.inkPixels(paint(loading, 420, 260)) > 200)
        }
        assertFalse(Disposer.isDisposed(owner))
        Disposer.dispose(owner)
        assertTrue(Disposer.isDisposed(owner))
    }

    fun testRowPainterFillsOnlyStatesThatHaveABackground() {
        assertNull(RowPainter.background(RowState.NORMAL))
        for (state in listOf(RowState.HOVER, RowState.SELECTED, RowState.SELECTED_INACTIVE)) assertNotNull(RowPainter.background(state))
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        RowPainter.paint(g, 0, 0, 40, 20, RowState.NORMAL)
        g.dispose()
        assertEquals("a normal row paints nothing", 0, image.getRGB(20, 10))
    }

    fun testEveryDesignIconHasADarkVariantWithTheSameGeometry() {
        val loader = RouteGenieIcons::class.java
        val color = Regex("#[0-9A-Fa-f]{6}")
        for (name in RouteGenieIcons.DESIGN_FILE_NAMES) {
            val light = loader.getResource("${RouteGenieIcons.DESIGN_DIR}$name.svg")
            val dark = loader.getResource("${RouteGenieIcons.DESIGN_DIR}${name}_dark.svg")
            assertNotNull("$name.svg", light)
            assertNotNull("${name}_dark.svg", dark)
            val lightText = light!!.readText()
            val darkText = dark!!.readText()
            assertEquals("$name: light and dark must differ only in color", lightText.replace(color, "#"), darkText.replace(color, "#"))
            assertTrue("$name: dark variant must change at least one color", lightText != darkText)
        }
        assertNotNull(loader.getResource("/icons/routeGenie_dark.svg"))
    }

    /** The size the SVG at [path] declares in its root element (`<svg width="32" height="32" ...>`). */
    private fun declaredSize(path: String): Pair<Int, Int> {
        val url = RouteGenieIcons::class.java.getResource(path)
        assertNotNull("$path is missing", url)
        val root = Regex("<svg\\b[^>]*>").find(url!!.readText())?.value
        assertNotNull("$path has no <svg> element", root)
        fun attribute(name: String): Int {
            val value = Regex("\\s$name=\"(\\d+)\"").find(root!!)?.groupValues?.get(1)
            assertNotNull("$path does not declare a $name", value)
            return value!!.toInt()
        }
        return attribute("width") to attribute("height")
    }

    fun testIconsLoadWithTheSizeTheirFilesDeclare() {
        val design = mapOf(
            "send" to RouteGenieIcons.SEND, "stop" to RouteGenieIcons.STOP, "copy" to RouteGenieIcons.COPY, "curl" to RouteGenieIcons.CURL,
            "history" to RouteGenieIcons.HISTORY, "stream" to RouteGenieIcons.STREAM, "clear" to RouteGenieIcons.CLEAR,
            "environment" to RouteGenieIcons.ENVIRONMENT, "auth" to RouteGenieIcons.AUTH, "panelHide" to RouteGenieIcons.PANEL_HIDE,
            "splitOrientation" to RouteGenieIcons.SPLIT_ORIENTATION, "prettyPrint" to RouteGenieIcons.PRETTY_PRINT, "wrap" to RouteGenieIcons.WRAP,
            "search" to RouteGenieIcons.SEARCH, "emptyRoutes" to RouteGenieIcons.EMPTY_ROUTES, "emptySearch" to RouteGenieIcons.EMPTY_SEARCH,
            "emptyProject" to RouteGenieIcons.EMPTY_PROJECT,
        )
        assertEquals("a design icon was added without being listed here", RouteGenieIcons.DESIGN_FILE_NAMES.toSet(), design.keys)
        val all = design.mapKeys { "${RouteGenieIcons.DESIGN_DIR}${it.key}.svg" } +
            mapOf("/icons/routeGenie.svg" to RouteGenieIcons.PLUGIN, "/icons/routeGenieGutter.svg" to RouteGenieIcons.GUTTER)
        // Each icon, as the platform loads it, is as large as its own file says. An icon that did not load at all reports 1 x 1.
        all.forEach { (path, icon) ->
            val (width, height) = declaredSize(path)
            assertEquals("$path width", width, icon.iconWidth)
            assertEquals("$path height", height, icon.iconHeight)
            assertEquals("$path is not square", icon.iconWidth, icon.iconHeight)
        }
        val small = RouteGenieIcons.SEND.iconWidth
        listOf(RouteGenieIcons.EMPTY_ROUTES, RouteGenieIcons.EMPTY_SEARCH, RouteGenieIcons.EMPTY_PROJECT).forEach {
            assertTrue("$it is ${it.iconWidth} wide, the small icons are $small", it.iconWidth > small)
        }
        assertTrue("gutter icon is ${RouteGenieIcons.GUTTER.iconWidth} wide, the small icons are $small", RouteGenieIcons.GUTTER.iconWidth < small)
    }
}
