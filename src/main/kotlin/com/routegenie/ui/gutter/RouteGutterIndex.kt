package com.routegenie.ui.gutter

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.routegenie.Limits
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.RouteSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Builds the [GutterModel] of every published snapshot off the UI thread and keeps only the latest. Owned by whoever
 * passes the [scope]; [close] (or cancelling the scope) ends the collection, so nothing outlives its owner.
 */
class GutterModelHolder(source: StateFlow<RouteSnapshot>, scope: CoroutineScope, private val onChange: () -> Unit) {
    @Volatile
    var model: GutterModel = GutterModel.EMPTY
        private set

    private val job = scope.launch(Dispatchers.Default) {
        source.collect { snapshot ->
            val next = GutterModel.build(snapshot)
            if (next != model) {
                model = next
                onChange()
            }
        }
    }

    /** Tests install a model built from a provider run instead of waiting for the engine. */
    internal fun replace(next: GutterModel) {
        model = next
    }

    fun close() {
        job.cancel()
        model = GutterModel.EMPTY
    }
}

/**
 * Per-project gutter state: the line index of the discovered endpoints and the one-time decision to start discovery.
 *
 * Activation: the marker pass of a Python editor calls [activateIfRouteFile] for files that have no entries yet. The
 * first file whose text names a supported framework and a routing construct switches discovery on once; a project
 * without such a file never starts a scan. After that the engine's own rule applies (it scans again only when files
 * change), and markers are repainted only when the line index actually changed.
 */
@Service(Service.Level.PROJECT)
class RouteGutterIndex(private val project: Project, scope: CoroutineScope) : Disposable {
    private val holder = GutterModelHolder(RouteDiscoveryService.getInstance(project).snapshot, scope) { refreshMarkers() }

    @Volatile
    var isActivated: Boolean = false
        private set

    val model: GutterModel get() = holder.model

    fun activateIfRouteFile(text: CharSequence) {
        if (isActivated || text.length > Limits.MAX_CANDIDATE_FILE_BYTES) return
        if (!RouteFileText.looksLikeRouteFile(text)) return
        isActivated = true
        RouteDiscoveryService.getInstance(project).setActive(true)
    }

    /** Tests share one light project between methods, so they must be able to start from "never activated". */
    internal fun resetActivationForTests() {
        isActivated = false
    }

    internal fun installModelForTests(next: GutterModel) = holder.replace(next)

    fun refreshMarkers() {
        if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart(this)
    }

    override fun dispose() = holder.close()

    companion object {
        fun getInstance(project: Project): RouteGutterIndex = project.service()
    }
}
