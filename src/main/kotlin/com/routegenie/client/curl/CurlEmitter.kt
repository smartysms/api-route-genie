package com.routegenie.client.curl

import com.routegenie.Limits
import com.routegenie.client.Header
import com.routegenie.client.MultipartPart
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.model.HttpMethod

/**
 * [RequestSpec] to command text for bash, cmd and PowerShell. The rules (quoting, `-g`, here-string files, caret form) are
 * those of research 03, section 5.1; emitting then parsing gives the same [RequestSpec] (see CurlRoundTripTest).
 */
object CurlEmitter {
    private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val SIMPLE_FIELD_NAME = Regex("[A-Za-z0-9_.~\\[\\]-]+")
    private val HERE_TERMINATOR = Regex("(^|\\n)['‘’‚‛]@")
    private const val CMD_BODY_FILE = "body.json"
    private const val PS_BODY_FILE = "ee_body.json"
    private const val PS_HEADER_FILE = "ee_headers.txt"
    private const val PS_FILE_ARG = "('@' + \$f)"
    private const val PS_HEADER_FILE_ARG = "('@' + \$hf)"
    private val VALUE_OPTIONS = setOf("-X", "-H", "--data-raw", "--data-binary", "--data-urlencode", "-F", "--form-string")

    fun emit(spec: RequestSpec, options: CurlOptions = CurlOptions()): CurlCommand = Builder(spec, options).build()

    private class Builder(val spec: RequestSpec, val o: CurlOptions) {
        val shell = o.shell
        val ps7 = o.powerShell7
        val args = ArrayList<String>()
        val prelude = ArrayList<String>()
        val postlude = ArrayList<String>()
        val sideFiles = ArrayList<SideFile>()
        val warnings = ArrayList<CurlWarning>()

        fun q(s: String): String = when (shell) {
            CurlShell.BASH -> CurlQuoting.posix(s)
            CurlShell.CMD -> CurlQuoting.cmd(s)
            CurlShell.POWERSHELL -> CurlQuoting.powershell(s, ps7)
        }

        /** Does [s] fit inline (as a quoted argument) in this dialect without a file detour? */
        fun inlineOk(s: String): Boolean = when (shell) {
            CurlShell.BASH -> true
            CurlShell.CMD -> CurlQuoting.cmdRepresentable(s)
            CurlShell.POWERSHELL -> ps7 || CurlQuoting.psInlineSafe51(s)
        }

        fun build(): CurlCommand {
            val url = if (o.maskSecrets) SecretMask.maskUrl(spec.url) else spec.url
            CurlQuoting.checkText(url, shell)
            if (url.any { it == '[' || it == ']' || it == '{' || it == '}' }) args += "-g"
            val body = spec.body
            val hasBody = body !is RequestBody.None
            when {
                spec.method == HttpMethod.GET && !hasBody -> Unit
                spec.method == HttpMethod.HEAD && !hasBody -> args += "-I"
                else -> { args += "-X"; args += spec.method.name }
            }
            args += q(url)
            emitHeaders(headersWithBodyType(body))
            emitBody(body)
            if (o.flags.compressed) args += "--compressed"
            if (o.flags.followRedirects) args += "-L"
            if (o.flags.insecure) args += "-k"
            return assemble()
        }

        private fun headersWithBodyType(body: RequestBody): List<Header> {
            val enabled = spec.headers.filter { it.enabled }
            val type = (body as? RequestBody.Text)?.contentType ?: return enabled
            return if (enabled.any { it.name.equals("Content-Type", true) }) enabled else enabled + Header("Content-Type", type)
        }

        private fun headerLine(h: Header): String {
            if (!TOKEN.matches(h.name)) throw CurlUnsupportedException(CurlProblem.HEADER_NAME_INVALID, shell)
            if (h.value.any { it == '\n' || it == '\r' }) throw CurlUnsupportedException(CurlProblem.HEADER_LINE_BREAK, shell)
            val value = if (o.maskSecrets && SecretMask.isSecretHeader(h.name)) SecretMask.PLACEHOLDER else h.value
            CurlQuoting.checkText(value, shell)
            // "Name:" would remove the header in curl; "Name;" sends it empty.
            return if (value.isEmpty()) "${h.name};" else "${h.name}: $value"
        }

        private fun emitHeaders(headers: List<Header>) {
            val lines = headers.map { headerLine(it) }
            if (shell == CurlShell.POWERSHELL && !ps7 && lines.any { !CurlQuoting.psInlineSafe51(it) }) {
                prelude += psFile("hdr", "hf", PS_HEADER_FILE, lines.joinToString("\n"))
                args += "-H"
                args += PS_HEADER_FILE_ARG
                postlude += "Remove-Item \$hf"
                return
            }
            for (l in lines) { args += "-H"; args += q(l) }
        }

        private fun emitBody(body: RequestBody) {
            when (body) {
                RequestBody.None -> Unit
                is RequestBody.Text -> emitText(body.text)
                is RequestBody.Form -> emitForm(body)
                is RequestBody.Multipart -> body.parts.forEach { emitPart(it) }
            }
        }

        private fun emitText(text: String) {
            CurlQuoting.checkText(text, shell)
            when (shell) {
                CurlShell.BASH -> { args += "--data-raw"; args += q(text) }
                CurlShell.CMD ->
                    if (CurlQuoting.cmdRepresentable(text) && text.length <= Limits.CURL_CMD_INLINE_BODY_MAX_CHARS) {
                        args += "--data-raw"; args += q(text)
                    } else {
                        sideFiles += SideFile(CMD_BODY_FILE, text)
                        warnings += CurlWarning.BODY_IN_SIDE_FILE
                        args += "--data-binary"; args += CurlQuoting.cmd("@$CMD_BODY_FILE")
                    }
                CurlShell.POWERSHELL ->
                    if (ps7 || CurlQuoting.psInlineSafe51(text)) {
                        args += "--data-raw"; args += q(text)
                    } else {
                        prelude += psFile("body", "f", PS_BODY_FILE, text)
                        args += "--data-binary"; args += PS_FILE_ARG
                        postlude += "Remove-Item \$f"
                    }
            }
        }

        /** The here-string plus temp file idiom of research 03 (PS 5.1); bytes are written as UTF-8 without BOM. */
        private fun psFile(contentVar: String, pathVar: String, fileName: String, text: String): List<String> {
            val safeHere = !text.contains('\r') && text.none { it == '\u0000' } && !HERE_TERMINATOR.containsMatchIn(text)
            val lines = ArrayList<String>()
            if (safeHere) {
                lines += "\$$contentVar = @'"
                lines += text
                lines += "'@"
                lines += "\$$pathVar = Join-Path \$env:TEMP '$fileName'"
                lines += "[IO.File]::WriteAllText(\$$pathVar, \$$contentVar, (New-Object Text.UTF8Encoding \$false))"
            } else {
                val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                lines += "\$$pathVar = Join-Path \$env:TEMP '$fileName'"
                lines += "[IO.File]::WriteAllBytes(\$$pathVar, [Convert]::FromBase64String('$b64'))"
            }
            return lines
        }

        private fun emitForm(form: RequestBody.Form) {
            val fields = form.fields
            val inline = fields.all { (n, v) ->
                SIMPLE_FIELD_NAME.matches(n) && inlineOk(n) && inlineOk(v) &&
                    !(shell == CurlShell.CMD && v.length > Limits.CURL_CMD_INLINE_BODY_MAX_CHARS)
            }
            if (fields.isEmpty() || !inline) {
                val encoded = fields.joinToString("&") { (n, v) -> CurlQuoting.formEncode(n) + "=" + CurlQuoting.formEncode(v) }
                args += "--data-raw"; args += q(encoded)
                return
            }
            for ((n, v) in fields) { args += "--data-urlencode"; args += q("$n=$v") }
        }

        private fun quotedFormValue(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun emitPart(p: MultipartPart) {
            if (p.name.any { it == '=' || it == '"' || it == ';' || it == '\n' || it == '\r' }) {
                throw CurlUnsupportedException(CurlProblem.ARGUMENT_NOT_REPRESENTABLE, shell)
            }
            val path = p.filePath
            if (path != null) {
                val awkward = path.any { it == ';' || it == '"' || it == ',' }
                val sb = StringBuilder(p.name).append("=@").append(if (awkward) quotedFormValue(path) else path)
                p.contentType?.let { sb.append(";type=").append(it) }
                p.fileName?.let { sb.append(";filename=").append(if (it.any { c -> c == ';' || c == '"' }) quotedFormValue(it) else it) }
                args += "-F"; args += q(sb.toString())
                return
            }
            val text = p.value.orEmpty()
            val special = text.startsWith("@") || text.startsWith("<") || text.contains(';') || text.contains('"')
            if (p.contentType == null) {
                args += if (special) "--form-string" else "-F"
                args += q("${p.name}=$text")
            } else {
                val v = if (special) quotedFormValue(text) else text
                args += "-F"; args += q("${p.name}=$v;type=${p.contentType}")
            }
        }

        private fun assemble(): CurlCommand {
            val exe = if (shell == CurlShell.BASH) "curl" else "curl.exe"
            val sep = when {
                !o.multiline -> " "
                shell == CurlShell.BASH -> " \\\n  "
                shell == CurlShell.CMD -> " ^\n  "
                else -> " `\n  "
            }
            // An option and its value stay on one line.
            val parts = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a in VALUE_OPTIONS && i + 1 < args.size) { parts += "$a ${args[i + 1]}"; i += 2 } else { parts += a; i++ }
            }
            val command = (listOf(exe) + parts).joinToString(sep)
            val text = (prelude + command + postlude).joinToString("\n")
            if (shell == CurlShell.CMD && command.length > Limits.CURL_CMD_MAX_LINE_CHARS) warnings += CurlWarning.CMD_LINE_TOO_LONG
            return CurlCommand(text, sideFiles, warnings)
        }
    }
}
