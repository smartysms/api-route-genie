package com.routegenie.client.curl

/** Words of a command line plus what was noticed while splitting it. */
internal class Tokens(val words: List<String>, val notes: List<ParseNote>)

/**
 * The three dialects of research 03 (5.2). Each turns pasted text into the argument list curl would receive; variable
 * references become `{{NAME}}` placeholders (never evaluated), command substitution is reported, and an unquoted operator
 * ends the command.
 */
internal object CurlTokenizers {
    private val NAME_START = Regex("[A-Za-z_]")
    private val CMD_VAR = Regex("%([A-Za-z_][A-Za-z0-9_]*)%")
    private val PS_QUOTES = "'\u2018\u2019\u201A\u201B"

    private fun placeholder(name: String) = "{{$name}}"

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun readName(src: String, from: Int): String {
        var j = from
        while (j < src.length && isNameChar(src[j])) j++
        return src.substring(from, j)
    }

    // --- POSIX ----------------------------------------------------------------------------------------------------

    fun posix(src: String): Tokens {
        val words = ArrayList<String>()
        val notes = ArrayList<ParseNote>()
        val cur = StringBuilder()
        var inWord = false
        var i = 0
        val n = src.length

        fun flush() { if (inWord) { words += cur.toString(); cur.setLength(0); inWord = false } }

        fun variable(at: Int): Int {
            // returns the index after the reference, or -1 when `$` is not a reference
            if (at + 1 >= n) return -1
            val c = src[at + 1]
            if (c == '{') {
                val close = src.indexOf('}', at + 2)
                if (close < 0) return -1
                val name = src.substring(at + 2, close)
                if (name.isEmpty() || !name.all { isNameChar(it) }) return -1
                cur.append(placeholder(name)); notes += ParseNote(NoteKind.UNRESOLVED_VARIABLE, name)
                return close + 1
            }
            if (c == '(') { notes += ParseNote(NoteKind.COMMAND_SUBSTITUTION); return -1 }
            if (NAME_START.matches(c.toString())) {
                val name = readName(src, at + 1)
                cur.append(placeholder(name)); notes += ParseNote(NoteKind.UNRESOLVED_VARIABLE, name)
                return at + 1 + name.length
            }
            return -1
        }

        while (i < n) {
            val c = src[i]
            when {
                c == '\\' -> {
                    when {
                        i + 1 >= n -> i++
                        src[i + 1] == '\n' -> i += 2
                        src[i + 1] == '\r' && i + 2 < n && src[i + 2] == '\n' -> i += 3
                        else -> { cur.append(src[i + 1]); inWord = true; i += 2 }
                    }
                }
                c == '\'' -> {
                    inWord = true
                    val close = src.indexOf('\'', i + 1).let { if (it < 0) n else it }
                    cur.append(src, i + 1, close)
                    i = close + 1
                }
                c == '"' || (c == '$' && i + 1 < n && src[i + 1] == '"') -> {
                    inWord = true
                    i += if (c == '"') 1 else 2
                    while (i < n && src[i] != '"') {
                        val d = src[i]
                        if (d == '\\' && i + 1 < n && src[i + 1] in "\"\\$`\n") {
                            if (src[i + 1] != '\n') cur.append(src[i + 1])
                            i += 2
                        } else if (d == '$') {
                            val next = variable(i)
                            if (next < 0) { cur.append(d); i++ } else i = next
                        } else if (d == '`') {
                            notes += ParseNote(NoteKind.COMMAND_SUBSTITUTION); cur.append(d); i++
                        } else { cur.append(d); i++ }
                    }
                    i++
                }
                c == '$' && i + 1 < n && src[i + 1] == '\'' -> {
                    inWord = true
                    i = ansiC(src, i + 2, cur)
                }
                c == '$' -> {
                    inWord = true
                    val next = variable(i)
                    if (next < 0) { cur.append(c); i++ } else i = next
                }
                c == '`' -> { notes += ParseNote(NoteKind.COMMAND_SUBSTITUTION); cur.append(c); inWord = true; i++ }
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> { flush(); i++ }
                c == '#' && !inWord -> { while (i < n && src[i] != '\n') i++ }
                c == '|' || c == ';' || c == '&' || c == '<' || c == '>' -> {
                    flush()
                    notes += ParseNote(NoteKind.TAIL_IGNORED, c.toString())
                    return Tokens(words, notes)
                }
                else -> { cur.append(c); inWord = true; i++ }
            }
        }
        flush()
        return Tokens(words, notes)
    }

    /** Reads an ANSI-C string body starting after `$'`; returns the index after the closing quote. */
    private fun ansiC(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        val n = src.length
        while (i < n && src[i] != '\'') {
            val c = src[i]
            if (c != '\\' || i + 1 >= n) { out.append(c); i++; continue }
            val e = src[i + 1]
            i += 2
            when (e) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); 'a' -> out.append('\u0007')
                'b' -> out.append('\b'); 'e', 'E' -> out.append('\u001b'); 'f' -> out.append('\u000c'); 'v' -> out.append('\u000b')
                '\\', '\'', '"', '?' -> out.append(e)
                'x' -> {
                    var j = i
                    while (j < n && j < i + 2 && src[j].digitToIntOrNull(16) != null) j++
                    if (j > i) { out.append(src.substring(i, j).toInt(16).toChar()); i = j } else out.append("\\x")
                }
                'u', 'U' -> {
                    val max = if (e == 'u') 4 else 8
                    var j = i
                    while (j < n && j < i + max && src[j].digitToIntOrNull(16) != null) j++
                    if (j > i) { out.appendCodePoint(src.substring(i, j).toInt(16)); i = j } else out.append('\\').append(e)
                }
                in '0'..'7' -> {
                    var j = i
                    while (j < n && j < i + 2 && src[j] in '0'..'7') j++
                    out.append((e.digitToInt() * 1).let { src.substring(i - 1, j).toInt(8) }.toChar()); i = j
                }
                else -> out.append('\\').append(e)
            }
        }
        return i + 1
    }

    // --- cmd.exe --------------------------------------------------------------------------------------------------

    fun cmd(src: String): Tokens {
        val notes = ArrayList<ParseNote>()
        val line = StringBuilder()
        var inQuote = false
        var newlineIsData = false
        var i = 0
        val n = src.length
        loop@ while (i < n) {
            val c = src[i]
            if (c == '%') {
                if (!inQuote && i + 1 < n && src[i + 1] == '^') { line.append('%'); i += 2; newlineIsData = false; continue }
                val m = CMD_VAR.matchAt(src, i)
                if (m != null) {
                    line.append(placeholder(m.groupValues[1])); notes += ParseNote(NoteKind.UNRESOLVED_VARIABLE, m.groupValues[1])
                    i += m.value.length; newlineIsData = false; continue
                }
                line.append(c); i++; newlineIsData = false; continue
            }
            if (c == '"') { inQuote = !inQuote; line.append(c); i++; newlineIsData = false; continue }
            if (inQuote) { line.append(c); i++; continue }
            when (c) {
                '^' -> {
                    when {
                        i + 1 >= n -> i++
                        src[i + 1] == '\n' -> { i += 2; newlineIsData = true }
                        src[i + 1] == '\r' && i + 2 < n && src[i + 2] == '\n' -> { i += 3; newlineIsData = true }
                        else -> { line.append(src[i + 1]); i += 2; newlineIsData = false }
                    }
                }
                '&', '|', '<', '>' -> {
                    notes += ParseNote(if (c == '&') NoteKind.CMD_AMPERSAND_ENDED else NoteKind.TAIL_IGNORED, c.toString())
                    break@loop
                }
                '\r' -> i++
                '\n' -> { if (newlineIsData) line.append('\n') else line.append(' '); newlineIsData = false; i++ }
                else -> { line.append(c); i++; newlineIsData = false }
            }
        }
        return Tokens(crtSplit(line.toString()), notes)
    }

    /** The Microsoft C runtime's rules for splitting a command line (post-2008: `""` inside quotes is a quote). */
    fun crtSplit(line: String): List<String> {
        val words = ArrayList<String>()
        var i = 0
        val n = line.length
        while (i < n) {
            while (i < n && (line[i] == ' ' || line[i] == '\t')) i++
            if (i >= n) break
            val cur = StringBuilder()
            var inQuote = false
            while (i < n) {
                val c = line[i]
                if (!inQuote && (c == ' ' || c == '\t')) break
                if (c == '\\') {
                    var run = 0
                    while (i < n && line[i] == '\\') { run++; i++ }
                    if (i < n && line[i] == '"') {
                        repeat(run / 2) { cur.append('\\') }
                        if (run % 2 == 1) { cur.append('"'); i++ }
                    } else repeat(run) { cur.append('\\') }
                } else if (c == '"') {
                    if (inQuote && i + 1 < n && line[i + 1] == '"') { cur.append('"'); i += 2 } else { inQuote = !inQuote; i++ }
                } else { cur.append(c); i++ }
            }
            words += cur.toString()
        }
        return words
    }

    // --- PowerShell -----------------------------------------------------------------------------------------------

    fun powershell(src: String, ps7: Boolean): Tokens {
        val words = ArrayList<String>()
        val notes = ArrayList<ParseNote>()
        val cur = StringBuilder()
        var inWord = false
        var i = 0
        val n = src.length

        fun finish() {
            if (!inWord) return
            var w = cur.toString()
            if (!ps7) {
                if (w.contains("\\\"")) w = w.replace("\\\"", "\"")
                else if (w.contains('"')) notes += ParseNote(NoteKind.POWERSHELL_73_STYLE)
                if (w.any { it == ' ' || it == '\t' }) {
                    val trailing = w.length - w.trimEnd('\\').length
                    if (trailing >= 2 && trailing % 2 == 0) w = w.dropLast(trailing / 2)
                }
            }
            words += w; cur.setLength(0); inWord = false
        }

        fun variable(at: Int): Int {
            if (at + 1 >= n) return -1
            if (src[at + 1] == '(') { notes += ParseNote(NoteKind.COMMAND_SUBSTITUTION); return -1 }
            var from = at + 1
            if (src.startsWith("env:", from, ignoreCase = true)) from += 4
            if (from >= n || !NAME_START.matches(src[from].toString())) return -1
            val name = readName(src, from)
            cur.append(placeholder(name)); notes += ParseNote(NoteKind.UNRESOLVED_VARIABLE, name)
            return from + name.length
        }

        while (i < n) {
            val c = src[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> { finish(); i++ }
                c == '`' -> {
                    when {
                        i + 1 >= n -> i++
                        src[i + 1] == '\n' -> i += 2
                        src[i + 1] == '\r' && i + 2 < n && src[i + 2] == '\n' -> i += 3
                        else -> { cur.append(src[i + 1]); inWord = true; i += 2 }
                    }
                }
                c in PS_QUOTES -> {
                    inWord = true
                    i++
                    while (i < n) {
                        val d = src[i]
                        if (d in PS_QUOTES) {
                            if (i + 1 < n && src[i + 1] in PS_QUOTES) { cur.append(d); i += 2 } else break
                        } else { cur.append(d); i++ }
                    }
                    i++
                }
                c == '"' -> {
                    inWord = true
                    i++
                    while (i < n) {
                        val d = src[i]
                        if (d == '"') {
                            if (i + 1 < n && src[i + 1] == '"') { cur.append('"'); i += 2 } else break
                        } else if (d == '`' && i + 1 < n) {
                            cur.append(
                                when (val e = src[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '0' -> '\u0000'; else -> e },
                            )
                            i += 2
                        } else if (d == '$') {
                            val next = variable(i)
                            if (next < 0) { cur.append(d); i++ } else i = next
                        } else { cur.append(d); i++ }
                    }
                    i++
                }
                c == '$' -> {
                    inWord = true
                    val next = variable(i)
                    if (next < 0) { cur.append(c); i++ } else i = next
                }
                c == '@' && i + 2 < n && (src[i + 1] == '\'' || src[i + 1] == '"') && (src[i + 2] == '\n' || src[i + 2] == '\r') && !inWord -> {
                    val quote = src[i + 1]
                    val start = src.indexOf('\n', i) + 1
                    val end = src.indexOf("\n$quote@", start - 1)
                    val body = if (end < start) "" else src.substring(start, end)
                    words += body.removeSuffix("\r")
                    i = if (end < 0) n else end + 3
                }
                c == '-' && !inWord && src.startsWith("--%", i) && (i + 3 >= n || src[i + 3].isWhitespace()) -> {
                    val eol = src.indexOf('\n', i).let { if (it < 0) n else it }
                    words += "--%"
                    words += crtSplit(src.substring(i + 3, eol).trim())
                    i = eol
                }
                c == '#' && !inWord -> { while (i < n && src[i] != '\n') i++ }
                c == '|' || c == ';' || c == '&' || c == '<' || c == '>' -> {
                    finish()
                    notes += ParseNote(NoteKind.TAIL_IGNORED, c.toString())
                    return Tokens(words, notes)
                }
                else -> { cur.append(c); inWord = true; i++ }
            }
        }
        finish()
        return Tokens(words, notes)
    }
}
