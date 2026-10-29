package com.routegenie.discovery.core

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
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.openapi.vfs.VirtualFileWithId
import com.jetbrains.python.PythonLanguage
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.SourceFacts
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.ProjectPaths
import com.routegenie.discovery.RouteProvider
import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.EndpointKeys
import com.routegenie.model.PathSyntax
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.Severity
import com.routegenie.model.SourceRef
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

/**
 * The discovery pipeline: candidates -> context-free per-file facts (cached by file and modification stamp, bounded by
 * estimated size) -> the files those facts depend on -> link per provider outside any lock -> immutable [RouteSnapshot].
 *
 * Shape of the work, and why:
 * - A file is read in its own short read action, never in a batch, so typing (a write action) can only cancel the one
 *   file in flight; the cheap comparison of cached stamps runs in larger batches because it never parses.
 * - Extraction runs on a small pool ([parallelism]) and never on the UI thread.
 * - Facts hold nothing from other files, so a file that other files depend on is just another cached entry: when it
 *   changes, the files that use it are relinked, not re-parsed. Which extra files are needed is asked of the provider
 *   ([RouteProvider.missingModules]) after each round, until nothing is missing.
 * - When a pass leaves every provider's linker input equal to the last one, nothing is linked and nothing is published.
 *
 * The owner passes a [parent] that disposes the engine: listeners are disconnected and every coroutine it started is
 * cancelled. [providers] is a supplier so tests can drive the engine with fakes.
 */
class RouteDiscoveryEngine(
    private val project: Project,
    private val scope: CoroutineScope,
    parent: Disposable,
    private val providers: () -> List<RouteProvider>,
    private val debounceMs: Long = Limits.REFRESH_DEBOUNCE_MS,
    cacheWeightLimit: Long = Limits.MAX_FACTS_CACHE_BYTES,
    active: Boolean = true,
    parallelism: Int = Limits.extractParallelism(Runtime.getRuntime().availableProcessors()),
) {
    private val log = Logger.getInstance(RouteDiscoveryEngine::class.java)
    private val state = MutableStateFlow(RouteSnapshot.EMPTY)
    private val generation = AtomicLong(0)
    private val runLock = Mutex()
    private val triggers = Channel<Unit>(Channel.CONFLATED)

    /** When the newest request arrived; the debounce waits one delay after this instant, however many requests came before. */
    private val lastTriggerNanos = AtomicLong(0)
    private val cache = WeightedLruCache<String, FactsEntry>(cacheWeightLimit) { it.weight }
    private val jobs = ArrayList<Job>()
    private val listeners: Disposable = Disposer.newDisposable("RouteDiscoveryEngine listeners")

    /** Module name to project file, with "no such file" remembered too; valid while [layoutVersion] is unchanged. */
    private val moduleFiles = WeightedLruCache<String, ModuleFile>(Limits.MAX_MODULE_LOOKUPS.toLong()) { 1 }
    private val layoutVersion = AtomicLong(0)
    private var moduleFilesVersion = -1L

    /** What the last pass linked per provider, to skip linking and publishing when nothing changed. */
    private val memo = HashMap<String, Memo>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val workers = parallelism.coerceIn(1, Limits.EXTRACT_PARALLELISM_CAP)
    private val extractPool = Dispatchers.Default.limitedParallelism(workers)

    @Volatile
    private var disposed = false

    /** False while nobody looks at the result (tool window hidden): changes then only mark the snapshot stale. */
    @Volatile
    private var active = active

    /** True when something may have changed since the last run that nobody has been shown yet. */
    @Volatile
    var isStale = true
        private set

    private val runs = AtomicInteger()

    /** How many discovery passes have started; tests use it to prove that irrelevant changes cost no pass at all. */
    val runCount: Int get() = runs.get()

    private val pythonTracker = PsiModificationTracker.getInstance(project).forLanguage(PythonLanguage.getInstance())

    @Volatile
    private var lastPythonCount = pythonTracker.modificationCount

    val snapshot: StateFlow<RouteSnapshot> get() = state

    /** Number of cached per-file entries, for tests that check the cache stays bounded. */
    val cachedEntries: Int get() = cache.size

    /** Estimated bytes of the cached facts, for the same tests. */
    val cachedWeight: Long get() = cache.weight

    /** True once every coroutine this engine started has finished or been cancelled. */
    val isStopped: Boolean get() = synchronized(jobs) { jobs.all { !it.isActive } }

    init {
        Disposer.register(parent, listeners)
        Disposer.register(parent) { stop() }
    }

    /** Subscribes to change events and starts the debounce loop; [coldStart] also schedules the first full scan. */
    fun start(coldStart: Boolean = true) {
        val connection = project.messageBus.connect(listeners)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // A checkout delivers one batch for thousands of files; the debounce turns it into one pass.
                val relevant = events.filter { isRelevant(it.path) }
                if (relevant.isEmpty()) return
                if (relevant.any { it.changesLayout() }) layoutVersion.incrementAndGet()
                requestRefresh()
            }
        })
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener {
            // Edits in other languages (markdown, json) leave the Python counter alone and cost nothing here.
            val count = pythonTracker.modificationCount
            if (count != lastPythonCount) {
                lastPythonCount = count
                requestRefresh()
            }
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() {
                layoutVersion.incrementAndGet()
                requestRefresh()
            }
        })
        synchronized(jobs) {
            jobs += scope.launch { debounceLoop() }
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
            withBackgroundProgress(project, RouteGenieBundle.message("progress.scanning")) { refreshNow() }
        }
    }

    /** Asks for a refresh after the quiet period; many calls in a row cause one run. Cheap and thread-safe. */
    fun requestRefresh() {
        if (disposed) return
        isStale = true
        if (active) trigger()
    }

    private fun trigger() {
        lastTriggerNanos.set(System.nanoTime())
        triggers.trySend(Unit)
    }

    /**
     * One full pass: re-reads only the files whose stamp changed, relinks what changed, publishes if the result differs.
     * Runs one at a time; a caller that arrives meanwhile waits and then runs again against the newer state.
     */
    suspend fun refreshNow() {
        runLock.withLock {
            if (disposed) return
            runs.incrementAndGet()
            isStale = false
            val endpoints = ArrayList<Endpoint>()
            val diagnostics = ArrayList<Diagnostic>()
            var changed = false
            val sorted = providers().sortedBy { it.id }
            if (memo.keys != sorted.mapTo(HashSet()) { it.id }) changed = true
            memo.keys.retainAll(sorted.mapTo(HashSet()) { it.id })
            for (provider in sorted) {
                val outcome = runProvider(provider)
                changed = changed || outcome.changed
                endpoints += outcome.endpoints
                diagnostics += outcome.diagnostics
            }
            if (changed || state.value.generation == 0L) publish(endpoints, diagnostics)
        }
    }

    private class Outcome(val endpoints: List<Endpoint>, val diagnostics: List<Diagnostic>, val changed: Boolean)

    /**
     * What a link was made from. The facts are held weakly on purpose: the cache bounds how much extracted data stays in
     * memory, and a strong reference here would defeat it. A fact object that was evicted and collected counts as changed.
     */
    private class Memo(
        val inputs: List<Input>,
        val failures: List<Diagnostic>,
        val endpoints: List<Endpoint>,
        val diagnostics: List<Diagnostic>,
    ) {
        fun sameAs(located: List<LocatedFacts>, newFailures: List<Diagnostic>): Boolean =
            failures == newFailures && inputs.size == located.size &&
                inputs.indices.all { i -> inputs[i].matches(located[i]) }
    }

    private class Input(val path: String, val root: String, val facts: WeakReference<SourceFacts>) {
        constructor(located: LocatedFacts) : this(located.path, located.sourceRoot, WeakReference(located.source))

        fun matches(located: LocatedFacts) = path == located.path && root == located.sourceRoot && facts.get() === located.source
    }

    private class ModuleFile(val file: VirtualFile?)

    /** One file of a pass: where it is, its cache key and stamp, and the entry that is valid for it (null: must be extracted). */
    private class Checked(val file: VirtualFile, val key: String, val fileKey: String, val stamp: Long, val path: String, val root: String, var entry: FactsEntry?)

    private suspend fun runProvider(provider: RouteProvider): Outcome {
        try {
            val candidates = smartReadAction(project) { provider.findCandidates(project).toList() }
            val located = LinkedHashMap<String, LocatedFacts>()
            val failures = ArrayList<Diagnostic>()
            val live = HashSet<String>()
            load(provider, candidates, dependency = false, located, failures, live)
            for (round in 0 until Limits.MAX_DEPENDENCY_ROUNDS) {
                val missing = provider.missingModules(located.values)
                if (missing.isEmpty()) break
                val files = filesOf(provider, missing).filter { fileKeyOf(it) !in located }
                if (files.isEmpty()) break
                load(provider, files, dependency = true, located, failures, live)
            }
            evictUnused(provider, live)
            val inputs = located.values.toList()
            val previous = memo[provider.id]
            if (previous != null && previous.sameAs(inputs, failures)) {
                return Outcome(previous.endpoints, previous.diagnostics, changed = false)
            }
            // No lock is held here: linking is pure data work and may take a while for large projects.
            val linked = provider.link(inputs)
            val diagnostics = failures + linked.diagnostics
            memo[provider.id] = Memo(inputs.map(::Input), failures, linked.endpoints, diagnostics)
            return Outcome(linked.endpoints, diagnostics, changed = true)
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            log.warn("Route provider ${provider.id} failed", e)
            memo.remove(provider.id)
            return Outcome(emptyList(), listOf(failure(provider.id, null, e)), changed = true)
        }
    }

    /**
     * Brings the facts of [files] up to date: stamps are compared in cheap batches, files whose stamp changed are
     * extracted one per read action on the pool. Located facts are appended to [located] (first one wins per file).
     */
    private suspend fun load(
        provider: RouteProvider,
        files: List<VirtualFile>,
        dependency: Boolean,
        located: MutableMap<String, LocatedFacts>,
        failures: MutableList<Diagnostic>,
        live: MutableSet<String>,
    ) {
        val checked = ArrayList<Checked>(files.size)
        for (batch in files.chunked(Limits.STAMP_CHECK_BATCH_FILES)) {
            checked += smartReadAction(project) { checkBatch(provider, batch, dependency) }
        }
        val dirty = checked.filter { it.entry == null }
        if (dirty.isNotEmpty()) extract(provider, dirty, dependency)
        for (c in checked) {
            live += c.key
            val entry = c.entry ?: continue
            entry.failure?.let { failures += it }
            entry.facts?.let { located.putIfAbsent(c.fileKey, LocatedFacts(c.path, it, c.root)) }
        }
    }

    /** Runs inside a read action that may restart, so it builds fresh objects each time; it only reads and never parses. */
    private fun checkBatch(provider: RouteProvider, files: List<VirtualFile>, dependency: Boolean): List<Checked> {
        val out = ArrayList<Checked>(files.size)
        for (file in files) {
            ProgressManager.checkCanceled()
            if (!file.isValid) continue
            val fileKey = fileKeyOf(file)
            val key = cacheKey(provider, fileKey, dependency)
            val stamp = stampOf(file)
            val path = ProjectPaths.relative(project, file)
            val entry = cache[key]?.takeIf { it.stamp == stamp }
            out += Checked(file, key, fileKey, stamp, path, ProjectPaths.sourceRoot(project, file, path), entry)
        }
        return out
    }

    /** Extracts [dirty] files, one read action each, on at most [workers] threads. */
    private suspend fun extract(provider: RouteProvider, dirty: List<Checked>, dependency: Boolean) {
        val next = AtomicInteger()
        val interrupted = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        coroutineScope {
            repeat(minOf(workers, dirty.size)) {
                launch(extractPool) {
                    while (interrupted.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= dirty.size) break
                        val c = dirty[i]
                        try {
                            c.entry = smartReadAction(project) { extractOne(provider, c, dependency) }
                        } catch (e: CancellationException) {
                            // A child that is cancelled does not fail its scope, so the pass would go on with holes in it.
                            interrupted.compareAndSet(null, e)
                            break
                        }
                    }
                }
            }
        }
        interrupted.get()?.let { throw it }
    }

    private fun extractOne(provider: RouteProvider, c: Checked, dependency: Boolean): FactsEntry? {
        if (!c.file.isValid) return null
        val psiManager = PsiManager.getInstance(project)
        val psi = psiManager.findFile(c.file) ?: return null
        val stamp = stampOf(c.file)
        val entry = try {
            ProgressManager.checkCanceled()
            val facts = if (dependency) provider.extractDependency(psi) else provider.extract(psi)
            // Equal facts keep their old identity, so "did this file change what it contributes" is an identity check later.
            FactsEntry(stamp, facts?.let { fresh -> cache[c.key]?.facts?.takeIf { it == fresh } ?: fresh }, null)
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            log.warn("Route provider ${provider.id} failed on ${c.file.path}", e)
            FactsEntry(stamp, null, failure(provider.id, c.file, e))
        }
        cache[c.key] = entry
        return entry
    }

    /**
     * Changes whenever the text of [file] changes, without touching PSI. A loaded document starts with the stamp of its
     * file, so loading one does not look like an edit. While a document has uncommitted changes the PSI still shows the old
     * text, so the stamp is a marker no real stamp equals: facts made now are replaced as soon as the commit arrives.
     */
    private fun stampOf(file: VirtualFile): Long {
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return file.modificationStamp
        return if (PsiDocumentManager.getInstance(project).isCommitted(document)) document.modificationStamp else UNCOMMITTED_STAMP
    }

    private fun fileKeyOf(file: VirtualFile): String = ((file as? VirtualFileWithId)?.id ?: file.path.hashCode()).toString()

    private fun cacheKey(provider: RouteProvider, fileKey: String, dependency: Boolean) =
        provider.id + KEY_SEPARATOR + fileKey + if (dependency) DEPENDENCY_SUFFIX else CANDIDATE_SUFFIX

    /** Deleted files, files that are no longer candidates and dependencies nobody needs any more lose their cached facts. */
    private fun evictUnused(provider: RouteProvider, live: Set<String>) {
        val prefix = provider.id + KEY_SEPARATOR
        cache.keys().forEach { if (it.startsWith(prefix) && it !in live) cache.remove(it) }
    }

    /** The project files of [modules]; unknown names are remembered as such until the file layout changes. */
    private suspend fun filesOf(provider: RouteProvider, modules: Set<String>): List<VirtualFile> {
        val version = layoutVersion.get()
        if (moduleFilesVersion != version) {
            moduleFiles.clear()
            moduleFilesVersion = version
        }
        val unknown = modules.filter { moduleFiles[it] == null }
        for (batch in unknown.chunked(Limits.STAMP_CHECK_BATCH_FILES)) {
            val found = smartReadAction(project) {
                batch.map { it to provider.findModuleFile(project, it) }
            }
            found.forEach { (name, file) -> moduleFiles[name] = ModuleFile(file) }
        }
        return modules.mapNotNull { moduleFiles[it]?.file }.filter { it.isValid }.distinct()
    }

    private fun VFileEvent.changesLayout(): Boolean =
        this is VFileCreateEvent || this is VFileDeleteEvent || this is VFileMoveEvent || this is VFileCopyEvent ||
            (this is VFilePropertyChangeEvent && isRename)

    private fun isRelevant(path: String): Boolean {
        val name = path.substringAfterLast('/')
        // Directories have no extension and may hold Python files (moved, deleted or renamed as a whole).
        return !name.contains('.') || name.endsWith(PYTHON_SUFFIX)
    }

    /**
     * Last gate before publishing: the list is correct or absent, never guessed. A provider that lets a partial endpoint
     * through (problems recorded, or an unknown `{?}` segment in the path) loses that endpoint here, and the drop is
     * reported once per endpoint so it cannot disappear silently. [HttpMethod.ANY] stays: it is a documented, exact
     * answer for views that accept every verb.
     */
    private fun publish(candidates: List<Endpoint>, found: List<Diagnostic>) {
        val endpoints = ArrayList<Endpoint>(candidates.size)
        val diagnostics = ArrayList<Diagnostic>(found)
        for (endpoint in candidates) {
            if (endpoint.isPartial || endpoint.path.contains(PathSyntax.UNKNOWN_SEGMENT)) {
                log.warn("Dropped an endpoint that is not fully resolved: ${endpoint.method} ${endpoint.path}")
                diagnostics += Diagnostic(
                    Severity.WARNING, DiagnosticCode.INTERNAL_ERROR,
                    listOf(endpoint.method.name, endpoint.path), endpoint.source,
                )
            } else {
                endpoints += endpoint
            }
        }
        val numbered = EndpointKeys.disambiguate(endpoints)
        val previous = state.value
        if (previous.generation > 0 && previous.endpoints == numbered && previous.diagnostics == diagnostics) return
        state.value = RouteSnapshot(generation.incrementAndGet(), numbered, diagnostics)
    }

    private fun failure(providerId: String, file: VirtualFile?, error: Throwable): Diagnostic {
        val path = file?.let { ProjectPaths.relative(project, it) }
        val args = listOfNotNull(providerId, path, error.javaClass.simpleName)
        return Diagnostic(Severity.WARNING, DiagnosticCode.INTERNAL_ERROR, args, path?.let { SourceRef(it, 0, 1) })
    }

    private suspend fun debounceLoop() {
        for (signal in triggers) {
            // Trailing edge: the pass starts one delay after the LAST request, not one full delay per request that arrived meanwhile.
            while (true) {
                val remainingMs = debounceMs - (System.nanoTime() - lastTriggerNanos.get()) / NANOS_PER_MS
                if (remainingMs <= 0) break
                delay(remainingMs)
            }
            // A request that came in during the wait is covered by the pass that starts now.
            triggers.tryReceive()
            if (!active) continue
            try {
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Route refresh failed", e)
            }
        }
    }

    private fun stop() {
        disposed = true
        triggers.close()
        synchronized(jobs) { jobs.forEach { it.cancel() } }
        cache.clear()
        moduleFiles.clear()
        memo.clear()
    }

    private fun rethrowIfControlFlow(e: Throwable) {
        if (e is CancellationException || e is ProcessCanceledException) throw e
    }

    private companion object {
        const val PYTHON_SUFFIX = ".py"
        const val KEY_SEPARATOR = "|"
        const val CANDIDATE_SUFFIX = "|c"
        const val DEPENDENCY_SUFFIX = "|d"
        const val UNCOMMITTED_STAMP = Long.MIN_VALUE
        const val NANOS_PER_MS = 1_000_000L
    }
}
