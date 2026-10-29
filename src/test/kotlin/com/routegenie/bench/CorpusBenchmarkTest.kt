package com.routegenie.bench

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.SourceFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.core.RouteDiscoveryEngine
import com.routegenie.model.RouteSnapshot
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.ProjectFiles
import com.routegenie.testing.PythonPlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Names, sizes and knobs of the opt-in corpus benchmark. Nothing here ships; the benchmark only runs on request. */
object BenchSettings {
    /** Directory that holds one sub-directory per cloned repository. The benchmark is skipped when it is not set. */
    const val CORPUS_ENV = "ROUTEGENIE_CORPUS"

    /** Where results go, relative to the project directory. */
    const val REPORT_DIR = "build/reports/routegenie/bench"

    const val WARM_RUNS = 5
    const val EDIT_CYCLES_TIMED = 10
    const val EDIT_CYCLES_LEAK = 50
    const val PUMP_SLEEP_MS = 2L
    const val RUN_TIMEOUT_MS = 40L * 60 * 1000
    const val HEAP_SAMPLE_MS = 20L
    const val WAIT_CAP_MS = 20_000L
    const val SETTLE_MS = 150L
    const val PROBE_PATH = "/__bench_probe__"
    const val ADDED_DIR = "bench_added"
    const val COLLECT_TIMEOUT_MS = 10_000L

    /** Source roots a developer would have (PyCharm marks `src` and similar); relative to the repo root. */
    val SOURCE_ROOTS = mapOf(
        "full-stack-fastapi-template" to listOf("backend"),
        "flask" to listOf("src"),
        "sentry" to listOf("src"),
        "netbox" to listOf("netbox"),
    )
}

/** One provider wrapped with timers; the engine sees a normal provider, so the whole real pipeline is measured. */
class TimedProvider(private val inner: RouteProvider, val stats: Stats) : RouteProvider {
    class Stats {
        val candidatesNs = AtomicLong()
        val candidates = AtomicLong()
        val extractNs = AtomicLong()
        val extractCalls = AtomicLong()
        val extractFacts = AtomicLong()
        val linkNs = AtomicLong()
        val edtCalls = AtomicLong()
        val edtTotal = AtomicLong()
        fun reset() {
            listOf(candidatesNs, candidates, extractNs, extractCalls, extractFacts, linkNs, edtCalls).forEach { it.set(0) }
        }
    }

    override val id: String get() = inner.id

    private fun noteThread() {
        if (ApplicationManager.getApplication().isDispatchThread) {
            stats.edtCalls.incrementAndGet()
            stats.edtTotal.incrementAndGet()
        }
    }

    override fun findCandidates(project: Project): Collection<VirtualFile> {
        noteThread()
        val t = System.nanoTime()
        val r = inner.findCandidates(project)
        stats.candidatesNs.addAndGet(System.nanoTime() - t)
        stats.candidates.addAndGet(r.size.toLong())
        return r
    }

    override fun extract(file: PsiFile): SourceFacts? {
        noteThread()
        val t = System.nanoTime()
        val r = inner.extract(file)
        stats.extractNs.addAndGet(System.nanoTime() - t)
        stats.extractCalls.incrementAndGet()
        if (r != null) stats.extractFacts.incrementAndGet()
        return r
    }

    override fun extractDependency(file: PsiFile): SourceFacts? = inner.extractDependency(file)

    override fun missingModules(facts: Collection<FileFacts>): Set<String> = inner.missingModules(facts)

    override fun findModuleFile(project: Project, module: String): VirtualFile? = inner.findModuleFile(project, module)

    override fun link(facts: Collection<FileFacts>): LinkResult {
        noteThread()
        val t = System.nanoTime()
        val r = inner.link(facts)
        stats.linkNs.addAndGet(System.nanoTime() - t)
        return r
    }
}

/**
 * End-to-end benchmark of the real providers and the real engine on a real repository, loaded as a content root of the
 * light test project (no files are copied). Opt-in: set [BenchSettings.CORPUS_ENV]. The code of the repositories is read,
 * never executed. Results are written to [BenchSettings.REPORT_DIR].
 */
class CorpusBenchmarkTest : PythonPlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var owner: Disposable
    private val out = StringBuilder()
    private val stats = LinkedHashMap<String, TimedProvider.Stats>()
    private val heapPeak = AtomicLong()
    private val contentRoots = ArrayList<VirtualFile>()

    @Volatile
    private var sampling = false

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        owner = Disposer.newDisposable("corpus bench owner")
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
                // The JS web-symbols context of the platform keeps file pointers to the repo's front end package.json
                // in a project-level cache; the harness reports them as undisposed. Not ours, so it must not fail the run.
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
        println("BENCH $key=$value")
    }

    private fun ms(ns: Long) = String.format(Locale.ROOT, "%.1f", ns / 1e6)

    private fun bench(repo: String) {
        val corpus = System.getenv(BenchSettings.CORPUS_ENV) ?: return
        val dir = File(corpus, repo)
        if (!dir.isDirectory) return
        val reportDir = File(ProjectFiles.root, BenchSettings.REPORT_DIR).also { it.mkdirs() }
        try {
            runRepo(repo, dir, reportDir)
        } finally {
            File(reportDir, "$repo.txt").writeText(out.toString())
        }
    }

    private fun excludeWebFolders(root: VirtualFile, dir: File): List<String> {
        val found = dir.walkTopDown().onEnter { it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.name == WEB_MARKER && it.parentFile != dir }.map { it.parentFile }.toList()
        val result = ArrayList<String>()
        for (folder in found) {
            val rel = folder.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (result.any { rel.startsWith("$it/") }) continue
            val vf = root.findFileByRelativePath(rel) ?: continue
            PsiTestUtil.addExcludedRoot(module, vf)
            result += rel
        }
        return result
    }

    private fun pyFileCount(dir: File): Int =
        dir.walkTopDown().onEnter { it.name != ".git" }.count { it.isFile && it.extension == "py" }

    private fun runRepo(repo: String, dir: File, reportDir: File) {
        val rt = Runtime.getRuntime()
        line("repo", repo)
        line("max_heap_mb", rt.maxMemory() / MB)
        line("cpus", rt.availableProcessors())
        line("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
        line("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
        val pyFiles = pyFileCount(dir)
        line("py_files", pyFiles)

        val t0 = System.nanoTime()
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir) ?: error("cannot open $dir")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        contentRoots += root
        BenchSettings.SOURCE_ROOTS[repo].orEmpty().forEach { rel ->
            root.findFileByRelativePath(rel)?.let { PsiTestUtil.addSourceRoot(module, it) }
        }
        // Web front ends (package.json) are not Python and make the platform keep file pointers that the test harness
        // reports as leaks at teardown; a Python plugin user's front end folders are excluded the same way in practice.
        val excluded = excludeWebFolders(root, dir)
        line("excluded_web_folders", excluded.joinToString(","))
        val tVfs = System.nanoTime()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val tIdx = System.nanoTime()
        line("vfs_refresh_ms", ms(tVfs - t0))
        line("indexing_ms", ms(tIdx - tVfs))

        val providers = RouteProvider.EP_NAME.extensionList.sortedBy { it.id }
        line("providers", providers.joinToString(",") { it.id })
        val timed = providers.map { p -> TimedProvider(p, TimedProvider.Stats().also { stats[p.id] = it }) }

        val baseline = LeakProbe.usedHeapBytes()
        line("heap_baseline_mb", baseline / MB)

        val engineOwner = Disposer.newDisposable("engine A")
        Disposer.register(owner, engineOwner)
        val ref = phaseA(repo, root, reportDir, pyFiles, baseline, timed, engineOwner)

        Disposer.dispose(engineOwner)
        runCatching { LeakProbe.assertCollected("engine after dispose", ref, BenchSettings.COLLECT_TIMEOUT_MS) }
            .onSuccess { line("engine_collectable_after_dispose", true) }
            .onFailure { line("engine_collectable_after_dispose", "NO: ${it.message}") }

        addDelete(timed)
        line("edt_calls_in_providers", stats.values.sumOf { it.edtTotal.get() })
    }

    private fun resetStats() = stats.values.forEach { it.reset() }

    private fun phaseA(
        repo: String,
        root: VirtualFile,
        reportDir: File,
        pyFiles: Int,
        baseline: Long,
        timed: List<TimedProvider>,
        engineOwner: Disposable,
    ): WeakReference<RouteDiscoveryEngine> {
        val engine = RouteDiscoveryEngine(project, scope, engineOwner, { timed }, active = false)
        resetStats()
        startHeapSampler()
        val cold = timedPumped("cold") { engine.refreshNow() }
        stopHeapSampler()
        val snap = engine.snapshot.value
        line("cold_ms", ms(cold.ns))
        line("cold_files_per_s", String.format(Locale.ROOT, "%.0f", pyFiles / (cold.ns / 1e9)))
        line("cold_max_edt_gap_ms", cold.maxGapMs)
        line("heap_peak_sampled_mb", heapPeak.get() / MB)
        phaseReport("cold")
        summarize(repo, snap, reportDir)
        val afterCold = LeakProbe.usedHeapBytes()
        line("heap_after_cold_mb", afterCold / MB)
        line("heap_retained_after_cold_mb", (afterCold - baseline) / MB)
        line("cache_entries", engine.cachedEntries)

        val warm = ArrayList<Long>()
        repeat(BenchSettings.WARM_RUNS) {
            resetStats()
            warm += timedPumped("warm") { engine.refreshNow() }.ns
        }
        line("warm_ms_median", ms(warm.sorted()[warm.size / 2]))
        line("warm_ms_all", warm.joinToString(",") { ms(it) })
        phaseReport("warm_last")

        val perFile = snap.endpoints.groupingBy { it.source.path }.eachCount()
        val target = perFile.filterKeys { !it.contains("test") }.maxByOrNull { it.value } ?: perFile.maxByOrNull { it.value }
        if (target == null) {
            line("edit_skipped", "no endpoints")
        } else {
            val file = findProjectFile(root, target.key)
            line("edit_file", "${target.key} endpoints=${target.value} bytes=${file?.length}")
            if (file != null) editBenchmarks(file, snap, engine, timed)
        }
        return WeakReference(engine)
    }

    private class Timed(val ns: Long, val maxGapMs: Long)

    /** Runs [body] on a pool thread while this (UI) thread keeps pumping events; the longest gap between pumps is the UI stall. */
    private fun timedPumped(label: String, body: suspend () -> Unit): Timed {
        var error: Throwable? = null
        val start = System.nanoTime()
        val job: Job = scope.launch {
            try {
                body()
            } catch (e: Throwable) {
                error = e
            }
        }
        var last = System.nanoTime()
        var maxGap = 0L
        val deadline = System.currentTimeMillis() + BenchSettings.RUN_TIMEOUT_MS
        while (!job.isCompleted) {
            if (System.currentTimeMillis() > deadline) {
                job.cancel()
                line("TIMEOUT", label)
                fail("$label exceeded ${BenchSettings.RUN_TIMEOUT_MS} ms")
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val now = System.nanoTime()
            maxGap = maxOf(maxGap, (now - last) / 1_000_000)
            Thread.sleep(BenchSettings.PUMP_SLEEP_MS)
            last = System.nanoTime()
        }
        val ns = System.nanoTime() - start
        error?.let {
            line("ERROR_$label", it.toString())
            throw it
        }
        return Timed(ns, maxGap)
    }

    private fun phaseReport(tag: String) {
        for ((id, s) in stats) {
            line(
                "phase_${tag}_$id",
                "candidates=${s.candidates.get()} candidates_ms=${ms(s.candidatesNs.get())} extract_calls=${s.extractCalls.get()} " +
                    "extract_with_facts=${s.extractFacts.get()} extract_ms=${ms(s.extractNs.get())} link_ms=${ms(s.linkNs.get())}",
            )
        }
    }

    private fun startHeapSampler() {
        sampling = true
        heapPeak.set(0)
        val rt = Runtime.getRuntime()
        Thread {
            while (sampling) {
                heapPeak.accumulateAndGet(rt.totalMemory() - rt.freeMemory()) { a, b -> maxOf(a, b) }
                Thread.sleep(BenchSettings.HEAP_SAMPLE_MS)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun stopHeapSampler() {
        sampling = false
    }

    private fun summarize(repo: String, snap: RouteSnapshot, reportDir: File) {
        line("endpoints", snap.endpoints.size)
        line("diagnostics", snap.diagnostics.size)
        snap.endpoints.groupingBy { it.framework.name }.eachCount().toSortedMap().forEach { (k, v) -> line("endpoints_by_framework.$k", v) }
        snap.endpoints.groupingBy { it.method.name }.eachCount().toSortedMap().forEach { (k, v) -> line("endpoints_by_method.$k", v) }
        snap.endpoints.groupingBy { it.framework.name + "/" + it.method.name }.eachCount().toSortedMap()
            .forEach { (k, v) -> line("endpoints_by_framework_method.$k", v) }
        snap.diagnostics.groupingBy { it.code.name }.eachCount().toSortedMap().forEach { (k, v) -> line("diagnostics_by_code.$k", v) }
        snap.diagnostics.groupingBy { it.severity.name }.eachCount().toSortedMap().forEach { (k, v) -> line("diagnostics_by_severity.$k", v) }
        val bad = snap.endpoints.filter { !it.path.startsWith("/") || it.path.contains("{?}") || it.isPartial }
        line("invariant_violations", bad.size)
        File(reportDir, "$repo-endpoints.tsv").bufferedWriter().use { w ->
            for (e in snap.endpoints) {
                w.write("${e.framework}\t${e.method}\t${e.path}\t${e.handler.qualifiedName}\t${e.source.path}\t${e.source.line}\n")
            }
        }
        File(reportDir, "$repo-diagnostics.tsv").bufferedWriter().use { w ->
            for (d in snap.diagnostics) {
                w.write("${d.code}\t${d.severity}\t${d.args.joinToString("|").replace('\n', ' ')}\t${d.source?.path}\t${d.source?.line}\n")
            }
        }
    }

    private fun findProjectFile(root: VirtualFile, relative: String): VirtualFile? {
        root.findFileByRelativePath(relative)?.let { return it }
        BenchSettings.SOURCE_ROOTS.values.flatten().forEach { sr -> root.findFileByRelativePath("$sr/$relative")?.let { return it } }
        return null
    }

    private fun document(file: VirtualFile): Document =
        FileDocumentManager.getInstance().getDocument(file) ?: error("no document ${file.path}")

    private fun edit(doc: Document, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { action() }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
    }

    private fun probeText(text: String, n: Int): String {
        val fast = Regex("(?m)^(\\w+)\\s*(?::[^=\\n]+)?=\\s*(?:fastapi\\.)?(APIRouter|FastAPI)\\(").find(text)
        val flask = Regex("(?m)^(\\w+)\\s*=\\s*(?:flask\\.)?(Blueprint|Flask)\\(").find(text)
        return when {
            fast != null -> "\n\n@${fast.groupValues[1]}.get(\"${BenchSettings.PROBE_PATH}$n\")\ndef bench_probe_$n():\n    return None\n"
            flask != null -> "\n\n@${flask.groupValues[1]}.route(\"${BenchSettings.PROBE_PATH}$n\")\ndef bench_probe_$n():\n    return None\n"
            else -> "\n\nurlpatterns = urlpatterns + [path(\"bench-probe-$n/\", bench_probe_view_$n)]\n"
        }
    }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    private fun editBenchmarks(file: VirtualFile, snap: RouteSnapshot, engine: RouteDiscoveryEngine, timed: List<TimedProvider>) {
        val doc = document(file)
        val original = doc.text
        val refreshOnly = ArrayList<Long>()
        var changedEndpoints = 0
        var n = 0
        repeat(BenchSettings.EDIT_CYCLES_TIMED) {
            val probe = probeText(original, n++)
            edit(doc) { doc.insertString(doc.textLength, probe) }
            val before = engine.snapshot.value
            refreshOnly += timedPumped("edit-refresh") { engine.refreshNow() }.ns
            if (engine.snapshot.value.endpoints.size != before.endpoints.size) changedEndpoints++
            edit(doc) { doc.setText(original) }
            refreshOnly += timedPumped("revert-refresh") { engine.refreshNow() }.ns
        }
        val sorted = refreshOnly.sorted()
        line("edit_refresh_ms_median", ms(median(refreshOnly)))
        line("edit_refresh_ms_best", ms(sorted.first()))
        line("edit_refresh_ms_worst", ms(sorted.last()))
        line("edit_refresh_ms_all", refreshOnly.joinToString(",") { ms(it) })
        line("edit_cycles_that_changed_endpoint_count", changedEndpoints)
        resetStats()
        edit(doc) { doc.insertString(doc.textLength, probeText(original, n++)) }
        resetStats()
        timedPumped("edit-refresh-profile") { engine.refreshNow() }
        phaseReport("edit_one")
        edit(doc) { doc.setText(original) }
        timedPumped("revert") { engine.refreshNow() }

        // End to end through the engine's own listeners and debounce: the user types, the snapshot must change.
        val liveOwner = Disposer.newDisposable("engine live")
        Disposer.register(owner, liveOwner)
        val live = RouteDiscoveryEngine(project, scope, liveOwner, { timed }, active = true)
        live.start(coldStart = false)
        timedPumped("live-prime") { live.refreshNow() }
        val e2e = ArrayList<Long>()
        repeat(BenchSettings.EDIT_CYCLES_TIMED) {
            val gen = live.snapshot.value.generation
            val runs = live.runCount
            val probe = probeText(original, n++)
            val t = System.nanoTime()
            edit(doc) { doc.insertString(doc.textLength, probe) }
            waitGeneration(live, gen, runs)
            e2e += System.nanoTime() - t
            val gen2 = live.snapshot.value.generation
            val runs2 = live.runCount
            edit(doc) { doc.setText(original) }
            waitGeneration(live, gen2, runs2)
        }
        line("edit_e2e_ms_median_incl_debounce", ms(median(e2e)))
        line("edit_e2e_ms_median_minus_debounce", ms(median(e2e) - Limits.REFRESH_DEBOUNCE_MS * 1_000_000))
        line("edit_e2e_ms_all", e2e.joinToString(",") { ms(it) })
        line("debounce_ms", Limits.REFRESH_DEBOUNCE_MS)
        Disposer.dispose(liveOwner)

        val heapBefore = LeakProbe.usedHeapBytes()
        repeat(BenchSettings.EDIT_CYCLES_LEAK) {
            edit(doc) { doc.insertString(doc.textLength, probeText(original, n++)) }
            timedPumped("leak-edit") { engine.refreshNow() }
            edit(doc) { doc.setText(original) }
            timedPumped("leak-revert") { engine.refreshNow() }
        }
        val heapAfter = LeakProbe.usedHeapBytes()
        line("heap_before_50_edits_mb", heapBefore / MB)
        line("heap_after_50_edits_mb", heapAfter / MB)
        line("heap_growth_50_edits_kb", (heapAfter - heapBefore) / 1024)
        line("cache_entries_after_edits", engine.cachedEntries)
        line("endpoints_after_edits_restored", engine.snapshot.value.endpoints.size == snap.endpoints.size)
    }

    /**
     * Waits until the engine reacted to a change: the snapshot changed, or a pass ran and nothing further was pending for
     * a settle period (an edit that changes no endpoint publishes nothing). Returns false on the cap, never hangs.
     */
    private fun waitGeneration(engine: RouteDiscoveryEngine, previous: Long, runsBefore: Int = -1): Boolean {
        val deadline = System.currentTimeMillis() + BenchSettings.WAIT_CAP_MS
        var quietSince = 0L
        var seenRuns = runsBefore
        while (engine.snapshot.value.generation == previous) {
            val now = System.currentTimeMillis()
            if (now > deadline) {
                line("NO_REACTION", "snapshot unchanged after ${BenchSettings.WAIT_CAP_MS} ms")
                return false
            }
            if (runsBefore >= 0 && engine.runCount > runsBefore) {
                if (engine.runCount != seenRuns || engine.isStale) {
                    seenRuns = engine.runCount
                    quietSince = now
                } else if (quietSince == 0L) {
                    quietSince = now
                } else if (now - quietSince > BenchSettings.SETTLE_MS) {
                    return true
                }
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(1)
        }
        return true
    }

    private fun addDelete(timed: List<TimedProvider>) {
        val tmp = java.nio.file.Files.createTempDirectory("routegenie-bench-added").toFile()
        try {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp) ?: error("tmp")
            PsiTestUtil.addContentRoot(module, vf)
            contentRoots += vf
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val liveOwner = Disposer.newDisposable("engine add-delete")
            Disposer.register(owner, liveOwner)
            val live = RouteDiscoveryEngine(project, scope, liveOwner, { timed }, active = true)
            live.start(coldStart = false)
            timedPumped("add-prime") { live.refreshNow() }
            val addMs = ArrayList<Long>()
            val delMs = ArrayList<Long>()
            repeat(BenchSettings.EDIT_CYCLES_TIMED) { i ->
                val gen = live.snapshot.value.generation
                val t = System.nanoTime()
                var created: VirtualFile? = null
                WriteCommandAction.runWriteCommandAction(project) {
                    val d = vf.findChild(BenchSettings.ADDED_DIR) ?: vf.createChildDirectory(this, BenchSettings.ADDED_DIR)
                    val f = d.createChildData(this, "added_$i.py")
                    VfsUtil.saveText(f, "from fastapi import FastAPI\napp = FastAPI()\n\n@app.get(\"/added$i\")\ndef added_$i():\n    return 1\n")
                    created = f
                }
                waitGeneration(live, gen)
                addMs += System.nanoTime() - t
                if (live.snapshot.value.endpoints.none { it.path == "/added$i" }) line("ADD_NOT_FOUND", i)
                val gen2 = live.snapshot.value.generation
                val t2 = System.nanoTime()
                WriteCommandAction.runWriteCommandAction(project) { created!!.delete(this) }
                waitGeneration(live, gen2)
                delMs += System.nanoTime() - t2
                if (live.snapshot.value.endpoints.any { it.path == "/added$i" }) line("DELETE_STILL_LISTED", i)
            }
            line("add_file_e2e_ms_median_incl_debounce", ms(median(addMs)))
            line("delete_file_e2e_ms_median_incl_debounce", ms(median(delMs)))
            line("add_file_ms_all", addMs.joinToString(",") { ms(it) })
            line("delete_file_ms_all", delMs.joinToString(",") { ms(it) })
            Disposer.dispose(liveOwner)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val WEB_MARKER = "package.json"
    }
}
