package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.ContentTypes
import com.routegenie.client.DevServerDefaults
import com.routegenie.client.Environment
import com.routegenie.client.ErrorKind
import com.routegenie.client.RequestHistory
import com.routegenie.client.ResponseData
import com.routegenie.client.SecretLookup
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.clientide.SecretStore
import com.routegenie.json.JsonContent
import com.routegenie.json.JsonSniffer
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.routegenie.client.HistoryEntry as StoredHistoryEntry

/** How a buffered response reaches the JSON visualizer: the one place that decides between memory, spill file and cut. */
object ResponseJson {
    fun isJson(r: ResponseData): Boolean = r.body.isNotEmpty() && JsonSniffer.isJson(r.header("content-type"), r.body)

    /**
     * A body that went to a spill file is passed as that file (the visualizer reads it lazily and never loads it whole). A
     * body cut at the display cap without a file passes its full size, so the viewer can say the document is incomplete.
     */
    fun content(r: ResponseData): JsonContent {
        val charset = ContentTypes.charset(r.header("content-type"))
        val file = r.bodyFile
        if (file != null && Files.exists(file)) {
            // A download that stopped at the spill limit has no known full size; one more byte marks it as cut.
            return JsonContent.File(file, charset, if (r.bodyTruncatedAtLimit) r.bodySize + 1 else -1L)
        }
        return JsonContent.Bytes(r.body, charset, if (r.truncated) r.bodySize else -1L)
    }
}

/**
 * The JSON body of a finished response, kept so the history can compare it with a later one. Small bodies are shared with the
 * [ResponseData] (no copy, memory bounded by [Limits.HISTORY_BODY_KEPT_MAX_BYTES]); a spilled body keeps its file alive through
 * a lease until the entry leaves the bounded set. Bodies are never persisted and never leave the process.
 */
class KeptBody private constructor(private val response: ResponseData, private val lease: AutoCloseable?) : AutoCloseable {
    val sizeBytes: Long get() = response.bodySize
    val inFile: Boolean get() = lease != null

    fun content(): JsonContent = ResponseJson.content(response)

    override fun close() { lease?.close() }

    companion object {
        fun of(r: ResponseData): KeptBody? {
            if (!ResponseJson.isJson(r)) return null
            if (r.bodyFile != null) return KeptBody(r, r.responseBody.retain())
            if (r.bodySize > Limits.HISTORY_BODY_KEPT_MAX_BYTES) return null
            return KeptBody(r, null)
        }
    }
}

/** One sent request, for the History tab. [form] is a copy with credential headers redacted; null for lines loaded from disk. */
class HistoryEntry(
    val endpointKey: String,
    val atMillis: Long,
    val method: HttpMethod,
    val displayUrl: String,
    val status: Int,
    val error: ErrorKind?,
    val durationMs: Long,
    val size: Long,
    val form: RequestForm?,
    body: KeptBody? = null,
    val environment: String = "",
) {
    var body: KeptBody? = body
        private set

    fun dropBody() {
        body?.close()
        body = null
    }
}

/**
 * The requests of this window, newest first, bounded by [Limits.HISTORY_MAX_ENTRIES]. Lines are mirrored into [persisted]
 * (already redacted, no bodies). Only the newest [Limits.HISTORY_BODIES_KEPT] entries keep a response body, of which at most
 * [Limits.HISTORY_SPILLED_BODIES_KEPT] live in a spill file; older entries lose theirs and show "body not kept".
 */
class HistoryLog(
    private val max: Int = Limits.HISTORY_MAX_ENTRIES,
    private val persisted: RequestHistory? = null,
    private val bodiesKept: Int = Limits.HISTORY_BODIES_KEPT,
    private val spilledKept: Int = Limits.HISTORY_SPILLED_BODIES_KEPT,
) {
    private val entries = ArrayDeque<HistoryEntry>()

    init {
        persisted?.snapshot()?.take(max)?.forEach { s ->
            entries.addLast(HistoryEntry(s.endpointKey, s.timeMs, s.method, s.url, s.status, s.errorKind, s.durationMs, s.size, null, null, s.environment))
        }
    }

    val size: Int get() = entries.size

    fun add(entry: HistoryEntry) {
        entries.addFirst(entry)
        persisted?.add(
            StoredHistoryEntry(entry.atMillis, entry.method, entry.displayUrl, entry.status, entry.durationMs, entry.size, entry.environment, entry.error, entry.endpointKey),
        )
        while (entries.size > max) entries.removeLast().dropBody()
        enforceBodyBounds()
    }

    private fun enforceBodyBounds() {
        var kept = 0
        var spilled = 0
        for (e in entries) {
            val b = e.body ?: continue
            val withinCount = kept < bodiesKept
            val withinSpill = !b.inFile || spilled < spilledKept
            if (withinCount && withinSpill) {
                kept++
                if (b.inFile) spilled++
            } else {
                e.dropBody()
            }
        }
    }

    fun all(): List<HistoryEntry> = entries.toList()

    /** The newest entry that still has a body and is not [excluding]; the answer to "compare with the previous response". */
    fun previousWithBody(excluding: HistoryEntry?): HistoryEntry? = entries.firstOrNull { it !== excluding && it.body != null }

    fun withBodies(): List<HistoryEntry> = entries.filter { it.body != null }

    /** Releases every kept body; the lines stay. Called when the owner goes. */
    fun releaseBodies() = entries.forEach { it.dropBody() }

    fun clear() {
        releaseBodies()
        entries.clear()
    }
}

/**
 * Where the environment to send to comes from. [PersistentEnvironments] serves the window; the interface keeps the panels
 * independent of the IDE's state component.
 */
interface EnvironmentProvider {
    fun current(): Environment
    fun all(): List<Environment>
    fun select(name: String)
    fun save(env: Environment)
    fun remove(name: String)
    fun updateBaseUrl(url: String)

    /** Makes sure one environment exists; a first one points at the usual dev server of [framework]. */
    fun ensureFor(framework: Framework)
}

class PersistentEnvironments(private val state: RouteGenieClientState, private val defaultName: String) : EnvironmentProvider {
    @Volatile private var lastFramework: Framework = Framework.FASTAPI

    override fun current(): Environment = state.selected() ?: Environment(defaultName, DevServerDefaults.baseUrl(lastFramework))

    override fun all(): List<Environment> = state.environments()

    override fun select(name: String) = state.select(name)

    override fun save(env: Environment) = state.save(env)

    override fun remove(name: String) = state.remove(name)

    override fun updateBaseUrl(url: String) = state.save(current().copy(baseUrl = url))

    override fun ensureFor(framework: Framework) {
        lastFramework = framework
        state.ensureDefault(framework, defaultName)
    }
}

/**
 * Secret values of the current environment. Reads come from memory only (the send path runs on the UI thread and must never
 * wait for the credential store); [useScope] loads the environment's secrets from the [store] on a background thread and
 * writes go to the store in the background. Nothing here touches XML, history or logs.
 */
class LayeredSecrets(private val store: SecretStore, private val executor: (Runnable) -> Unit) : SecretLookup {
    private data class Slot(val scope: String, val key: String)

    private val cache = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<Slot, String>()
    private val flushing = AtomicBoolean(false)
    @Volatile private var scope: String = ""

    /** Called on the thread that finished loading; the window uses it to refresh hints. */
    @Volatile var onLoaded: (() -> Unit)? = null

    val currentScope: String get() = scope

    /** Switches to the secrets of environment [name]: memory is emptied, [keys] are read from the store in the background. */
    fun useScope(name: String, keys: Collection<String>) {
        if (name != scope) cache.clear()
        scope = name
        executor(Runnable {
            for (key in keys) {
                if (scope != name) return@Runnable
                val v = runCatching { store.get(name, key) }.getOrNull() ?: continue
                if (scope == name && !pending.containsKey(Slot(name, key))) cache.putIfAbsent(key, v)
            }
            onLoaded?.invoke()
        })
    }

    fun put(key: String, value: String) = putFor(scope, key, value)

    /** Stores a secret of environment [envScope]; the memory layer is touched only when that is the current environment. */
    fun putFor(envScope: String, key: String, value: String) {
        if (envScope == scope) {
            if (value.isEmpty()) cache.remove(key) else cache[key] = value
        }
        pending[Slot(envScope, key)] = value
        scheduleFlush()
    }

    fun has(key: String): Boolean = cache.containsKey(key)

    override fun get(key: String): String? = cache[key]

    private fun scheduleFlush() {
        if (!flushing.compareAndSet(false, true)) return
        executor(Runnable {
            try {
                drain()
            } finally {
                flushing.set(false)
            }
            if (pending.isNotEmpty()) scheduleFlush()
        })
    }

    private fun drain() {
        while (true) {
            val e = pending.entries.firstOrNull() ?: return
            pending.remove(e.key, e.value)
            runCatching { store.set(e.key.scope, e.key.key, e.value.ifEmpty { null }) }
        }
    }

    /** Forgets the in-memory values (the window closes); writes already queued still reach the store. */
    fun clear() = cache.clear()
}
