package com.routegenie.ui.request

import com.routegenie.client.ApiKeyLocation
import com.routegenie.client.AuthConfig
import com.routegenie.client.Header
import com.routegenie.client.MultipartPart
import com.routegenie.client.QueryParam
import com.routegenie.client.Redactor
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestInput
import com.routegenie.client.RequestSpec
import com.routegenie.client.UrlResult
import com.routegenie.client.UrlSupport
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.FileSpec

enum class BodyType(val contentType: String?) {
    NONE(null), JSON("application/json"), TEXT("text/plain"), FORM("application/x-www-form-urlencoded"), MULTIPART("multipart/form-data");

    companion object {
        fun of(contentType: String): BodyType = entries.firstOrNull { it.contentType != null && contentType.substringBefore(';').trim().equals(it.contentType, true) }
            ?: if (contentType.contains("json", true)) JSON else TEXT
    }
}

/** One editable row of a table (query, headers, form fields, multipart parts). [file] marks a multipart row whose value is a path. */
data class FormRow(
    val name: String = "",
    val value: String = "",
    val enabled: Boolean = true,
    val file: Boolean = false,
    /** Sent as the part's Content-Type, and the file name sent to the server; null lets the engine decide from the path. */
    val contentType: String? = null,
    val fileName: String? = null,
    /** True for a file this plugin made at random for this request (it may be replaced by Regenerate); never saved. */
    val generated: Boolean = false,
)

enum class AuthKind { INHERIT, NONE, BEARER, BASIC, API_KEY }

/**
 * Everything the user edits for one endpoint. Pure and Swing free: the panel binds widgets to it and the tests drive it
 * directly. [toInput] never sends anything; it only describes the request for the preparer.
 */
class RequestForm(val endpoint: Endpoint) {
    var method: HttpMethod = if (endpoint.method.isConcrete) endpoint.method else HttpMethod.GET
    val pathValues = LinkedHashMap<String, String>().also { m -> endpoint.pathParams.forEach { m[it.name] = "" } }
    var query: List<FormRow> = emptyList()
    var headers: List<FormRow> = emptyList()
    var bodyKind: BodyType = BodyType.NONE
    var bodyText: String = ""
    var formFields: List<FormRow> = emptyList()
    var parts: List<FormRow> = emptyList()

    /** What the code allows for each file part (by part name), set when the parts were filled from the code; not saved. */
    var fileSpecs: Map<String, FileSpec> = emptyMap()
    var authKind: AuthKind = AuthKind.INHERIT
    var authName: String = ""

    /** The method dropdown exists only for endpoints whose code does not restrict the verb. */
    val methodIsChoosable: Boolean get() = !endpoint.method.isConcrete

    fun toInput(): RequestInput {
        val body = when (bodyKind) {
            BodyType.NONE -> RequestBody.None
            BodyType.JSON, BodyType.TEXT -> RequestBody.Text(bodyKind.contentType!!, bodyText)
            BodyType.FORM -> RequestBody.Form(formFields.filter { it.enabled && it.name.isNotEmpty() }.map { it.name to it.value })
            BodyType.MULTIPART -> RequestBody.Multipart(
                parts.filter { it.enabled && it.name.isNotEmpty() }.map {
                    if (it.file) MultipartPart(it.name, filePath = it.value, fileName = it.fileName, contentType = it.contentType) else MultipartPart(it.name, value = it.value)
                },
            )
        }
        val auth: AuthConfig? = when (authKind) {
            AuthKind.INHERIT -> null
            AuthKind.NONE -> AuthConfig.None
            AuthKind.BEARER -> AuthConfig.Bearer
            AuthKind.BASIC -> AuthConfig.Basic(authName)
            AuthKind.API_KEY -> AuthConfig.ApiKey(authName.ifEmpty { DEFAULT_API_KEY_HEADER }, ApiKeyLocation.HEADER)
        }
        return RequestInput(
            method = method,
            pathValues = LinkedHashMap(pathValues),
            query = query.filter { it.name.isNotEmpty() }.map { QueryParam(it.name, it.value, it.enabled) },
            headers = headers.filter { it.name.isNotEmpty() }.map { Header(it.name, it.value, it.enabled) },
            body = body,
            auth = auth,
        )
    }

    /**
     * Fills the form from what was remembered for this endpoint (see [com.routegenie.client.LastInputs]). Only parameters this
     * endpoint still has are restored, and the method only where the code leaves it open, so a changed route never receives a
     * stale value.
     */
    fun restore(input: RequestInput) {
        if (methodIsChoosable && input.method.isConcrete) method = input.method
        input.pathValues.forEach { (k, v) -> if (k in pathValues) pathValues[k] = v }
        query = input.query.map { FormRow(it.name, it.value, it.enabled) }
        headers = input.headers.map { FormRow(it.name, it.value, it.enabled) }
        when (val b = input.body) {
            RequestBody.None -> { bodyKind = BodyType.NONE; bodyText = "" }
            is RequestBody.Text -> { bodyKind = BodyType.of(b.contentType); bodyText = b.text }
            is RequestBody.Form -> { bodyKind = BodyType.FORM; formFields = b.fields.map { FormRow(it.first, it.second) } }
            is RequestBody.Multipart -> {
                bodyKind = BodyType.MULTIPART
                parts = b.parts.map { FormRow(it.name, it.filePath ?: it.value.orEmpty(), true, it.filePath != null, it.contentType, it.fileName) }
            }
        }
        when (val a = input.auth) {
            null -> { authKind = AuthKind.INHERIT; authName = "" }
            AuthConfig.None -> { authKind = AuthKind.NONE; authName = "" }
            AuthConfig.Bearer -> { authKind = AuthKind.BEARER; authName = "" }
            is AuthConfig.Basic -> { authKind = AuthKind.BASIC; authName = a.username }
            is AuthConfig.ApiKey -> { authKind = AuthKind.API_KEY; authName = a.name }
        }
    }

    fun copy(redact: Boolean = false): RequestForm = RequestForm(endpoint).also { c ->
        c.method = method
        c.pathValues.putAll(pathValues)
        c.query = query
        c.headers = if (redact) headers.map { it.copy(value = Redactor.header(it.name, it.value)) } else headers
        c.bodyKind = bodyKind
        c.bodyText = bodyText
        c.formFields = formFields
        c.parts = parts
        c.fileSpecs = fileSpecs
        c.authKind = authKind
        c.authName = authName
    }

    /**
     * Fills the form from a request that was pasted as cURL. The URL is split into base URL, path parameters (when its path
     * has the endpoint's shape) and query. Returns what could not be applied.
     */
    fun apply(spec: RequestSpec): ImportResult {
        var baseUrl: String? = null
        var pathApplied = true
        val parsed = (UrlSupport.parse(spec.url) as? UrlResult.Ok)?.url
        if (parsed != null) {
            val text = parsed.text
            val schemeEnd = text.indexOf("://")
            val authorityEnd = text.indexOf('/', schemeEnd + 3).let { if (it < 0) text.length else it }
            baseUrl = text.substring(0, authorityEnd)
            val rawPath = text.substring(authorityEnd).substringBefore('?').substringBefore('#')
            val values = PathTemplate.match(endpoint.path, rawPath)
            if (values != null) values.forEach { (k, v) -> if (k in pathValues) pathValues[k] = UrlSupport.decode(v) } else pathApplied = false
            val q = text.substringAfter('?', "").substringBefore('#')
            query = if (q.isEmpty()) emptyList() else q.split('&').map { p ->
                FormRow(UrlSupport.decode(p.substringBefore('=')), UrlSupport.decode(p.substringAfter('=', "")))
            }
        }
        if (spec.method.isConcrete && (methodIsChoosable || spec.method == endpoint.method)) method = spec.method
        headers = spec.headers.map { FormRow(it.name, it.value, it.enabled) }
        when (val b = spec.body) {
            RequestBody.None -> { bodyKind = BodyType.NONE; bodyText = "" }
            is RequestBody.Text -> { bodyKind = BodyType.of(b.contentType); bodyText = b.text; if (bodyKind == BodyType.TEXT && !b.contentType.startsWith("text/plain")) headers = headers + FormRow("Content-Type", b.contentType) }
            is RequestBody.Form -> { bodyKind = BodyType.FORM; formFields = b.fields.map { FormRow(it.first, it.second) } }
            is RequestBody.Multipart -> { bodyKind = BodyType.MULTIPART; parts = b.parts.map { FormRow(it.name, it.filePath ?: it.value.orEmpty(), true, it.filePath != null, it.contentType, it.fileName) } }
        }
        return ImportResult(baseUrl, pathApplied)
    }

    /** [baseUrl] is the scheme and authority of the pasted URL (for the environment field); [pathApplied] is false when its path does not fit this endpoint. */
    data class ImportResult(val baseUrl: String?, val pathApplied: Boolean)

    private companion object {
        const val DEFAULT_API_KEY_HEADER = "X-API-Key"
    }
}

/** Matches a URL path against an endpoint template such as `/users/{id}`, `/users/<int:id>` or `/files/{p:path}`. */
object PathTemplate {
    private val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::([A-Za-z_][A-Za-z0-9_]*))?}""")

    /** Values by parameter name (still percent-encoded), or null when [path] does not have the template's shape. */
    fun match(template: String, path: String): Map<String, String>? {
        val names = ArrayList<String>()
        val sb = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER.findAll(template)) {
            sb.append(Regex.escape(template.substring(last, m.range.first)))
            names += m.groupValues[1]
            sb.append(if (m.groupValues[2] == "path") "(.+)" else "([^/]+)")
            last = m.range.last + 1
        }
        sb.append(Regex.escape(template.substring(last)))
        val match = Regex(sb.toString()).matchEntire(path.trimEnd('/').ifEmpty { "/" }.let { if (template.endsWith("/")) path else it }) ?: return null
        return names.indices.associate { names[it] to match.groupValues[it + 1] }
    }
}
