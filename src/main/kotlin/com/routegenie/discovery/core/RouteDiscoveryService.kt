package com.routegenie.discovery.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.routegenie.discovery.RouteProvider
import com.routegenie.model.RouteSnapshot
import com.routegenie.pinside.PinsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Project-level entry point of discovery. The platform hands in a coroutine scope that it cancels when the project
 * closes; the engine does the work. Consumers collect [snapshot] and never recompute anything.
 */
class RouteDiscoveryService(project: Project, scope: CoroutineScope) : Disposable {
    private val engine = RouteDiscoveryEngine(
        project, scope, this, { RouteProvider.EP_NAME.extensionList }, active = false,
        pins = { PinsService.getInstance(project).env },
    )

    val snapshot: StateFlow<RouteSnapshot> get() = engine.snapshot

    init {
        // No scan until somebody looks: the tool window calls setActive(true) when it is shown.
        engine.start(coldStart = false)
    }

    /** True when changes are waiting for the next time the result is shown. */
    val isStale: Boolean get() = engine.isStale

    /** The tool window (or any other consumer) tells whether anyone looks; hidden means stale-marking only. */
    fun setActive(active: Boolean) = engine.setActive(active)

    /** Schedules a debounced refresh; used when something outside the IDE's change events may have changed routes. */
    fun requestRefresh() = engine.requestRefresh()

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): RouteDiscoveryService = project.service()
    }
}
