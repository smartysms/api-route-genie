package com.routegenie.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.routegenie.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one way to ask "show me this endpoint": gutter icons, editor actions and anything else call [reveal]; the endpoint tree
 * observes [requested], selects the endpoint and calls [consume]. The two sides never reference each other.
 */
@Service(Service.Level.PROJECT)
class EndpointNavigator(private val project: Project) {
    private val pending = MutableStateFlow<String?>(null)

    /** [com.routegenie.model.Endpoint.key] of the endpoint the user asked to see, until the tree has handled it. */
    val requested: StateFlow<String?> = pending.asStateFlow()

    /** Opens and focuses the tool window and asks the tree to select the endpoint with [endpointKey]. Call on the UI thread. */
    fun reveal(endpointKey: String) {
        pending.value = endpointKey
        ToolWindowManager.getInstance(project).getToolWindow(Ids.TOOL_WINDOW)?.activate(null)
    }

    /** Called by the tree once it handled the request; a newer request for another key is kept. */
    fun consume(endpointKey: String) {
        pending.compareAndSet(endpointKey, null)
    }

    companion object {
        fun getInstance(project: Project): EndpointNavigator = project.service()
    }
}
