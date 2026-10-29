package com.routegenie.ui.json

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.json.JsonContent
import com.routegenie.json.T
import com.routegenie.testing.LeakProbe
import java.lang.ref.WeakReference

class JsonDiffPanelTest : BasePlatformTestCase() {
    private val owner = Disposer.newDisposable("json diff test")

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    private fun compared(left: String, right: String, panel: JsonDiffPanel = JsonDiffPanel(owner)): JsonDiffPanel {
        panel.compare(JsonContent.text(left), JsonContent.text(right), "previous", "current")
        waitFor("result") { panel.cardName() == "result" || panel.cardName() == "message" }
        UIUtil.dispatchAllInvocationEvents()
        return panel
    }

    fun testStartsEmpty() {
        assertEquals("empty", JsonDiffPanel(owner).cardName())
    }

    fun testShowsWhatChanged() {
        val panel = compared("""{"a":1,"b":[1,2],"c":"x"}""", """{"a":2,"b":[1,2,3],"d":true}""")
        assertEquals("result", panel.cardName())
        val r = panel.differences()!!
        assertEquals(4, r.totalDifferences)
        assertTrue(panel.summaryText(), panel.summaryText().contains("added"))
        assertTrue(panel.summaryText().contains("removed"))
        assertTrue(panel.summaryText().contains("changed"))
        assertTrue(panel.lineCount() > 3)
    }

    fun testIdenticalDocumentsSaySo() {
        val panel = compared("""{"a":[1,2]}""", """{ "a" : [1, 2] }""")
        assertTrue(panel.summaryText(), panel.summaryText().contains("Identical"))
        assertEquals(0, panel.lineCount())
    }

    fun testAnInvalidSideIsNamedWithItsPosition() {
        val panel = JsonDiffPanel(owner)
        panel.compare(JsonContent.text("{\"a\":1}"), JsonContent.text("{\"a\":}"), "previous", "current")
        waitFor("message") { panel.cardName() == "message" }
    }

    fun testOptionsApplyWithoutReadingAgain() {
        val panel = compared("""{"id":1,"ts":5,"v":1}""", """{"id":1,"ts":9,"v":2}""")
        assertEquals(2, panel.differences()!!.totalDifferences)
        panel.configure(JsonDiffSettings(ignore = "ts"))
        waitFor("fewer differences") { panel.differences()?.totalDifferences == 1 }
    }

    fun testArraysCanBeMatchedByKey() {
        val panel = compared("""[{"id":1,"v":1},{"id":2,"v":2}]""", """[{"id":2,"v":2},{"id":1,"v":9}]""")
        assertTrue(panel.differences()!!.totalDifferences > 1)
        panel.configure(JsonDiffSettings(arrayKey = "id"))
        waitFor("matched by key") { panel.differences()?.totalDifferences == 1 }
    }

    fun testClearReleasesBothDocuments() {
        val panel = compared("""{"a":1}""", """{"a":2}""")
        val result = panel.differences()!!
        panel.clear()
        assertEquals("empty", panel.cardName())
        assertNull(panel.differences())
        assertEquals(com.routegenie.json.ScanStatus.COMPLETE, result.left.status)
    }

    fun testALargerComparisonProducesBoundedRows() {
        val a = String(T.users(1_000_000), Charsets.UTF_8)
        val b = a.replace("User number 5", "User numbeR 5")
        val panel = compared(a, b)
        assertTrue(panel.differences()!!.totalDifferences > 100)
        assertTrue(panel.lineCount() <= com.routegenie.Limits.JSON_DIFF_MAX_ROWS + 2)
    }

    fun testThePanelIsCollectableAfterItIsDisposed() {
        val ref = disposedPanelReference()
        UIUtil.dispatchAllInvocationEvents()
        LeakProbe.assertCollected("JsonDiffPanel", ref)
    }

    private fun disposedPanelReference(): WeakReference<JsonDiffPanel> {
        val local: Disposable = Disposer.newDisposable("local diff owner")
        val panel = JsonDiffPanel(local)
        compared("""{"a":1}""", """{"a":2}""", panel)
        Disposer.dispose(local)
        return WeakReference(panel)
    }
}
