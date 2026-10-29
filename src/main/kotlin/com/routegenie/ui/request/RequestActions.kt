package com.routegenie.ui.request

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.routegenie.Ids
import com.routegenie.client.DevServerDefaults
import com.routegenie.client.RequestSpec
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.client.curl.CurlEmitter
import com.routegenie.client.curl.CurlOptions
import com.routegenie.client.curl.CurlShell
import com.routegenie.client.curl.CurlUnsupportedException
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.ui.EndpointDataKeys
import com.routegenie.ui.EndpointNavigator
import java.awt.datatransfer.StringSelection

/** Finds the live request page of the project's window, so actions outside the window can reach it. */
@Service(Service.Level.PROJECT)
class RequestWorkspace(private val project: Project) {
    @Volatile
    private var section: RequestSection? = null

    fun register(s: RequestSection) { section = s }

    fun unregister(s: RequestSection) { if (section === s) section = null }

    /** Opens the window on the endpoint's Request page and focuses Send. The request is NOT sent. */
    fun open(endpoint: Endpoint) {
        ToolWindowManager.getInstance(project).getToolWindow(Ids.TOOL_WINDOW)?.activate {
            EndpointNavigator.getInstance(project).reveal(endpoint.key)
            ApplicationManager.getApplication().invokeLater { section?.focusSend() }
        }
    }

    /**
     * The command for [endpoint]: from the open form when it is valid, otherwise from the template (path parameters stay as
     * `{name}` placeholders) so the action always has something honest to copy.
     */
    fun curlFor(endpoint: Endpoint, shell: CurlShell): String? {
        val spec = section?.specFor(endpoint) ?: templateSpec(endpoint)
        return try {
            CurlEmitter.emit(spec, CurlOptions(shell, maskSecrets = true)).text
        } catch (e: CurlUnsupportedException) {
            null
        }
    }

    fun copyAsCurl(endpoint: Endpoint) {
        val shell = CurlShell.entries.firstOrNull { it.name == PropertiesComponent.getInstance().getValue(ResponsePanel.PROP_SHELL) } ?: ResponsePanel.defaultShell()
        curlFor(endpoint, shell)?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
    }

    private fun templateSpec(endpoint: Endpoint): RequestSpec {
        val base = RouteGenieClientState.getInstance(project).selected()?.baseUrl ?: DevServerDefaults.baseUrl(endpoint.framework)
        val method = if (endpoint.method.isConcrete) endpoint.method else HttpMethod.GET
        return RequestSpec(method, base.trimEnd('/') + endpoint.path)
    }

    companion object {
        fun getInstance(project: Project): RequestWorkspace = project.service()
    }
}

/** "Send Request...": shows the Request page of the selected endpoint and focuses Send. Never sends by itself. */
class SendRequestAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(EndpointDataKeys.SELECTED_ENDPOINT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val endpoint = e.getData(EndpointDataKeys.SELECTED_ENDPOINT) ?: return
        RequestWorkspace.getInstance(project).open(endpoint)
    }
}

/** "Copy as cURL": puts the command for the selected endpoint (in the shell chosen on the cURL page) on the clipboard. */
class CopyAsCurlAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(EndpointDataKeys.SELECTED_ENDPOINT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val endpoint = e.getData(EndpointDataKeys.SELECTED_ENDPOINT) ?: return
        RequestWorkspace.getInstance(project).copyAsCurl(endpoint)
    }
}
