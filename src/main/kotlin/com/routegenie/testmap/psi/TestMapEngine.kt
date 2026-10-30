package com.routegenie.testmap.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.PyFile
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.ProjectPaths
import com.routegenie.discovery.core.WeightedLruCache
import com.routegenie.frameworks.common.PyProviderSupport
import com.routegenie.model.RouteSnapshot
import com.routegenie.testmap.IndexPhase
import com.routegenie.testmap.LocatedTestFacts
import com.routegenie.testmap.TestFileFacts
import com.routegenie.testmap.TestIndex
import com.routegenie.testmap.TestLinker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * What one extraction of one test file produced, valid for exactly one [stamp] of that file. [facts] is null when the file
 * holds nothing the mapping uses; [failed] is set when the extractor threw, so a broken file is not retried until it changes.
 */
class TestFactsEntry(val stamp: Long, val facts: TestFileFacts?, val failed: Boolean) {
    val weight: Int = Limits.FACT_ENTRY_BYTES + (facts?.weight ?: 0)
}

/**
 * The test mapping pipeline, shaped exactly like route discovery ([com.routegenie.discovery.core.RouteDiscoveryEngine]):
 * candidates from the word index -> context-free per-file facts (cached by file and modification stamp, bounded by estimated
 * size) -> pure link against the current [routes] -> immutable [TestIndex] through a StateFlow.
 *
 * - A file is read in its own short read action on a small pool; typing can only cancel the one file in flight. Never on the UI thread.
 * - Lazy: while [setActive] is false (nobody looks at the tab) changes only mark the index stale and nothing is read.
 * - A new route snapshot or an edited test file triggers a debounced pass; a pass whose link input is identical publishes nothing.
 * - Parent disposal disconnects every listener and cancels every coroutine.
 */
class TestMapEngine(
    private val project: Project,
    private val scope: CoroutineScope,
    parent: Disposable,
    private val routes: StateFlow<RouteSnapshot>,
    private val debounceMs: Long = Limits.REFRESH_DEBOUNCE_MS,
    cacheWeightLimit: Long = Limits.MAX_TEST_FACTS_CACHE_BYTES,
    active: Boolean = true,
    parallelism: Int = Limits.extractParallelism(Runtime.getRuntime().availableProcessors()),
    private val findCandidates: (Project) -> List<VirtualFile> = TestCandidates::find,
    /** The project file of a dotted module name (`app.testutils.cases`), or null when the project has none (a library). */
    private val findModule: (Project, String) -> VirtualFile? = PyProviderSupport::findModuleFile,
) {
    private val log = Logger.getInstance(TestMapEngine::class.java)
    private val state = MutableStateFlow(TestIndex.EMPTY)
    private val generation = AtomicLong(0)
    private val runLock = Mutex()
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val lastTriggerNanos = AtomicLong(0)
    private val cache = WeightedLruCache<String, TestFactsEntry>(cacheWeightLimit) { it.weight }
    private val jobs = ArrayList<Job>()
    private val listeners: Disposable = Disposer.newDisposable("TestMapEngine listeners")

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workers = parallelism.coerceIn(1, Limits.EXTRACT_PARALLELISM_CAP)
    private val extractPool = Dispatchers.Default.limitedParallelism(workers)

    @Volatile
    private var disposed = false

    @Volatile
    private var active = active

    /** True when something may have changed since the last run that nobody has been shown yet. */
    @Volatile
    var isStale = true
        private set

    private val runs = AtomicInteger()
    val runCount: Int get() = runs.get()

    private val pythonTracker = PsiModificationTracker.getInstance(project).forLanguage(PythonLanguage.getInstance())

    @Volatile
    private var lastPythonCount = pythonTracker.modificationCount

    /** What the last link was made from: weakly held facts (the cache bounds memory) and the route snapshot. */
    @Volatile
    private var memo: Memo? = null

    val index: StateFlow<TestIndex> get() = state
    val cachedEntries: Int get() = cache.size
    val cachedWeight: Long get() = cache.weight
    val isStopped: Boolean get() = synchronized(jobs) { jobs.all { !it.isActive } }

    init {
        Disposer.register(parent, listeners)
        Disposer.register(parent) { stop() }
    }

    fun start(coldStart: Boolean = false) {
        val connection = project.messageBus.connect(listeners)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { isRelevant(it.path) }) requestRefresh()
            }
        })
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener {
            val count = pythonTracker.modificationCount
            if (count != lastPythonCount) {
                lastPythonCount = count
                requestRefresh()
            }
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() = requestRefresh()
        })
        synchronized(jobs) {
            jobs += scope.launch { debounceLoop() }
            // A new route list changes what the calls map to, even when no test file changed.
            jobs += scope.launch { routes.collect { if (it.generation > 0) requestRefresh() } }
            if (coldStart) launchColdScan()
        }
    }

    /** Switches the engine on or off. Turning it on runs a scan at once when changes (or the first scan) are pending. */
    fun setActive(value: Boolean) {
        active = value
        if (!value || disposed || !isStale) return
        synchronized(jobs) {
            if (runs.get() == 0) launchColdScan() else trigger()
        }
    }

    private fun launchColdScan() {
        jobs += scope.launch {
            withBackgroundProgress(project, RouteGenieBundle.message("tests.progress")) { refreshNow() }
        }
    }

    fun requestRefresh() {
        if (disposed) return
        isStale = true
        if (active) trigger()
    }

    private fun trigger() {
        lastTriggerNanos.set(System.nanoTime())
        triggers.trySend(Unit)
    }

    /** One full pass. Runs one at a time; a caller that arrives meanwhile waits and then runs against the newer state. */
    suspend fun refreshNow() {
        runLock.withLock {
            if (disposed) return
            runs.incrementAndGet()
            isStale = false
            if (state.value.phase != IndexPhase.READY) publishPhase(IndexPhase.INDEXING)
            try {
                pass()
            } catch (e: Throwable) {
                rethrowIfControlFlow(e)
                log.warn("Test mapping failed", e)
            }
        }
    }

    private fun publishPhase(phase: IndexPhase) {
        val previous = state.value
        state.value = TestIndex(generation.incrementAndGet(), phase, previous.stats, previous.byEndpoint, previous.unattributed)
    }

    private class Checked(val file: VirtualFile, val key: String, val stamp: Long, val path: String, val root: String, var entry: TestFactsEntry?)

    private suspend fun pass() {
        val candidates = smartReadAction(project) { findCandidates(project) }
        val checked = ArrayList<Checked>(candidates.size)
        val seen = HashSet<String>()
        load(candidates, checked, seen)
        var located = locate(checked)
        // Base classes of test cases live in project files that no test path or client word selects: read the ones that are named.
        for (round in 0 until Limits.MAX_DEPENDENCY_ROUNDS) {
            val missing = TestLinker.missingModules(located)
            if (missing.isEmpty()) break
            val files = smartReadAction(project) { missing.mapNotNull { findModule(project, it) } }.filter { it.isValid }.distinct()
            val before = checked.size
            load(files, checked, seen)
            if (checked.size == before) break
            located = locate(checked)
        }
        val live = HashSet<String>()
        var failed = 0
        for (c in checked) {
            live += c.key
            if (c.entry?.failed == true) failed++
        }
        cache.keys().forEach { if (it !in live) cache.remove(it) }

        val snapshot = routes.value
        if (snapshot.generation == 0L) {
            // The route list is not ready: keep the facts, show "indexing" until the route collector triggers the next pass.
            return
        }
        val previous = memo
        if (previous != null && previous.sameAs(located, snapshot, failed) && state.value.phase == IndexPhase.READY) return
        val linked = TestLinker.link(located, snapshot.endpoints, generation.incrementAndGet())
        val withFailures = if (failed == 0) linked else TestIndex(
            linked.generation, linked.phase, linked.stats.copy(failedFiles = failed), linked.byEndpoint, linked.unattributed,
        )
        memo = Memo(located.map { Input(it) }, snapshot, failed)
        state.value = withFailures
    }

    /** Brings the facts of [files] that are not in [seen] up to date: stamps are compared in cheap batches, changed files are extracted. */
    private suspend fun load(files: List<VirtualFile>, checked: MutableList<Checked>, seen: MutableSet<String>) {
        val fresh = ArrayList<Checked>()
        for (batch in files.chunked(Limits.STAMP_CHECK_BATCH_FILES)) {
            fresh += smartReadAction(project) { checkBatch(batch) }.filter { seen.add(it.key) }
        }
        val dirty = fresh.filter { it.entry == null }
        if (dirty.isNotEmpty()) extract(dirty)
        checked += fresh
    }

    private fun locate(checked: List<Checked>): List<LocatedTestFacts> =
        checked.mapNotNull { c -> c.entry?.facts?.let { LocatedTestFacts(c.path, it, c.root) } }

    private fun checkBatch(files: List<VirtualFile>): List<Checked> {
        val out = ArrayList<Checked>(files.size)
        for (file in files) {
            ProgressManager.checkCanceled()
            if (!file.isValid) continue
            val key = fileKeyOf(file)
            val stamp = stampOf(file)
            val path = ProjectPaths.relative(project, file)
            val entry = cache[key]?.takeIf { it.stamp == stamp }
            out += Checked(file, key, stamp, path, ProjectPaths.sourceRoot(project, file, path), entry)
        }
        return out
    }

    private suspend fun extract(dirty: List<Checked>) {
        val next = AtomicInteger()
        val interrupted = AtomicReference<Throwable?>()
        coroutineScope {
            repeat(minOf(workers, dirty.size)) {
                launch(extractPool) {
                    while (interrupted.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= dirty.size) break
                        val c = dirty[i]
                        try {
                            c.entry = smartReadAction(project) { extractOne(c) }
                        } catch (e: CancellationException) {
                            interrupted.compareAndSet(null, e)
                            break
                        }
                    }
                }
            }
        }
        interrupted.get()?.let { throw it }
    }

    private fun extractOne(c: Checked): TestFactsEntry? {
        if (!c.file.isValid) return null
        val psi = PsiManager.getInstance(project).findFile(c.file) ?: return null
        val stamp = stampOf(c.file)
        val entry = try {
            ProgressManager.checkCanceled()
            val facts = (psi as? PyFile)?.let { PyTestFactsExtractor.extract(it) }
            // Equal facts keep their old identity, so "this file changed nothing" is an identity check later.
            TestFactsEntry(stamp, facts?.let { fresh -> cache[c.key]?.facts?.takeIf { it == fresh } ?: fresh }, false)
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            log.warn("Test file analysis failed on ${c.file.path}", e)
            TestFactsEntry(stamp, null, true)
        }
        cache[c.key] = entry
        return entry
    }

    /** Changes whenever the text of [file] changes, without touching PSI; an uncommitted document counts as a marker no stamp equals. */
    private fun stampOf(file: VirtualFile): Long {
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return file.modificationStamp
        return if (PsiDocumentManager.getInstance(project).isCommitted(document)) document.modificationStamp else UNCOMMITTED_STAMP
    }

    private fun fileKeyOf(file: VirtualFile): String = ((file as? VirtualFileWithId)?.id ?: file.path.hashCode()).toString()

    private fun isRelevant(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return !name.contains('.') || name.endsWith(PYTHON_SUFFIX)
    }

    private class Input(val path: String, val root: String, val facts: WeakReference<TestFileFacts>) {
        constructor(located: LocatedTestFacts) : this(located.path, located.sourceRoot, WeakReference(located.source))

        fun matches(located: LocatedTestFacts) = path == located.path && root == located.sourceRoot && facts.get() === located.source
    }

    private class Memo(val inputs: List<Input>, routes: RouteSnapshot, val failed: Int) {
        private val routesRef = WeakReference(routes)

        fun sameAs(located: List<LocatedTestFacts>, snapshot: RouteSnapshot, failedNow: Int): Boolean =
            failed == failedNow && routesRef.get() === snapshot && inputs.size == located.size && inputs.indices.all { inputs[it].matches(located[it]) }
    }

    private suspend fun debounceLoop() {
        for (signal in triggers) {
            while (true) {
                val remainingMs = debounceMs - (System.nanoTime() - lastTriggerNanos.get()) / NANOS_PER_MS
                if (remainingMs <= 0) break
                delay(remainingMs)
            }
            triggers.tryReceive()
            if (!active) continue
            try {
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Test refresh failed", e)
            }
        }
    }

    private fun stop() {
        disposed = true
        triggers.close()
        synchronized(jobs) { jobs.forEach { it.cancel() } }
        cache.clear()
        memo = null
    }

    private fun rethrowIfControlFlow(e: Throwable) {
        if (e is CancellationException || e is ProcessCanceledException) throw e
    }

    private companion object {
        const val PYTHON_SUFFIX = ".py"
        const val UNCOMMITTED_STAMP = Long.MIN_VALUE
        const val NANOS_PER_MS = 1_000_000L
    }
}
