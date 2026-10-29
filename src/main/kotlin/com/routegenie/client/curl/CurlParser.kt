package com.routegenie.client.curl

import com.routegenie.client.Header
import com.routegenie.client.MultipartPart
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.model.HttpMethod

/**
 * A pasted cURL command (bash, cmd or PowerShell style) to a [RequestSpec]. Known flags are mapped, flags that do not change
 * the request are listed as ignored, unknown flags are listed and never guessed (research 03, 5.2).
 */
object CurlParser {
    private const val MARK = '\u0001'
    private const val FORM_TYPE = "application/x-www-form-urlencoded"
    private const val JSON_TYPE = "application/json"
    private val CURL_WORD = Regex("(?i)(.*[/\\\\])?curl(\\.exe)?")
    private val WEB_CMDLET = Regex("(?im)^\\s*(invoke-restmethod|invoke-webrequest|iwr|irm)\\b")
    private val CURL_LINE = Regex("(?m)^[ \\t]*curl(\\.exe)?\\b")
    private val HERE_STRING = Regex("(?s)\\$(\\w+)\\s*=\\s*@'\\r?\\n(.*?)\\r?\\n'@")
    private val WRITE_TEXT = Regex("WriteAllText\\(\\s*\\$(\\w+)\\s*,\\s*\\$(\\w+)")
    private val WRITE_BYTES = Regex("WriteAllBytes\\(\\s*\\$(\\w+)\\s*,\\s*\\[Convert]::FromBase64String\\('([^']*)'\\)")
    private val FILE_CONCAT = Regex("\\(\\s*'@'\\s*\\+\\s*\\$(\\w+)\\s*\\)")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val GLOB = Regex("\\[[^\\]]*]|\\{[^}]*}")

    private val SHORT_TO_LONG = mapOf(
        'X' to "request", 'H' to "header", 'd' to "data", 'F' to "form", 'u' to "user", 'A' to "user-agent", 'e' to "referer",
        'b' to "cookie", 'c' to "cookie-jar", 'm' to "max-time", 'o' to "output", 'w' to "write-out", 'x' to "proxy",
        'E' to "cert", 'T' to "upload-file", 'r' to "range", 'C' to "continue-at", 'K' to "config", 'z' to "time-cond",
        'Y' to "speed-limit", 'y' to "speed-time", 'U' to "proxy-user", 't' to "telnet-option", 'Q' to "quote",
        'G' to "get", 'I' to "head", 'L' to "location", 'k' to "insecure", 'g' to "globoff", 's' to "silent",
        'S' to "show-error", 'v' to "verbose", 'i' to "include", 'f' to "fail", 'N' to "no-buffer", 'O' to "remote-name",
        'J' to "remote-header-name", 'l' to "list-only", 'p' to "proxytunnel", 'q' to "disable", 'B' to "use-ascii",
        'a' to "append", 'j' to "junk-session-cookies", 'n' to "netrc", 'Z' to "parallel", '0' to "http1.0",
        '1' to "tlsv1", '2' to "sslv2", '3' to "sslv3", '4' to "ipv4", '6' to "ipv6", '#' to "progress-bar",
    )
    private val VALUE_SHORTS = "XHdFuAebcmowxETrCKzYyUtQ".toSet()

    private val HANDLED_VALUE = setOf(
        "request", "header", "data", "data-ascii", "data-raw", "data-binary", "data-urlencode", "form", "form-string",
        "user", "url", "json", "user-agent", "referer", "cookie",
    )
    private val IGNORED_VALUE = mapOf(
        "max-time" to IgnoredReason.NO_EFFECT_HERE, "connect-timeout" to IgnoredReason.NO_EFFECT_HERE,
        "proxy" to IgnoredReason.NO_EFFECT_HERE, "proxy-user" to IgnoredReason.NO_EFFECT_HERE,
        "cacert" to IgnoredReason.NO_EFFECT_HERE, "capath" to IgnoredReason.NO_EFFECT_HERE,
        "cert" to IgnoredReason.NO_EFFECT_HERE, "key" to IgnoredReason.NO_EFFECT_HERE,
        "output" to IgnoredReason.NO_EFFECT_HERE, "write-out" to IgnoredReason.NO_EFFECT_HERE,
        "resolve" to IgnoredReason.NO_EFFECT_HERE, "connect-to" to IgnoredReason.NO_EFFECT_HERE,
        "interface" to IgnoredReason.NO_EFFECT_HERE, "retry" to IgnoredReason.NO_EFFECT_HERE,
        "retry-delay" to IgnoredReason.NO_EFFECT_HERE, "retry-max-time" to IgnoredReason.NO_EFFECT_HERE,
        "cookie-jar" to IgnoredReason.NO_EFFECT_HERE, "dump-header" to IgnoredReason.NO_EFFECT_HERE,
        "limit-rate" to IgnoredReason.NO_EFFECT_HERE, "max-redirs" to IgnoredReason.NO_EFFECT_HERE,
        "speed-limit" to IgnoredReason.NO_EFFECT_HERE, "speed-time" to IgnoredReason.NO_EFFECT_HERE,
        "range" to IgnoredReason.UNSUPPORTED, "continue-at" to IgnoredReason.UNSUPPORTED,
        "upload-file" to IgnoredReason.UNSUPPORTED, "config" to IgnoredReason.UNSUPPORTED,
        "time-cond" to IgnoredReason.UNSUPPORTED, "telnet-option" to IgnoredReason.UNSUPPORTED,
        "quote" to IgnoredReason.UNSUPPORTED, "oauth2-bearer" to IgnoredReason.UNSUPPORTED,
        "aws-sigv4" to IgnoredReason.UNSUPPORTED, "proxy-header" to IgnoredReason.NO_EFFECT_HERE,
        "tlsv1.2" to IgnoredReason.NO_EFFECT_HERE,
    )
    private val SILENT_BOOL = setOf(
        "silent", "show-error", "verbose", "include", "fail", "fail-with-body", "no-buffer", "progress-bar", "no-progress-meter",
        "no-keepalive", "tcp-nodelay", "ipv4", "ipv6", "http1.1", "http2", "http1.0", "styled-output", "retry-connrefused",
        "path-as-is", "tr-encoding", "basic", "disable", "use-ascii", "append", "junk-session-cookies", "netrc", "parallel",
        "tlsv1", "sslv2", "sslv3", "list-only", "remote-header-name", "raw", "get", "head", "location", "insecure", "globoff",
        "compressed", "location-trusted",
    )
    private val IGNORED_BOOL = mapOf(
        "remote-name" to IgnoredReason.NO_EFFECT_HERE, "proxytunnel" to IgnoredReason.NO_EFFECT_HERE,
        "digest" to IgnoredReason.UNSUPPORTED, "ntlm" to IgnoredReason.UNSUPPORTED, "negotiate" to IgnoredReason.UNSUPPORTED,
        "anyauth" to IgnoredReason.UNSUPPORTED, "http2-prior-knowledge" to IgnoredReason.UNSUPPORTED,
        "http3" to IgnoredReason.UNSUPPORTED,
    )

    private class DataPiece(val kind: Char, val text: String, val name: String? = null, val fromUrlencode: Boolean = false)

    private class State(val files: (String) -> String?) {
        var method: String? = null
        var url: String? = null
        val headers = ArrayList<Header>()
        val pieces = ArrayList<DataPiece>()
        val parts = ArrayList<MultipartPart>()
        var get = false
        var head = false
        var globoff = false
        var json = false
        var user: String? = null
        var digestLike = false
        var follow = false
        var insecure = false
        var compressed = false
        val ignored = ArrayList<IgnoredFlag>()
        val notes = ArrayList<ParseNote>()
        var stop = false
    }

    fun parse(
        text: String,
        shell: CurlShell? = null,
        files: Map<String, String> = emptyMap(),
        powerShell7: Boolean = false,
    ): CurlParseResult {
        if (text.isBlank()) return CurlParseResult.Rejected(CurlRejection.EMPTY)
        val dialect = shell ?: detect(text)
        var command = text
        val psFiles = HashMap<String, String>()
        if (dialect == CurlShell.POWERSHELL) {
            // A body line inside a here-string may itself start with "curl"; the command starts after the last terminator.
            val m = CURL_LINE.find(text, maxOf(0, text.lastIndexOf("\n'@")))
            if (m == null) {
                return CurlParseResult.Rejected(if (WEB_CMDLET.containsMatchIn(text)) CurlRejection.POWERSHELL_WEB_CMDLET else CurlRejection.NOT_CURL)
            }
            psFiles += psPrelude(text.substring(0, m.range.first))
            command = text.substring(m.range.first).lines().filterNot { it.trimStart().startsWith("Remove-Item", ignoreCase = true) }
                .joinToString("\n")
            command = FILE_CONCAT.replace(command) { "'@$MARK${it.groupValues[1]}'" }
        } else if (WEB_CMDLET.containsMatchIn(text) && !CURL_WORD.containsMatchIn(text)) {
            return CurlParseResult.Rejected(CurlRejection.POWERSHELL_WEB_CMDLET)
        }
        val tokens = when (dialect) {
            CurlShell.BASH -> CurlTokenizers.posix(command)
            CurlShell.CMD -> CurlTokenizers.cmd(command)
            CurlShell.POWERSHELL -> CurlTokenizers.powershell(command, powerShell7)
        }
        val words = tokens.words
        if (words.isEmpty()) return CurlParseResult.Rejected(CurlRejection.EMPTY)
        if (!CURL_WORD.matches(words[0])) return CurlParseResult.Rejected(CurlRejection.NOT_CURL)

        val st = State { ref -> if (ref.startsWith(MARK)) psFiles[ref.substring(1)] else files[ref] }
        st.notes += tokens.notes
        var i = 1
        while (i < words.size && !st.stop) {
            val w = words[i]
            i++
            when {
                w == "--" -> Unit
                w.startsWith("--") -> i = longOption(st, words, w, i)
                w.length > 1 && w[0] == '-' -> i = shortCluster(st, words, w, i)
                else -> positionalUrl(st, w)
            }
        }
        return finish(st, dialect)
    }

    // --- dialect detection ----------------------------------------------------------------------------------------

    fun detect(text: String): CurlShell {
        val ps = listOf("`\n", "`\r\n", "@'", "@\"", "\$env:", "--%", "Invoke-", "[IO.File]", "Join-Path")
        if (ps.any { text.contains(it) }) return CurlShell.POWERSHELL
        if (Regex("\\^\\s*\\r?\\n").containsMatchIn(text) || text.contains("^\"") || Regex("%[A-Za-z_][A-Za-z0-9_]*%").containsMatchIn(text)) return CurlShell.CMD
        if (text.contains("curl.exe", ignoreCase = true)) return if (text.contains('\'')) CurlShell.POWERSHELL else CurlShell.CMD
        return CurlShell.BASH
    }

    private fun psPrelude(prelude: String): Map<String, String> {
        val here = HERE_STRING.findAll(prelude).associate { it.groupValues[1] to it.groupValues[2] }
        val out = HashMap<String, String>()
        WRITE_TEXT.findAll(prelude).forEach { m -> here[m.groupValues[2]]?.let { out[m.groupValues[1]] = it } }
        WRITE_BYTES.findAll(prelude).forEach { m ->
            runCatching { String(java.util.Base64.getDecoder().decode(m.groupValues[2]), Charsets.UTF_8) }.getOrNull()
                ?.let { out[m.groupValues[1]] = it }
        }
        return out
    }

    // --- options --------------------------------------------------------------------------------------------------

    private fun longOption(st: State, words: List<String>, word: String, next: Int): Int {
        var i = next
        val eq = word.indexOf('=')
        val name = (if (eq > 0) word.substring(2, eq) else word.substring(2))
        var inline: String? = if (eq > 0) word.substring(eq + 1) else null
        val takesValue = name in HANDLED_VALUE || name in IGNORED_VALUE
        val known = takesValue || name in SILENT_BOOL || name in IGNORED_BOOL || name == "next"
        if (!known) {
            st.ignored += IgnoredFlag(word, IgnoredReason.UNKNOWN_OPTION)
            return i
        }
        if (takesValue) {
            if (inline != null) st.notes += ParseNote(NoteKind.INVALID_LONG_OPTION_SYNTAX, word.substring(0, eq))
            else if (i < words.size) { inline = words[i]; i++ } else { st.ignored += IgnoredFlag(word, IgnoredReason.INVALID); return i }
        }
        apply(st, name, inline, word)
        return i
    }

    private fun shortCluster(st: State, words: List<String>, word: String, next: Int): Int {
        var i = next
        var k = 1
        while (k < word.length) {
            val c = word[k]
            val long = SHORT_TO_LONG[c]
            if (long == null) { st.ignored += IgnoredFlag("-$c", IgnoredReason.UNKNOWN_OPTION); k++; continue }
            if (c in VALUE_SHORTS) {
                val glued = word.substring(k + 1)
                val value = if (glued.isNotEmpty()) glued else if (i < words.size) words[i++] else null
                if (value == null) st.ignored += IgnoredFlag("-$c", IgnoredReason.INVALID) else apply(st, long, value, "-$c")
                return i
            }
            apply(st, long, null, "-$c")
            k++
        }
        return i
    }

    private fun positionalUrl(st: State, w: String) {
        if (st.url == null) st.url = w else st.ignored += IgnoredFlag(w, IgnoredReason.UNSUPPORTED)
    }

    private fun apply(st: State, name: String, value: String?, display: String) {
        when (name) {
            "next" -> { st.ignored += IgnoredFlag("--next", IgnoredReason.UNSUPPORTED); st.notes += ParseNote(NoteKind.TAIL_IGNORED, "--next"); st.stop = true }
            "request" -> {
                val m = HttpMethod.parse(value.orEmpty())
                if (m == null) { st.ignored += IgnoredFlag("$display ${value.orEmpty()}", IgnoredReason.INVALID); st.notes += ParseNote(NoteKind.UNKNOWN_METHOD, value.orEmpty()) }
                else st.method = m.name
            }
            "header" -> header(st, value.orEmpty(), display)
            "data", "data-ascii" -> data(st, 'd', value.orEmpty(), display)
            "data-raw" -> data(st, 'r', value.orEmpty(), display)
            "data-binary" -> data(st, 'b', value.orEmpty(), display)
            "data-urlencode" -> data(st, 'u', value.orEmpty(), display)
            "json" -> { st.json = true; data(st, 'b', value.orEmpty(), display) }
            "form" -> form(st, value.orEmpty(), literal = false, display)
            "form-string" -> form(st, value.orEmpty(), literal = true, display)
            "user" -> st.user = value
            "url" -> positionalUrl(st, value.orEmpty())
            "user-agent" -> st.headers += Header("User-Agent", value.orEmpty())
            "referer" -> st.headers += Header("Referer", value.orEmpty())
            "cookie" -> if (value.orEmpty().contains('=')) st.headers += Header("Cookie", value.orEmpty()) else st.ignored += IgnoredFlag("$display $value", IgnoredReason.FILE_NOT_READ)
            "get" -> st.get = true
            "head" -> st.head = true
            "location" -> st.follow = true
            "location-trusted" -> { st.follow = true; st.notes += ParseNote(NoteKind.LOCATION_TRUSTED) }
            "insecure" -> st.insecure = true
            "compressed" -> st.compressed = true
            "globoff" -> st.globoff = true
            else -> {
                IGNORED_VALUE[name]?.let { st.ignored += IgnoredFlag(display, it) }
                IGNORED_BOOL[name]?.let { r -> st.ignored += IgnoredFlag(display, r); if (r == IgnoredReason.UNSUPPORTED && name in setOf("digest", "ntlm", "negotiate", "anyauth")) st.digestLike = true }
            }
        }
    }

    private fun header(st: State, raw: String, display: String) {
        if (raw.startsWith("@")) {
            val content = st.files(raw.substring(1))
            if (content == null) st.ignored += IgnoredFlag("$display $raw", IgnoredReason.FILE_NOT_READ)
            else content.lines().filter { it.isNotBlank() }.forEach { header(st, it.trimEnd('\r'), display) }
            return
        }
        val colon = raw.indexOf(':')
        if (colon > 0) {
            val name = raw.substring(0, colon).trim()
            val value = raw.substring(colon + 1).trim()
            if (value.isEmpty()) st.headers.removeAll { it.name.equals(name, true) } else st.headers += Header(name, value)
        } else if (raw.endsWith(";") && raw.length > 1) {
            st.headers += Header(raw.dropLast(1).trim(), "")
        } else st.ignored += IgnoredFlag("$display $raw".take(60), IgnoredReason.INVALID)
    }

    private fun data(st: State, kind: Char, value: String, display: String) {
        when (kind) {
            'd' -> if (value.startsWith("@")) {
                val c = st.files(value.substring(1))
                if (c == null) st.ignored += IgnoredFlag("$display $value", IgnoredReason.FILE_NOT_READ)
                else st.pieces += DataPiece('d', c.replace("\r", "").replace("\n", ""))
            } else st.pieces += DataPiece('d', value)
            'r' -> st.pieces += DataPiece('r', value)
            'b' -> if (value.startsWith("@")) {
                val c = st.files(value.substring(1))
                if (c == null) st.ignored += IgnoredFlag("$display $value", IgnoredReason.FILE_NOT_READ) else st.pieces += DataPiece('b', c)
            } else st.pieces += DataPiece('b', value)
            else -> {
                val eq = value.indexOf('=')
                val at = value.indexOf('@')
                when {
                    eq >= 0 -> st.pieces += DataPiece('u', value.substring(eq + 1), value.substring(0, eq).ifEmpty { null }, true)
                    at >= 0 -> {
                        val c = st.files(value.substring(at + 1))
                        if (c == null) st.ignored += IgnoredFlag("$display $value", IgnoredReason.FILE_NOT_READ)
                        else st.pieces += DataPiece('u', c, value.substring(0, at).ifEmpty { null }, true)
                    }
                    else -> st.pieces += DataPiece('u', value, null, true)
                }
            }
        }
    }

    private fun form(st: State, value: String, literal: Boolean, display: String) {
        val eq = value.indexOf('=')
        if (eq <= 0) { st.ignored += IgnoredFlag("$display ${value.take(40)}", IgnoredReason.INVALID); return }
        val name = value.substring(0, eq)
        val rest = value.substring(eq + 1)
        if (literal) { st.parts += MultipartPart(name, value = rest); return }
        val marker = rest.firstOrNull()
        if (marker == '@' || marker == '<') {
            val (path, attrs) = splitFileValue(rest.substring(1))
            val content = if (marker == '<') st.files(path) else null
            if (marker == '<' && content != null) {
                st.parts += MultipartPart(name, value = content, contentType = attrs["type"])
            } else {
                if (marker == '<') st.notes += ParseNote(NoteKind.FILE_AS_PART_TEXT, path)
                st.parts += MultipartPart(name, filePath = path, fileName = attrs["filename"], contentType = attrs["type"])
            }
            return
        }
        val (text, attrs) = if (rest.startsWith("\"")) splitFileValue(rest) else splitTextValue(rest)
        st.parts += MultipartPart(name, value = text, contentType = attrs["type"])
    }

    /** `path;type=x;filename=y` with an optional double-quoted path (`\\` and `\"` escapes). */
    private fun splitFileValue(s: String): Pair<String, Map<String, String>> {
        val path = StringBuilder()
        var i = 0
        if (s.startsWith("\"")) {
            i = 1
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length && (s[i + 1] == '"' || s[i + 1] == '\\')) { path.append(s[i + 1]); i += 2 } else { path.append(s[i]); i++ }
            }
            i++
        } else {
            while (i < s.length && s[i] != ';') { path.append(s[i]); i++ }
        }
        return path.toString() to attributes(s.substring(minOf(i, s.length)))
    }

    private fun splitTextValue(s: String): Pair<String, Map<String, String>> {
        val idx = s.indexOf(";type=")
        return if (idx < 0) s to emptyMap() else s.substring(0, idx) to attributes(s.substring(idx))
    }

    private fun attributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            if (s[i] != ';') { i++; continue }
            i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq)
            val sb = StringBuilder()
            i = eq + 1
            if (i < s.length && s[i] == '"') {
                i++
                while (i < s.length && s[i] != '"') {
                    if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
                }
                i++
            } else {
                while (i < s.length && s[i] != ';') { sb.append(s[i]); i++ }
            }
            out[key] = sb.toString()
        }
        return out
    }

    // --- assembling the request -----------------------------------------------------------------------------------

    private fun finish(st: State, shell: CurlShell): CurlParseResult {
        var url = st.url ?: return CurlParseResult.Rejected(CurlRejection.NO_URL)
        if (!SCHEME.containsMatchIn(url) && !url.startsWith("{{")) { url = "http://$url"; st.notes += ParseNote(NoteKind.SCHEME_ASSUMED, url) }
        if (!st.globoff && GLOB.containsMatchIn(url.replace(Regex("\\{\\{[^}]*}}"), ""))) st.notes += ParseNote(NoteKind.GLOB_URL)

        val headers = ArrayList(st.headers)
        val contentType = headers.lastOrNull { it.name.equals("Content-Type", true) }?.value
        var body: RequestBody = RequestBody.None

        if (st.parts.isNotEmpty()) {
            headers.removeAll { it.name.equals("Content-Type", true) }
            body = RequestBody.Multipart(st.parts.toList())
        } else if (st.pieces.isNotEmpty()) {
            if (st.get) {
                val q = st.pieces.joinToString("&") { encodedText(it) }
                url += (if (url.contains('?')) "&" else "?") + q
                headers.removeIf { it.name.equals("Content-Type", true) }
            } else {
                val explicit = when {
                    contentType != null -> contentType
                    st.json -> JSON_TYPE
                    else -> null
                }
                val isForm = explicit == null || explicit.substringBefore(';').trim().equals(FORM_TYPE, true)
                val fields = if (isForm) formFields(st.pieces) else null
                body = if (fields != null) RequestBody.Form(fields)
                else RequestBody.Text(explicit ?: FORM_TYPE, st.pieces.joinToString("&") { encodedText(it) })
                headers.removeAll { it.name.equals("Content-Type", true) }
                if (st.json && headers.none { it.name.equals("Accept", true) }) headers += Header("Accept", JSON_TYPE)
            }
        }

        st.user?.let { u ->
            val colon = u.indexOf(':')
            if (st.digestLike) st.ignored += IgnoredFlag("-u", IgnoredReason.UNSUPPORTED)
            else if (colon < 0) { st.notes += ParseNote(NoteKind.BASIC_WITHOUT_PASSWORD, u); st.ignored += IgnoredFlag("-u $u", IgnoredReason.INVALID) }
            else headers += Header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(u.toByteArray(Charsets.UTF_8)))
        }

        val method = when {
            st.method != null -> HttpMethod.parse(st.method!!)!!
            st.head -> HttpMethod.HEAD
            st.get -> HttpMethod.GET
            body !is RequestBody.None -> HttpMethod.POST
            else -> HttpMethod.GET
        }
        val spec = RequestSpec(method, url, headers, body)
        return CurlParseResult.Parsed(
            ParsedCurl(spec, CurlFlags(st.compressed, st.follow, st.insecure), shell, st.ignored, st.notes),
        )
    }

    private fun encodedText(p: DataPiece): String = when {
        p.fromUrlencode -> (if (p.name != null) p.name + "=" else "") + CurlQuoting.formEncode(p.text)
        else -> p.text
    }

    /** Fields when every piece is a `name=value` pair, otherwise null (the body is kept as text). */
    private fun formFields(pieces: List<DataPiece>): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        for (p in pieces) {
            if (p.fromUrlencode) {
                if (p.name == null) return null
                out.add(p.name to p.text)
                continue
            }
            if (p.text.isEmpty()) continue
            for (pair in p.text.split('&')) {
                val eq = pair.indexOf('=')
                if (eq < 0) return null
                val k = CurlQuoting.formDecode(pair.substring(0, eq)) ?: return null
                val v = CurlQuoting.formDecode(pair.substring(eq + 1)) ?: return null
                out.add(k to v)
            }
        }
        return out
    }
}
