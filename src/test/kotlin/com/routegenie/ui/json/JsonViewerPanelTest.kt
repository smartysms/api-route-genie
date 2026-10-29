package com.routegenie.ui.json

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.Limits
import com.routegenie.json.JsonContent
import com.routegenie.json.JsonPath
import com.routegenie.json.JsonPrinter
import com.routegenie.json.T
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PlatformLeaks
import com.routegenie.testing.ProjectFiles
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files

/** The viewer panel driven headless: real index, real editors, real tree. Painting is checked in the gallery test. */
class JsonViewerPanelTest : BasePlatformTestCase() {
    private var owner = Disposer.newDisposable("json panel test")

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
        } finally {
            super.tearDown()
        }
    }

    private fun panel(): JsonViewerPanel = JsonViewerPanel(owner)

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    private fun shown(panel: JsonViewerPanel, content: JsonContent): JsonViewerPanel {
        panel.showBody(content)
        waitFor("indexed") { panel.currentIndex() != null && !panel.isIndexing() }
        UIUtil.dispatchAllInvocationEvents()
        return panel
    }

    private val users = """{"users":[{"id":1,"name":"Ann","tags":["a","b"]},{"id":2,"name":"Bob","tags":[]}],"total":2}"""

    fun testEmptyBodyShowsTheEmptyState() {
        val panel = panel()
        panel.showBody(JsonContent.Bytes(ByteArray(0)))
        assertEquals("empty", panel.cardName())
    }

    fun testValidDocumentShowsTheTreeWithItsStatus() {
        val panel = shown(panel(), JsonContent.text(users))
        waitFor("tree card") { panel.cardName() == "tree" }
        assertTrue(panel.statusText(), panel.statusText().contains("values"))
        assertTrue(panel.treeView.tree.rowCount >= 1)
        assertFalse(panel.hasErrorBanner())
    }

    fun testSelectingANodeGivesItsPath() {
        val panel = shown(panel(), JsonContent.text(users))
        val index = panel.currentIndex()!!
        val bob = JsonPath.resolve(index, JsonPath.parse("$.users[1].name").segments!!)
        assertTrue(panel.treeView.select(bob))
        assertEquals("$.users[1].name", panel.selectedPath)
        assertEquals(bob, panel.treeView.selectedNode())
    }

    fun testSearchSelectsTheFirstMatchAndFindsNothingForNonsense() {
        val panel = shown(panel(), JsonContent.text(users))
        panel.search("Bob")
        waitFor("hits") { panel.searchHitCount() > 0 }
        waitFor("first hit selected") { panel.selectedPath != null }
        assertEquals("$.users[1].name", panel.selectedPath)
        panel.search("zzz-none")
        waitFor("no hits") { panel.searchHitCount() == 0 }
    }

    fun testPrettyViewHoldsTheFormattedText() {
        val panel = shown(panel(), JsonContent.text(users))
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("pretty text") { panel.prettyView.hasEditor }
        val index = panel.currentIndex()!!
        assertEquals("pretty", panel.cardName())
        assertEquals(JsonPrinter.toString(index), panel.prettyView.text)
        // Objects and arrays fold: the users array, its two objects, their tag arrays and the root.
        assertTrue("fold regions: ${panel.prettyView.foldRegionCount}", panel.prettyView.foldRegionCount >= 4)
    }

    fun testRawViewHoldsTheTextAsReceived() {
        val panel = shown(panel(), JsonContent.text(users))
        panel.setMode(JsonViewMode.RAW)
        waitFor("raw text") { panel.rawView.hasEditor }
        assertEquals("raw", panel.cardName())
        assertEquals(users, panel.rawView.text)
    }

    fun testTableViewForAnArrayOfObjects() {
        val panel = shown(panel(), JsonContent.text("""[{"a":1,"b":"x"},{"a":2,"c":true},{"a":3}]"""))
        panel.setMode(JsonViewMode.TABLE)
        waitFor("table card") { panel.cardName() == "table" }
        assertEquals(3, panel.tableView.table.rowCount)
        assertEquals(listOf("a", "b", "c"), panel.tableView.tableCore!!.columns)
    }

    fun testTableViewFindsTheArrayInsideAnObject() {
        val panel = shown(panel(), JsonContent.text(users))
        panel.setMode(JsonViewMode.TABLE)
        waitFor("table card") { panel.cardName() == "table" }
        assertEquals(2, panel.tableView.table.rowCount)
    }

    fun testTableIsNotOfferedForAScalarDocument() {
        val panel = shown(panel(), JsonContent.text("42"))
        panel.setMode(JsonViewMode.TABLE)
        waitFor("no table card") { panel.cardName() == "tableNone" }
    }

    fun testInvalidJsonShowsTheErrorAndOffersRaw() {
        val panel = shown(panel(), JsonContent.text("{\n  \"a\": 1,\n  \"b\": oops\n}"))
        waitFor("banner with location") { panel.hasErrorBanner() && panel.errorBannerText().contains("line 3") }
        assertTrue(panel.errorBannerText(), panel.errorBannerText().contains("column 8"))
        waitFor("raw card") { panel.cardName() == "raw" }
        assertEquals(JsonViewMode.RAW, panel.mode)
        waitFor("raw editor") { panel.rawView.hasEditor }
        // Pretty is refused with a hint.
        panel.setMode(JsonViewMode.PRETTY)
        assertEquals("notice", panel.cardName())
    }

    fun testACutResponseSaysItWasCut() {
        val bytes = users.substring(0, 30).toByteArray()
        val panel = shown(panel(), JsonContent.Bytes(bytes, fullSizeBytes = 5_000_000))
        waitFor("banner") { panel.hasErrorBanner() && panel.errorBannerText().contains("cut") }
    }

    fun testASpilledFileIsShownLikeBytes() {
        val file = Files.createTempFile("rg-panel-", ".json")
        Files.write(file, users.toByteArray())
        val local = Disposer.newDisposable("spill owner")
        try {
            val panel = JsonViewerPanel(local)
            shown(panel, JsonContent.File(file))
            assertEquals("tree", panel.cardName())
            panel.setMode(JsonViewMode.RAW)
            waitFor("raw text") { panel.rawView.hasEditor }
            assertEquals(users, panel.rawView.text)
            panel.clear()
        } finally {
            Disposer.dispose(local)
            Files.deleteIfExists(file)
        }
    }

    fun testPrettyTextOfALargerDocumentIsShownAPageAtATime() {
        val panel = shown(panel(), JsonContent.Bytes(T.users(6_000_000)))
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("first page") { panel.prettyView.hasEditor }
        assertTrue("one page only: ${panel.prettyView.text.length}", panel.prettyView.text.length <= Limits.JSON_PAGE_UNITS + 10_000)
        waitFor("page count") { panel.prettyView.pageCount > 3 }
        val first = panel.prettyView.text
        panel.prettyView.showPage(2, -1, -1)
        waitFor("second page shown") { panel.prettyView.currentPage == 2 }
        assertNotSame(first, panel.prettyView.text)
        assertFalse(panel.prettyView.text.startsWith("\n"))
    }

    private fun toolbarButtons(root: java.awt.Component, place: String): List<com.intellij.openapi.actionSystem.impl.ActionButton> {
        val out = ArrayList<com.intellij.openapi.actionSystem.impl.ActionButton>()
        fun walk(c: java.awt.Component) {
            if (c is com.intellij.openapi.actionSystem.impl.ActionToolbarImpl && c.place == place) {
                c.updateActionsImmediately()
                c.components.filterIsInstance<com.intellij.openapi.actionSystem.impl.ActionButton>().filterTo(out) { it.isVisible }
            }
            if (c is java.awt.Container) c.components.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    fun testTheToolbarOffersTheActionsOfTheCurrentMode() {
        val panel = shown(panel(), JsonContent.text(users))
        // Tree: expand, collapse, copy, save, open as file.
        assertEquals(5, toolbarButtons(panel, "RouteGenie.JsonViewer").size)
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("pretty") { panel.prettyView.hasEditor }
        // Pretty: soft-wrap, format, copy, save, open as file.
        assertEquals(5, toolbarButtons(panel, "RouteGenie.JsonViewer").size)
        panel.setMode(JsonViewMode.TABLE)
        waitFor("table") { panel.cardName() == "table" }
        assertEquals(3, toolbarButtons(panel, "RouteGenie.JsonViewer").size)
    }

    fun testClearReleasesTheDocumentAndEditors() {
        val before = EditorFactory.getInstance().allEditors.size
        val panel = shown(panel(), JsonContent.text(users))
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("pretty") { panel.prettyView.hasEditor }
        panel.setMode(JsonViewMode.RAW)
        waitFor("raw") { panel.rawView.hasEditor }
        panel.clear()
        assertEquals("empty", panel.cardName())
        assertNull(panel.currentIndex())
        assertEquals(before, EditorFactory.getInstance().allEditors.size)
        assertFalse(panel.prettyView.hasEditor)
    }

    fun testShowingAnotherBodyReplacesTheFirst() {
        val panel = shown(panel(), JsonContent.text(users))
        val first = panel.currentIndex()
        shown(panel, JsonContent.text("[1,2,3]"))
        assertNotSame(first, panel.currentIndex())
        assertEquals(5, panel.currentIndex()!!.nodeCount)
    }

    fun testALargeDocumentShowsRowsBeforeIndexingEnds() {
        val file = File.createTempFile("rg-panel-large-", ".json")
        val local = Disposer.newDisposable("large owner")
        try {
            T.writeUsers(file, LARGE_BYTES)
            val panel = JsonViewerPanel(local)
            val t0 = System.nanoTime()
            panel.showBody(JsonContent.File(file.toPath()))
            var firstRows = -1.0
            val deadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                if (panel.cardName() == "tree" && panel.treeView.tree.rowCount > 1) {
                    firstRows = (System.nanoTime() - t0) / 1_000_000.0
                    break
                }
                Thread.sleep(1)
            }
            assertTrue("rows appeared", firstRows > 0)
            val stillIndexing = panel.isIndexing()
            waitFor("indexed") { !panel.isIndexing() }
            val total = (System.nanoTime() - t0) / 1_000_000.0
            record("panel.first_rows_ms", "%.0f".format(firstRows))
            record("panel.fully_indexed_ms", "%.0f".format(total))
            record("panel.rows_before_indexing_done", stillIndexing.toString())
            assertTrue("first rows after $firstRows ms", firstRows <= Limits.BUDGET_JSON_TREE_FIRST_PAINT_50MB_MS * PANEL_FACTOR)
            assertTrue(panel.treeView.tree.rowCount > 1)
            panel.clear()
        } finally {
            Disposer.dispose(local)
            file.delete()
        }
    }

    fun testTextViewsAreRefusedAboveTheHardCapButTheTreeStays() {
        val file = File.createTempFile("rg-panel-huge-", ".json")
        val local = Disposer.newDisposable("huge owner")
        try {
            java.io.BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
                out.write('['.code)
                val chunk = ByteArray(1 shl 16) { ' '.code.toByte() }
                var left = Limits.JSON_TEXT_HARD_CAP_BYTES
                while (left > 0) {
                    out.write(chunk)
                    left -= chunk.size
                }
                out.write(']'.code)
            }
            val panel = JsonViewerPanel(local)
            shown(panel, JsonContent.File(file.toPath()))
            assertEquals("tree", panel.cardName())
            panel.setMode(JsonViewMode.RAW)
            assertEquals("notice", panel.cardName())
            panel.setMode(JsonViewMode.PRETTY)
            assertEquals("notice", panel.cardName())
            panel.setMode(JsonViewMode.TREE)
            assertEquals("tree", panel.cardName())
            panel.clear()
        } finally {
            Disposer.dispose(local)
            file.delete()
        }
    }

    fun testDisposingThePanelReleasesEditorsAndTheDocument() {
        val before = EditorFactory.getInstance().allEditors.size
        val local = Disposer.newDisposable("local owner")
        val panel = JsonViewerPanel(local)
        panel.showBody(JsonContent.text(users))
        waitFor("indexed") { panel.currentIndex() != null && !panel.isIndexing() }
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("pretty") { panel.prettyView.hasEditor }
        panel.setMode(JsonViewMode.RAW)
        waitFor("raw") { panel.rawView.hasEditor }
        Disposer.dispose(local)
        assertEquals(before, EditorFactory.getInstance().allEditors.size)
        assertNull(panel.currentIndex())
    }

    fun testNoEditorOrDisposedProjectIsLeftReachableAfterTheScenario() {
        PlatformLeaks.assertScenarioClean("json viewer open, use and dispose") {
            val local = Disposer.newDisposable("scenario owner")
            val panel = JsonViewerPanel(local)
            panel.showBody(JsonContent.text(users))
            waitFor("indexed") { panel.currentIndex() != null && !panel.isIndexing() }
            panel.setMode(JsonViewMode.PRETTY)
            waitFor("pretty") { panel.prettyView.hasEditor }
            panel.setMode(JsonViewMode.RAW)
            waitFor("raw") { panel.rawView.hasEditor }
            panel.search("Ann")
            waitFor("text hits") { panel.searchHitCount() > 0 }
            Disposer.dispose(local)
        }
    }

    fun testThePanelIsCollectableAfterItIsDisposed() {
        val ref = disposedPanelReference()
        UIUtil.dispatchAllInvocationEvents()
        LeakProbe.assertCollected("JsonViewerPanel", ref)
    }

    private fun disposedPanelReference(): WeakReference<JsonViewerPanel> {
        val local: Disposable = Disposer.newDisposable("local owner")
        val panel = JsonViewerPanel(local)
        panel.showBody(JsonContent.text(users))
        waitFor("indexed") { panel.currentIndex() != null && !panel.isIndexing() }
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("pretty") { panel.prettyView.hasEditor }
        Disposer.dispose(local)
        return WeakReference(panel)
    }

    fun testHeapReturnsToTheBaselineAfterOpeningAndClosingALargeDocument() {
        val file = File.createTempFile("rg-panel-heap-", ".json")
        try {
            T.writeUsers(file, LARGE_BYTES)
            val baseline = LeakProbe.usedHeapBytes()
            openAndClose(file)
            UIUtil.dispatchAllInvocationEvents()
            val after = LeakProbe.usedHeapBytes() - baseline
            record("panel.heap_after_close_mb", "%.2f".format(after / 1048576.0))
            assertTrue("heap after close ${after / 1048576} MB", after <= 8L * 1048576)
        } finally {
            file.delete()
        }
    }

    private fun openAndClose(file: File) {
        val local = Disposer.newDisposable("heap owner")
        val panel = JsonViewerPanel(local)
        panel.showBody(JsonContent.File(file.toPath()))
        waitFor("indexed") { panel.currentIndex() != null && !panel.isIndexing() }
        panel.setMode(JsonViewMode.RAW)
        waitFor("raw") { panel.rawView.hasEditor }
        panel.setMode(JsonViewMode.PRETTY)
        waitFor("pretty") { panel.prettyView.hasEditor }
        panel.search("User number 77")
        Thread.sleep(400)
        UIUtil.dispatchAllInvocationEvents()
        panel.clear()
        Disposer.dispose(local)
    }

    private fun record(key: String, value: String) {
        val out = File(ProjectFiles.root, "build/reports/routegenie/json/panel-numbers.txt")
        out.parentFile.mkdirs()
        out.appendText("$key=$value\n")
    }

    private companion object {
        const val LARGE_BYTES = 25_000_000L
        const val PANEL_FACTOR = 3
    }
}
