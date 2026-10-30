package com.routegenie.diagnostics

import com.routegenie.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ErrorReportTest {
    private val facts = ErrorReport.Facts("0.1.9", "PyCharm 2026.2 (262.1)", "Windows 10", "25")

    private fun query(url: String, name: String): String {
        val raw = URI(url).rawQuery.split("&").first { it.startsWith("$name=") }.substringAfter("=")
        return URLDecoder.decode(raw, StandardCharsets.UTF_8)
    }

    @Test
    fun `the home folder and the user name are replaced in either slash style`() {
        val text = "at C:\\Users\\alice\\proj\\app.py and C:/Users/alice/proj/app.py by alice"
        val out = ErrorReport.redact(text, "C:\\Users\\alice", "alice")
        assertFalse(out, out.contains("alice"))
        assertTrue(out, out.contains("<home>"))
    }

    @Test
    fun `values after secret words and bearer credentials are hidden`() {
        val out = ErrorReport.redact("failed: Authorization: Bearer abcdefgh12345 token=xyz123 password: hunter2", null, null)
        assertFalse(out, out.contains("abcdefgh12345"))
        assertFalse(out, out.contains("xyz123"))
        assertFalse(out, out.contains("hunter2"))
    }

    @Test
    fun `a short or missing user name is left alone instead of mangling every word`() {
        assertEquals("a b ab", ErrorReport.redact("a b ab", null, "ab"))
        assertEquals("text", ErrorReport.redact("text", "", null))
    }

    @Test
    fun `the title is the first line of the first error, cut`() {
        assertEquals("Error: java.lang.IllegalStateException: boom", ErrorReport.title(listOf("java.lang.IllegalStateException: boom\n\tat x.Y.z(Y.kt:1)")))
        val long = ErrorReport.title(listOf("x".repeat(500)))
        assertTrue(long.length <= "Error: ".length + Limits.REPORT_TITLE_MAX_CHARS)
        assertTrue(long.endsWith("..."))
        assertEquals("Error: Unexpected error", ErrorReport.title(emptyList()))
    }

    @Test
    fun `the body carries the versions, the comment and the trace in a fence`() {
        val body = ErrorReport.body(facts, "I pressed Send", listOf("boom\n```inner```"))
        assertTrue(body.contains("I pressed Send"))
        assertTrue(body.contains("- Plugin: 0.1.9"))
        assertTrue(body.contains("- IDE: PyCharm 2026.2 (262.1)"))
        assertEquals("a fence inside the trace must not close ours", 2, Regex("```").findAll(body).count())
    }

    @Test
    fun `a missing comment asks for one`() {
        assertTrue(ErrorReport.body(facts, "  ", listOf("boom")).contains("please add what you were doing"))
    }

    @Test
    fun `the address points at the new issue page of the repository and decodes back to the report`() {
        val url = ErrorReport.issueUrl("owner/name", facts, "my note", listOf("java.lang.RuntimeException: boom\n\tat a.B.c(B.kt:1)"), null, null)
        assertTrue(url, url.startsWith("https://github.com/owner/name/issues/new?title="))
        assertTrue(query(url, "title").contains("boom"))
        assertTrue(query(url, "body").contains("my note"))
        assertFalse("spaces are percent-encoded, not plus signs", url.contains("+"))
    }

    @Test
    fun `a huge trace and a huge comment still give an address within the limit`() {
        val trace = (1..5_000).joinToString("\n") { "\tat com.example.Deep.method$it(Deep.kt:$it) with some \"quoted\" text & symbols" }
        val url = ErrorReport.issueUrl("owner/name", facts, "c".repeat(20_000), List(10) { "java.lang.Error: x\n$trace" }, null, null)
        assertTrue("length ${url.length}", url.length <= Limits.REPORT_MAX_URL_CHARS)
        assertTrue(query(url, "body").contains("- Plugin: 0.1.9"))
    }

    @Test
    fun `at most the configured number of errors is reported`() {
        val url = ErrorReport.issueUrl("o/n", facts, null, List(10) { "java.lang.Error: number$it" }, null, null)
        val body = query(url, "body")
        assertEquals(Limits.REPORT_MAX_EVENTS, Regex("## Error").findAll(body).count())
    }

    @Test
    fun `personal data is gone from the address`() {
        val url = ErrorReport.issueUrl("o/n", facts, "see C:\\Users\\alice\\x", listOf("java.io.IOException: C:\\Users\\alice\\p.py"), "C:\\Users\\alice", "alice")
        assertFalse(URLDecoder.decode(url, StandardCharsets.UTF_8).contains("alice"))
    }
}
