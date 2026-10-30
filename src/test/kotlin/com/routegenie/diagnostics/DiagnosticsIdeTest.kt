package com.routegenie.diagnostics

import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.Branding
import com.routegenie.RouteGenieBundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class DiagnosticsIdeTest : BasePlatformTestCase() {
    fun testTheReportOpensTheIssuePageOfThisRepositoryWithTheErrorAndTheVersions() {
        val submitter = RouteGenieErrorReportSubmitter()
        val event = IdeaLoggingEvent("It broke", IllegalStateException("kaboom"))
        val url = submitter.issueUrl(arrayOf(event), "Pressed Send on a POST")
        assertTrue(url, url.startsWith("https://github.com/${Branding.REPO}/issues/new?title="))
        val decoded = URLDecoder.decode(url, StandardCharsets.UTF_8)
        assertTrue(decoded, decoded.contains("kaboom"))
        assertTrue(decoded, decoded.contains("Pressed Send on a POST"))
        assertTrue(decoded, decoded.contains("- Plugin: "))
        assertTrue(decoded, decoded.contains("- IDE: "))
    }

    fun testTheButtonAndThePrivacyNoticeAreReadable() {
        val submitter = RouteGenieErrorReportSubmitter()
        assertTrue(submitter.reportActionText.contains(Branding.SHORT))
        assertTrue(submitter.privacyNoticeText!!.contains("Nothing is sent until you press Submit"))
    }

    fun testTheWhatsNewMessagesFormat() {
        assertTrue(RouteGenieBundle.message("whatsnew.title", Branding.NAME, "0.1.9").contains("0.1.9"))
    }

    fun testTheInstalledPluginHasNoChangeNotesInTestsButTheWhatsNewPolicyIsExact() {
        assertTrue(WhatsNew.shouldShow(null, "0.1.9"))
        assertTrue(WhatsNew.shouldShow("0.1.8", "0.1.9"))
        assertFalse(WhatsNew.shouldShow("0.1.9", "0.1.9"))
        assertFalse(WhatsNew.shouldShow("0.1.9", null))
        assertFalse(WhatsNew.shouldShow(null, ""))
    }
}
