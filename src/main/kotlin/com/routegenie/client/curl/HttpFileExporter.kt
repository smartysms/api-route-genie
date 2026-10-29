package com.routegenie.client.curl

import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec

/**
 * [RequestSpec] as text of a JetBrains-style `.http` file (research 03, 6.2). Plain text only: no dependency on the HTTP
 * Client plugin. Values are written as given, so callers pass a masked spec for anything that is shared.
 */
object HttpFileExporter {
    private const val SEPARATOR = "###"
    private const val NO_REDIRECT = "# @no-redirect"
    private const val BOUNDARY = "RouteGenieBoundary"
    private const val FORM_TYPE = "application/x-www-form-urlencoded"

    fun export(spec: RequestSpec, name: String? = null, followRedirects: Boolean = true, maskSecrets: Boolean = false): String {
        val sb = StringBuilder()
        sb.append(SEPARATOR)
        if (!name.isNullOrBlank()) sb.append(' ').append(name.replace('\n', ' ').trim())
        sb.append('\n')
        if (!followRedirects) sb.append(NO_REDIRECT).append('\n')
        val url = if (maskSecrets) SecretMask.maskUrl(spec.url) else spec.url
        sb.append(spec.method.name).append(' ').append(url).append('\n')
        val headers = spec.headers.filter { it.enabled }.map {
            it.name to (if (maskSecrets && SecretMask.isSecretHeader(it.name)) SecretMask.PLACEHOLDER else it.value)
        }.toMutableList()
        val body = spec.body
        fun addType(type: String) {
            if (headers.none { it.first.equals("Content-Type", true) }) headers += "Content-Type" to type
        }
        when (body) {
            is RequestBody.Text -> addType(body.contentType)
            is RequestBody.Form -> addType(FORM_TYPE)
            is RequestBody.Multipart -> {
                headers.removeAll { it.first.equals("Content-Type", true) }
                headers += "Content-Type" to "multipart/form-data; boundary=$BOUNDARY"
            }
            RequestBody.None -> Unit
        }
        headers.forEach { (n, v) -> sb.append(n).append(": ").append(v).append('\n') }
        when (body) {
            RequestBody.None -> Unit
            is RequestBody.Text -> sb.append('\n').append(body.text).append('\n')
            is RequestBody.Form -> sb.append('\n').append(body.fields.joinToString("&") { (k, v) -> CurlQuoting.formEncode(k) + "=" + CurlQuoting.formEncode(v) }).append('\n')
            is RequestBody.Multipart -> {
                sb.append('\n')
                for (p in body.parts) {
                    sb.append("--").append(BOUNDARY).append('\n')
                    sb.append("Content-Disposition: form-data; name=\"").append(escape(p.name)).append('"')
                    p.filePath?.let { sb.append("; filename=\"").append(escape(p.fileName ?: it.substringAfterLast('/').substringAfterLast('\\'))).append('"') }
                    sb.append('\n')
                    p.contentType?.let { sb.append("Content-Type: ").append(it).append('\n') }
                    sb.append('\n')
                    if (p.filePath != null) sb.append("< ").append(p.filePath).append('\n') else sb.append(p.value.orEmpty()).append('\n')
                }
                sb.append("--").append(BOUNDARY).append("--\n")
            }
        }
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")
}
