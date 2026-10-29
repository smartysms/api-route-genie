package com.routegenie.client

import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import java.time.Instant
import java.util.Base64
import java.util.UUID

enum class ApiKeyLocation { HEADER, QUERY }

/**
 * How an environment authenticates. Holds names only, never a secret: the values come from a [SecretLookup] at send time
 * (PasswordSafe in the IDE), so this model can be persisted, exported and logged.
 */
sealed interface AuthConfig {
    data object None : AuthConfig
    data object Bearer : AuthConfig
    data class Basic(val username: String) : AuthConfig
    data class ApiKey(val name: String, val location: ApiKeyLocation = ApiKeyLocation.HEADER) : AuthConfig
}

/** Source of secret values by key (see [SecretKeys]); null when none is stored. Called on a background thread. */
fun interface SecretLookup {
    fun get(key: String): String?

    companion object {
        val NONE = SecretLookup { null }
    }
}

object SecretKeys {
    const val TOKEN = "auth.token"
    const val PASSWORD = "auth.password"
    const val API_KEY = "auth.apikey"
    fun variable(name: String) = "var.$name"
}

/**
 * Environment: a name, the base URL and variables for `{{name}}`. Variables listed in [secretVariables] get their value
 * from the [SecretLookup], never from [variables].
 */
data class Environment(
    val name: String,
    val baseUrl: String,
    val variables: Map<String, String> = emptyMap(),
    val secretVariables: Set<String> = emptySet(),
    val auth: AuthConfig = AuthConfig.None,
    val defaultHeaders: List<Header> = emptyList(),
    val tls: TlsMode = TlsMode.IDE_TRUST,
)

data class QueryParam(val name: String, val value: String, val enabled: Boolean = true)

/**
 * What the user edited for one endpoint. [pathValues] maps parameter name to the raw (unencoded) value. [auth] overrides
 * the environment's auth when not null.
 */
data class RequestInput(
    val method: HttpMethod,
    val pathValues: Map<String, String> = emptyMap(),
    val query: List<QueryParam> = emptyList(),
    val headers: List<Header> = emptyList(),
    val body: RequestBody = RequestBody.None,
    val auth: AuthConfig? = null,
)

enum class FieldLocation { URL, PATH, QUERY, HEADER, BODY, VARIABLE, AUTH, METHOD }

/** One problem that blocks sending, with the field it belongs to and a bundle message (arguments in [args]). */
data class FieldError(val location: FieldLocation, val name: String, val bundleKey: String, val args: List<String> = emptyList())

/**
 * A request ready for the engine. [spec] contains the credentials (it is what goes on the wire), so never log it or
 * persist it; use [displayUrl] and [redactedHeaders] for anything shown or stored. [sensitiveHeaders] and
 * [sensitiveQuery] name what must be removed on a cross-origin redirect and masked everywhere else.
 */
class PreparedRequest(
    val spec: RequestSpec,
    val sensitiveHeaders: Set<String>,
    val sensitiveQuery: Set<String>,
    val tls: TlsMode,
) {
    val displayUrl: String get() = Redactor.url(spec.url, sensitiveQuery)

    val redactedHeaders: List<Header>
        get() = spec.headers.map { it.copy(value = Redactor.header(it.name, it.value, sensitiveHeaders)) }

    fun options(base: SendOptions = SendOptions()): SendOptions = base.copy(sensitiveHeaders = sensitiveHeaders, tls = tls)

    override fun toString(): String = "PreparedRequest(${spec.method} $displayUrl)"
}

sealed interface PrepareResult {
    data class Ready(val request: PreparedRequest) : PrepareResult
    data class Invalid(val errors: List<FieldError>) : PrepareResult
}

/** Time and randomness for the built-in variables, replaceable in tests. */
class BuiltIns(
    private val now: () -> Instant = { Instant.now() },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val randomInt: () -> Int = { java.util.concurrent.ThreadLocalRandom.current().nextInt(RANDOM_INT_BOUND) },
) {
    fun value(name: String): String? = when (name) {
        "\$uuid" -> uuid()
        "\$timestamp" -> now().epochSecond.toString()
        "\$isoTimestamp" -> now().toString()
        "\$randomInt" -> randomInt().toString()
        else -> null
    }

    private companion object {
        const val RANDOM_INT_BOUND = 1000
    }
}

/**
 * Pure: turns an endpoint, the user's input and an environment into a [PreparedRequest], or into field errors that block
 * sending. Order: variables, path parameters (type checked, then percent-encoded), query, base URL join, header merge, auth.
 */
object RequestPreparer {
    private val VARIABLE = Regex("""\{\{\s*([^{}\s][^{}]*?)\s*}}""")
    private val PATH_PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::[A-Za-z_][A-Za-z0-9_]*)?}""")

    fun prepare(
        endpoint: Endpoint,
        input: RequestInput,
        env: Environment,
        secrets: SecretLookup = SecretLookup.NONE,
        builtIns: BuiltIns = BuiltIns(),
    ): PrepareResult {
        val errors = ArrayList<FieldError>()
        if (!input.method.isConcrete) {
            return PrepareResult.Invalid(listOf(FieldError(FieldLocation.METHOD, input.method.name, "prepare.error.method")))
        }
        val vars = Vars(env, secrets, builtIns, errors)
        val secretUsed = HashSet<String>()

        fun sub(location: FieldLocation, field: String, text: String): String {
            val out = vars.substitute(location, field, text)
            return out
        }

        val baseUrl = sub(FieldLocation.URL, "baseUrl", env.baseUrl).trim()
        if (baseUrl.isEmpty()) errors += FieldError(FieldLocation.URL, "baseUrl", "prepare.error.no_base_url")

        // Path: validate the raw values against the converter types, then encode.
        val types = endpoint.pathParams.associateBy { it.name }
        var path = PATH_PLACEHOLDER.replace(endpoint.path) { m ->
            val name = m.groupValues[1]
            val raw = vars.substitute(FieldLocation.PATH, name, input.pathValues[name].orEmpty())
            val problem = PathConverters.check(types[name] ?: PathParam(name), raw, endpoint.framework)
            if (problem != null) {
                errors += FieldError(FieldLocation.PATH, name, problem, listOf(name, types[name]?.type ?: ""))
                ""
            } else if (types[name]?.type == "path") UrlSupport.encodePath(raw) else UrlSupport.encodeComponent(raw)
        }
        if (!path.startsWith("/")) path = "/$path"

        val queryParts = ArrayList<Pair<String, String>>()
        val sensitiveQuery = HashSet<String>()
        for (q in input.query.filter { it.enabled }) {
            val name = sub(FieldLocation.QUERY, q.name, q.name)
            val value = sub(FieldLocation.QUERY, name, q.value)
            if (name.isEmpty()) continue
            queryParts += name to value
            if (referencesSecret(q.value, env)) sensitiveQuery += name
        }

        val headers = mergeHeaders(env.defaultHeaders, input.headers).map { h ->
            val name = vars.substitute(FieldLocation.HEADER, h.name, h.name).trim()
            val value = vars.substitute(FieldLocation.HEADER, name, h.value)
            if (name.isEmpty() || name.any { it == '\r' || it == '\n' || it == ':' } || value.any { it == '\r' || it == '\n' }) {
                errors += FieldError(FieldLocation.HEADER, name.ifEmpty { h.name }, "prepare.error.header_invalid")
            }
            if (referencesSecret(h.value, env)) secretUsed += name.lowercase()
            Header(name, value)
        }.toMutableList()

        // Auth: an explicit Authorization header of the request wins over the environment's auth.
        val auth = input.auth ?: env.auth
        val sensitiveHeaders = HashSet<String>(secretUsed)
        fun secret(key: String): String? = secrets.get(key).also {
            if (it == null) errors += FieldError(FieldLocation.AUTH, key, "prepare.error.secret_missing", listOf(key))
        }
        when (auth) {
            AuthConfig.None -> Unit
            AuthConfig.Bearer -> if (headers.none { it.name.equals(AUTHORIZATION, true) }) {
                secret(SecretKeys.TOKEN)?.let { headers += Header(AUTHORIZATION, "Bearer $it") }
                sensitiveHeaders += AUTHORIZATION.lowercase()
            }
            is AuthConfig.Basic -> if (headers.none { it.name.equals(AUTHORIZATION, true) }) {
                val user = vars.substitute(FieldLocation.AUTH, "username", auth.username)
                secret(SecretKeys.PASSWORD)?.let {
                    // curl semantics: the user name ends at the first colon of "user:password", the password may contain colons.
                    val cred = Base64.getEncoder().encodeToString("$user:$it".toByteArray(Charsets.UTF_8))
                    headers += Header(AUTHORIZATION, "Basic $cred")
                }
                sensitiveHeaders += AUTHORIZATION.lowercase()
            }
            is AuthConfig.ApiKey -> {
                val name = vars.substitute(FieldLocation.AUTH, "apiKeyName", auth.name)
                secret(SecretKeys.API_KEY)?.let { key ->
                    if (auth.location == ApiKeyLocation.HEADER) {
                        if (headers.none { it.name.equals(name, true) }) headers += Header(name, key)
                        sensitiveHeaders += name.lowercase()
                    } else {
                        queryParts += name to key
                        sensitiveQuery += name
                    }
                }
            }
        }

        val body = substituteBody(input.body, vars)

        // The query is encoded here, after the secrets are in, so one pass builds the final URL.
        val query = queryParts.joinToString("&") { (n, v) -> UrlSupport.encodeComponent(n) + "=" + UrlSupport.encodeComponent(v) }
        val joined = baseUrl.trimEnd('/') + path
        val full = if (query.isEmpty()) joined else joined + (if ('?' in joined) "&" else "?") + query

        if (errors.isEmpty()) {
            when (val parsed = UrlSupport.parse(full)) {
                is UrlResult.Bad -> errors += FieldError(FieldLocation.URL, "url", "prepare.error.url", listOf(parsed.error.detail.orEmpty()))
                is UrlResult.Ok -> if (errors.isEmpty()) {
                    val spec = RequestSpec(input.method, parsed.url.text, headers.distinctBy { it.name.lowercase() }, body)
                    return PrepareResult.Ready(PreparedRequest(spec, sensitiveHeaders, sensitiveQuery, env.tls))
                }
            }
        }
        return PrepareResult.Invalid(errors.distinct())
    }

    private fun referencesSecret(text: String, env: Environment): Boolean =
        env.secretVariables.isNotEmpty() && VARIABLE.findAll(text).any { it.groupValues[1].trim() in env.secretVariables }

    private fun mergeHeaders(defaults: List<Header>, own: List<Header>): List<Header> {
        val ownNames = own.filter { it.enabled }.map { it.name.trim().lowercase() }.toSet()
        return defaults.filter { it.enabled && it.name.trim().lowercase() !in ownNames } + own.filter { it.enabled }
    }

    private fun substituteBody(body: RequestBody, vars: Vars): RequestBody = when (body) {
        RequestBody.None -> body
        is RequestBody.Text -> RequestBody.Text(body.contentType, vars.substitute(FieldLocation.BODY, "body", body.text))
        is RequestBody.Form -> RequestBody.Form(body.fields.map { (n, v) -> vars.substitute(FieldLocation.BODY, n, n) to vars.substitute(FieldLocation.BODY, n, v) })
        is RequestBody.Multipart -> RequestBody.Multipart(body.parts.map { p ->
            p.copy(value = p.value?.let { vars.substitute(FieldLocation.BODY, p.name, it) }, filePath = p.filePath?.let { vars.substitute(FieldLocation.BODY, p.name, it) })
        })
    }

    private const val AUTHORIZATION = "Authorization"

    /** `{{name}}` substitution in a single pass (a value is never scanned again, so variables cannot loop). */
    private class Vars(
        val env: Environment,
        val secrets: SecretLookup,
        val builtIns: BuiltIns,
        val errors: MutableList<FieldError>,
    ) {
        fun substitute(location: FieldLocation, field: String, text: String): String {
            if (!text.contains("{{")) return text
            return VARIABLE.replace(text) { m ->
                val name = m.groupValues[1].trim()
                val value = when {
                    name in env.secretVariables -> secrets.get(SecretKeys.variable(name))
                    name.startsWith("$") -> builtIns.value(name)
                    else -> env.variables[name]
                }
                if (value == null) {
                    errors += FieldError(FieldLocation.VARIABLE, name, "prepare.error.variable_undefined", listOf(name, field))
                    ""
                } else value
            }
        }
    }
}

/** Checks a path value against the converter a framework names (int, uuid, slug, str, path, float). */
object PathConverters {
    private val DIGITS = Regex("[0-9]+")
    private val FLOAT = Regex("[0-9]+(\\.[0-9]+)?")
    private val FLASK_FLOAT = Regex("[0-9]+\\.[0-9]+")
    private val SLUG = Regex("[-a-zA-Z0-9_]+")
    private val UUID_ANY = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val UUID_LOWER = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** Bundle key of the problem, or null when the value is acceptable. An empty value is always a problem. */
    fun check(param: PathParam, value: String, framework: Framework): String? {
        if (value.isEmpty()) return "prepare.error.required"
        return when (param.type) {
            "int" -> "prepare.error.type_int".takeUnless { DIGITS.matches(value) }
            "float" -> "prepare.error.type_float".takeUnless { (if (framework == Framework.FLASK) FLASK_FLOAT else FLOAT).matches(value) }
            "uuid" -> "prepare.error.type_uuid".takeUnless { (if (framework == Framework.DJANGO || framework == Framework.DRF) UUID_LOWER else UUID_ANY).matches(value) }
            "slug" -> "prepare.error.type_slug".takeUnless { SLUG.matches(value) }
            "path" -> if (value.startsWith("/") && framework == Framework.FLASK) "prepare.error.type_path" else null
            "any" -> null
            else -> "prepare.error.type_str".takeIf { '/' in value }
        }
    }
}
