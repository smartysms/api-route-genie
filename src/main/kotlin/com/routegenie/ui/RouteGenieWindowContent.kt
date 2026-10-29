package com.routegenie.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanelWithEmptyText
import com.routegenie.RouteGenieBundle
import javax.swing.JComponent

/**
 * Owner of everything the tool window shows. It is registered below the tool window's disposable, so closing the window
 * or the project releases it; later phases attach their listeners and coroutine scopes to this object, never to the
 * application. Construction must stay cheap (see Limits.BUDGET_WINDOW_OPEN_MS): no PSI, no index, no file access here.
 */
class RouteGenieWindowContent(parent: Disposable) : Disposable {
    val component: JComponent = JBPanelWithEmptyText().withEmptyText(RouteGenieBundle.message("window.empty"))

    init {
        Disposer.register(parent, this)
    }

    override fun dispose() = Unit
}
