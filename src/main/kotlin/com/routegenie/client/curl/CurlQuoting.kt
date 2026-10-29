package com.routegenie.client.curl

/**
 * The quoting rules of research 03 (5.1), one function per dialect. This is security-critical code: a missed metacharacter
 * in cmd or PowerShell lets a crafted header value append a second command. Anything that cannot be represented throws
 * [CurlUnsupportedException] instead of being guessed.
 */
internal object CurlQuoting {
    private val PS_QUOTES = setOf('\'', '‘', '’', '‚', '‛')
    private const val DEL = '\u007f'
    private const val CTRL_LIMIT = ' '
    private const val BACKSLASH = '\\'

    fun checkText(s: String, shell: CurlShell) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u0000') throw CurlUnsupportedException(CurlProblem.NUL_CHARACTER, shell)
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) throw CurlUnsupportedException(CurlProblem.LONE_SURROGATE, shell)
                i++
            } else if (Character.isLowSurrogate(c)) throw CurlUnsupportedException(CurlProblem.LONE_SURROGATE, shell)
            i++
        }
    }

    private fun isControl(c: Char) = (c < CTRL_LIMIT && c != '\n' && c != '\t') || c == DEL

    // --- POSIX ----------------------------------------------------------------------------------------------------

    fun posix(s: String): String {
        checkText(s, CurlShell.BASH)
        if (s.none { isControl(it) }) return "'" + s.replace("'", "'\\''") + "'"
        val sb = StringBuilder("$'")
        for (c in s) {
            when {
                c == BACKSLASH -> sb.append("\\\\")
                c == '\'' -> sb.append("\\'")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                isControl(c) -> sb.append("\\x").append(String.format("%02x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('\'').toString()
    }

    // --- cmd.exe --------------------------------------------------------------------------------------------------

    /** True when [s] can be put on a cmd command line at all (ASCII, no control characters other than LF). */
    fun cmdRepresentable(s: String): Boolean = s.all { (it in ' '..'~') || it == '\n' }

    /** N backslashes before a quote become 2N plus the escaped quote; trailing backslashes are doubled (closing quote follows). */
    private fun crtEscape(s: String): String {
        val sb = StringBuilder()
        var run = 0
        for (c in s) {
            when (c) {
                BACKSLASH -> run++
                '"' -> { repeat(run * 2) { sb.append(BACKSLASH) }; run = 0; sb.append("\\\"") }
                else -> { repeat(run) { sb.append(BACKSLASH) }; run = 0; sb.append(c) }
            }
        }
        repeat(run * 2) { sb.append(BACKSLASH) }
        return sb.toString()
    }

    fun cmd(s: String): String {
        checkText(s, CurlShell.CMD)
        if (!cmdRepresentable(s)) throw CurlUnsupportedException(CurlProblem.ARGUMENT_NOT_REPRESENTABLE, CurlShell.CMD)
        val crt = crtEscape(s)
        if (s.none { it == '"' || it == '%' || it == '\n' }) return "\"$crt\""
        val sb = StringBuilder("^\"")
        for (c in crt) {
            when (c) {
                '&', '|', '<', '>', '^', '(', ')', '"' -> sb.append('^').append(c)
                '%' -> sb.append("%^")
                '\n' -> sb.append("^\n\n")
                else -> sb.append(c)
            }
        }
        return sb.append("^\"").toString()
    }

    // --- PowerShell -----------------------------------------------------------------------------------------------

    /** True when [s] can be an inline single-quoted argument on Windows PowerShell 5.1 (no quote, ASCII, one line). */
    fun psInlineSafe51(s: String): Boolean = s.all { (it in ' '..'~') && it != '"' }

    /** Single-quoted literal; typographic quotes count as quotes in PowerShell and are doubled as well. */
    fun powershell(s: String, ps7: Boolean): String {
        checkText(s, CurlShell.POWERSHELL)
        if (!ps7 && s.any { it == '"' || it == '\n' || it == '\r' || isControl(it) || it > '~' }) {
            throw CurlUnsupportedException(CurlProblem.ARGUMENT_NOT_REPRESENTABLE, CurlShell.POWERSHELL)
        }
        val sb = StringBuilder("'")
        for (c in s) {
            if (c in PS_QUOTES) sb.append(c)
            sb.append(c)
        }
        // Windows PowerShell 5.1 wraps an argument that has whitespace in quotes without escaping a trailing backslash.
        if (!ps7 && s.any { it == ' ' || it == '\t' }) {
            val trailing = s.length - s.trimEnd(BACKSLASH).length
            repeat(trailing) { sb.append(BACKSLASH) }
        }
        return sb.append('\'').toString()
    }

    /** Percent-encoding of an application/x-www-form-urlencoded component (space becomes `+`). */
    fun formEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '*' -> sb.append(c)
                c == ' ' -> sb.append('+')
                else -> sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
            }
        }
        return sb.toString()
    }

    /** Reverse of [formEncode]; null when a percent sign is not followed by two hex digits. */
    fun formDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '+') {
                out.write(' '.code); i++
            } else if (c == '%') {
                if (i + 3 > s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(v); i += 3
            } else {
                val cp = s.codePointAt(i)
                out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)); i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
