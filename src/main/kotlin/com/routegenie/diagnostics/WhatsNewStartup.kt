package com.routegenie.diagnostics

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.routegenie.Branding
import com.routegenie.Ids
import com.routegenie.RouteGenieBundle

/** When to tell the user what changed: once per installed version, never twice for the same one. */
object WhatsNew {
    fun shouldShow(lastShown: String?, current: String?): Boolean = !current.isNullOrBlank() && current != lastShown
}

/**
 * After the plugin was installed or updated, shows the change notes (the newest CHANGELOG.md section, which the build puts
 * into plugin.xml) in one balloon. The IDE's own "What's New" page is for the IDE only; this is the plugin's equivalent.
 * Nothing leaves the machine: the notes are read from the installed plugin.
 */
class WhatsNewStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val plugin = PluginManager.getPluginByClass(javaClass)?.let { PluginManagerCore.getPlugin(it.pluginId) } ?: return
        val properties = PropertiesComponent.getInstance()
        if (!WhatsNew.shouldShow(properties.getValue(PROP_LAST_SHOWN), plugin.version)) return
        // Remembered before showing, so a second project opened right now does not show it again.
        properties.setValue(PROP_LAST_SHOWN, plugin.version)
        val notes = plugin.changeNotes?.takeIf { it.isNotBlank() } ?: return
        NotificationGroupManager.getInstance().getNotificationGroup(Ids.NOTIFICATION_GROUP)
            .createNotification(RouteGenieBundle.message("whatsnew.title", Branding.NAME, plugin.version), notes, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(RouteGenieBundle.message("whatsnew.open_window", Branding.SHORT)) {
                ToolWindowManager.getInstance(project).getToolWindow(Ids.TOOL_WINDOW)?.activate(null)
            })
            .addAction(NotificationAction.createSimpleExpiring(RouteGenieBundle.message("whatsnew.release_page")) {
                BrowserUtil.browse("https://github.com/${Branding.REPO}/releases/tag/v${plugin.version}")
            })
            .notify(project)
    }

    private companion object {
        const val PROP_LAST_SHOWN = "routegenie.whatsnew.lastShown"
    }
}
