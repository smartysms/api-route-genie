package com.routegenie.diagnostics

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.Consumer
import com.routegenie.Branding
import com.routegenie.RouteGenieBundle
import java.awt.Component

/**
 * The "Report" button of the IDE's error dialog for errors thrown by this plugin. It opens a GitHub issue that is already
 * filled in, in the browser; nothing leaves the machine until the user presses Submit on GitHub. Nothing is sent by the IDE
 * itself and no server of ours is involved.
 */
class RouteGenieErrorReportSubmitter : ErrorReportSubmitter() {
    override fun getReportActionText(): String = RouteGenieBundle.message("report.action", Branding.SHORT)

    override fun getPrivacyNoticeText(): String = RouteGenieBundle.message("report.privacy")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        BrowserUtil.browse(issueUrl(events, additionalInfo))
        consumer.consume(SubmittedReportInfo(null, null, SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    internal fun issueUrl(events: Array<out IdeaLoggingEvent>, additionalInfo: String?): String {
        val traces = events.map { event -> event.throwableText.ifBlank { event.message.orEmpty() } }
        return ErrorReport.issueUrl(Branding.REPO, facts(), additionalInfo, traces, System.getProperty("user.home"), System.getProperty("user.name"))
    }

    private fun facts(): ErrorReport.Facts {
        val app = ApplicationInfo.getInstance()
        val plugin = PluginManager.getPluginByClass(javaClass)?.let { PluginManagerCore.getPlugin(it.pluginId) }?.version ?: "unknown"
        return ErrorReport.Facts(plugin, "${app.fullApplicationName} (${app.build.asString()})", SystemInfo.getOsNameAndVersion(), SystemInfo.JAVA_VERSION)
    }
}
