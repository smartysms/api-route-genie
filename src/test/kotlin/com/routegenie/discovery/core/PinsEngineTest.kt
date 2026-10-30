package com.routegenie.discovery.core

import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.testing.RecordingProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins through the real engine: a changed pin relinks (no file is read again), an unchanged one costs nothing, many changes in
 * a row are batched by the debounce, and the published snapshot carries the unresolved settings and the pinned endpoints.
 */
class PinsEngineTest : EngineTestCase() {
    private val pins = AtomicReference(PinEnv.EMPTY)
    private val provider = RecordingProvider(FastApiRouteProvider())

    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    private fun pinnedEngine(debounceMs: Long = 20) =
        RouteDiscoveryEngine(project, scope, owner, { listOf(provider) }, debounceMs, pins = { pins.get() })

    private fun setPin(value: String?) {
        pins.set(if (value == null) PinEnv.EMPTY else PinEnv(mapOf("settings.API_V1_STR" to value)))
    }

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject("pins/fastapi_template", "")
    }

    fun testWithoutAPinTheSnapshotListsNothingAndReportsTheSetting() {
        val engine = pinnedEngine()
        engine.refreshBlocking()
        val snapshot = engine.snapshot.value
        assertTrue(snapshot.endpoints.isEmpty())
        val setting = snapshot.settings.single()
        assertEquals("settings.API_V1_STR", setting.expression)
        assertEquals(11, setting.routeCount)
        assertEquals("/api/v1", setting.suggestion?.value)
        assertTrue(snapshot.usedPins.isEmpty())
    }

    fun testThePinShowsTheEndpointsAndClearsTheSetting() {
        val engine = pinnedEngine()
        engine.refreshBlocking()
        setPin("/api/v1")
        engine.refreshBlocking()
        val snapshot = engine.snapshot.value
        assertEquals(11, snapshot.endpoints.size)
        assertTrue(snapshot.settings.isEmpty())
        assertEquals(setOf("settings.API_V1_STR"), snapshot.usedPins)
        assertTrue(snapshot.endpoints.all { it.path.startsWith("/api/v1/") })
    }

    fun testChangingARemovingAPinRelinksWithoutReadingAnyFileAgain() {
        val engine = pinnedEngine()
        engine.refreshBlocking()
        val extracted = provider.extracted.size
        val links = provider.linkCount.get()

        setPin("/one")
        engine.refreshBlocking()
        setPin("/two")
        engine.refreshBlocking()
        assertTrue(engine.snapshot.value.endpoints.all { it.path.startsWith("/two/") })
        setPin(null)
        engine.refreshBlocking()

        assertEquals("pins never cause a parse", extracted, provider.extracted.size)
        assertEquals("one relink per change", links + 3, provider.linkCount.get())
        assertTrue(engine.snapshot.value.endpoints.isEmpty())
        assertEquals(1, engine.snapshot.value.settings.size)
    }

    fun testAnUnchangedPinCostsNothing() {
        setPin("/api/v1")
        val engine = pinnedEngine()
        engine.refreshBlocking()
        val snapshot = engine.snapshot.value
        val links = provider.linkCount.get()
        setPin("/api/v1")
        engine.refreshBlocking()
        engine.refreshBlocking()
        assertSame("equal pins are equal input: nothing is linked or published", snapshot, engine.snapshot.value)
        assertEquals(links, provider.linkCount.get())
    }

    fun testPinsFromTheSupplierAreReadAtEachPassSoAFileEditAlsoSeesTheLatest() {
        val engine = pinnedEngine()
        engine.refreshBlocking()
        setPin("/late")
        editFile("backend/app/api/routes/utils.py", "from fastapi import APIRouter\nrouter = APIRouter(prefix='/utils')\n")
        engine.refreshBlocking()
        assertTrue(engine.snapshot.value.endpoints.isNotEmpty())
        assertTrue(engine.snapshot.value.endpoints.all { it.path.startsWith("/late/") })
    }

    fun testManyPinChangesInARowAreBatchedByTheDebounce() {
        val engine = pinnedEngine(debounceMs = 150)
        engine.start(coldStart = false)
        engine.setActive(true)
        waitUntil("first pass") { engine.runCount >= 1 }
        val before = engine.runCount
        repeat(25) { i ->
            setPin("/p$i")
            engine.requestRefresh()
        }
        waitUntil("batched pass") { engine.snapshot.value.endpoints.firstOrNull()?.path?.startsWith("/p24/") == true }
        assertTrue("25 changes caused ${engine.runCount - before} passes", engine.runCount - before <= 3)
    }
}
