package com.routegenie.client

import com.routegenie.Limits

enum class TlsMode {
    /** Trust as the IDE does (its certificate manager); the default. */
    IDE_TRUST,

    /** No certificate or host name checks (curl -k). Only honored for loopback hosts. */
    INSECURE,
}

enum class HttpVersionPolicy {
    /** HTTP/1.1 on http://, HTTP/2 with fallback on https://. */
    AUTO,
    HTTP_1_1,
    HTTP_2,
}

enum class CrossOriginRedirect {
    /** Follow, without Authorization, Cookie and the caller's secret headers. */
    FOLLOW_STRIPPED,

    /** Return the 3xx answer so the caller can ask the user. */
    STOP,
}

/**
 * Per-send settings. [sensitiveHeaders] names headers that carry credentials (from [PreparedRequest.sensitiveHeaders]);
 * they are removed on a redirect to another origin, together with the built-in credential names.
 * [redirectHostAllowed] is asked for every host a redirect points to; false stops the redirect.
 * A [totalTimeoutMs] of 0 means no deadline.
 */
data class SendOptions(
    val followRedirects: Boolean = true,
    val maxRedirects: Int = Limits.HTTP_MAX_REDIRECTS,
    val crossOrigin: CrossOriginRedirect = CrossOriginRedirect.FOLLOW_STRIPPED,
    val headersTimeoutMs: Long = Limits.HTTP_HEADERS_TIMEOUT_MS,
    val totalTimeoutMs: Long = Limits.HTTP_TOTAL_TIMEOUT_MS,
    val tls: TlsMode = TlsMode.IDE_TRUST,
    val httpVersion: HttpVersionPolicy = HttpVersionPolicy.AUTO,
    val compressed: Boolean = true,
    val displayCapBytes: Int = Limits.HTTP_DISPLAY_CAP_BYTES,
    val sensitiveHeaders: Set<String> = emptySet(),
    val redirectHostAllowed: (String) -> Boolean = { true },
)
