package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.ContentTypes

enum class ContentKind { JSON, XML, HTML, TEXT, BINARY }

/** Something the user should be told about the text that is shown. [value] is a count or an offset. */
enum class BodyNotice { ENGINE_CUT, VIEW_CUT, NOT_FORMATTED, INVALID_JSON, BINARY }

/**
 * What the plain body viewer shows for a response. [pretty] is true when [text] was re-indented; [highlight] says whether
 * lexer highlighting is worth its cost for this size; [softWrap] is set for very long lines.
 */
class FormattedBody(
    val text: String,
    val kind: ContentKind,
    val pretty: Boolean,
    val highlight: Boolean,
    val softWrap: Boolean,
    val notices: List<Pair<BodyNotice, Long>>,
)

/**
 * Pure and thread-agnostic: turns response bytes into viewer text. It decodes at most the display cap, pretty-prints JSON
 * only below [Limits.VIEWER_PRETTY_MAX_CHARS] and never throws. Callers run it off the UI thread (a 5 MB body takes a few
 * milliseconds, but nothing here may ever run inside a paint or an event handler).
 */
object BodyFormatter {
    private val BINARY_TYPES = listOf("image/", "audio/", "video/", "application/octet-stream", "application/pdf", "application/zip", "application/gzip")
    private const val SNIFF_BYTES = 8_000

    fun kindOf(contentType: String?, head: CharSequence): ContentKind {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            type.endsWith("json") || type.contains("+json") -> ContentKind.JSON
            type.contains("html") -> ContentKind.HTML
            type.endsWith("xml") || type.contains("+xml") -> ContentKind.XML
            type.startsWith("text/") -> ContentKind.TEXT
            type.isEmpty() || type == "application/javascript" -> sniff(head)
            else -> ContentKind.TEXT
        }
    }

    private fun sniff(head: CharSequence): ContentKind {
        val first = (0 until minOf(head.length, SNIFF_BYTES)).firstOrNull { !head[it].isWhitespace() }?.let { head[it] } ?: return ContentKind.TEXT
        return when (first) {
            '{', '[' -> ContentKind.JSON
            '<' -> ContentKind.XML
            else -> ContentKind.TEXT
        }
    }

    fun isBinary(contentType: String?, bytes: ByteArray): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (BINARY_TYPES.any { type.startsWith(it) }) return true
        return (0 until minOf(bytes.size, SNIFF_BYTES)).any { bytes[it].toInt() == 0 }
    }

    /**
     * [bytes] is the in-memory head of the body; [totalSize] the real size, so a head shorter than the body is announced.
     */
    fun format(
        bytes: ByteArray,
        contentType: String?,
        totalSize: Long,
        maxChars: Int = Limits.VIEWER_DISPLAY_CAP_CHARS,
        prettyJson: Boolean = true,
    ): FormattedBody {
        val notices = ArrayList<Pair<BodyNotice, Long>>()
        if (isBinary(contentType, bytes)) {
            return FormattedBody("", ContentKind.BINARY, false, false, false, listOf(BodyNotice.BINARY to totalSize))
        }
        if (totalSize > bytes.size) notices += BodyNotice.ENGINE_CUT to totalSize
        // Decode at most what can be shown: a char needs at least one byte, so cutting bytes first bounds the work.
        val charset = ContentTypes.charset(contentType)
        val cutBytes = if (bytes.size > maxChars * 4L) maxChars * 4 else bytes.size
        var text = String(bytes, 0, cutBytes, charset)
        if (text.length > maxChars) {
            text = text.substring(0, maxChars)
            notices += BodyNotice.VIEW_CUT to maxChars.toLong()
        } else if (cutBytes < bytes.size) notices += BodyNotice.VIEW_CUT to text.length.toLong()
        val kind = kindOf(contentType, text)
        val small = text.length <= Limits.VIEWER_PRETTY_MAX_CHARS
        var pretty = false
        if (kind == ContentKind.JSON && prettyJson) {
            if (small && notices.none { it.first == BodyNotice.ENGINE_CUT || it.first == BodyNotice.VIEW_CUT }) {
                val result = JsonPretty.format(text)
                if (result.text != null) { text = result.text; pretty = true } else notices += BodyNotice.INVALID_JSON to result.errorOffset.toLong()
            } else notices += BodyNotice.NOT_FORMATTED to text.length.toLong()
        }
        return FormattedBody(text, kind, pretty, highlight = small, softWrap = hasLongLine(text), notices = notices)
    }

    fun hasLongLine(text: CharSequence): Boolean {
        var run = 0
        for (c in text) {
            if (c == '\n') run = 0 else if (++run > Limits.VIEWER_LONG_LINE_CHARS) return true
        }
        return false
    }
}

/** Single-pass JSON re-indenter: keeps number text and key order, no tree, no recursion (depth is bounded). */
object JsonPretty {
    class Result(val text: String?, val errorOffset: Int)

    private const val INDENT = "  "

    fun format(src: CharSequence): Result {
        val out = StringBuilder(src.length + src.length / 4)
        val stack = StringBuilder()
        var i = 0
        val n = src.length
        fun newline() {
            out.append('\n')
            repeat(stack.length) { out.append(INDENT) }
        }
        fun nextNonSpace(from: Int): Int {
            var j = from
            while (j < n && src[j].isWhitespace()) j++
            return j
        }
        var seen = false
        while (i < n) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c == '{' || c == '[' -> {
                    if (stack.length >= Limits.VIEWER_JSON_MAX_DEPTH) return Result(null, i)
                    out.append(c)
                    val j = nextNonSpace(i + 1)
                    if (j < n && src[j] == (if (c == '{') '}' else ']')) { out.append(src[j]); i = j + 1; seen = true; continue }
                    stack.append(c)
                    newline()
                    i++
                }
                c == '}' || c == ']' -> {
                    if (stack.isEmpty() || stack.last() != (if (c == '}') '{' else '[')) return Result(null, i)
                    stack.setLength(stack.length - 1)
                    newline()
                    out.append(c)
                    i++
                    seen = true
                }
                c == ',' -> { out.append(','); newline(); i++ }
                c == ':' -> { out.append(": "); i++ }
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n && src[i] != '"') i += if (src[i] == '\\') 2 else 1
                    if (i >= n) return Result(null, start)
                    out.append(src, start, i + 1)
                    i++
                    seen = true
                }
                else -> {
                    val start = i
                    while (i < n && !src[i].isWhitespace() && src[i] !in ",:]}[{\"") i++
                    if (i == start) return Result(null, i)
                    out.append(src, start, i)
                    seen = true
                }
            }
        }
        if (stack.isNotEmpty() || !seen) return Result(null, n)
        return Result(out.toString(), -1)
    }
}

/** Human readable sizes for the status line. */
object ByteSize {
    private const val KB = 1024L

    fun format(bytes: Long): String = when {
        bytes < KB -> "$bytes B"
        bytes < KB * KB -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / KB.toDouble())
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (KB * KB).toDouble())
    }

    fun millis(ms: Long): String = if (ms < 1000) "$ms ms" else String.format(java.util.Locale.ROOT, "%.2f s", ms / 1000.0)
}
