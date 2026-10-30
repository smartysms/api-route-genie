package com.routegenie.testmap

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.testmap.psi.TestMapService

/** The project service as the plugin registers it: it exists, starts inactive, and works only once somebody looks. */
class TestMapServiceTest : BasePlatformTestCase() {
    fun testTheServiceIsRegisteredStartsInactiveAndWaitsForTheRouteList() {
        val service = TestMapService.getInstance(project)
        assertSame("one service per project", service, TestMapService.getInstance(project))
        assertEquals(IndexPhase.IDLE, service.index.value.phase)
        assertTrue(service.isStale)

        myFixture.addFileToProject("tests/test_service.py", "def test_x(client):\n    client.get('/s')\n")
        Thread.sleep(200)
        assertEquals("nothing is read while nobody looks", IndexPhase.IDLE, service.index.value.phase)

        // Showing the tab starts the pass: "indexing" until the route list exists, then the result.
        service.setActive(true)
        PlatformTestUtil.waitWithEventsDispatching("a pass started", { service.index.value.phase != IndexPhase.IDLE }, 30)
        service.setActive(false)
    }
}
