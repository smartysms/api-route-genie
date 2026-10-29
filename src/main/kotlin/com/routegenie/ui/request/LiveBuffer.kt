package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.ContentTypes
import com.routegenie.client.ResponseData
import com.routegenie.client.ResponseMode
import com.routegenie.client.StreamEvent
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/** One row of the events view of an SSE stream. [atMs] is the time since the request was started. */
data class SseRow(val atMs: Long, val event: String?, val id: String?, val data: String)

/** What the UI takes from the buffer in one update: bounded text and rows, counters since the start, and the milestones seen. */
class LiveDrain(
    val text: String,
    val rows: List<SseRow>,
    val droppedChars: Long,
    val droppedRows: Long,
    val bytes: Long,
    val events: Long,
    val headers: StreamEvent.HeadersReceived?,
    val buffered: ResponseData?,
    val terminal: StreamEvent?,
    val moreAvailable: Boolean,
)

/**
 * The hand-over point between the engine's worker thread (which calls [add] with coalesced batches) and the UI thread (which
 * calls [drain] on its own schedule). Everything is bounded: when the UI lags, the oldest pending text and rows are dropped and
 * counted, so a fast stream cannot grow memory, and one [drain] never returns more than the caller's character budget, so one
 * UI update never freezes. Thread-safe.
 */
class LiveBuffer(
    private val clock: () -> Long = System::nanoTime,
    private val maxPendingChars: Int = Limits.LIVE_APPEND_MAX_CHARS * Limits.LIVE_PENDING_FACTOR,
    private val maxPendingRows: Int = Limits.LIVE_EVENT_ROWS_MAX,
) {
    private val lock = Any()
    private val startNanos = clock()
    private val text = StringBuilder()
    private val rows = ArrayDeque<SseRow>()
    private var decoder: CharsetDecoder = ContentTypes.charset(null).newDecoder().replaceBad()
    private var leftover = ByteArray(0)
    private var headers: StreamEvent.HeadersReceived? = null
    private var buffered: ResponseData? = null
    private var terminal: StreamEvent? = null
    private var jsonLines = false

    private var droppedChars = 0L
    private var droppedRows = 0L
    private var bytes = 0L
    private var events = 0L
    private var headersPending = false
    private var bufferedPending = false
    private var terminalPending = false

    private fun CharsetDecoder.replaceBad() = onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)

    fun add(batch: List<StreamEvent>) = synchronized(lock) {
        for (e in batch) {
            when (e) {
                is StreamEvent.HeadersReceived -> {
                    headers = e
                    headersPending = true
                    val type = e.headers.firstOrNull { it.first.equals("content-type", true) }?.second
                    decoder = ContentTypes.charset(type).newDecoder().replaceBad()
                    jsonLines = e.mode == ResponseMode.NDJSON
                }
                is StreamEvent.Chunk -> { bytes += e.bytes.size; decodeChunk(e.bytes) }
                is StreamEvent.Line -> {
                    events++
                    bytes += utf8Length(e.text) + 1
                    text.append(if (jsonLines) prettyLine(e.text) else e.text).append('\n')
                }
                is StreamEvent.SseEvent -> {
                    events++
                    bytes += utf8Length(e.data) + (e.event?.length ?: 0) + (e.id?.length ?: 0)
                    text.append(raw(e))
                    rows.addLast(SseRow((clock() - startNanos) / NANOS_PER_MS, e.event, e.id, e.data))
                    while (rows.size > maxPendingRows) { rows.removeFirst(); droppedRows++ }
                }
                is StreamEvent.BufferedResponse -> { buffered = e.response; bufferedPending = true }
                is StreamEvent.Completed, is StreamEvent.Failed -> { terminal = e; terminalPending = true }
            }
        }
        if (text.length > maxPendingChars) {
            val excess = text.length - maxPendingChars
            text.delete(0, excess)
            droppedChars += excess
        }
    }

    /** Takes at most [maxChars] characters of text; milestones (headers, buffered response, end) are reported once. */
    fun drain(maxChars: Int = Limits.LIVE_APPEND_MAX_CHARS): LiveDrain = synchronized(lock) {
        val take = minOf(text.length, maxChars)
        val out = text.substring(0, take)
        text.delete(0, take)
        val moreText = text.isNotEmpty()
        val r = rows.toList()
        rows.clear()
        val d = LiveDrain(
            out, r, droppedChars, droppedRows, bytes, events,
            headers.takeIf { headersPending }, buffered.takeIf { bufferedPending },
            terminal.takeIf { terminalPending && !moreText }, moreText,
        )
        headersPending = false
        bufferedPending = false
        if (!moreText) terminalPending = false
        d
    }

    val hasPending: Boolean get() = synchronized(lock) { text.isNotEmpty() || rows.isNotEmpty() || headersPending || bufferedPending || terminalPending }

    private fun raw(e: StreamEvent.SseEvent): String = buildString {
        e.id?.let { append("id: ").append(it).append('\n') }
        e.event?.let { append("event: ").append(it).append('\n') }
        e.retry?.let { append("retry: ").append(it).append('\n') }
        for (line in e.data.split('\n')) append("data: ").append(line).append('\n')
        append('\n')
    }

    private fun prettyLine(line: String): String {
        val t = line.trim()
        if (t.isEmpty() || (t[0] != '{' && t[0] != '[') || t.length > Limits.VIEWER_PRETTY_MAX_CHARS) return line
        return JsonPretty.format(t).text ?: line
    }

    private fun decodeChunk(data: ByteArray) {
        val input = ByteBuffer.wrap(if (leftover.isEmpty()) data else leftover + data)
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        out.flip()
        text.append(out)
        leftover = ByteArray(input.remaining()).also { input.get(it) }
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
