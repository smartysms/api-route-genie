package com.routegenie.testmap

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.routegenie.discovery.core.EngineTestCase
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SourceRef
import com.routegenie.testmap.psi.TestCandidates
import com.routegenie.testmap.psi.TestMapEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The pipeline around the linker: candidates, stamps, cache, laziness, relink on a new route list. */
class TestMapEngineTest : EngineTestCase() {
    private fun endpoint(path: String, method: HttpMethod = HttpMethod.GET) =
        Endpoint(method, path, Framework.FASTAPI, Handler("h", "app.h"), SourceRef("app/main.py", 0, 1))

    private val routes = MutableStateFlow(RouteSnapshot(1, listOf(endpoint("/items/"), endpoint("/health"))))

    private fun testEngine(active: Boolean = true, debounce: Long = 30L): TestMapEngine =
        TestMapEngine(project, scope, owner, routes, debounceMs = debounce, active = active).also { it.start(coldStart = false) }

    private fun TestMapEngine.refreshBlocking() {
        runBlocking(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { refreshNow() } }
    }

    private fun TestIndex.lines() = byEndpoint.entries.flatMap { (key, calls) -> calls.map { "${it.verb} ${key.split('|')[2]} <- ${it.test.name}" } }.sorted()

    private fun addTest(path: String, body: String) = myFixture.addFileToProject(path, "def test_it(client):\n    $body\n")

    fun testColdPassMapsTheCall() {
        addTest("tests/test_items.py", "client.get('/items/')")
        val engine = testEngine()
        assertEquals(IndexPhase.IDLE, engine.index.value.phase)
        engine.refreshBlocking()
        val index = engine.index.value
        assertEquals(IndexPhase.READY, index.phase)
        assertEquals(listOf("GET /items/ <- test_it"), index.lines())
        assertEquals(1, index.stats.testFiles)
        assertEquals(1, engine.cachedEntries)
    }

    fun testAnInactiveEngineReadsNothingUntilItIsShown() {
        addTest("tests/test_items.py", "client.get('/items/')")
        val engine = testEngine(active = false)
        addTest("tests/test_health.py", "client.get('/health')")
        Thread.sleep(150)
        assertEquals(0, engine.runCount)
        assertTrue(engine.isStale)
        engine.setActive(true)
        waitUntil("first pass") { engine.index.value.phase == IndexPhase.READY }
        assertEquals(listOf("GET /health <- test_it", "GET /items/ <- test_it"), engine.index.value.lines())
        assertFalse(engine.isStale)
    }

    fun testEditingOneFileRemapsAndTheOtherFileKeepsItsFacts() {
        addTest("tests/test_items.py", "client.get('/items/')")
        addTest("tests/test_health.py", "client.get('/health')")
        val engine = testEngine()
        engine.refreshBlocking()
        val before = engine.index.value
        assertEquals(2, engine.cachedEntries)
        editFile("tests/test_health.py", "def test_it(client):\n    client.get('/nowhere')\n")
        waitUntil("remapped") { engine.index.value !== before && engine.index.value.lines() == listOf("GET /items/ <- test_it") }
        assertEquals(2, engine.cachedEntries)
        assertEquals(listOf("NO_ENDPOINT"), engine.index.value.unattributed.map { it.reason.name.removePrefix("TEST_") })
    }

    fun testAPassWithNothingChangedPublishesNothing() {
        addTest("tests/test_items.py", "client.get('/items/')")
        val engine = testEngine()
        engine.refreshBlocking()
        val first = engine.index.value
        engine.refreshBlocking()
        assertSame(first, engine.index.value)
    }

    fun testANewRouteListRelinksWithoutReadingAgain() {
        addTest("tests/test_items.py", "client.get('/items/')")
        val engine = testEngine()
        engine.refreshBlocking()
        assertEquals(listOf("GET /items/ <- test_it"), engine.index.value.lines())
        routes.value = RouteSnapshot(2, listOf(endpoint("/items/"), endpoint("/items/", HttpMethod.POST)))
        waitUntil("relinked") { engine.index.value.lines().isNotEmpty() && engine.index.value.byEndpoint.size == 1 && engine.index.value.generation > 1 }
        routes.value = RouteSnapshot(3, emptyList())
        waitUntil("nothing to map") { engine.index.value.lines().isEmpty() && engine.index.value.unattributed.size == 1 }
    }

    fun testIndexingIsShownUntilTheRouteListIsReady() {
        addTest("tests/test_items.py", "client.get('/items/')")
        routes.value = RouteSnapshot.EMPTY
        val engine = testEngine()
        engine.refreshBlocking()
        assertEquals(IndexPhase.INDEXING, engine.index.value.phase)
        routes.value = RouteSnapshot(1, listOf(endpoint("/items/")))
        waitUntil("ready after routes") { engine.index.value.phase == IndexPhase.READY }
        assertEquals(listOf("GET /items/ <- test_it"), engine.index.value.lines())
    }

    fun testDisposingTheOwnerStopsEveryCoroutine() {
        val child = Disposer.newDisposable("engine owner")
        Disposer.register(owner, child)
        val engine = TestMapEngine(project, scope, child, routes, debounceMs = 30L).also { it.start(coldStart = true) }
        waitUntil("cold scan ran") { engine.runCount > 0 }
        Disposer.dispose(child)
        waitUntil("stopped") { engine.isStopped }
        assertEquals(0, engine.cachedEntries)
    }

    fun testABaseClassInAProjectFileOutsideTheTestPathsIsRead() {
        routes.value = RouteSnapshot(1, listOf(Endpoint(HttpMethod.GET, "/shop/", Framework.DJANGO, Handler("h", "app.h"), SourceRef("app/urls.py", 0, 1))))
        myFixture.addFileToProject("app/testutils/cases.py", "from django.test import TestCase\n\nclass BaseCase(TestCase):\n    pass\n")
        myFixture.addFileToProject(
            "tests/test_shop.py",
            "from app.testutils.cases import BaseCase\n\nclass ShopTests(BaseCase):\n    def test_x(self):\n        self.client.get('/shop/')\n",
        )
        val engine = testEngine()
        engine.refreshBlocking()
        val index = engine.index.value
        assertEquals(listOf("GET /shop/ <- test_x"), index.lines())
        assertEquals(0, index.stats.unresolvedReceivers)
        assertEquals("the base class file was read as a dependency", 2, engine.cachedEntries)
    }

    fun testACallThroughAnUnreadBaseClassIsCountedNotGuessed() {
        routes.value = RouteSnapshot(1, listOf(Endpoint(HttpMethod.GET, "/shop/", Framework.DJANGO, Handler("h", "app.h"), SourceRef("app/urls.py", 0, 1))))
        myFixture.addFileToProject(
            "tests/test_shop.py",
            "from vendor.lib import BaseCase\n\nclass ShopTests(BaseCase):\n    def test_x(self):\n        self.client.get('/shop/')\n",
        )
        val engine = testEngine()
        engine.refreshBlocking()
        assertTrue(engine.index.value.lines().isEmpty())
        assertEquals(1, engine.index.value.stats.unresolvedReceivers)
    }

    fun testCandidatesAreTestFilesWithAClientWord() {
        addTest("tests/test_a.py", "client.get('/items/')")
        myFixture.addFileToProject("shop/tests.py", "class T:\n    def test_x(self):\n        self.client.get('/shop/')\n")
        myFixture.addFileToProject("tests/conftest.py", "import pytest\n\n@pytest.fixture\ndef client():\n    return TestClient(app)\n")
        myFixture.addFileToProject("app/service.py", "def call(client):\n    client.get('/x')\n")
        myFixture.addFileToProject("tests/test_plain.py", "def test_nothing():\n    assert 1\n")
        val index = ProjectFileIndex.getInstance(project)
        val names = TestCandidates.find(project).map { it.name }.sorted()
        assertEquals(listOf("conftest.py", "test_a.py", "tests.py"), names)
        assertTrue(TestCandidates.isTestSource(project, TestCandidates.find(project).first(), index))
    }
}
