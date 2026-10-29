package com.routegenie.client

/** Why a send did not produce a usable response. Each kind has one user-facing message in the bundle. */
enum class ErrorKind(val bundleKey: String) {
    /** The response headers did not arrive within the headers timeout. */
    TIMEOUT_HEADERS("engine.error.timeout_headers"),

    /** The total deadline of the send (including the body download) passed. */
    TIMEOUT_TOTAL("engine.error.timeout_total"),

    /** The connection could not be opened in time. */
    TIMEOUT_CONNECT("engine.error.timeout_connect"),

    /** A stream delivered no bytes for the idle timeout. */
    TIMEOUT_IDLE("engine.error.timeout_idle"),
    CONNECTION_REFUSED("engine.error.connection_refused"),
    DNS("engine.error.dns"),
    TLS("engine.error.tls"),

    /** The server announced a body above the hard limit; status and headers are still reported. */
    TOO_LARGE("engine.error.too_large"),

    /** The user cancelled the send; a state, not a failure. */
    CANCELLED("engine.error.cancelled"),
    INVALID_URL("engine.error.invalid_url"),

    /** A header or body part the engine cannot send as given (restricted or malformed header, unreadable file). */
    INVALID_REQUEST("engine.error.invalid_request"),
    TOO_MANY_REDIRECTS("engine.error.too_many_redirects"),

    /** A redirect was not followed (HTTPS to HTTP, a host that is not allowed, or cross-origin follow turned off). */
    REDIRECT_BLOCKED("engine.error.redirect_blocked"),

    /** TLS verification off was asked for a host that is not on this machine. */
    INSECURE_NOT_ALLOWED("engine.error.insecure_not_allowed"),
    UNKNOWN("engine.error.unknown"),
}

/**
 * [kind] picks the message; [detail] is technical text for a details line (never a secret: it holds host names, header
 * names and exception class names, no values).
 */
data class EngineError(val kind: ErrorKind, val detail: String? = null)

/** One followed (or refused) redirect: the answer that carried it and where it pointed. */
data class RedirectHop(val status: Int, val fromUrl: String, val location: String, val elapsedMs: Long)

/**
 * The outcome of one send. It always completes normally: a failure is [error] with [status] 0, except for errors that
 * still carry an answer (TOO_LARGE, REDIRECT_BLOCKED), where [status] and [headers] describe it.
 *
 * [headers] are lower-case names in the order the JDK reports them (alphabetical; the wire order is not available).
 * The body is in [responseBody]: [body] is its in-memory head (at most the display cap), [bodyFile] the complete body on
 * disk when it was bigger, [bodySize] its exact size (a lower bound when [bodyTruncatedAtLimit]). Call [release] when the
 * response is replaced or dropped; it deletes the file.
 */
class ResponseData(
    val status: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val responseBody: ResponseBody,
    val durationMs: Long,
    val headersMs: Long,
    val finalUrl: String,
    val redirects: List<RedirectHop>,
    val httpVersion: String,
    val error: EngineError? = null,
    val insecureTls: Boolean = false,
) {
    val body: ByteArray get() = responseBody.head
    val truncated: Boolean get() = responseBody.isCut
    val bodyFile: java.nio.file.Path? get() = responseBody.file
    val bodySize: Long get() = responseBody.size
    val bodyTruncatedAtLimit: Boolean get() = responseBody.truncatedAtLimit
    val isSuccess: Boolean get() = error == null

    fun release() = responseBody.release()

    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun headerValues(name: String): List<String> = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /** The in-memory head as text in the charset of the Content-Type, UTF-8 otherwise; invalid bytes become replacement characters. */
    fun bodyText(): String = String(body, ContentTypes.charset(header("content-type")))

    override fun toString(): String = "ResponseData(status=$status, size=$bodySize, error=$error)"

    companion object {
        fun failed(error: EngineError, url: String, durationMs: Long, redirects: List<RedirectHop> = emptyList()) = ResponseData(
            status = 0, reason = "", headers = emptyList(), responseBody = ResponseBody.EMPTY, durationMs = durationMs,
            headersMs = 0, finalUrl = url, redirects = redirects, httpVersion = "", error = error,
        )
    }
}

object ContentTypes {
    private val CHARSET = Regex("""charset\s*=\s*"?([A-Za-z0-9_.:\-]+)"?""", RegexOption.IGNORE_CASE)

    fun charset(contentType: String?): java.nio.charset.Charset {
        val name = contentType?.let { CHARSET.find(it)?.groupValues?.get(1) } ?: return Charsets.UTF_8
        return runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }
}
