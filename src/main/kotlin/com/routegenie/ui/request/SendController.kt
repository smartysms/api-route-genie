package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.EngineError
import com.routegenie.client.ErrorKind
import com.routegenie.client.FieldError
import com.routegenie.client.Header
import com.routegenie.client.HostGuard
import com.routegenie.client.HttpEngine
import com.routegenie.client.ModeOverride
import com.routegenie.client.PrepareResult
import com.routegenie.client.PreparedRequest
import com.routegenie.client.Redactor
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestPreparer
import com.routegenie.client.RequestSpec
import com.routegenie.client.ResponseData
import com.routegenie.client.ResponseMode
import com.routegenie.client.ResponseSlot
import com.routegenie.client.SecretLookup
import com.routegenie.client.SendOptions
import com.routegenie.client.StreamCall
import com.routegenie.client.StreamEvent
import com.routegenie.client.StreamOptions
import com.routegenie.model.Endpoint
import java.util.concurrent.atomic.AtomicBoolean

/** What the controller needs from the UI toolkit; tests run it synchronously. */
interface UiHooks {
    /** Runs [r] on the UI thread. */
    fun post(r: Runnable)

    /** Runs [r] on the UI thread after [ms]; the returned handle cancels it. */
    fun postDelayed(ms: Long, r: Runnable): () -> Unit

    /** Asks whether a request to a host outside this machine and the private network may be sent. UI thread only. */
    fun confirmHost(host: String): Boolean
}

enum class Phase { IDLE, SENDING, STREAMING, DONE, FAILED, CANCELLED }

/** The request exactly as it went out, with credentials hidden, for the Request and cURL tabs. */
class SentRequest(val spec: RequestSpec, val displayUrl: String, val headers: List<Header>, val body: String?)

/** Everything the response panel shows; mutated on the UI thread only. */
class ResponseState {
    var phase: Phase = Phase.IDLE
    var sent: SentRequest? = null
    var head: StreamEvent.HeadersReceived? = null
    var response: ResponseData? = null
    var error: EngineError? = null
    var mode: ResponseMode? = null
    var bytes = 0L
    var events = 0L
    var droppedChars = 0L
    var droppedRows = 0L
    var elapsedMs = 0L
    var startedNanos = 0L
    val running: Boolean get() = phase == Phase.SENDING || phase == Phase.STREAMING
}

sealed interface SendResult {
    data object Started : SendResult
    data object Busy : SendResult
    data object Declined : SendResult
    data class Invalid(val errors: List<FieldError>) : SendResult
}

/**
 * Prepares a request, asks the host guard, runs it through the engine's streaming API (a buffered answer arrives as one
 * event) and hands bounded updates to the UI. Nothing is sent unless [send] is called, and nothing here blocks the UI thread:
 * the engine's worker delivers into a [LiveBuffer] and the UI drains it on its own schedule.
 */
class SendController(
    private val engine: () -> HttpEngine,
    private val guard: HostGuard,
    private val secrets: SecretLookup,
    private val ui: UiHooks,
    private val history: HistoryLog,
    private val clock: () -> Long = System::nanoTime,
    private val now: () -> Long = System::currentTimeMillis,
    /** Owns the response on screen: replacing it deletes the previous one's spill file (the project's slot in the IDE). */
    private val slot: ResponseSlot = ResponseSlot(),
) : AutoCloseable {
    interface Listener {
        /** The response on screen is about to be released: stop reading its body (the viewers close their files). */
        fun releasing() {}

        /** The state changed; [drain] carries newly arrived text and rows (null for pure state changes). */
        fun changed(state: ResponseState, drain: LiveDrain?)
    }

    val state = ResponseState()
    var listener: Listener? = null

    /** Told after every state change on the UI thread (the Send button follows it). */
    var observer: (() -> Unit)? = null
    var override: ModeOverride = ModeOverride.AUTO

    private var buffer = LiveBuffer(clock)
    private var call: StreamCall? = null
    private var runId = 0
    private var closed = false
    private val flushScheduled = AtomicBoolean(false)
    @Volatile private var cancelFlush: (() -> Unit)? = null
    private var cancelTick: (() -> Unit)? = null
    private var lastPrepared: PreparedRequest? = null
    private var lastForm: RequestForm? = null
    private var lastEnvironment: String = ""
    var updates = 0
        private set

    /** The request of the last send with its real credentials, for "copy with secrets". Never persisted. */
    fun lastRequestWithSecrets(): RequestSpec? = lastPrepared?.spec

    fun prepare(endpoint: Endpoint, form: RequestForm, env: com.routegenie.client.Environment): PrepareResult =
        RequestPreparer.prepare(endpoint, form.toInput(), env, secrets)

    fun send(endpoint: Endpoint, form: RequestForm, env: com.routegenie.client.Environment): SendResult {
        check(!closed) { "controller closed" }
        if (state.running) return SendResult.Busy
        val prepared = when (val p = prepare(endpoint, form, env)) {
            is PrepareResult.Invalid -> return SendResult.Invalid(p.errors)
            is PrepareResult.Ready -> p.request
        }
        val spec = prepared.spec
        if (guard.needsConfirmation(spec)) {
            val host = guard.hostOf(spec) ?: return SendResult.Declined
            if (!ui.confirmHost(host)) return SendResult.Declined
            guard.confirm(host)
        }
        dropResponse()
        runId++
        val id = runId
        buffer = LiveBuffer(clock)
        lastPrepared = prepared
        lastEnvironment = env.name
        lastForm = form.copy(redact = true)
        state.apply {
            phase = Phase.SENDING; head = null; response = null; error = null; mode = null
            bytes = 0; events = 0; droppedChars = 0; droppedRows = 0; elapsedMs = 0; startedNanos = clock()
            sent = SentRequest(
                RequestSpec(spec.method, prepared.displayUrl, prepared.redactedHeaders, spec.body),
                prepared.displayUrl, prepared.redactedHeaders, describeBody(spec.body),
            )
        }
        notifyChanged(null)
        scheduleTick(id)
        val options = prepared.options(SendOptions(redirectHostAllowed = guard::isAllowed))
        val buf = buffer
        call = engine().stream(spec, options, StreamOptions(override)) { batch ->
            buf.add(batch)
            scheduleFlush(id)
        }
        return SendResult.Started
    }

    fun cancel() {
        call?.cancel()
    }

    // ---- UI thread side ------------------------------------------------------------------------------------------

    private fun scheduleFlush(id: Int) {
        if (!flushScheduled.compareAndSet(false, true)) return
        // postDelayed is thread-safe: the engine's worker thread calls this, the callback runs on the UI thread.
        cancelFlush = ui.postDelayed(Limits.LIVE_FLUSH_INTERVAL_MS) { flushScheduled.set(false); flush(id) }
    }

    private fun scheduleTick(id: Int) {
        cancelTick?.invoke()
        cancelTick = ui.postDelayed(Limits.LIVE_TICK_MS) {
            if (!closed && id == runId && state.running) {
                state.elapsedMs = (clock() - state.startedNanos) / NANOS_PER_MS
                notifyChanged(null)
                scheduleTick(id)
            }
        }
    }

    internal fun flush(id: Int) {
        if (closed || id != runId) return
        val d = buffer.drain()
        applyDrain(d)
        notifyChanged(d)
        if (d.moreAvailable || buffer.hasPending) scheduleFlush(id)
    }

    private fun applyDrain(d: LiveDrain) {
        state.bytes = d.bytes
        state.events = d.events
        state.droppedChars = d.droppedChars
        state.droppedRows = d.droppedRows
        state.elapsedMs = (clock() - state.startedNanos) / NANOS_PER_MS
        d.headers?.let {
            state.head = it
            state.mode = it.mode
            if (state.phase == Phase.SENDING) state.phase = Phase.STREAMING
        }
        d.buffered?.let { r ->
            slot.set(r)
            state.response = r
            state.bytes = r.bodySize
            r.error?.let { state.error = it }
        }
        when (val t = d.terminal) {
            is StreamEvent.Completed -> {
                state.phase = if (state.error != null) Phase.FAILED else Phase.DONE
                finish()
            }
            is StreamEvent.Failed -> {
                state.error = state.error ?: t.error
                state.phase = if (t.error.kind == ErrorKind.CANCELLED) Phase.CANCELLED else Phase.FAILED
                finish()
            }
            else -> Unit
        }
    }

    private fun finish() {
        cancelTick?.invoke()
        cancelTick = null
        call = null
        val sent = state.sent ?: return
        val form = lastForm ?: return
        history.add(
            HistoryEntry(
                form.endpoint.key, now(), sent.spec.method, sent.displayUrl,
                state.response?.status ?: state.head?.status ?: 0, state.error?.kind, state.elapsedMs,
                state.response?.bodySize ?: state.bytes, form, state.response?.let { KeptBody.of(it) }, lastEnvironment,
            ),
        )
    }

    private fun notifyChanged(d: LiveDrain?) {
        updates++
        listener?.changed(state, d)
        observer?.invoke()
    }

    /** Forgets the shown response (the Clear button); a running send is cancelled first. */
    fun clear() {
        call?.cancel()
        runId++
        cancelTick?.invoke()
        dropResponse()
        state.apply {
            phase = Phase.IDLE; sent = null; head = null; response = null; error = null; mode = null
            bytes = 0; events = 0; droppedChars = 0; droppedRows = 0; elapsedMs = 0
        }
        buffer = LiveBuffer(clock)
        notifyChanged(null)
    }

    override fun close() {
        if (closed) return
        closed = true
        dropResponse()
        listener = null
        observer = null
        call?.cancel()
        call = null
        cancelFlush?.invoke()
        cancelTick?.invoke()
        lastPrepared = null
    }

    /** Releases the shown response through the slot, after the viewers that read its file were told to let go. */
    private fun dropResponse() {
        val shown = state.response ?: return
        listener?.releasing()
        if (slot.get() === shown) slot.set(null) else shown.release()
        state.response = null
    }

    private fun describeBody(body: RequestBody): String? = when (body) {
        RequestBody.None -> null
        is RequestBody.Text -> body.text.take(Limits.SENT_BODY_PREVIEW_CHARS)
        is RequestBody.Form -> body.fields.joinToString("&") { (n, v) -> "$n=" + if (com.routegenie.client.SensitiveNames.isSensitiveField(n)) Redactor.PLACEHOLDER else v }
        is RequestBody.Multipart -> body.parts.joinToString("\n") { p -> p.name + "=" + (p.filePath?.let { "@$it" } ?: p.value.orEmpty()) }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
