package com.routegenie.ui.gutter

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.routegenie.RouteGenieBundle
import com.routegenie.ui.EndpointNavigator

/** Click and action behavior shared by the gutter icon and the "Select in" action. Call on the UI thread. */
object RouteNavigation {
    /** One endpoint: reveal it. Several: let the user choose, [show] places the popup. */
    fun open(project: Project, entry: LineEntry, show: (JBPopup) -> Unit) {
        val navigator = EndpointNavigator.getInstance(project)
        val choices = entry.choices
        if (choices.size <= 1) {
            choices.firstOrNull()?.let { navigator.reveal(it.key) }
            return
        }
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle(RouteGenieBundle.message("gutter.choose.title"))
            .setRenderer(SimpleListCellRenderer.create { label, choice, _ -> label.text = choice.text })
            .setItemChosenCallback { navigator.reveal(it.key) }
            .createPopup()
        show(popup)
    }

    fun handler(entry: LineEntry): GutterIconNavigationHandler<PsiElement> =
        GutterIconNavigationHandler { event, element -> open(element.project, entry) { it.show(RelativePoint(event)) } }
}
