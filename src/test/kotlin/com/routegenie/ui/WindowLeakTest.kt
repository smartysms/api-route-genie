package com.routegenie.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.Limits
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.ResponseSlot
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.model.HttpMethod
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PlatformLeaks
import com.routegenie.ui.model.Leaf
import org.junit.Assert.fail
import com.routegenie.ui.request.FakeEngine
import com.routegenie.ui.request.ManualUi
import com.routegenie.ui.request.MapSecretStore
import com.routegenie.ui.request.RequestSection
import com.routegenie.ui.request.bufferedEvents
import com.routegenie.ui.request.directExecutor
import com.routegenie.ui.request.endpoint
import com.routegenie.ui.request.jsonResponse
import java.lang.ref.WeakReference

/**
 * The whole window (tree, details, request form, response, JSON viewer, diff, environment editor) is released when its owner is
 * disposed: collectable (weak reference), no disposed editor or project reachable, and the heap does not grow over many cycles.
 *
 * Every scenario runs in its own function and only returns weak references. That is deliberate: the interpreter keeps the local
 * variables of a running test method alive as GC roots, so a panel held in a local of the test itself looks leaked although
 * nothing in the plugin holds it. (That is what the earlier "disposed EndpointsPanel is never collected" finding was; see
 * docs/ARCHITECTURE.md, "Leak tests".)
 */
class WindowLeakTest : BasePlatformTestCase() {
    private fun settle() = UIUtil.dispatchAllInvocationEvents()

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    /**
     * The same file as EndpointsPanelTest.fastApiFile: the light project is shared between test classes and the discovery of a
     * file that an earlier test left behind can still be in the next test's first snapshot, so every test of this package uses
     * one path and one content.
     */
    private fun withEndpoints() {
        myFixture.configureByText("app.py", APP)
    }

    /** Opens the window over a project that has endpoints, selects one, and disposes everything. */
    private fun openSelectDispose(waitForTree: Boolean): List<Pair<String, WeakReference<*>>> {
        val owner = Disposer.newDisposable("window leak")
        val content = RouteGenieWindowContent(project, owner)
        val panel = content.panel
        panel.setShown(true)
        if (waitForTree) {
            waitFor("endpoints listed") { panel.currentCard() == "tree" }
            panel.expandAll()
            val row = (0 until panel.tree.rowCount).first { panel.tree.getPathForRow(it).lastPathComponent is Leaf }
            panel.tree.setSelectionRow(row)
            settle()
        }
        val refs = listOf("EndpointsPanel" to WeakReference(panel), "tree" to WeakReference(panel.tree), "details" to WeakReference(panel.details))
        panel.setShown(false)
        Disposer.dispose(owner)
        settle()
        return refs
    }

    fun testADisposedEndpointsPanelIsCollectedWhenNeverShown() {
        val owner = Disposer.newDisposable("never shown")
        val ref = WeakReference(RouteGenieWindowContent(project, owner).panel)
        Disposer.dispose(owner)
        settle()
        LeakProbe.assertCollected("EndpointsPanel after dispose", ref, timeoutMs = LEAK_WAIT_MS)
    }

    fun testADisposedEndpointsPanelWithEndpointsAndASelectionIsCollected() {
        withEndpoints()
        openSelectDispose(waitForTree = true).forEach { (name, ref) -> LeakProbe.assertCollected("$name after dispose", ref, timeoutMs = LEAK_WAIT_MS) }
    }

    fun testAPanelDisposedWhileStillLoadingIsCollected() {
        withEndpoints()
        openSelectDispose(waitForTree = false).forEach { (name, ref) -> LeakProbe.assertCollected("$name disposed while loading", ref, timeoutMs = LEAK_WAIT_MS) }
    }

    private class Rig(val section: RequestSection, val engine: FakeEngine, val ui: ManualUi)

    private fun rig(owner: Disposable): Rig {
        val engine = FakeEngine()
        val ui = ManualUi()
        val section = RequestSection(
            project, owner, engine = { engine }, bodyTemplates = { BodyTemplateSource { null } }, ui = ui,
            state = RouteGenieClientState(), secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = ResponseSlot(),
        )
        return Rig(section, engine, ui)
    }

    /** Request form, a JSON response in the visualizer, two responses compared, the environment editor open. */
    private fun useEverything(r: Rig) {
        r.section.show(endpoint(HttpMethod.POST, "/items", emptyList()))
        repeat(2) {
            r.section.send()
            r.engine.emit(*bufferedEvents(jsonResponse("""{"n":$it,"items":[1,2,3]}""")))
            r.ui.runPending()
        }
        r.section.responseForTest().comparePreviousForTest()
        r.section.showEnvironmentEditor(true)
    }

    private fun requestSectionScenario(): List<Pair<String, WeakReference<*>>> {
        val owner = Disposer.newDisposable("section leak")
        val r = rig(owner)
        useEverything(r)
        waitFor("diff computed") { r.section.responseForTest().diffForTest().cardName() == "result" }
        val refs = listOf(
            "RequestSection" to WeakReference(r.section),
            "JSON viewer" to WeakReference(r.section.responseForTest().jsonForTest()),
            "diff panel" to WeakReference(r.section.responseForTest().diffForTest()),
            "environment editor" to WeakReference(r.section.environmentEditorForTest()),
        )
        Disposer.dispose(owner)
        settle()
        return refs
    }

    fun testRequestSectionWithJsonDiffAndEditorIsCollected() {
        requestSectionScenario().forEach { (name, ref) -> LeakProbe.assertCollected("$name after dispose", ref, timeoutMs = LEAK_WAIT_MS) }
    }

    fun testWholeWindowCreateUseDisposeLeavesNothingBehind() {
        withEndpoints()
        PlatformLeaks.assertScenarioClean("whole window") {
            repeat(4) {
                val owner = Disposer.newDisposable("whole window")
                val content = RouteGenieWindowContent(project, owner)
                useEverything(rig(owner))
                content.panel.setShown(true)
                content.panel.setShown(false)
                Disposer.dispose(owner)
                settle()
            }
        }
    }

    fun testHeapDoesNotGrowOverManyWholeWindowCyclesWithEndpoints() {
        withEndpoints()
        LeakProbe.assertNoHeapGrowth("whole window cycles", maxGrowthBytes = HEAP_GROWTH_LIMIT, warmups = 8, iterations = 40) {
            val owner = Disposer.newDisposable("loop")
            val content = RouteGenieWindowContent(project, owner)
            content.panel.setShown(true)
            useEverything(rig(owner))
            content.panel.setShown(false)
            Disposer.dispose(owner)
            settle()
        }
    }

    /** Holds what the next test method keeps alive on purpose, so the check below has something to find. */
    private val kept = ArrayList<Any>()

    fun testTheCollectabilityCheckCatchesASectionThatIsKept() {
        val owner = Disposer.newDisposable("kept")
        val r = rig(owner)
        kept += r.section
        val ref = WeakReference(r.section)
        Disposer.dispose(owner)
        settle()
        try {
            LeakProbe.assertCollected("kept section", ref, timeoutMs = 1_500)
            fail("expected the kept section to be reported as a leak")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("kept section"))
        }
        kept.clear()
    }

    private fun bigJson(megabytes: Int): String = buildString {
        append("{\"items\":[")
        var i = 0
        while (length < megabytes * 1024 * 1024) { if (i > 0) append(','); append("{\"id\":$i,\"name\":\"item number $i\",\"ok\":true}"); i++ }
        append("]}")
    }

    fun testUiThreadStepsOfTheWorkspaceStayWithinTheBudget() {
        val owner = Disposer.newDisposable("ui steps")
        try {
            val r = rig(owner)
            val a = endpoint(HttpMethod.POST, "/a", emptyList())
            val b = endpoint(HttpMethod.GET, "/b/{id}", listOf(com.routegenie.model.PathParam("id", "int")))
            var flip = false
            val select = PerfBudget.assertWithin("ui-step-select-endpoint", Limits.BUDGET_UI_STEP_MS, warmups = 5, runs = 15) {
                flip = !flip
                r.section.show(if (flip) a else b)
            }
            r.section.show(a)
            val body = bigJson(5)
            val response = jsonResponse(body)
            var sends = 0
            val show = PerfBudget.assertWithin("ui-step-show-5mb-json-response", Limits.BUDGET_UI_STEP_MS, warmups = 1, runs = 5) {
                // Only the UI half is timed by the budget: the engine delivers on a worker thread, the UI drains and swaps.
                r.section.send()
                r.engine.emit(*bufferedEvents(response))
                r.ui.runPending()
                sends++
            }
            waitFor("indexed") { r.section.responseForTest().jsonForTest().let { it.currentIndex() != null && !it.isIndexing() } }
            // Bodies above the history limit are not kept for comparison; two smaller ones are.
            repeat(2) {
                r.section.send()
                r.engine.emit(*bufferedEvents(jsonResponse(bigJson(1))))
                r.ui.runPending()
            }
            waitFor("indexed again") { r.section.responseForTest().jsonForTest().let { it.currentIndex() != null && !it.isIndexing() } }
            val compare = PerfBudget.assertWithin("ui-step-start-compare-1mb", Limits.BUDGET_UI_STEP_MS, warmups = 1, runs = 5) {
                check(r.section.responseForTest().comparePreviousForTest()) { "the comparison did not start" }
                r.section.responseForTest().comparePreviousForTest()
            }
            assertTrue(select.medianMs < Limits.BUDGET_UI_STEP_MS && show.medianMs < Limits.BUDGET_UI_STEP_MS && compare.medianMs < Limits.BUDGET_UI_STEP_MS)
        } finally {
            Disposer.dispose(owner)
        }
    }

    private companion object {
        const val LEAK_WAIT_MS = 15_000L

        /** About 75 KB per cycle would be a leak of a small part; a leaked window costs hundreds of KB per cycle. */
        const val HEAP_GROWTH_LIMIT = 3L * 1024 * 1024

        const val APP = "from fastapi import FastAPI\napp = FastAPI()\n\n@app.get('/users')\ndef list_users():\n    return []\n\n" +
            "@app.post('/users', tags=['users'])\ndef create_user():\n    return {}\n"
    }
}
