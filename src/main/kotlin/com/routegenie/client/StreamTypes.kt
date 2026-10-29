package com.routegenie.client

import com.routegenie.Limits
import java.util.concurrent.CompletableFuture

/** How a response is consumed. Decided from the headers by [ResponseModes], or forced by the user. */
enum class ResponseMode { BUFFERED, SSE, NDJSON, RAW_STREAM }

enum class ModeOverride { AUTO, FORCE_STREAM, FORCE_BUFFERED }

data class StreamSummary(val bytes: Long, val events: Long, val durationMs: Long)

/**
 * What [HttpEngine.stream] delivers, in order: HeadersReceived, then the body events of the mode (SseEvent, Line or Chunk;
 * in BUFFERED mode one BufferedResponse instead), then exactly one of Completed or Failed.
 */
sealed interface StreamEvent {
    data class HeadersReceived(
        val status: Int,
        val reason: String,
        val headers: List<Pair<String, String>>,
        val protocol: String,
        val mode: ResponseMode,
        val finalUrl: String,
    ) : StreamEvent

    /** Raw bytes of a stream that is neither SSE nor line based. */
    class Chunk(val bytes: ByteArray) : StreamEvent

    data class SseEvent(val id: String?, val event: String?, val data: String, val retry: Long?) : StreamEvent

    data class Line(val text: String) : StreamEvent

    /** The whole response of a buffered mode send (capped as in [HttpEngine.execute]). */
    class BufferedResponse(val response: ResponseData) : StreamEvent

    data class Completed(val summary: StreamSummary) : StreamEvent

    data class Failed(val error: EngineError) : StreamEvent
}

/** Called on a worker thread with each coalesced batch (never empty); do not block in it, hop to the UI thread. */
fun interface StreamListener {
    fun onEvents(batch: List<StreamEvent>)
}

/** A stream in flight. [finished] completes with the terminal event (Completed or Failed) after the listener got it. */
interface StreamCall {
    val finished: CompletableFuture<StreamEvent>

    /** Closes the connection; the stream ends with Failed(CANCELLED) within about 100 ms. */
    fun cancel()
}

/** Settings of a streaming send. A [maxDurationMs] of 0 means the stream may run for ever (until idle timeout or cancel). */
data class StreamOptions(
    val mode: ModeOverride = ModeOverride.AUTO,
    val idleTimeoutMs: Long = Limits.STREAM_IDLE_TIMEOUT_MS,
    val maxDurationMs: Long = 0,
)

object ResponseModes {
    private val NDJSON_TYPES = setOf(
        "application/x-ndjson", "application/ndjson", "application/stream+json", "application/jsonl",
        "application/x-jsonlines", "application/jsonlines",
    )
    private val STREAMING_HINTS = listOf("stream", "event")

    /**
     * text/event-stream is SSE; the NDJSON family is line based; a chunked answer without Content-Length whose type looks
     * like a stream is raw streaming; everything else is buffered. [override] wins over the decision (forcing a stream on
     * an unknown type gives RAW_STREAM).
     */
    fun decide(headers: List<Pair<String, String>>, override: ModeOverride = ModeOverride.AUTO): ResponseMode {
        fun h(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val type = h("content-type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val detected = when {
            type == "text/event-stream" -> ResponseMode.SSE
            type in NDJSON_TYPES -> ResponseMode.NDJSON
            h("content-length") == null && h("transfer-encoding")?.contains("chunked", ignoreCase = true) == true &&
                STREAMING_HINTS.any { type.contains(it) } -> ResponseMode.RAW_STREAM
            else -> ResponseMode.BUFFERED
        }
        return when (override) {
            ModeOverride.AUTO -> detected
            ModeOverride.FORCE_BUFFERED -> ResponseMode.BUFFERED
            ModeOverride.FORCE_STREAM -> if (detected == ResponseMode.BUFFERED) ResponseMode.RAW_STREAM else detected
        }
    }
}
