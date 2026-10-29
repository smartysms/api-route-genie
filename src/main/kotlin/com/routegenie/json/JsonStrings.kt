package com.routegenie.json

import java.nio.charset.Charset

/** Decoding and encoding of JSON string literals. */
object JsonStrings {
    private const val BACKSLASH = '\\'.code.toByte()

    /** Text between the quotes of a JSON string, with escapes resolved. Input that the scanner accepted never fails. */
    fun decode(bytes: ByteArray, off: Int, len: Int, charset: Charset): String {
        var hasEscape = false
        for (k in off until off + len) {
            if (bytes[k] == BACKSLASH) {
                hasEscape = true
                break
            }
        }
        val raw = String(bytes, off, len, charset)
        return if (hasEscape) unescape(raw) else raw
    }

    fun unescape(raw: String): String {
        if (raw.indexOf('\\') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= n) {
                out.append(c)
                i++
                continue
            }
            when (val e = raw[i + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val code = if (i + 6 <= n) raw.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append("\\u")
                }
                else -> out.append('\\').append(e)
            }
            i += 2
        }
        return out.toString()
    }

    /** A JSON string literal (with quotes) for [text]. */
    fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
