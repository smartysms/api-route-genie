package com.routegenie.client

import com.routegenie.Limits

/**
 * Server-Sent Events parser following the WHATWG rules: fields event, data, id, retry; several data lines joined with LF;
 * lines starting with a colon are comments; one optional space after the colon is dropped; an id containing NUL is ignored;
 * a retry that is not all digits is ignored; an event with no data is not delivered; a last event not ended by a blank line
 * is discarded. An event larger than [maxEventBytes] is dropped and counted in [droppedEvents].
 */
class SseParser(private val maxEventBytes: Int = Limits.SSE_MAX_EVENT_BYTES, private val sink: (StreamEvent.SseEvent) -> Unit) {
    private val data = StringBuilder()
    private var hasData = false
    private var event: String? = null
    private var retry: Long? = null
    private var size = 0
    private var oversized = false
    private var cutBase = 0L

    /** The last event id seen; it carries over to later events that have no id line, as Last-Event-ID does. */
    var lastEventId: String? = null
        private set

    var droppedEvents = 0L
        private set

    private val splitter = LineSplitter(maxEventBytes) { line(it) }

    fun feed(bytes: ByteArray, off: Int = 0, count: Int = bytes.size - off) = splitter.feed(bytes, off, count)

    private fun line(line: String) {
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(':')) return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        size += line.length
        if (size > maxEventBytes || splitter.cutLines != cutBase) oversized = true
        if (oversized) return
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> event = value
            "id" -> if ('\u0000' !in value) lastEventId = value
            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) retry = value.toLongOrNull()
        }
    }

    private fun dispatch() {
        if (oversized) droppedEvents++
        if (hasData && !oversized) sink(StreamEvent.SseEvent(lastEventId, event?.takeIf { it.isNotEmpty() }, data.toString(), retry))
        data.setLength(0)
        if (data.capacity() > SHRINK_ABOVE) data.trimToSize()
        hasData = false
        event = null
        retry = null
        size = 0
        oversized = false
        cutBase = splitter.cutLines
    }

    private companion object {
        const val SHRINK_ABOVE = 64 * 1024
    }
}
