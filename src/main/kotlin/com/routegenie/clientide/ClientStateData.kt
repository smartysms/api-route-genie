package com.routegenie.clientide

import com.routegenie.Limits
import com.routegenie.client.ApiKeyLocation
import com.routegenie.client.AuthConfig
import com.routegenie.client.Environment
import com.routegenie.client.ErrorKind
import com.routegenie.client.Header
import com.routegenie.client.HistoryEntry
import com.routegenie.client.LastInputs
import com.routegenie.client.MultipartPart
import com.routegenie.client.QueryParam
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestInput
import com.routegenie.client.SensitiveNames
import com.routegenie.client.TlsMode
import com.routegenie.model.HttpMethod

/*
 * Plain beans for the XML serializer (public mutable properties, no-argument constructors). They hold no secret by
 * construction: secret variables keep only their names, auth keeps names and kinds, sensitive header and query values are
 * blanked by LastInputs.sanitize and by the mapper below.
 */

class NameValue {
    var name: String = ""
    var value: String = ""
    var flag: Boolean = true
}

class PartData {
    var name: String = ""
    var value: String? = null
    var filePath: String? = null
    var fileName: String? = null
    var contentType: String? = null
}

class EnvData {
    var name: String = ""
    var baseUrl: String = ""
    var variables: MutableList<NameValue> = ArrayList()
    var secretVariables: MutableList<String> = ArrayList()
    var authKind: String = AUTH_NONE
    var authUser: String = ""
    var authName: String = ""
    var authLocation: String = ApiKeyLocation.HEADER.name
    var tls: String = TlsMode.IDE_TRUST.name
    var headers: MutableList<NameValue> = ArrayList()
}

class InputData {
    var key: String = ""
    var method: String = HttpMethod.GET.name
    var pathValues: MutableList<NameValue> = ArrayList()
    var query: MutableList<NameValue> = ArrayList()
    var headers: MutableList<NameValue> = ArrayList()
    var bodyKind: String = BODY_NONE
    var bodyContentType: String = ""
    var bodyText: String = ""
    var fields: MutableList<NameValue> = ArrayList()
    var parts: MutableList<PartData> = ArrayList()
    var authKind: String = AUTH_INHERIT
    var authUser: String = ""
    var authName: String = ""
    var authLocation: String = ApiKeyLocation.HEADER.name
}

class HistoryData {
    var timeMs: Long = 0
    var method: String = HttpMethod.GET.name
    var url: String = ""
    var status: Int = 0
    var durationMs: Long = 0
    var size: Long = 0
    var environment: String = ""
    var errorKind: String = ""
    var endpointKey: String = ""
}

class ClientStateData {
    var lastEnvironment: String = ""
    var environments: MutableList<EnvData> = ArrayList()
    var lastInputs: MutableList<InputData> = ArrayList()
    var history: MutableList<HistoryData> = ArrayList()
    var confirmedHosts: MutableList<String> = ArrayList()
}

private const val AUTH_NONE = "none"
private const val AUTH_BEARER = "bearer"
private const val AUTH_BASIC = "basic"
private const val AUTH_API_KEY = "apikey"
private const val AUTH_INHERIT = "inherit"
private const val BODY_NONE = "none"
private const val BODY_TEXT = "text"
private const val BODY_FORM = "form"
private const val BODY_MULTIPART = "multipart"

/** Conversions between the live model and the beans. Pure, so they are tested without an IDE. */
object ClientStateMapper {
    private fun nv(name: String, value: String, flag: Boolean = true) = NameValue().also { it.name = name; it.value = value; it.flag = flag }

    private fun blankIfSensitiveHeader(h: Header) = if (SensitiveNames.isSensitiveHeader(h.name)) "" else h.value

    fun toData(env: Environment): EnvData = EnvData().also { d ->
        d.name = env.name
        d.baseUrl = env.baseUrl
        d.variables = env.variables.entries.take(Limits.ENVIRONMENT_VARIABLES_MAX).map { nv(it.key, it.value) }.toMutableList()
        d.secretVariables = env.secretVariables.take(Limits.ENVIRONMENT_VARIABLES_MAX).toMutableList()
        d.tls = env.tls.name
        d.headers = env.defaultHeaders.map { nv(it.name, blankIfSensitiveHeader(it), it.enabled) }.toMutableList()
        fillAuth(env.auth, { d.authKind = it }, { d.authUser = it }, { d.authName = it }, { d.authLocation = it })
    }

    private fun fillAuth(a: AuthConfig?, kind: (String) -> Unit, user: (String) -> Unit, name: (String) -> Unit, location: (String) -> Unit) {
        when (a) {
            null -> kind(AUTH_INHERIT)
            AuthConfig.None -> kind(AUTH_NONE)
            AuthConfig.Bearer -> kind(AUTH_BEARER)
            is AuthConfig.Basic -> { kind(AUTH_BASIC); user(a.username) }
            is AuthConfig.ApiKey -> { kind(AUTH_API_KEY); name(a.name); location(a.location.name) }
        }
    }

    fun fromData(d: EnvData): Environment = Environment(
        name = d.name,
        baseUrl = d.baseUrl,
        variables = d.variables.associate { it.name to it.value },
        secretVariables = d.secretVariables.toSet(),
        auth = authOf(d.authKind, d.authUser, d.authName, d.authLocation) ?: AuthConfig.None,
        defaultHeaders = d.headers.map { Header(it.name, it.value, it.flag) },
        tls = runCatching { TlsMode.valueOf(d.tls) }.getOrDefault(TlsMode.IDE_TRUST),
    )

    private fun authOf(kind: String, user: String, name: String, location: String): AuthConfig? = when (kind) {
        AUTH_NONE -> AuthConfig.None
        AUTH_BEARER -> AuthConfig.Bearer
        AUTH_BASIC -> AuthConfig.Basic(user)
        AUTH_API_KEY -> AuthConfig.ApiKey(name, runCatching { ApiKeyLocation.valueOf(location) }.getOrDefault(ApiKeyLocation.HEADER))
        else -> null
    }

    /** [input] must already be sanitized (LastInputs does it when storing). */
    fun toData(key: String, input: RequestInput): InputData = InputData().also { d ->
        d.key = key
        d.method = input.method.name
        d.pathValues = input.pathValues.map { nv(it.key, it.value) }.toMutableList()
        d.query = input.query.map { nv(it.name, it.value, it.enabled) }.toMutableList()
        d.headers = input.headers.map { nv(it.name, it.value, it.enabled) }.toMutableList()
        when (val b = input.body) {
            RequestBody.None -> d.bodyKind = BODY_NONE
            is RequestBody.Text -> { d.bodyKind = BODY_TEXT; d.bodyContentType = b.contentType; d.bodyText = b.text }
            is RequestBody.Form -> { d.bodyKind = BODY_FORM; d.fields = b.fields.map { nv(it.first, it.second) }.toMutableList() }
            is RequestBody.Multipart -> {
                d.bodyKind = BODY_MULTIPART
                d.parts = b.parts.map { p -> PartData().also { it.name = p.name; it.value = p.value; it.filePath = p.filePath; it.fileName = p.fileName; it.contentType = p.contentType } }.toMutableList()
            }
        }
        fillAuth(input.auth, { d.authKind = it }, { d.authUser = it }, { d.authName = it }, { d.authLocation = it })
    }

    fun fromData(d: InputData): RequestInput? {
        val method = HttpMethod.parse(d.method) ?: return null
        val body = when (d.bodyKind) {
            BODY_TEXT -> RequestBody.Text(d.bodyContentType, d.bodyText)
            BODY_FORM -> RequestBody.Form(d.fields.map { it.name to it.value })
            BODY_MULTIPART -> RequestBody.Multipart(d.parts.map { MultipartPart(it.name, it.value, it.filePath, it.fileName, it.contentType) })
            else -> RequestBody.None
        }
        return RequestInput(
            method = method,
            pathValues = d.pathValues.associate { it.name to it.value },
            query = d.query.map { QueryParam(it.name, it.value, it.flag) },
            headers = d.headers.map { Header(it.name, it.value, it.flag) },
            body = body,
            auth = authOf(d.authKind, d.authUser, d.authName, d.authLocation),
        )
    }

    fun toData(e: HistoryEntry): HistoryData = HistoryData().also {
        it.timeMs = e.timeMs; it.method = e.method.name; it.url = e.url; it.status = e.status; it.durationMs = e.durationMs
        it.size = e.size; it.environment = e.environment; it.errorKind = e.errorKind?.name.orEmpty()
        it.endpointKey = e.endpointKey
    }

    fun fromData(d: HistoryData): HistoryEntry? {
        val method = HttpMethod.parse(d.method) ?: return null
        return HistoryEntry(d.timeMs, method, d.url, d.status, d.durationMs, d.size, d.environment, runCatching { ErrorKind.valueOf(d.errorKind) }.getOrNull(), d.endpointKey)
    }

    /** Everything bounded again on load, in case the file was edited by hand or by an older version. */
    fun lastInputsFrom(list: List<InputData>): LastInputs = LastInputs().also { li ->
        // Stored oldest first, so the newest ends up most recently used.
        list.forEach { d -> fromData(d)?.let { li.put(d.key, it) } }
    }
}
