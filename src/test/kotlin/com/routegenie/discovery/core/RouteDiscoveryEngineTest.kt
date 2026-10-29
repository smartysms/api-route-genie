package com.routegenie.discovery.core

import com.intellij.testFramework.ExtensionTestUtil
import com.routegenie.discovery.RouteProvider
import com.routegenie.model.DiagnosticCode
import com.routegenie.testing.FakeRouteProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RouteDiscoveryEngineTest : EngineTestCase() {
    fun testStartsEmptyWithGenerationZero() {
        val engine = engine(FakeRouteProvider())
        assertEquals(0L, engine.snapshot.value.generation)
        assertTrue(engine.snapshot.value.endpoints.isEmpty())
    }

    fun testColdStartRunsInTheBackgroundAndPublishes() {
        addSyntheticFiles(5, routesPerFile = 2)
        val provider = FakeRouteProvider()
        val engine = engine(provider)
        engine.start(coldStart = true)

        waitUntil("first snapshot") { engine.snapshot.value.generation > 0 }

        assertEquals(10, engine.snapshot.value.endpoints.size)
        assertFalse("discovery must never touch PSI on the UI thread", provider.sawEdt)
        assertTrue(engine.snapshot.value.diagnostics.isEmpty())
    }

    fun testGenerationIncreasesOnlyWhenTheResultChanges() {
        addSyntheticFiles(2)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        val first = engine.snapshot.value
        engine.refreshBlocking()
        assertSame("nothing changed, so no new snapshot", first, engine.snapshot.value)

        editFile("pkg0/module0.py", "# route /changed\n")
        engine.refreshBlocking()
        assertTrue(engine.snapshot.value.generation > first.generation)
    }

    fun testOneFileEditRereadsOnlyThatFile() {
        addSyntheticFiles(20, routesPerFile = 2)
        val provider = FakeRouteProvider()
        val engine = engine(provider)
        engine.refreshBlocking()
        assertEquals(20, provider.extractCount.get())

        editFile("pkg0/module3.py", "# route /only\n# route /two\n# route /three\n")
        engine.refreshBlocking()

        assertEquals("only the edited file is extracted again", 21, provider.extractCount.get())
        val paths = engine.snapshot.value.endpoints.map { it.path }
        assertTrue("/only" in paths && "/three" in paths)
        assertEquals(20 * 2 - 2 + 3, paths.size)
    }

    fun testDeletedFileDisappearsFromTheSnapshot() {
        addSyntheticFiles(3, routesPerFile = 1)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        assertEquals(3, engine.snapshot.value.endpoints.size)

        com.intellij.openapi.application.runWriteAction { myFixture.findFileInTempDir("pkg0/module1.py").delete(this) }
        engine.refreshBlocking()
        assertEquals(2, engine.snapshot.value.endpoints.size)
    }

    fun testFileWithoutRoutesContributesNothingAndIsCached() {
        myFixture.addFileToProject("plain.py", "x = 1\n")
        val provider = FakeRouteProvider()
        val engine = engine(provider)
        engine.refreshBlocking()
        engine.refreshBlocking()
        assertEquals("a negative result is cached too", 1, provider.extractCount.get())
        assertTrue(engine.snapshot.value.endpoints.isEmpty())
    }

    fun testDebouncedRefreshAfterAnEdit() {
        addSyntheticFiles(3, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, debounceMs = 50)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val before = engine.snapshot.value.generation

        editFile("pkg0/module0.py", "# route /edited\n")

        waitUntil("snapshot after edit") { engine.snapshot.value.generation > before }
        assertTrue("/edited" in engine.snapshot.value.endpoints.map { it.path })
    }

    fun testManyRequestsInOneBurstRunOnce() {
        addSyntheticFiles(2, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, debounceMs = 150)
        engine.start(coldStart = false)
        repeat(20) { engine.requestRefresh() }

        waitUntil("first run") { engine.snapshot.value.generation > 0 }
        Thread.sleep(400)
        assertEquals("one coalesced run, so one link", 1, provider.linkCount.get())
    }

    fun testFailingProviderIsIsolated() {
        addSyntheticFiles(2, routesPerFile = 1)
        val broken = FakeRouteProvider("a-broken").apply { failCandidates = true }
        val good = FakeRouteProvider("b-good")
        val engine = engine(broken, good)
        engine.refreshBlocking()

        val snapshot = engine.snapshot.value
        assertEquals("the healthy provider still delivers", 2, snapshot.endpoints.size)
        val failure = snapshot.diagnostics.single()
        assertEquals(DiagnosticCode.INTERNAL_ERROR, failure.code)
        assertTrue(failure.args.first() == "a-broken")
    }

    fun testFailingLinkIsIsolated() {
        addSyntheticFiles(2, routesPerFile = 1)
        val broken = FakeRouteProvider("a-broken").apply { failLink = true }
        val good = FakeRouteProvider("b-good")
        val engine = engine(broken, good)
        engine.refreshBlocking()

        assertEquals(2, engine.snapshot.value.endpoints.size)
        assertEquals(DiagnosticCode.INTERNAL_ERROR, engine.snapshot.value.diagnostics.single().code)
    }

    fun testFailingFileIsIsolatedAndNotRetriedUntilItChanges() {
        addSyntheticFiles(3, routesPerFile = 1)
        val provider = FakeRouteProvider().apply { failOnFileName = "module1.py" }
        val engine = engine(provider)
        engine.refreshBlocking()

        val snapshot = engine.snapshot.value
        assertEquals("the other files still deliver", 2, snapshot.endpoints.size)
        val failure = snapshot.diagnostics.single()
        assertEquals(DiagnosticCode.INTERNAL_ERROR, failure.code)
        assertTrue("diagnostic names the file", failure.source!!.path.endsWith("module1.py"))

        val extracted = provider.extractCount.get()
        engine.refreshBlocking()
        assertEquals("a broken file is not retried while unchanged", extracted, provider.extractCount.get())

        provider.failOnFileName = null
        editFile("pkg0/module1.py", "# route /fixed\n")
        engine.refreshBlocking()
        assertTrue(engine.snapshot.value.diagnostics.isEmpty())
        assertEquals(3, engine.snapshot.value.endpoints.size)
    }

    fun testCancellationStopsTheRunAndLeavesTheEngineUsable() {
        addSyntheticFiles(5, routesPerFile = 1)
        val started = CountDownLatch(1)
        val release = AtomicBoolean(false)
        val provider = FakeRouteProvider().apply {
            onExtract = {
                started.countDown()
                while (!release.get()) {
                    com.intellij.openapi.progress.ProgressManager.checkCanceled()
                    Thread.sleep(5)
                }
            }
        }
        val engine = engine(provider)
        val job = scope.launch { engine.refreshNow() }
        assertTrue("extraction started", started.await(30, TimeUnit.SECONDS))

        job.cancel()
        runBlocking(Dispatchers.Default) { withTimeout(30_000) { job.join() } }
        assertTrue(job.isCancelled)
        assertEquals("a cancelled run publishes nothing", 0L, engine.snapshot.value.generation)

        release.set(true)
        provider.onExtract = {}
        engine.refreshBlocking()
        assertEquals("the next run works after a cancelled one", 5, engine.snapshot.value.endpoints.size)
    }

    fun testCancellationExceptionIsNotReportedAsProviderFailure() {
        addSyntheticFiles(2, routesPerFile = 1)
        val provider = FakeRouteProvider().apply { onExtract = { throw CancellationException("stop") } }
        val engine = engine(provider)
        var thrown: Throwable? = null
        try {
            engine.refreshBlocking()
        } catch (e: CancellationException) {
            thrown = e
        }
        assertNotNull("cancellation propagates instead of becoming a diagnostic", thrown)
        assertTrue(engine.snapshot.value.diagnostics.isEmpty())
    }

    fun testProvidersAreMergedInIdOrder() {
        addSyntheticFiles(1, routesPerFile = 1)
        val engine = engine(FakeRouteProvider("z"), FakeRouteProvider("a"))
        engine.refreshBlocking()
        assertEquals(2, engine.snapshot.value.endpoints.size)
    }

    fun testServiceUsesRegisteredProvidersAndStartsCold() {
        addSyntheticFiles(4, routesPerFile = 2)
        val provider = FakeRouteProvider()
        ExtensionTestUtil.maskExtensions(RouteProvider.EP_NAME, listOf<RouteProvider>(provider), testRootDisposable)

        // Built by hand instead of getInstance so the light project does not keep a live service for later tests.
        val service = RouteDiscoveryService(project, scope)
        try {
            service.setActive(true)
            waitUntil("service snapshot") { service.snapshot.value.endpoints.size == 8 }
            assertFalse(provider.sawEdt)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(service)
        }
    }

    fun testPartialEndpointsNeverReachTheSnapshot() {
        addSyntheticFiles(1, routesPerFile = 1)
        fun endpoint(path: String, problems: List<com.routegenie.model.Diagnostic> = emptyList()) = com.routegenie.model.Endpoint(
            com.routegenie.model.HttpMethod.GET, path, com.routegenie.model.Framework.FASTAPI,
            com.routegenie.model.Handler("h", "m.h"), com.routegenie.model.SourceRef("m.py", 0, 3), problems = problems,
        )
        val problem = com.routegenie.model.Diagnostic(com.routegenie.model.Severity.WARNING, DiagnosticCode.DYNAMIC_VALUE)
        val provider = FakeRouteProvider().apply {
            extraEndpoints = listOf(
                endpoint("/users/{?}/posts"),
                endpoint("/flagged", listOf(problem)),
                endpoint("/exact/{id}"),
            )
        }
        val engine = engine(provider)
        engine.refreshBlocking()

        val paths = engine.snapshot.value.endpoints.map { it.path }
        assertTrue("/exact/{id}" in paths)
        assertFalse(paths.any { it.contains(com.routegenie.model.PathSyntax.UNKNOWN_SEGMENT) })
        assertFalse("/flagged" in paths)
        assertTrue(engine.snapshot.value.endpoints.none { it.isPartial })
        val dropped = engine.snapshot.value.diagnostics
        assertEquals("one diagnostic per dropped endpoint", 2, dropped.size)
        assertTrue(dropped.all { it.code == DiagnosticCode.INTERNAL_ERROR && it.source?.path == "m.py" })
        assertTrue(dropped.any { "/users/{?}/posts" in it.args })
    }

    fun testServiceIsRegisteredInPluginXml() {
        val xml = com.routegenie.testing.ProjectFiles.mainResources.resolve("META-INF/plugin.xml").readText()
        assertTrue(xml.contains("serviceImplementation=\"${RouteDiscoveryService::class.java.name}\""))
    }
}
