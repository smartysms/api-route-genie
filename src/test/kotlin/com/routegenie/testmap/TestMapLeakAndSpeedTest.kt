package com.routegenie.testmap

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFileFactory
import com.intellij.util.ui.UIUtil
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyFile
import com.routegenie.Limits
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.ResponseSlot
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.discovery.core.EngineTestCase
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SourceRef
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testmap.psi.PyTestFactsExtractor
import com.routegenie.testmap.psi.TestMapEngine
import com.routegenie.ui.request.FakeEngine
import com.routegenie.ui.request.ManualUi
import com.routegenie.ui.request.MapSecretStore
import com.routegenie.ui.request.RequestSection
import com.routegenie.ui.request.directExecutor
import com.routegenie.ui.tests.FakeIndexSource
import com.routegenie.ui.tests.TestsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale

/** Speed budgets of the Tests tab (asserted, numbers written to build/reports/routegenie/perf) and leak checks of everything it creates. */
class TestMapLeakAndSpeedTest : EngineTestCase() {
    private fun endpoint(path: String) = Endpoint(HttpMethod.GET, path, Framework.FASTAPI, Handler("h", "app.h"), SourceRef("app/main.py", 0, 1))

    private val routes = MutableStateFlow(RouteSnapshot(1, (0 until 50).map { endpoint("/items$it/") }))

    private fun testFile(n: Int, functions: Int): String = buildString {
        append("import pytest\nfrom fastapi.testclient import TestClient\n\nclient = TestClient(app)\n\n")
        for (f in 0 until functions) {
            append("def test_case_${n}_$f(client):\n")
            append("    response = client.get(\"/items${f % 50}/\", params={\"q\": \"x$f\"})\n")
            append("    assert response.status_code == 200\n")
            append("    data = response.json()\n    assert data == {\"id\": $f, \"name\": \"item $f\", \"tags\": [\"a\", \"b\"]}\n\n")
        }
    }

    private fun TestMapEngine.refreshBlocking() {
        runBlocking(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { refreshNow() } }
    }

    private fun record(label: String, text: String) {
        runCatching {
            val file = File("build/reports/routegenie/perf/$label.txt")
            file.parentFile.mkdirs()
            file.writeText(text)
        }
    }

    // ---- speed -----------------------------------------------------------------------------------------------------

    fun testReextractingOneTypicalFileStaysWithinTheBudget() {
        val text = testFile(0, 150)
        assertTrue("a typical large test file is about 50 KB: ${text.length}", text.length in 30_000..80_000)
        // Like the route extraction budget: the walk over a parsed tree. The platform builds the tree itself (its cost is printed for information).
        val file = pythonFile("tests/test_big.py", text)
        assertEquals(150, PyTestFactsExtractor.extract(file)!!.calls.size)
        val result = PerfBudget.assertWithin("test-reextract-one-file", Limits.BUDGET_TEST_REEXTRACT_ONE_FILE_MS, warmups = 3, runs = 9) {
            PyTestFactsExtractor.extract(file)
        }
        val factory = PsiFileFactory.getInstance(project)
        val withParse = PerfBudget.measure("test-reextract-one-file-with-parse", Limits.BUDGET_TEST_REEXTRACT_ONE_FILE_MS, warmups = 3, runs = 9) {
            PyTestFactsExtractor.extract(factory.createFileFromText("test_big2.py", PythonFileType.INSTANCE, text) as PyFile)
        }
        println("TESTS reextract_walk_ms_median=${result.medianMs} with_parse_ms_median=${withParse.medianMs} budget=${Limits.BUDGET_TEST_REEXTRACT_ONE_FILE_MS} bytes=${text.length}")
    }

    fun testColdIndexOfAboutOneMegabyteStaysWithinTheBudgetPerMegabyte() {
        // Warm up on other files first: users have a warm IDE, and the budget is about files that were never read.
        val warm = (0 until 12).map { n -> myFixture.addFileToProject("tests/test_warm$n.py", testFile(1000 + n, 48)) }
        TestMapEngine(project, scope, owner, routes, debounceMs = 30L).also { it.start(coldStart = false) }.refreshBlocking()
        com.intellij.openapi.application.runWriteAction { warm.forEach { it.virtualFile.delete(this) } }
        var bytes = 0L
        repeat(60) { n ->
            val text = testFile(n, 48)
            bytes += text.length
            myFixture.addFileToProject("tests/test_f$n.py", text)
        }
        val engine = TestMapEngine(project, scope, owner, routes, debounceMs = 30L).also { it.start(coldStart = false) }
        val start = System.nanoTime()
        engine.refreshBlocking()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        val megabytes = bytes / (1024.0 * 1024.0)
        val perMb = ms / megabytes
        record("test-cold-index", String.format(Locale.ROOT, "budget_ms_per_mb=%d\nms_per_mb=%.1f\nmegabytes=%.2f\ntotal_ms=%.1f\n", Limits.BUDGET_TEST_COLD_INDEX_MS_PER_MB, perMb, megabytes, ms))
        println("TESTS cold_index files=60 mb=${"%.2f".format(Locale.ROOT, megabytes)} total_ms=${"%.0f".format(Locale.ROOT, ms)} ms_per_mb=${"%.0f".format(Locale.ROOT, perMb)}")
        // For the record (spike S0): what building the syntax trees alone costs, one thread, on the same kind of text.
        val factory = PsiFileFactory.getInstance(project)
        val parseStart = System.nanoTime()
        var parsedBytes = 0L
        repeat(20) { n ->
            val text = testFile(2000 + n, 48)
            parsedBytes += text.length
            (factory.createFileFromText("p$n.py", PythonFileType.INSTANCE, text) as PyFile).node
        }
        val parseMs = (System.nanoTime() - parseStart) / 1_000_000.0
        println("TESTS parse_only_ms_per_mb=${"%.0f".format(Locale.ROOT, parseMs / (parsedBytes / (1024.0 * 1024.0)))} cores=${Runtime.getRuntime().availableProcessors()}")
        assertEquals(IndexPhase.READY, engine.index.value.phase)
        assertEquals(60 * 48, engine.index.value.stats.mappedCalls)
        val budget = Limits.BUDGET_TEST_COLD_INDEX_MS_PER_MB * megabytes + Limits.BUDGET_TEST_COLD_INDEX_FIXED_MS
        assertTrue("cold index took $ms ms for $megabytes MB, budget $budget ms", ms <= budget)
    }

    fun testEditingOneFileOfALargeSetRefreshesWithinTheBudget() {
        repeat(150) { n -> myFixture.addFileToProject("tests/test_f$n.py", testFile(n, 12)) }
        val engine = TestMapEngine(project, scope, owner, routes, debounceMs = 30L).also { it.start(coldStart = false) }
        engine.refreshBlocking()
        var flip = false
        val result = PerfBudget.assertWithin("test-refresh-one-edited-file", Limits.BUDGET_TEST_REFRESH_ONE_FILE_MS, warmups = 3, runs = 9) {
            flip = !flip
            editFile("tests/test_f7.py", testFile(7, if (flip) 13 else 12))
            engine.refreshBlocking()
        }
        println("TESTS edit_refresh_ms_median=${result.medianMs} budget=${Limits.BUDGET_TEST_REFRESH_ONE_FILE_MS}")
        assertEquals(150 * 12 + (if (flip) 1 else 0), engine.index.value.stats.mappedCalls)
    }

    // ---- leaks -----------------------------------------------------------------------------------------------------

    private fun settle() = UIUtil.dispatchAllInvocationEvents()

    private fun engineScenario(): List<Pair<String, WeakReference<*>>> {
        myFixture.addFileToProject("tests/test_leak.py", testFile(0, 3))
        val child = Disposer.newDisposable("engine owner")
        Disposer.register(owner, child)
        val engine = TestMapEngine(project, scope, child, routes, debounceMs = 30L).also { it.start(coldStart = false) }
        engine.refreshBlocking()
        val refs = listOf("engine" to WeakReference(engine), "index" to WeakReference(engine.index.value))
        Disposer.dispose(child)
        waitUntil("stopped") { engine.isStopped }
        settle()
        return refs
    }

    fun testADisposedEngineAndItsIndexAreCollected() {
        engineScenario().forEach { (name, ref) -> LeakProbe.assertCollected("$name after dispose", ref, timeoutMs = 10_000) }
    }

    private fun tabScenario(): List<Pair<String, WeakReference<*>>> {
        val owner = Disposer.newDisposable("tests tab")
        val endpoint = com.routegenie.ui.request.endpoint(HttpMethod.POST, "/items", emptyList())
        val project = project
        val call = TestCall(
            ProjectTest("tests/t.py::test_a", "tests/t.py", null, "test_a", TestStyle.PYTEST, TestMark.NONE, SourceRef("tests/t.py", 0, 1)),
            HttpMethod.POST, "/items", SourceRef("tests/t.py", 5, 2), CallOrigin.DIRECT, false, null, 201, CallRequest.NONE,
        )
        val index = TestIndex(1, IndexPhase.READY, TestStats(1, 1, 1, 1, emptyMap()), mapOf(endpoint.key to listOf(call)), emptyList())
        val source = FakeIndexSource(index)
        val section = RequestSection(
            project, owner, engine = { FakeEngine() }, bodyTemplates = { BodyTemplateSource { null } }, ui = ManualUi(),
            state = RouteGenieClientState(), secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = ResponseSlot(),
            tests = { source },
        )
        section.show(endpoint)
        val tests = section.responseForTest().testsPanelForTest()!!
        tests.setShown(true)
        tests.selectRowForTest(0)
        val exec = tests.executionForTest()!!
        exec.runQuickTest()
        val refs = listOf("RequestSection" to WeakReference(section), "TestsPanel" to WeakReference(tests), "TestExecutionPanel" to WeakReference(exec), "quick form" to WeakReference(exec.formPanelForTest()))
        tests.setShown(false)
        Disposer.dispose(owner)
        settle()
        return refs
    }

    fun testTheTestsTabWithItsExecutionPageIsCollectedAfterDispose() {
        tabScenario().forEach { (name, ref) -> LeakProbe.assertCollected("$name after dispose", ref, timeoutMs = 10_000) }
    }

    fun testHeapDoesNotGrowOverManyEngineCycles() {
        myFixture.addFileToProject("tests/test_cycle.py", testFile(0, 5))
        LeakProbe.assertNoHeapGrowth("engine cycles", maxGrowthBytes = HEAP_GROWTH_LIMIT, warmups = 3, iterations = 12) {
            val child = Disposer.newDisposable("cycle")
            Disposer.register(owner, child)
            val engine = TestMapEngine(project, scope, child, routes, debounceMs = 30L).also { it.start(coldStart = false) }
            engine.refreshBlocking()
            Disposer.dispose(child)
            settle()
        }
    }

    fun testHeapDoesNotGrowOverManyTabCycles() {
        LeakProbe.assertNoHeapGrowth("tests tab cycles", maxGrowthBytes = HEAP_GROWTH_LIMIT, warmups = 8, iterations = 30) {
            val owner: Disposable = Disposer.newDisposable("loop")
            val panel = TestsPanel(project, owner, { FakeIndexSource(TestIndex.EMPTY) })
            panel.setShown(true)
            panel.show(com.routegenie.ui.request.endpoint())
            panel.setShown(false)
            Disposer.dispose(owner)
            settle()
        }
    }

    fun testSelectingATestOnAWarmIndexIsWithinTheUiBudget() {
        val owner = Disposer.newDisposable("select speed")
        try {
            val endpoint = com.routegenie.ui.request.endpoint(HttpMethod.POST, "/items", emptyList())
            val calls = (0 until 500).map { n ->
                TestCall(
                    ProjectTest("tests/t.py::test_$n", "tests/t.py", null, "test_$n", TestStyle.PYTEST, TestMark.NONE, SourceRef("tests/t.py", 0, 1)),
                    HttpMethod.POST, "/items", SourceRef("tests/t.py", n, n + 1), CallOrigin.DIRECT, false, null, 201,
                    CallRequest(listOf("a" to "1"), listOf("X" to "1"), BodyLiteral.Json("{\n  \"n\": $n\n}"), false),
                )
            }
            val index = TestIndex(1, IndexPhase.READY, TestStats(1, 500, 500, 500, emptyMap()), mapOf(endpoint.key to calls), emptyList())
            val section = RequestSection(
                project, owner, engine = { FakeEngine() }, bodyTemplates = { BodyTemplateSource { null } }, ui = ManualUi(),
                state = RouteGenieClientState(), secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = ResponseSlot(),
                tests = { FakeIndexSource(index) },
            )
            val tests = section.responseForTest().testsPanelForTest()!!
            tests.setShown(true)
            val show = PerfBudget.assertWithin("tests-tab-show-endpoint", Limits.BUDGET_TEST_SELECT_MS, warmups = 3, runs = 9) { section.show(endpoint) }
            tests.selectRowForTest(0)
            var row = 0
            val select = PerfBudget.assertWithin("tests-tab-select-test", Limits.BUDGET_TEST_SELECT_MS, warmups = 3, runs = 9) {
                row = (row + 1) % 500
                tests.selectRowForTest(row)
            }
            println("TESTS show_endpoint_ms=${show.medianMs} select_test_ms=${select.medianMs} budget=${Limits.BUDGET_TEST_SELECT_MS}")
        } finally {
            Disposer.dispose(owner)
        }
    }

    private companion object {
        const val HEAP_GROWTH_LIMIT = 3L * 1024 * 1024
    }
}
