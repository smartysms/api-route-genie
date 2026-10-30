package com.routegenie.testmap.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.testmap.TestIndex
import com.routegenie.testmap.TestIndexSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Project-level entry point of the test mapping. The platform hands in a coroutine scope that it cancels when the project
 * closes. The service starts inactive: nothing is read until a Tests tab is on screen ([setActive]), so a project whose
 * user never looks at tests pays nothing.
 */
class TestMapService(project: Project, scope: CoroutineScope) : Disposable, TestIndexSource {
    private val engine = TestMapEngine(project, scope, this, RouteDiscoveryService.getInstance(project).snapshot, active = false)

    init {
        engine.start(coldStart = false)
    }

    override val index: StateFlow<TestIndex> get() = engine.index

    /** True when changes are waiting for the next time the result is shown. */
    val isStale: Boolean get() = engine.isStale

    override fun setActive(active: Boolean) = engine.setActive(active)

    override fun requestRefresh() = engine.requestRefresh()

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): TestMapService = project.service()
    }
}
