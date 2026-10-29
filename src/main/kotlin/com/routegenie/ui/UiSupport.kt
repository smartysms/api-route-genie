package com.routegenie.ui

import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.pom.Navigatable
import com.routegenie.Ids
import com.routegenie.RouteGenieBundle
import com.routegenie.model.Endpoint
import com.routegenie.model.SourceRef
import javax.swing.Icon

/** Ids and keys of the window's own UI plumbing (not plugin.xml ids): data keys, action places, stored settings. */
internal object UiKeys {
    const val SPLITTER = "RouteGenie.DetailsSplitter"
    const val PROP_GROUP_MODE = "routegenie.ui.groupMode"
    const val PROP_SHOW_DEPRECATED = "routegenie.ui.showDeprecated"
    const val TOOLBAR_PLACE = "RouteGenie.Toolbar"
    const val POPUP_PLACE = "RouteGenie.TreePopup"
}

object EndpointDataKeys {
    /** The endpoint selected in the tree; actions of this window read it from the data context. */
    val SELECTED_ENDPOINT: DataKey<Endpoint> = DataKey.create("RouteGenie.SelectedEndpoint")
}

/**
 * Resolves a project-relative source position and opens it. Runs on the UI thread, where the platform no longer grants
 * read access implicitly, so every read of the project model or of a document takes its own read action.
 */
object EndpointSourceNavigation {
    fun resolve(project: Project, path: String): VirtualFile? = runReadAction {
        project.guessProjectDir()?.findFileByRelativePath(path)?.takeIf { it.isValid }
            ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { root ->
                root.findFileByRelativePath(path)?.takeIf { it.isValid }
            }
    }

    /** The file may have changed since the scan; a stale offset must never point past the end. */
    fun clampOffset(offset: Int, length: Int): Int = offset.coerceIn(0, maxOf(length, 0))

    fun navigate(project: Project, source: SourceRef): Boolean {
        val file = resolve(project, source.path)
        if (file == null) {
            NotificationGroupManager.getInstance().getNotificationGroup(Ids.NOTIFICATION_GROUP)
                .createNotification(RouteGenieBundle.message("navigate.file_missing", source.path), NotificationType.WARNING)
                .notify(project)
            return false
        }
        val length = runReadAction { FileDocumentManager.getInstance().getDocument(file)?.textLength ?: file.length.toInt() }
        OpenFileDescriptor(project, file, clampOffset(source.offset, length)).navigate(true)
        return true
    }
}

/** What the platform's navigate action (F4) and "open source" handlers use. */
class EndpointNavigatable(private val project: Project, private val endpoint: Endpoint) : Navigatable {
    override fun navigate(requestFocus: Boolean) {
        EndpointSourceNavigation.navigate(project, endpoint.source)
    }

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true
}

internal class UiAction(
    text: String,
    description: String,
    icon: Icon?,
    private val enabled: () -> Boolean = { true },
    private val run: () -> Unit,
) : DumbAwareAction(text, description, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = enabled()
    }

    override fun actionPerformed(e: AnActionEvent) = run()
}

internal class UiToggle(
    text: String,
    description: String,
    icon: Icon?,
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
) : DumbAwareToggleAction(text, description, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = get()
    override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
}
