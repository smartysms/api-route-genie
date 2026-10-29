package com.routegenie.client

/** Which names carry credentials. One place for history, logs, redirects and exports (research 03, section 7.2). */
object SensitiveNames {
    /** Lower-case header names that are credentials whatever the user does. */
    val HEADERS: Set<String> = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key", "x-auth-token",
        "x-csrf-token", "x-xsrf-token", "x-amz-security-token",
    )

    private val NAME = Regex("(token|secret|password|passwd|pwd|api[_-]?key|apikey|auth|session|jwt|bearer|credential|signature|sig|code)", RegexOption.IGNORE_CASE)

    fun isSensitiveHeader(name: String, extra: Set<String> = emptySet()): Boolean {
        val n = name.trim().lowercase()
        return n in HEADERS || n in extra
    }

    fun isSensitiveField(name: String): Boolean = NAME.containsMatchIn(name)
}

/** Replaces secret values by a fixed placeholder, so the output reveals neither the value nor its length. */
object Redactor {
    const val PLACEHOLDER = "<redacted>"

    fun header(name: String, value: String, extra: Set<String> = emptySet()): String =
        if (SensitiveNames.isSensitiveHeader(name, extra)) PLACEHOLDER else value

    /** URL with user info and the values of sensitive query parameters replaced; the rest is kept as is. */
    fun url(url: String, extraSensitiveQuery: Set<String> = emptySet()): String {
        val hash = url.indexOf('#')
        val noFragment = if (hash >= 0) url.substring(0, hash) else url
        val q = noFragment.indexOf('?')
        var head = if (q >= 0) noFragment.substring(0, q) else noFragment
        val scheme = head.indexOf("://")
        if (scheme >= 0) {
            val authorityEnd = head.indexOf('/', scheme + 3).let { if (it < 0) head.length else it }
            val at = head.lastIndexOf('@', authorityEnd - 1)
            if (at > scheme) head = head.substring(0, scheme + 3) + PLACEHOLDER + head.substring(at)
        }
        if (q < 0) return head
        val query = noFragment.substring(q + 1).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) pair else {
                val name = pair.substring(0, eq)
                val decoded = UrlSupport.decode(name)
                if (SensitiveNames.isSensitiveField(decoded) || decoded in extraSensitiveQuery) "$name=$PLACEHOLDER" else pair
            }
        }
        return "$head?$query"
    }
}
