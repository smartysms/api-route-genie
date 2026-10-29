package com.routegenie.discovery.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.PythonLanguage
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.RouteProvider
import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.PathSyntax
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.Severity
import com.routegenie.model.SourceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * The discovery pipeline: candidates -> per-file facts (cached by path and modification stamp) -> link per provider
 * outside any lock -> immutable [RouteSnapshot]. Everything runs on [scope]; read actions are short batches so typing
 * can interleave, and a failing provider or file becomes a [Diagnostic] without affecting the others.
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
    cacheCapacity: Int = Limits.MAX_CACHED_FILE_FACTS,
    active: Boolean = true,
) {
    private val log = Logger.getInstance(RouteDiscoveryEngine::class.java)
    private val state = MutableStateFlow(RouteSnapshot.EMPTY)
    private val generation = AtomicLong(0)
    private val runLock = Mutex()
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val cache = BoundedLruCache<String, FileFactsEntry>(cacheCapacity)
    private val jobs = ArrayList<Job>()
    private val listeners: Disposable = Disposer.newDisposable("RouteDiscoveryEngine listeners")

    @Volatile
    private var disposed = false

    /** False while nobody looks at the result (tool window hidden): changes then only mark the snapshot stale. */
    @Volatile
    private var active = active

    /** True when something may have changed since the last run that nobody has been shown yet. */
    @Volatile
    var isStale = true
        private set

    private val runs = java.util.concurrent.atomic.AtomicInteger()

    /** How many discovery passes have started; tests use it to prove that irrelevant changes cost no pass at all. */
    val runCount: Int get() = runs.get()

    private val pythonTracker = PsiModificationTracker.getInstance(project).forLanguage(PythonLanguage.getInstance())

    @Volatile
    private var lastPythonCount = pythonTracker.modificationCount

    val snapshot: StateFlow<RouteSnapshot> get() = state

    /** Number of cached per-file entries, for tests that check the cache stays bounded. */
    val cachedEntries: Int get() = cache.size

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
                if (events.any { isRelevant(it.path) }) requestRefresh()
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
            override fun exitDumbMode() = requestRefresh()
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
            if (runs.get() == 0) launchColdScan() else triggers.trySend(Unit)
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
        if (active) triggers.trySend(Unit)
    }

    /**
     * One full pass: re-reads only the files whose stamp changed, relinks, publishes if the result differs. Runs one at
     * a time; a caller that arrives meanwhile waits and then runs again against the newer state.
     */
    suspend fun refreshNow() {
        runLock.withLock {
            if (disposed) return
            runs.incrementAndGet()
            isStale = false
            val endpoints = ArrayList<Endpoint>()
            val diagnostics = ArrayList<Diagnostic>()
            for (provider in providers().sortedBy { it.id }) {
                runProvider(provider, endpoints, diagnostics)
            }
            publish(endpoints, diagnostics)
        }
    }

    private suspend fun runProvider(provider: RouteProvider, endpoints: MutableList<Endpoint>, diagnostics: MutableList<Diagnostic>) {
        try {
            val candidates = smartReadAction(project) { provider.findCandidates(project).toList() }
            evictVanished(provider, candidates)
            val facts = ArrayList<FileFacts>()
            for (batch in candidates.chunked(Limits.DISCOVERY_BATCH_FILES)) {
                val read = smartReadAction(project) { readBatch(provider, batch) }
                facts += read.facts
                diagnostics += read.diagnostics
            }
            // No lock is held here: linking is pure data work and may take a while for large projects.
            val linked = provider.link(facts)
            endpoints += linked.endpoints
            diagnostics += linked.diagnostics
        } catch (e: Throwable) {
            rethrowIfControlFlow(e)
            log.warn("Route provider ${provider.id} failed", e)
            diagnostics += failure(provider.id, null, e)
        }
    }

    /** Deleted, moved and renamed files drop out of the candidates; their cached facts go with them. */
    private fun evictVanished(provider: RouteProvider, candidates: List<VirtualFile>) {
        val prefix = provider.id + KEY_SEPARATOR
        val live = HashSet<String>(candidates.size * 2)
        candidates.forEach { live += prefix + it.path }
        cache.keys().forEach { if (it.startsWith(prefix) && it !in live) cache.remove(it) }
    }

    private fun isRelevant(path: String): Boolean {
        val name = path.substringAfterLast('/')
        // Directories have no extension and may hold Python files (moved, deleted or renamed as a whole).
        return !name.contains('.') || name.endsWith(PYTHON_SUFFIX)
    }

    private class BatchResult(val facts: List<FileFacts>, val diagnostics: List<Diagnostic>)

    /** Runs inside a read action that may restart, so it builds fresh lists each time; its only side effect is the cache. */
    private fun readBatch(provider: RouteProvider, files: List<VirtualFile>): BatchResult {
        val facts = ArrayList<FileFacts>(files.size)
        val diagnostics = ArrayList<Diagnostic>()
        val psiManager = PsiManager.getInstance(project)
        for (file in files) {
            ProgressManager.checkCanceled()
            val psi = psiManager.findFile(file) ?: continue
            val key = provider.id + KEY_SEPARATOR + file.path
            val stamp = psi.modificationStamp
            var entry = cache[key]
            if (entry == null || entry.stamp != stamp) {
                entry = try {
                    FileFactsEntry(stamp, provider.extract(psi), null)
                } catch (e: Throwable) {
                    rethrowIfControlFlow(e)
                    log.warn("Route provider ${provider.id} failed on ${file.path}", e)
                    FileFactsEntry(stamp, null, failure(provider.id, file, e))
                }
                cache[key] = entry
            }
            entry.facts?.let { facts += it }
            entry.failure?.let { diagnostics += it }
        }
        return BatchResult(facts, diagnostics)
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
        val previous = state.value
        if (previous.generation > 0 && previous.endpoints == endpoints && previous.diagnostics == diagnostics) return
        state.value = RouteSnapshot(generation.incrementAndGet(), endpoints, diagnostics)
    }

    private fun failure(providerId: String, file: VirtualFile?, error: Throwable): Diagnostic {
        val path = file?.let { relativePath(it) }
        val args = listOfNotNull(providerId, path, error.javaClass.simpleName)
        return Diagnostic(Severity.WARNING, DiagnosticCode.INTERNAL_ERROR, args, path?.let { SourceRef(it, 0, 1) })
    }

    private fun relativePath(file: VirtualFile): String {
        val base = project.basePath ?: return file.path
        return FileUtil.getRelativePath(base, file.path, '/') ?: file.path
    }

    private suspend fun debounceLoop() {
        for (signal in triggers) {
            // Trailing-edge debounce: keep waiting while new requests keep arriving.
            do {
                delay(debounceMs)
            } while (triggers.tryReceive().isSuccess)
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
    }

    private fun rethrowIfControlFlow(e: Throwable) {
        if (e is CancellationException || e is ProcessCanceledException) throw e
    }

    private companion object {
        const val PYTHON_SUFFIX = ".py"
        const val KEY_SEPARATOR = "|"
    }
}
