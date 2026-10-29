package com.routegenie.client

import com.routegenie.Limits
import com.routegenie.model.HttpMethod
import com.routegenie.testing.LeakProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class StreamingTest {
    private fun sse(vararg chunks: String, max: Int = Limits.SSE_MAX_EVENT_BYTES): List<StreamEvent.SseEvent> {
        val out = ArrayList<StreamEvent.SseEvent>()
        val p = SseParser(max) { out += it }
        chunks.forEach { p.feed(it.toByteArray(Charsets.UTF_8)) }
        return out
    }

    // --- pure parsers ------------------------------------------------------------------------------------------------

    @Test fun `sse basic event with all fields`() {
        val e = sse("id: 7\nevent: tick\nretry: 3000\ndata: hello\n\n").single()
        assertEquals(StreamEvent.SseEvent("7", "tick", "hello", 3000L), e)
    }

    @Test fun `sse multi line data joins with newline`() = assertEquals("a\nb\nc", sse("data: a\ndata: b\ndata:c\n\n").single().data)

    @Test fun `sse comments and unknown fields are ignored`() = assertEquals(1, sse(": ping\nfoo: bar\ndata: x\n\n").size)

    @Test fun `sse event without data is not delivered`() = assertEquals(0, sse("event: x\n\n").size)

    @Test fun `sse handles crlf cr and lf and a split crlf`() {
        assertEquals(listOf("a", "b", "c"), sse("data: a\r\n\r\ndata: b\r\rdata: c\n\n").map { it.data })
        assertEquals(listOf("a"), sse("data: a\r", "\n\r", "\n").map { it.data })
    }

    @Test fun `sse event split in the middle of a field and of a utf8 character`() {
        val bytes = "data: héllo\n\n".toByteArray(Charsets.UTF_8)
        val out = ArrayList<StreamEvent.SseEvent>()
        val p = SseParser { out += it }
        for (b in bytes) p.feed(byteArrayOf(b))
        assertEquals("héllo", out.single().data)
    }

    @Test fun `sse strips a byte order mark`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "data: x\n\n".toByteArray()
        val out = ArrayList<StreamEvent.SseEvent>()
        SseParser { out += it }.feed(bytes)
        assertEquals("x", out.single().data)
    }

    @Test fun `sse only one leading space is removed and a field without colon has empty value`() {
        assertEquals("  x", sse("data:   x\n\n").single().data)
        assertEquals("", sse("data\n\n").single().data)
    }

    @Test fun `sse id carries over and an id with NUL is ignored`() {
        val events = sse("id: 1\ndata: a\n\ndata: b\n\nid: x\u0000y\ndata: c\n\n")
        assertEquals(listOf("1", "1", "1"), events.map { it.id })
    }

    @Test fun `sse invalid retry is ignored and the last event without a blank line is discarded`() {
        assertEquals(null, sse("retry: abc\ndata: a\n\n").single().retry)
        assertEquals(0, sse("data: unfinished\n").size)
    }

    @Test fun `sse oversized event is dropped and counted and the next one works`() {
        val out = ArrayList<StreamEvent.SseEvent>()
        val p = SseParser(maxEventBytes = 64) { out += it }
        p.feed(("data: " + "x".repeat(200) + "\n\ndata: ok\n\n").toByteArray())
        assertEquals(listOf("ok"), out.map { it.data })
        assertEquals(1L, p.droppedEvents)
    }

    @Test fun `line splitter delivers lines across chunks and the unterminated tail`() {
        val lines = ArrayList<String>()
        val s = LineSplitter { lines += it }
        s.feed("{\"a\":1}\n{\"b\"".toByteArray())
        s.feed(":2}\r\n".toByteArray())
        s.feed("tail".toByteArray())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), lines)
        assertEquals("tail", s.finish())
    }

    @Test fun `line splitter cuts a runaway line`() {
        val lines = ArrayList<String>()
        val s = LineSplitter(maxLineBytes = 10) { lines += it }
        s.feed(("x".repeat(100) + "\nok\n").toByteArray())
        assertEquals(listOf("x".repeat(10), "ok"), lines)
        assertEquals(1L, s.cutLines)
    }

    @Test fun `mode decision from headers`() {
        fun mode(type: String?, chunked: Boolean = false, length: String? = null, o: ModeOverride = ModeOverride.AUTO): ResponseMode {
            val h = ArrayList<Pair<String, String>>()
            if (type != null) h += "content-type" to type
            if (chunked) h += "transfer-encoding" to "chunked"
            if (length != null) h += "content-length" to length
            return ResponseModes.decide(h, o)
        }
        assertEquals(ResponseMode.SSE, mode("text/event-stream; charset=utf-8"))
        for (t in listOf("application/x-ndjson", "application/stream+json", "application/jsonl", "application/ndjson")) assertEquals(t, ResponseMode.NDJSON, mode(t))
        assertEquals(ResponseMode.RAW_STREAM, mode("application/octet-stream", chunked = true))
        assertEquals(ResponseMode.BUFFERED, mode("application/octet-stream", chunked = true, length = "5"))
        assertEquals(ResponseMode.BUFFERED, mode("application/json", chunked = true))
        assertEquals(ResponseMode.BUFFERED, mode(null))
        assertEquals(ResponseMode.RAW_STREAM, mode("application/json", o = ModeOverride.FORCE_STREAM))
        assertEquals(ResponseMode.SSE, mode("text/event-stream", o = ModeOverride.FORCE_STREAM))
        assertEquals(ResponseMode.BUFFERED, mode("text/event-stream", o = ModeOverride.FORCE_BUFFERED))
    }

    @Test fun `bounded log drops the oldest and counts`() {
        val log = BoundedLog<String>(maxBytes = 10, maxItems = 100) { it.length }
        repeat(10) { log.add("abcd") }
        assertEquals(2, log.size)
        assertEquals(8, log.droppedItems)
        assertEquals(32L, log.droppedBytes)
        val big = BoundedLog<String>(maxBytes = 5, maxItems = 3) { it.length }
        big.add("x".repeat(50))
        assertEquals(1, big.size)
        repeat(5) { big.add("a") }
        assertEquals(listOf("a", "a", "a"), big.snapshot())
    }

    // --- engine streaming ------------------------------------------------------------------------------------------------

    private lateinit var server: LocalServer
    private lateinit var spillDir: java.nio.file.Path
    private lateinit var engine: JdkHttpEngine

    @Before fun setUp() {
        server = LocalServer()
        spillDir = Files.createTempDirectory("rg-stream-spill")
        engine = JdkHttpEngine(spill = SpillDirectory(spillDir))
    }

    @After fun tearDown() {
        engine.close()
        server.close()
        spillDir.toFile().deleteRecursively()
    }

    private class Collector : StreamListener {
        val events = CopyOnWriteArrayList<StreamEvent>()
        val batches = CopyOnWriteArrayList<Int>()
        override fun onEvents(batch: List<StreamEvent>) {
            batches += batch.size
            events += batch
        }
    }

    private fun stream(path: String, so: StreamOptions = StreamOptions(), options: SendOptions = SendOptions()): Pair<StreamCall, Collector> {
        val c = Collector()
        return engine.stream(RequestSpec(HttpMethod.GET, server.url(path)), options, so, c) to c
    }

    private fun slowWrite(ex: com.sun.net.httpserver.HttpExchange, parts: List<String>, pauseMs: Long) {
        ex.sendResponseHeaders(200, 0)
        for (p in parts) {
            ex.responseBody.write(p.toByteArray())
            ex.responseBody.flush()
            Thread.sleep(pauseMs)
        }
        ex.responseBody.close()
    }

    @Test fun `sse stream with events split mid event across chunks`() {
        server.on("/sse") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            slowWrite(ex, listOf("id: 1\nevent: a\nda", "ta: one\n\nda", "ta: two\ndata: lines\n\n", ": comment\n\n", "data: three\n\n"), 40)
        }
        val (call, c) = stream("/sse")
        val end = call.finished.get(10, TimeUnit.SECONDS)
        assertTrue(end is StreamEvent.Completed)
        val head = c.events.first() as StreamEvent.HeadersReceived
        assertEquals(ResponseMode.SSE, head.mode)
        assertEquals("HTTP/1.1", head.protocol)
        val sse = c.events.filterIsInstance<StreamEvent.SseEvent>()
        assertEquals(listOf("one", "two\nlines", "three"), sse.map { it.data })
        assertEquals("a", sse[0].event)
        assertEquals((end as StreamEvent.Completed).summary.events, 3L)
    }

    @Test fun `ndjson stream delivers lines`() {
        server.on("/nd") { ex ->
            ex.responseHeaders.add("Content-Type", "application/x-ndjson")
            slowWrite(ex, listOf("{\"n\":1}\n{\"n\"", ":2}\n\n", "{\"n\":3}"), 30)
        }
        val (call, c) = stream("/nd")
        call.finished.get(10, TimeUnit.SECONDS)
        assertEquals(listOf("{\"n\":1}", "{\"n\":2}", "{\"n\":3}"), c.events.filterIsInstance<StreamEvent.Line>().map { it.text })
    }

    @Test fun `chunked slow raw stream is coalesced into few batches`() {
        server.on("/raw") { ex ->
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.sendResponseHeaders(200, 0)
            repeat(400) { ex.responseBody.write(ByteArray(10) { 1 }); ex.responseBody.flush() }
            ex.responseBody.close()
        }
        val (call, c) = stream("/raw")
        val end = call.finished.get(10, TimeUnit.SECONDS) as StreamEvent.Completed
        assertEquals(4000L, end.summary.bytes)
        val total = c.events.filterIsInstance<StreamEvent.Chunk>().sumOf { it.bytes.size }
        assertEquals(4000, total)
        assertTrue("batches=${c.batches}", c.batches.size < c.events.size)
    }

    @Test fun `buffered response through stream api`() {
        val (call, c) = stream("/echo")
        call.finished.get(10, TimeUnit.SECONDS)
        val buffered = c.events.filterIsInstance<StreamEvent.BufferedResponse>().single()
        assertEquals(200, buffered.response.status)
        assertTrue(c.events.last() is StreamEvent.Completed)
    }

    @Test fun `forced streaming of a json response yields raw chunks`() {
        val (call, c) = stream("/echo", StreamOptions(mode = ModeOverride.FORCE_STREAM))
        call.finished.get(10, TimeUnit.SECONDS)
        assertTrue(c.events.filterIsInstance<StreamEvent.Chunk>().isNotEmpty())
    }

    @Test fun `never ending stream is cancelled and the connection is closed promptly`() {
        server.on("/forever") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            while (true) {
                ex.responseBody.write("data: tick\n\n".toByteArray())
                ex.responseBody.flush()
                Thread.sleep(20)
            }
        }
        val (call, c) = stream("/forever")
        Thread.sleep(400)
        assertTrue(c.events.filterIsInstance<StreamEvent.SseEvent>().isNotEmpty())
        val t = System.nanoTime()
        call.cancel()
        val end = call.finished.get(5, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(ErrorKind.CANCELLED, (end as StreamEvent.Failed).error.kind)
        assertTrue("cancel took $ms ms", ms < 1_000)
        // The server side notices that the client is gone (its write fails).
        val deadline = System.nanoTime() + 5_000_000_000L
        while (server.clientGone.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue("server never saw the connection close", server.clientGone.get() > 0)
    }

    @Test fun `idle timeout ends a silent stream but not a slow one`() {
        server.on("/silent") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("data: first\n\n".toByteArray())
            ex.responseBody.flush()
            Thread.sleep(5_000)
        }
        val (call, c) = stream("/silent", StreamOptions(idleTimeoutMs = 400))
        val end = call.finished.get(10, TimeUnit.SECONDS)
        assertEquals(ErrorKind.TIMEOUT_IDLE, (end as StreamEvent.Failed).error.kind)
        assertEquals(1, c.events.filterIsInstance<StreamEvent.SseEvent>().size)

        server.on("/slowok") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            slowWrite(ex, List(8) { "data: $it\n\n" }, 150)
        }
        val (call2, _) = stream("/slowok", StreamOptions(idleTimeoutMs = 600))
        assertTrue(call2.finished.get(10, TimeUnit.SECONDS) is StreamEvent.Completed)
    }

    @Test fun `stream is not bound by the total deadline but by max duration`() {
        server.on("/forever") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            while (true) {
                ex.responseBody.write("data: t\n\n".toByteArray())
                ex.responseBody.flush()
                Thread.sleep(50)
            }
        }
        val (call, _) = stream("/forever", StreamOptions(maxDurationMs = 600), SendOptions(totalTimeoutMs = 200))
        val end = call.finished.get(10, TimeUnit.SECONDS)
        assertEquals(ErrorKind.TIMEOUT_TOTAL, (end as StreamEvent.Failed).error.kind)
    }

    @Test fun `huge stream is consumed in bounded memory`() {
        val totalBytes = 150L * 1024 * 1024
        server.on("/huge") { ex ->
            ex.responseHeaders.add("Content-Type", "application/x-ndjson")
            ex.sendResponseHeaders(200, 0)
            val line = ("{\"v\":\"" + "x".repeat(1000) + "\"}\n").toByteArray()
            var sent = 0L
            while (sent < totalBytes) {
                ex.responseBody.write(line)
                sent += line.size
            }
            ex.responseBody.close()
        }
        // The consumer keeps only a bounded log, as the UI does.
        val log = BoundedLog<String>(maxBytes = 1_000_000, maxItems = 1_000) { it.length }
        val counted = java.util.concurrent.atomic.AtomicLong()
        val listener = StreamListener { batch ->
            for (e in batch) if (e is StreamEvent.Line) {
                counted.incrementAndGet()
                synchronized(log) { log.add(e.text) }
            }
        }
        // warm up, then measure growth over the big stream
        engine.stream(RequestSpec(HttpMethod.GET, server.url("/echo")), SendOptions(), StreamOptions(), listener).finished.get(10, TimeUnit.SECONDS)
        val before = LeakProbe.usedHeapBytes()
        val end = engine.stream(RequestSpec(HttpMethod.GET, server.url("/huge")), SendOptions(), StreamOptions(), listener).finished.get(120, TimeUnit.SECONDS)
        assertTrue(end is StreamEvent.Completed)
        assertTrue((end as StreamEvent.Completed).summary.bytes >= totalBytes)
        val after = LeakProbe.usedHeapBytes()
        assertTrue("log kept ${log.size} items, dropped ${log.droppedItems}", log.droppedItems > 0)
        // 150 MB went through; retained growth stays far below it.
        val growth = after - before
        assertTrue("retained heap grew by $growth bytes", growth < 20L * 1024 * 1024)
    }
}
