package com.routegenie.client

/**
 * Writes a request as text in the `.http` format of the JetBrains HTTP Client (explicit export only, research 03 section 6).
 * Credentials never leave: a sensitive header value becomes a `{{variable}}` placeholder, the value of a sensitive query
 * parameter likewise, so the file can be committed.
 */
object HttpFileExport {
    fun export(name: String?, spec: RequestSpec, sensitiveHeaders: Set<String> = emptySet(), sensitiveQuery: Set<String> = emptySet()): String {
        val sb = StringBuilder()
        if (!name.isNullOrBlank()) sb.append("### ").append(name.replace('\n', ' ').replace('\r', ' ')).append('\n')
        sb.append(spec.method.name).append(' ').append(maskQuery(spec.url, sensitiveQuery)).append('\n')
        for (h in spec.headers.filter { it.enabled }) {
            val value = if (SensitiveNames.isSensitiveHeader(h.name, sensitiveHeaders)) placeholder(h) else h.value
            sb.append(h.name).append(": ").append(value.replace('\r', ' ').replace('\n', ' ')).append('\n')
        }
        when (val b = spec.body) {
            RequestBody.None -> Unit
            is RequestBody.Text -> {
                if (spec.headers.none { it.name.equals("Content-Type", true) }) sb.append("Content-Type: ").append(b.contentType).append('\n')
                sb.append('\n').append(b.text).append('\n')
            }
            is RequestBody.Form -> {
                if (spec.headers.none { it.name.equals("Content-Type", true) }) sb.append("Content-Type: application/x-www-form-urlencoded\n")
                sb.append('\n').append(b.fields.joinToString("&") { (n, v) -> UrlSupport.encodeForm(n) + "=" + UrlSupport.encodeForm(v) }).append('\n')
            }
            is RequestBody.Multipart -> {
                sb.append("Content-Type: multipart/form-data; boundary=").append(BOUNDARY).append("\n\n")
                for (p in b.parts) {
                    sb.append("--").append(BOUNDARY).append("\nContent-Disposition: form-data; name=\"").append(p.name).append('"')
                    if (p.filePath != null) {
                        sb.append("; filename=\"").append(p.fileName ?: p.filePath.substringAfterLast('/').substringAfterLast('\\')).append("\"\n\n")
                        sb.append("< ").append(p.filePath).append('\n')
                    } else {
                        sb.append("\n\n").append(p.value.orEmpty()).append('\n')
                    }
                }
                sb.append("--").append(BOUNDARY).append("--\n")
            }
        }
        return sb.toString()
    }

    private fun placeholder(h: Header): String {
        val scheme = h.value.substringBefore(' ', "")
        return when {
            h.name.equals("Authorization", true) && scheme.equals("Bearer", true) -> "Bearer {{token}}"
            h.name.equals("Authorization", true) && scheme.equals("Basic", true) -> "Basic {{credentials}}"
            else -> "{{" + h.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') + "}}"
        }
    }

    private fun maskQuery(url: String, extra: Set<String>): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val masked = url.substring(q + 1).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val name = UrlSupport.decode(pair.substring(0, eq))
            if (SensitiveNames.isSensitiveField(name) || name in extra) "${pair.substring(0, eq)}={{${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}}}" else pair
        }
        return url.substring(0, q + 1) + masked
    }

    private const val BOUNDARY = "RouteGenieBoundary"
}
