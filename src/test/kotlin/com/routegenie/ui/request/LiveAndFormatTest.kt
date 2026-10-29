package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.client.ResponseMode
import com.routegenie.client.StreamEvent
import com.routegenie.model.HttpMethod
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAndFormatTest {
    private fun head(mode: ResponseMode, type: String) =
        StreamEvent.HeadersReceived(200, "OK", listOf("content-type" to type), "HTTP/1.1", mode, "http://h/")

    @Test
    fun `a fast stream is drained in bounded steps and memory stays bounded`() {
        val buffer = LiveBuffer(maxPendingChars = 100_000)
        buffer.add(listOf(head(ResponseMode.RAW_STREAM, "text/plain")))
        val chunk = ByteArray(1_000) { 'x'.code.toByte() }
        repeat(20_000) { buffer.add(listOf(StreamEvent.Chunk(chunk))) } // 20 MB arrives while the UI sleeps
        var drains = 0
        var shown = 0L
        var dropped = 0L
        do {
            val d = buffer.drain(10_000)
            assertTrue("one update never exceeds its budget", d.text.length <= 10_000)
            shown += d.text.length
            dropped = d.droppedChars
            drains++
        } while (d.moreAvailable)
        assertEquals(100_000L, shown)
        assertEquals(20_000_000L - 100_000L, dropped)
        assertEquals(10, drains)
    }

    @Test
    fun `multi-byte characters split across chunks decode correctly`() {
        val bytes = "café 中文".toByteArray(Charsets.UTF_8)
        val b = LiveBuffer()
        b.add(listOf(head(ResponseMode.RAW_STREAM, "text/plain; charset=utf-8")))
        for (x in bytes) b.add(listOf(StreamEvent.Chunk(byteArrayOf(x))))
        assertEquals("café 中文", b.drain().text)
    }

    @Test
    fun `NDJSON lines are pretty printed one by one and SSE keeps rows`() {
        val n = LiveBuffer()
        n.add(listOf(head(ResponseMode.NDJSON, "application/x-ndjson"), StreamEvent.Line("{\"a\":1}"), StreamEvent.Line("not json")))
        assertEquals("{\n  \"a\": 1\n}\nnot json\n", n.drain().text)
        val s = LiveBuffer()
        s.add(listOf(head(ResponseMode.SSE, "text/event-stream"), StreamEvent.SseEvent("1", "tick", "a\nb", null)))
        val d = s.drain()
        assertEquals(listOf("tick"), d.rows.map { it.event })
        assertEquals("id: 1\nevent: tick\ndata: a\ndata: b\n\n", d.text)
        assertEquals(1L, d.events)
    }

    @Test
    fun `milestones are reported once`() {
        val b = LiveBuffer()
        b.add(listOf(head(ResponseMode.RAW_STREAM, "text/plain"), StreamEvent.Line("x")))
        assertNotNull(b.drain().headers)
        assertNull(b.drain().headers)
    }

    @Test
    fun `JSON pretty printer keeps numbers and order and rejects broken input`() {
        assertEquals("{\n  \"b\": 1.10,\n  \"a\": [\n    1,\n    2\n  ],\n  \"e\": {},\n  \"s\": \"x\\\"y\"\n}", JsonPretty.format("""{"b":1.10,"a":[1,2],"e":{},"s":"x\"y"}""").text)
        assertNull(JsonPretty.format("""{"a":""").text)
        assertNull(JsonPretty.format("""{"a":1}}""").text)
        assertNull(JsonPretty.format("   ").text)
        assertNull("deep nesting is refused, not a stack overflow", JsonPretty.format("[".repeat(Limits.VIEWER_JSON_MAX_DEPTH + 5)).text)
    }

    @Test
    fun `formatter caps what is shown and says so`() {
        val small = BodyFormatter.format("""{"a":1}""".toByteArray(), "application/json", 7)
        assertTrue(small.pretty && small.highlight && small.notices.isEmpty())
        val big = ByteArray(6_000_000) { 'a'.code.toByte() }
        val r = BodyFormatter.format(big, "text/plain", big.size.toLong())
        assertEquals(Limits.VIEWER_DISPLAY_CAP_CHARS, r.text.length)
        assertTrue(r.notices.any { it.first == BodyNotice.VIEW_CUT })
        assertFalse(r.highlight)
        val cut = BodyFormatter.format("""{"a":1""".toByteArray(), "application/json", 10_000_000)
        assertTrue(cut.notices.any { it.first == BodyNotice.ENGINE_CUT } && !cut.pretty)
        assertEquals(ContentKind.BINARY, BodyFormatter.format(byteArrayOf(1, 0, 2), null, 3).kind)
        assertTrue(BodyFormatter.format("{\"a\":".toByteArray(), "application/json", 5).let { it.notices.any { n -> n.first == BodyNotice.INVALID_JSON } })
    }

    @Test
    fun `5 MB JSON is formatted fast enough to run off the UI thread and capped to plain text`() {
        val sb = StringBuilder("[")
        while (sb.length < 5_000_000) sb.append("{\"id\":").append(sb.length).append(",\"name\":\"item\"},")
        sb.setLength(sb.length - 1)
        sb.append(']')
        val bytes = sb.toString().toByteArray()
        PerfBudget.assertWithin("viewer-format-5mb", Limits.BUDGET_WINDOW_OPEN_MS * 5, warmups = 1, runs = 3) {
            val r = BodyFormatter.format(bytes, "application/json", bytes.size.toLong())
            assertFalse("too large to re-indent", r.pretty)
            assertTrue(r.notices.any { it.first == BodyNotice.NOT_FORMATTED })
        }
    }

    @Test
    fun `path template matching`() {
        assertEquals(mapOf("id" to "7"), PathTemplate.match("/users/{id}", "/users/7"))
        assertEquals(mapOf("p" to "a/b"), PathTemplate.match("/files/{p:path}", "/files/a/b"))
        assertNull(PathTemplate.match("/users/{id}", "/items/7"))
    }

    @Test
    fun `importing a cURL request fills the form`() {
        val form = RequestForm(endpoint(HttpMethod.ANY, "/users/{user_id}", listOf(com.routegenie.model.PathParam("user_id", "int"))))
        val spec = RequestSpec(
            HttpMethod.POST, "http://localhost:9000/users/42?x=a%20b",
            listOf(com.routegenie.client.Header("X-A", "1")), RequestBody.Text("application/json", "{}"),
        )
        val r = form.apply(spec)
        assertEquals("http://localhost:9000", r.baseUrl)
        assertTrue(r.pathApplied)
        assertEquals("42", form.pathValues["user_id"])
        assertEquals(listOf(FormRow("x", "a b")), form.query)
        assertEquals(HttpMethod.POST, form.method)
        assertEquals(BodyType.JSON, form.bodyKind)
    }
}
