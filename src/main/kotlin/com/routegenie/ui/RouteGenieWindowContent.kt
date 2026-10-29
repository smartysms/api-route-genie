package com.routegenie.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import javax.swing.JComponent

/**
 * Owner of everything the tool window shows. It is registered below the tool window's disposable, so closing the window
 * or the project releases it; the panel's listeners and coroutine scope hang below this object, never below the
 * application. Construction must stay cheap (see Limits.BUDGET_WINDOW_OPEN_MS): no PSI, no index, no file access here;
 * discovery starts only when the panel is first shown.
 */
class RouteGenieWindowContent(project: Project, parent: Disposable) : Disposable {
    val panel = EndpointsPanel(project, this)
    val component: JComponent get() = panel

    init {
        Disposer.register(parent, this)
    }

    override fun dispose() = Unit
}
