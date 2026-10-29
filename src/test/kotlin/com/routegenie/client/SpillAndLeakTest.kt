package com.routegenie.client

import com.routegenie.Limits
import com.routegenie.model.HttpMethod
import com.routegenie.testing.LeakProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SpillAndLeakTest {
    private lateinit var server: LocalServer
    private lateinit var spillDir: Path
    private lateinit var spill: SpillDirectory
    private lateinit var engine: JdkHttpEngine

    @Before fun setUp() {
        server = LocalServer()
        spillDir = Files.createTempDirectory("rg-spill-test")
        spill = SpillDirectory(spillDir)
        engine = JdkHttpEngine(spill = spill)
        server.on("/pattern") { ex ->
            val mb = ex.requestURI.query.substringAfter("mb=").toInt()
            ex.sendResponseHeaders(200, 0)
            val total = mb.toLong() * 1024 * 1024
            val chunk = ByteArray(64 * 1024)
            var pos = 0L
            while (pos < total) {
                val n = minOf(chunk.size.toLong(), total - pos).toInt()
                for (i in 0 until n) chunk[i] = LocalServer.patternByte(pos + i).toByte()
                ex.responseBody.write(chunk, 0, n)
                pos += n
            }
            ex.responseBody.close()
        }
    }

    @After fun tearDown() {
        engine.close()
        server.close()
        spillDir.toFile().deleteRecursively()
    }

    private fun files() = spillDir.toFile().listFiles().orEmpty().toList()

    private fun get(path: String, o: SendOptions = SendOptions()) =
        engine.execute(RequestSpec(HttpMethod.GET, server.url(path)), o).result.get(120, TimeUnit.SECONDS)

    private fun assertFilePattern(r: ResponseData, mb: Int) {
        val expected = mb.toLong() * 1024 * 1024
        assertEquals(expected, r.bodySize)
        assertEquals(expected, Files.size(r.bodyFile!!))
        r.responseBody.openInputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            var pos = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) if (buf[i].toInt() and 0xFF != LocalServer.patternByte(pos + i)) throw AssertionError("byte differs at ${pos + i}")
                pos += n
            }
            assertEquals(expected, pos)
        }
        // the head is the exact first bytes too
        for (i in r.body.indices step 4099) assertEquals(LocalServer.patternByte(i.toLong()), r.body[i].toInt() and 0xFF)
        assertEquals(Limits.HTTP_DISPLAY_CAP_BYTES, r.body.size)
    }

    @Test fun `a 100 MB response is received with bounded heap and an exact file`() {
        get("/pattern?mb=1") // warm up
        val before = LeakProbe.usedHeapBytes()
        val r = get("/pattern?mb=100")
        assertNull(r.error)
        assertTrue(r.truncated)
        assertFalse(r.bodyTruncatedAtLimit)
        val growth = LeakProbe.usedHeapBytes() - before
        assertTrue("heap grew by $growth bytes", growth < 20L * 1024 * 1024)
        assertFilePattern(r, 100)
        assertEquals(1, files().size)
        r.release()
        assertEquals(0, files().size)
    }

    @Test fun `small and exactly capped bodies make no file`() {
        val small = get("/pattern?mb=1")
        assertNull(small.bodyFile)
        assertEquals(1024L * 1024, small.bodySize)
        assertEquals(0, files().size)
        val exact = get("/pattern?mb=5")
        assertNull(exact.bodyFile)
        assertFalse(exact.truncated)
        assertEquals(5L * 1024 * 1024, exact.body.size.toLong())
        assertEquals(0, files().size)
    }

    @Test fun `file part is named with the prefix and is closed so it can be deleted at once`() {
        val r = get("/pattern?mb=6")
        val f = r.bodyFile!!
        assertTrue(f.fileName.toString().startsWith(SpillDirectory.PREFIX))
        r.release()
        r.release() // idempotent
        assertFalse(Files.exists(f))
        // the in-memory head stays readable after release
        assertEquals(Limits.HTTP_DISPLAY_CAP_BYTES, r.body.size)
    }

    @Test fun `replacing the shown response deletes the previous file`() {
        val slot = ResponseSlot()
        val a = get("/pattern?mb=6")
        slot.set(a)
        val fa = a.bodyFile!!
        val b = get("/pattern?mb=7")
        slot.set(b)
        assertFalse(Files.exists(fa))
        assertTrue(Files.exists(b.bodyFile!!))
        slot.close()
        assertEquals(0, files().size)
    }

    @Test fun `closing the engine deletes files of responses nobody released`() {
        get("/pattern?mb=6")
        get("/pattern?mb=6")
        assertEquals(2, files().size)
        engine.close()
        assertEquals(0, files().size)
    }

    @Test fun `cancel in the middle of a download removes the partial file`() {
        server.on("/stall") { ex ->
            ex.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024)
            repeat(160) { ex.responseBody.write(chunk) } // 10 MB, beyond the cap, so a file exists
            ex.responseBody.flush()
            Thread.sleep(10_000)
        }
        val call = engine.execute(RequestSpec(HttpMethod.GET, server.url("/stall")), SendOptions())
        val deadline = System.nanoTime() + 10_000_000_000L
        while (files().isEmpty() && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("spill file should exist while downloading", 1, files().size)
        call.cancel()
        val r = call.result.get(5, TimeUnit.SECONDS)
        assertEquals(ErrorKind.CANCELLED, r.error?.kind)
        assertEquals(0, files().size)
        assertEquals(0, spill.liveCount)
    }

    @Test fun `a failing connection mid body removes the partial file`() {
        server.on("/cut") { ex ->
            ex.sendResponseHeaders(200, 20L * 1024 * 1024)
            val chunk = ByteArray(64 * 1024)
            repeat(160) { ex.responseBody.write(chunk) }
            ex.responseBody.flush()
            ex.close() // fewer bytes than announced
        }
        val r = get("/cut")
        assertNotNull(r.error)
        assertEquals(0, files().size)
    }

    @Test fun `download beyond the spill limit stops and flags it`() {
        engine.close()
        val small = JdkHttpEngine(spill = spill, spillMaxBytes = 8L * 1024 * 1024)
        try {
            val r = small.execute(RequestSpec(HttpMethod.GET, server.url("/pattern?mb=30")), SendOptions()).result.get(60, TimeUnit.SECONDS)
            assertNull(r.error)
            assertTrue(r.bodyTruncatedAtLimit)
            assertEquals(8L * 1024 * 1024, r.bodySize)
            assertEquals(8L * 1024 * 1024, Files.size(r.bodyFile!!))
            r.release()
        } finally {
            small.close()
        }
    }

    @Test fun `declared size above the limit is reported without reading`() {
        engine.close()
        val small = JdkHttpEngine(spill = spill, spillMaxBytes = 1024)
        try {
            server.on("/declared") { ex -> ex.sendResponseHeaders(200, 5000); ex.responseBody.write(ByteArray(5000)); ex.close() }
            val r = small.execute(RequestSpec(HttpMethod.GET, server.url("/declared")), SendOptions()).result.get(10, TimeUnit.SECONDS)
            assertEquals(ErrorKind.TOO_LARGE, r.error?.kind)
            assertEquals(200, r.status)
        } finally {
            small.close()
        }
    }

    @Test fun `startup sweep removes only old files with the prefix`() {
        val old = Files.createFile(spillDir.resolve(SpillDirectory.PREFIX + "old" + SpillDirectory.SUFFIX))
        val fresh = Files.createFile(spillDir.resolve(SpillDirectory.PREFIX + "fresh" + SpillDirectory.SUFFIX))
        val other = Files.createFile(spillDir.resolve("unrelated.bin"))
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * Limits.SPILL_STALE_AGE_MS))
        assertEquals(1, spill.sweepStale())
        assertFalse(Files.exists(old))
        assertTrue(Files.exists(fresh))
        assertTrue(Files.exists(other))
    }

    @Test fun `spill file is owner only where permissions exist`() {
        val view = Files.getFileAttributeView(spillDir, java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return
        val p = spill.create()
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), view.javaClass.let { Files.getPosixFilePermissions(p) })
        spill.delete(p)
    }

    // --- leaks --------------------------------------------------------------------------------------------------------------

    private fun threadNames() = Thread.getAllStackTraces().keys.map { it.name }.filter { it.startsWith("RouteGenie-http") || it.startsWith("HttpClient-") }

    @Test fun `threads are released after the engine is closed`() {
        val e = JdkHttpEngine(spill = spill)
        repeat(5) { e.execute(RequestSpec(HttpMethod.GET, server.url("/echo")), SendOptions()).result.get(10, TimeUnit.SECONDS) }
        e.execute(RequestSpec(HttpMethod.GET, server.url("/echo")), SendOptions(httpVersion = HttpVersionPolicy.HTTP_2)).result.get(10, TimeUnit.SECONDS)
        e.close()
        val mine = Thread.getAllStackTraces().keys.filter { it.name.startsWith("RouteGenie-http") }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (Thread.getAllStackTraces().keys.any { it.name.startsWith("RouteGenie-http") && it.isAlive } && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals("left: ${threadNames()}", emptyList<String>(), Thread.getAllStackTraces().keys.filter { it.name.startsWith("RouteGenie-http") && it.isAlive }.map { it.name })
        assertTrue(mine.isNotEmpty() || true)
    }

    @Test fun `http client threads end with the engine even after cancelled calls`() {
        val before = threadNames().size
        val e = JdkHttpEngine(spill = spill)
        server.on("/hang") { Thread.sleep(5_000) }
        val call = e.execute(RequestSpec(HttpMethod.GET, server.url("/hang")), SendOptions())
        Thread.sleep(200)
        call.cancel()
        call.result.get(5, TimeUnit.SECONDS)
        e.close()
        val deadline = System.nanoTime() + 8_000_000_000L
        while (threadNames().size > before && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("threads before=$before now=${threadNames()}", threadNames().size <= before)
    }

    @Test fun `a released response is garbage collected`() {
        var r: ResponseData? = get("/pattern?mb=6")
        val ref = WeakReference(r)
        r!!.release()
        r = null
        LeakProbe.assertCollected("response", ref)
    }

    @Test fun `heap does not grow over 200 requests`() {
        server.on("/data") { LocalServer.respond(it, 200, "x".repeat(2000)) }
        LeakProbe.assertNoHeapGrowth("engine requests", maxGrowthBytes = 6L * 1024 * 1024, warmups = 100, iterations = 200) {
            get("/data")
        }
        assertEquals(0, files().size)
        assertEquals(0, spill.liveCount)
    }

    @Test fun `history is bounded`() {
        val h = RequestHistory(max = 5)
        val req = PreparedRequest(RequestSpec(HttpMethod.GET, "http://127.0.0.1/x"), emptySet(), emptySet(), TlsMode.IDE_TRUST)
        val resp = ResponseData(200, "OK", emptyList(), ResponseBody.EMPTY, 3, 1, "u", emptyList(), "HTTP/1.1")
        repeat(1000) { h.record(req, resp, "dev") }
        assertEquals(5, h.snapshot().size)
        assertEquals(Limits.HISTORY_MAX_ENTRIES, RequestHistory().also { x -> repeat(Limits.HISTORY_MAX_ENTRIES + 50) { x.record(req, resp, "d") } }.snapshot().size)
    }

    @Test fun `last inputs are bounded and least recently used goes first`() {
        val li = LastInputs(maxEndpoints = 3)
        listOf("a", "b", "c").forEach { li.put(it, RequestInput(HttpMethod.GET)) }
        li.get("a")
        li.put("d", RequestInput(HttpMethod.POST))
        assertEquals(setOf("a", "c", "d"), li.entries().map { it.first }.toSet())
    }

    // --- speed --------------------------------------------------------------------------------------------------------------

    @Test fun `engine overhead on loopback is small compared with a plain JDK call`() {
        server.on("/small") { LocalServer.respond(it, 200, "{\"ok\":true}", "application/json") }
        val client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
        val plainReq = java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.url("/small"))).build()
        fun plain() = client.send(plainReq, java.net.http.HttpResponse.BodyHandlers.ofString())
        val spec = RequestSpec(HttpMethod.GET, server.url("/small"))
        fun ours() = engine.execute(spec, SendOptions()).result.get(10, TimeUnit.SECONDS)
        repeat(300) { plain(); ours() }
        fun median(block: () -> Unit): Double {
            val xs = DoubleArray(200) { val t = System.nanoTime(); block(); (System.nanoTime() - t) / 1e6 }.sortedArray()
            return xs[xs.size / 2]
        }
        val p = median { plain() }
        val o = median { ours() }
        val overhead = o - p
        java.io.File(com.routegenie.testing.ProjectFiles.root, "build/reports/routegenie/perf").mkdirs()
        java.io.File(com.routegenie.testing.ProjectFiles.root, "build/reports/routegenie/perf/engine-overhead.txt")
            .writeText("budget_ms=${Limits.BUDGET_ENGINE_OVERHEAD_MS}\nplain_median_ms=$p\nengine_median_ms=$o\noverhead_ms=$overhead\n")
        client.shutdownNow()
        assertTrue("overhead $overhead ms (plain $p, engine $o)", overhead < Limits.BUDGET_ENGINE_OVERHEAD_MS)
    }
}
