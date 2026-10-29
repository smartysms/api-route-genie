package com.routegenie.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.HttpMethod

/**
 * Drives the real panel against the real discovery service on a tiny FastAPI file. A real frame is not available headless, so
 * "shown" is driven through [EndpointsPanel.setShown], the same entry point the hierarchy listener uses.
 */
class EndpointsPanelTest : BasePlatformTestCase() {
    private val owner = Disposer.newDisposable("panel test")

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
        } finally {
            super.tearDown()
        }
    }

    private fun panel(): EndpointsPanel = RouteGenieWindowContent(project, owner).panel

    private fun fastApiFile() {
        myFixture.configureByText(
            "app.py",
            "from fastapi import FastAPI\napp = FastAPI()\n\n@app.get('/users')\ndef list_users():\n    return []\n\n" +
                "@app.post('/users', tags=['users'])\ndef create_user():\n    return {}\n",
        )
    }

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    fun testShowsLoadedEndpointsAndCountsThem() {
        fastApiFile()
        val panel = panel()
        assertEquals("loading", panel.currentCard())
        panel.setShown(true)
        waitFor("tree card") { panel.currentCard() == "tree" }
        assertTrue(panel.statusText(), panel.statusText().contains("2 endpoints"))
        assertTrue(panel.tree.rowCount >= 2)
    }

    fun testFilterNarrowsAndEmptyResultShowsTheNoMatchState() {
        fastApiFile()
        val panel = panel()
        panel.setShown(true)
        waitFor("tree card") { panel.currentCard() == "tree" }
        panel.setQueryForTest("method:POST")
        waitFor("filtered") { panel.statusText().contains("1 of 2") }
        panel.setQueryForTest("zzz-nothing")
        waitFor("no match") { panel.currentCard() == "nomatch" }
        panel.setQueryForTest("")
        waitFor("tree again") { panel.currentCard() == "tree" }
    }

    fun testSelectionByKeyExpandsSelectsAndConsumes() {
        fastApiFile()
        val panel = panel()
        panel.setShown(true)
        waitFor("tree card") { panel.currentCard() == "tree" }
        val target = RouteDiscoveryService.getInstance(project).snapshot.value.endpoints.first { it.method == HttpMethod.POST }
        panel.collapseAll()
        // The filter hides it first: the request must clear the filter and still select.
        panel.setQueryForTest("method:GET")
        waitFor("filtered") { panel.statusText().contains("1 of 2") }
        EndpointNavigator.getInstance(project).reveal(target.key)
        waitFor("selected") { panel.selectedKey() == target.key }
        waitFor("consumed") { EndpointNavigator.getInstance(project).requested.value == null }
    }

    fun testPendingRequestBeforeTheWindowIsShownStillWorks() {
        fastApiFile()
        EndpointNavigator.getInstance(project).reveal("unknown-yet")
        val panel = panel()
        panel.setShown(true)
        waitFor("tree card") { panel.currentCard() == "tree" }
        // Not in the snapshot: the request is dropped instead of lingering forever.
        waitFor("consumed") { EndpointNavigator.getInstance(project).requested.value == null }
    }

    fun testHiddenStopsCollectingAndDisposeReleases() {
        fastApiFile()
        val panel = panel()
        panel.setShown(true)
        waitFor("tree card") { panel.currentCard() == "tree" }
        panel.setShown(false)
        panel.setShown(true)
        waitFor("tree card again") { panel.currentCard() == "tree" }
        Disposer.dispose(owner)
        assertTrue(Disposer.isDisposed(panel))
    }

    fun testNavigationClampsOffsetsAndReportsMissingFiles() {
        assertEquals(0, EndpointSourceNavigation.clampOffset(-5, 10))
        assertEquals(10, EndpointSourceNavigation.clampOffset(500, 10))
        assertEquals(3, EndpointSourceNavigation.clampOffset(3, 10))
        assertNull(EndpointSourceNavigation.resolve(project, "does/not/exist.py"))
    }
}
