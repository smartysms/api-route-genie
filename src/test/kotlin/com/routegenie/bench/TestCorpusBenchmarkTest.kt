package com.routegenie.bench

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.routegenie.discovery.ProjectPaths
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.core.RouteDiscoveryEngine
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.RouteSnapshot
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.ProjectFiles
import com.routegenie.testing.PythonPlatformTestCase
import com.routegenie.testmap.IndexPhase
import com.routegenie.testmap.TestIndex
import com.routegenie.testmap.psi.TestCandidates
import com.routegenie.testmap.psi.TestMapEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * End-to-end benchmark of the Tests tab pipeline on real repositories, opt-in like [CorpusBenchmarkTest]: set
 * [BenchSettings.CORPUS_ENV] to the folder that holds the clones. The real route providers find the endpoints, then the real
 * test engine reads the test files and maps their calls. The code is read, never executed. Results: build/reports/routegenie/bench/<repo>-tests.txt.
 */
class TestCorpusBenchmarkTest : PythonPlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var owner: Disposable
    private val out = StringBuilder()
    private val contentRoots = ArrayList<VirtualFile>()

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        owner = Disposer.newDisposable("test corpus bench owner")
    }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            scope.cancel()
            contentRoots.forEach { runCatching { PsiTestUtil.removeContentEntry(module, it) } }
        } finally {
            try {
                super.tearDown()
            } catch (e: com.intellij.util.lang.CompoundRuntimeException) {
                if (!e.message.orEmpty().contains(WEB_MARKER)) throw e
                println("BENCH harness_warning=undisposed platform pointer to $WEB_MARKER ignored")
            }
        }
    }

    fun testFullStackFastapiTemplate() = bench("full-stack-fastapi-template")
    fun testFastapi() = bench("fastapi")
    fun testFlask() = bench("flask")
    fun testSuperset() = bench("superset")
    fun testDjango() = bench("django")
    fun testNetbox() = bench("netbox")
    fun testDjangoRestFramework() = bench("django-rest-framework")
    fun testSentry() = bench("sentry")

    private fun line(key: String, value: Any?) {
        out.append(key).append('=').append(value).append('\n')
        println("TESTBENCH $key=$value")
    }

    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.1f", ns / 1e6)

    private fun bench(repo: String) {
        val corpus = System.getenv(BenchSettings.CORPUS_ENV) ?: return
        val dir = File(corpus, repo)
        if (!dir.isDirectory) return
        val reportDir = File(ProjectFiles.root, BenchSettings.REPORT_DIR).also { it.mkdirs() }
        try {
            run(repo, dir, reportDir)
        } finally {
            File(reportDir, "$repo-tests.txt").writeText(out.toString())
        }
    }

    private fun excludeWebFolders(root: VirtualFile, dir: File) {
        val found = dir.walkTopDown().onEnter { it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.name == WEB_MARKER && it.parentFile != dir }.map { it.parentFile }.toList()
        val done = ArrayList<String>()
        for (folder in found) {
            val rel = folder.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (done.any { rel.startsWith("$it/") }) continue
            root.findFileByRelativePath(rel)?.let { PsiTestUtil.addExcludedRoot(module, it) }
            done += rel
        }
    }

    private fun run(repo: String, dir: File, reportDir: File) {
        line("repo", repo)
        line("cpus", Runtime.getRuntime().availableProcessors())
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir) ?: error("cannot open $dir")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        contentRoots += root
        BenchSettings.SOURCE_ROOTS[repo].orEmpty().forEach { rel -> root.findFileByRelativePath(rel)?.let { PsiTestUtil.addSourceRoot(module, it) } }
        excludeWebFolders(root, dir)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        // 1. Routes, exactly as the route benchmark does it.
        val providers = RouteProvider.EP_NAME.extensionList.sortedBy { it.id }
        val routeOwner = Disposer.newDisposable("route engine")
        Disposer.register(owner, routeOwner)
        val routeEngine = RouteDiscoveryEngine(project, scope, routeOwner, { providers }, active = false)
        val routeNs = pumped { routeEngine.refreshNow() }
        val snapshot = routeEngine.snapshot.value
        line("route_cold_ms", ms(routeNs))
        line("endpoints", snapshot.endpoints.size)
        line("endpoints_by_framework", snapshot.endpoints.groupingBy { it.framework }.eachCount())
        val routes = MutableStateFlow(snapshot)

        // 2. Which files the tests engine will read, and how many bytes they are.
        val candidates = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> { TestCandidates.find(project) }
        val bytes = candidates.sumOf { it.length }
        line("py_files", dir.walkTopDown().onEnter { it.name != ".git" }.count { it.isFile && it.extension == "py" })
        line("test_candidate_files", candidates.size)
        line("test_candidate_mb", String.format(Locale.ROOT, "%.2f", bytes / 1048576.0))

        val baseline = LeakProbe.usedHeapBytes()
        val engineOwner = Disposer.newDisposable("tests engine")
        Disposer.register(owner, engineOwner)
        val engine = TestMapEngine(project, scope, engineOwner, routes, active = false)
        val coldNs = pumped { engine.refreshNow() }
        val index = engine.index.value
        line("tests_cold_ms", ms(coldNs))
        line("tests_cold_ms_per_mb", String.format(Locale.ROOT, "%.0f", coldNs / 1e6 / maxOf(bytes / 1048576.0, 0.001)))
        line("phase", index.phase)
        line("test_files", index.stats.testFiles)
        line("test_functions", index.stats.testFunctions)
        line("client_calls", index.stats.clientCalls)
        line("unresolved_client_attribute_calls", index.stats.unresolvedReceivers)
        line("mapped_calls", index.stats.mappedCalls)
        line("unattributed_calls", index.stats.unattributedCalls)
        line("unattributed_by_reason", index.stats.unattributedByReason.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key.name.removePrefix("TEST_")}:${it.value}" })
        line("endpoints_reached", index.byEndpoint.size)
        line("endpoints_reached_percent", String.format(Locale.ROOT, "%.1f", 100.0 * index.byEndpoint.size / maxOf(snapshot.endpoints.size, 1)))
        line("distinct_tests_with_mapped_call", index.byEndpoint.values.flatten().map { it.test.id }.distinct().size)
        line("failed_files", index.stats.failedFiles)
        line("truncated", index.stats.truncated)
        val afterCold = LeakProbe.usedHeapBytes()
        line("heap_retained_after_cold_mb", (afterCold - baseline) / MB)
        line("facts_cache_entries", engine.cachedEntries)
        line("facts_cache_weight_mb", String.format(Locale.ROOT, "%.1f", engine.cachedWeight / 1048576.0))
        dumpUnattributed(repo, reportDir, index)

        // 3. A pass with nothing changed, then ten edits of the test file with the most calls.
        val warm = (1..BenchSettings.WARM_RUNS).map { pumped { engine.refreshNow() } }.sorted()
        line("warm_ms_median", ms(warm[warm.size / 2]))
        val target = index.byEndpoint.values.flatten().groupingBy { it.source.path }.eachCount().maxByOrNull { it.value }
        if (target == null) {
            line("edit_skipped", "no mapped calls")
        } else {
            val file = findProjectFile(root, target.key)
            line("edit_file", "${target.key} calls=${target.value} bytes=${file?.length}")
            if (file != null) editCycles(file, engine)
        }

        Disposer.dispose(engineOwner)
        line("final_phase", if (index.phase == IndexPhase.READY) "READY" else index.phase)
    }

    private fun findProjectFile(root: VirtualFile, relative: String): VirtualFile? {
        root.findFileByRelativePath(relative)?.let { return it }
        return com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { it.findFileByRelativePath(relative) }
    }

    private fun editCycles(file: VirtualFile, engine: TestMapEngine) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val original = document.text
        val times = ArrayList<Long>()
        try {
            repeat(BenchSettings.EDIT_CYCLES_TIMED) { n ->
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original + "\n\ndef test_bench_probe_$n(client):\n    client.get(\"/__bench_probe__\")\n") }
                PsiDocumentManager.getInstance(project).commitDocument(document)
                times += pumped { engine.refreshNow() }
            }
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        }
        val sorted = times.sorted()
        line("edit_refresh_ms_median", ms(sorted[sorted.size / 2]))
        line("edit_refresh_ms_all", times.joinToString(",") { ms(it) })
    }

    private fun dumpUnattributed(repo: String, reportDir: File, index: TestIndex) {
        File(reportDir, "$repo-tests-unattributed.tsv").bufferedWriter().use { w ->
            for (u in index.unattributed.take(DUMP_ROWS)) {
                w.write("${u.reason.name.removePrefix("TEST_")}\t${u.detail.orEmpty()}\t${u.source}\t${u.reach.javaClass.simpleName}\n")
            }
        }
        // The first ten examples of each reason, to read next to the numbers.
        for (code in DiagnosticCode.entries.filter { it.name.startsWith("TEST_") }) {
            val examples = index.unattributed.filter { it.reason == code }.take(EXAMPLES).joinToString(" | ") { "${it.source} ${it.detail.orEmpty()}" }
            if (examples.isNotEmpty()) line("examples_${code.name.removePrefix("TEST_").lowercase()}", examples)
        }
    }

    /** Runs [body] on a pool thread while this (UI) thread keeps pumping events; returns the elapsed nanoseconds. */
    private fun pumped(body: suspend () -> Unit): Long {
        var error: Throwable? = null
        val start = System.nanoTime()
        val job: Job = scope.launch {
            try {
                body()
            } catch (e: Throwable) {
                error = e
            }
        }
        val deadline = System.currentTimeMillis() + BenchSettings.RUN_TIMEOUT_MS
        while (!job.isCompleted) {
            if (System.currentTimeMillis() > deadline) {
                job.cancel()
                fail("exceeded ${BenchSettings.RUN_TIMEOUT_MS} ms")
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(BenchSettings.PUMP_SLEEP_MS)
        }
        error?.let { throw it }
        return System.nanoTime() - start
    }

    private companion object {
        const val WEB_MARKER = "package.json"
        const val MB = 1024L * 1024
        const val DUMP_ROWS = 20_000
        const val EXAMPLES = 10
    }
}
