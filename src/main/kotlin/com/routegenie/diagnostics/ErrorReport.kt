package com.routegenie.diagnostics

import com.routegenie.Limits
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Turns an IDE error into the address of a pre-filled GitHub issue. Pure text work, so it is tested without the IDE.
 *
 * The report is plain English whatever the IDE language is: the reader is the maintainer, not the user. It carries the error
 * text, the stack trace, the versions and the user's own comment, and nothing else. The user name and the home folder are
 * replaced, and values that follow words like "token" or "password" are hidden, because a message can quote them.
 */
object ErrorReport {
    /** What the report says about the machine; each value is one short line. */
    class Facts(val plugin: String, val ide: String, val os: String, val java: String)

    private val SECRET_VALUE = Regex("""(?i)\b(authorization|token|password|passwd|secret|api[_-]?key|cookie)\b(\s*[:=]\s*)(\S+)""")
    private val SCHEME_CREDENTIAL = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""")
    private const val FENCE = "```"
    private const val FENCE_REPLACEMENT = "'''"
    private const val COMMENT_FLOOR_CHARS = 500

    fun redact(text: String, home: String?, user: String?): String {
        var out = text
        // The home folder contains the user name, so it goes first.
        if (!home.isNullOrBlank() && home.length > 3) out = out.replace(home, "<home>").replace(home.replace('\\', '/'), "<home>")
        if (!user.isNullOrBlank() && user.length >= 3) out = out.replace(user, "<user>")
        out = SCHEME_CREDENTIAL.replace(out) { "${it.groupValues[1]} <hidden>" }
        return SECRET_VALUE.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<hidden>" }
    }

    /** First line of the first error, cut to [Limits.REPORT_TITLE_MAX_CHARS]. */
    fun title(traces: List<String>): String {
        val first = traces.firstOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { "Unexpected error" }
        val cut = if (first.length > Limits.REPORT_TITLE_MAX_CHARS) first.take(Limits.REPORT_TITLE_MAX_CHARS - 3) + "..." else first
        return "Error: $cut"
    }

    fun body(facts: Facts, comment: String?, traces: List<String>): String = buildString {
        appendLine("## What happened")
        appendLine(comment?.trim().takeUnless { it.isNullOrEmpty() } ?: "(please add what you were doing)")
        appendLine()
        appendLine("## Environment")
        appendLine("- Plugin: ${facts.plugin}")
        appendLine("- IDE: ${facts.ide}")
        appendLine("- OS: ${facts.os}")
        appendLine("- Java: ${facts.java}")
        traces.forEachIndexed { i, trace ->
            appendLine()
            appendLine(if (traces.size == 1) "## Error" else "## Error ${i + 1}")
            appendLine(FENCE)
            appendLine(trace.replace(FENCE, FENCE_REPLACEMENT).trimEnd())
            appendLine(FENCE)
        }
    }

    /**
     * The address to open. [home] and [user] are what [redact] hides. The trace is cut until the whole address fits in
     * [maxChars]; at the floor the comment is cut and only the first error is kept, so the result is always bounded.
     */
    fun issueUrl(
        repo: String,
        facts: Facts,
        comment: String?,
        traces: List<String>,
        home: String?,
        user: String?,
        maxChars: Int = Limits.REPORT_MAX_URL_CHARS,
    ): String {
        val cleanTraces = traces.take(Limits.REPORT_MAX_EVENTS).map { redact(it, home, user) }
        val cleanComment = comment?.let { redact(it, home, user) }
        val base = "https://github.com/$repo/issues/new?title=${encode(title(cleanTraces))}&body="
        var limit = Limits.REPORT_MAX_TRACE_CHARS
        while (true) {
            val url = base + encode(body(facts, cleanComment, cleanTraces.map { cut(it, limit / cleanTraces.size.coerceAtLeast(1)) }))
            if (url.length <= maxChars) return url
            if (limit <= Limits.REPORT_MIN_TRACE_CHARS) break
            limit = maxOf(limit * 3 / 4, Limits.REPORT_MIN_TRACE_CHARS)
        }
        val last = cleanTraces.take(1).map { cut(it, Limits.REPORT_MIN_TRACE_CHARS) }
        return base + encode(body(facts, cleanComment?.take(COMMENT_FLOOR_CHARS), last))
    }

    private fun cut(trace: String, limit: Int): String =
        if (trace.length <= limit) trace else trace.take(limit) + "\n... (cut; the full trace is in idea.log)"

    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
