package com.routegenie.client

import com.routegenie.Limits
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod

/** Where a freshly created environment points: the usual address of each framework's dev server. The one place for it. */
object DevServerDefaults {
    private const val LOOPBACK = "127.0.0.1"
    private const val DJANGO_PORT = 8000
    private const val FASTAPI_PORT = 8000
    private const val FLASK_PORT = 5000

    fun baseUrl(framework: Framework): String = when (framework) {
        Framework.DJANGO, Framework.DRF -> "http://$LOOPBACK:$DJANGO_PORT"
        Framework.FASTAPI -> "http://$LOOPBACK:$FASTAPI_PORT"
        Framework.FLASK -> "http://$LOOPBACK:$FLASK_PORT"
    }
}

/** One line of the history. Holds nothing secret: the URL is redacted, no headers and no bodies are kept. */
data class HistoryEntry(
    val timeMs: Long,
    val method: HttpMethod,
    val url: String,
    val status: Int,
    val durationMs: Long,
    val size: Long,
    val environment: String,
    val errorKind: ErrorKind? = null,
    /** The endpoint the request belonged to (`Endpoint.key`), empty when unknown; lets a stored line reveal its endpoint. */
    val endpointKey: String = "",
)

/** Bounded history, newest first; the oldest entries are dropped beyond [max]. Thread-safe. */
class RequestHistory(private val max: Int = Limits.HISTORY_MAX_ENTRIES, initial: List<HistoryEntry> = emptyList()) {
    private val entries = ArrayDeque<HistoryEntry>()

    init {
        initial.take(max).forEach { entries.addLast(it) }
    }

    @Synchronized
    fun record(request: PreparedRequest, response: ResponseData, environment: String, now: Long = System.currentTimeMillis()) {
        add(
            HistoryEntry(now, request.spec.method, request.displayUrl, response.status, response.durationMs, response.bodySize, environment, response.error?.kind),
        )
    }

    @Synchronized
    fun add(entry: HistoryEntry) {
        entries.addFirst(entry)
        while (entries.size > max) entries.removeLast()
    }

    @Synchronized
    fun snapshot(): List<HistoryEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * The last input per endpoint (keyed by `Endpoint.key`), least recently used dropped first. Values are made safe before they
 * are kept: credentials are blanked, oversized values dropped, so what is stored can be persisted as it is.
 */
class LastInputs(private val maxEndpoints: Int = Limits.LAST_INPUT_MAX_ENDPOINTS) {
    private val map = object : LinkedHashMap<String, RequestInput>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestInput>) = size > maxEndpoints
    }

    @Synchronized
    fun put(key: String, input: RequestInput) {
        map[key] = sanitize(input)
    }

    @Synchronized
    fun get(key: String): RequestInput? = map[key]

    @Synchronized
    fun entries(): List<Pair<String, RequestInput>> = map.entries.map { it.key to it.value }

    @Synchronized
    fun size(): Int = map.size

    companion object {
        private fun cap(s: String) = if (s.length > Limits.STORED_VALUE_MAX_CHARS) "" else s

        fun sanitize(input: RequestInput): RequestInput {
            val max = Limits.LAST_INPUT_MAX_FIELDS
            val body = when (val b = input.body) {
                RequestBody.None -> b
                is RequestBody.Text -> if (b.text.length > Limits.STORED_VALUE_MAX_CHARS) RequestBody.None else b
                is RequestBody.Form -> RequestBody.Form(b.fields.take(max).map { (n, v) -> n to if (SensitiveNames.isSensitiveField(n)) "" else cap(v) })
                is RequestBody.Multipart -> RequestBody.Multipart(
                    b.parts.take(max).map { p -> p.copy(value = p.value?.let { if (SensitiveNames.isSensitiveField(p.name)) "" else cap(it) }) },
                )
            }
            return RequestInput(
                method = input.method,
                pathValues = input.pathValues.entries.take(max).associate { it.key to cap(it.value) },
                query = input.query.take(max).map { it.copy(value = if (SensitiveNames.isSensitiveField(it.name)) "" else cap(it.value)) },
                headers = input.headers.take(max).map { it.copy(value = if (SensitiveNames.isSensitiveHeader(it.name) || SensitiveNames.isSensitiveField(it.name)) "" else cap(it.value)) },
                body = body,
                auth = input.auth,
            )
        }
    }
}
