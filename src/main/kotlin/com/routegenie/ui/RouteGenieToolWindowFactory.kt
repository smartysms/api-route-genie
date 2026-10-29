package com.routegenie.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Creates the content of the tool window. The platform calls this only when the window is first shown, so nothing runs
 * for projects that never open it. Placeholder until the endpoint tree lands.
 */
class RouteGenieToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val owner = RouteGenieWindowContent(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(owner.component, "", false)
        content.setDisposer(owner)
        toolWindow.contentManager.addContent(content)
    }
}
