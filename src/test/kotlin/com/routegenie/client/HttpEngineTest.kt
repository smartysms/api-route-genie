package com.routegenie.client

import com.routegenie.model.HttpMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class HttpEngineTest {
    private lateinit var server: LocalServer
    private lateinit var other: LocalServer
    private lateinit var spillDir: java.nio.file.Path
    private lateinit var engine: JdkHttpEngine

    @Before
    fun setUp() {
        server = LocalServer()
        other = LocalServer()
        spillDir = Files.createTempDirectory("rg-test-spill")
        engine = JdkHttpEngine(spill = SpillDirectory(spillDir))
    }

    @After
    fun tearDown() {
        engine.close()
        server.close()
        other.close()
        spillDir.toFile().deleteRecursively()
    }

    private fun send(spec: RequestSpec, options: SendOptions = SendOptions()): ResponseData =
        engine.execute(spec, options).result.get(30, TimeUnit.SECONDS)

    private fun get(path: String, options: SendOptions = SendOptions()) = send(RequestSpec(HttpMethod.GET, server.url(path)), options)

    @Test
    fun `every verb arrives as itself and patch is not post`() {
        for (m in listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS)) {
            val r = send(RequestSpec(m, server.url("/echo"), body = if (m == HttpMethod.GET || m == HttpMethod.OPTIONS) RequestBody.None else RequestBody.Text("text/plain", "x")))
            assertEquals(m.name, 200, r.status)
            assertEquals(m.name, server.seen.last().method)
            assertTrue(r.bodyText().contains("\"method\":\"${m.name}\""))
        }
    }

    @Test
    fun `head returns no body`() {
        server.on("/h") { ex -> ex.sendResponseHeaders(200, -1) }
        val r = send(RequestSpec(HttpMethod.HEAD, server.url("/h")))
        assertEquals(200, r.status)
        assertEquals(0, r.body.size)
    }

    @Test
    fun `http scheme uses HTTP 1 1 without upgrade headers`() {
        val r = get("/echo")
        assertEquals("HTTP/1.1", r.httpVersion)
        val seen = server.seen.last()
        assertFalse(seen.headers.containsKey("upgrade"))
        assertFalse(seen.headers.containsKey("http2-settings"))
    }

    @Test
    fun `json body and content type arrive`() {
        send(RequestSpec(HttpMethod.POST, server.url("/echo"), body = RequestBody.Text("application/json", "{\"a\":1}")))
        val s = server.seen.last()
        assertEquals("{\"a\":1}", s.text)
        assertEquals("application/json", s.headers["content-type"])
    }

    @Test
    fun `user content type wins over the body type`() {
        send(RequestSpec(HttpMethod.POST, server.url("/echo"), listOf(Header("Content-Type", "application/vnd.x+json")), RequestBody.Text("application/json", "{}")))
        assertEquals("application/vnd.x+json", server.seen.last().headers["content-type"])
    }

    @Test
    fun `form body is urlencoded`() {
        send(RequestSpec(HttpMethod.POST, server.url("/echo"), body = RequestBody.Form(listOf("a" to "x y", "b" to "&="))))
        val s = server.seen.last()
        assertEquals("a=x+y&b=%26%3D", s.text)
        assertEquals("application/x-www-form-urlencoded", s.headers["content-type"])
    }

    @Test
    fun `multipart carries text and file parts with a boundary`() {
        val f = File.createTempFile("rg-part", ".txt").apply { writeText("FILEDATA"); deleteOnExit() }
        send(
            RequestSpec(
                HttpMethod.POST, server.url("/echo"),
                body = RequestBody.Multipart(listOf(MultipartPart("title", value = "hi"), MultipartPart("file", filePath = f.path, fileName = "a\"b.txt", contentType = "text/plain"))),
            ),
        )
        val s = server.seen.last()
        val type = s.headers.getValue("content-type")
        assertTrue(type.startsWith("multipart/form-data; boundary="))
        val boundary = type.substringAfter("boundary=")
        val text = s.text
        assertTrue(text.startsWith("--$boundary\r\n"))
        assertTrue(text.contains("name=\"title\"\r\n\r\nhi\r\n"))
        assertTrue(text.contains("filename=\"a%22b.txt\""))
        assertTrue(text.contains("FILEDATA"))
        assertTrue(text.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun `missing multipart file is an invalid request not a crash`() {
        val r = send(RequestSpec(HttpMethod.POST, server.url("/echo"), body = RequestBody.Multipart(listOf(MultipartPart("f", filePath = "/no/such/file")))))
        assertEquals(ErrorKind.INVALID_REQUEST, r.error?.kind)
    }

    @Test
    fun `restricted and malformed headers are refused`() {
        assertEquals(ErrorKind.INVALID_REQUEST, send(RequestSpec(HttpMethod.GET, server.url("/echo"), listOf(Header("Host", "evil")))).error?.kind)
        assertEquals(ErrorKind.INVALID_REQUEST, send(RequestSpec(HttpMethod.GET, server.url("/echo"), listOf(Header("A B", "x")))).error?.kind)
        assertEquals(ErrorKind.INVALID_REQUEST, send(RequestSpec(HttpMethod.GET, server.url("/echo"), listOf(Header("A", "x\ny")))).error?.kind)
    }

    @Test
    fun `same origin redirects follow with method rules`() {
        server.on("/r301") { LocalServer.redirect(it, 301, "/echo") }
        server.on("/r303") { LocalServer.redirect(it, 303, "/echo") }
        server.on("/r307") { LocalServer.redirect(it, 307, "/echo") }
        server.on("/r308") { LocalServer.redirect(it, 308, "/echo") }
        fun finalMethod(path: String, m: HttpMethod): String {
            server.seen.clear()
            val r = send(RequestSpec(m, server.url(path), body = RequestBody.Text("text/plain", "body")))
            assertEquals(200, r.status)
            assertEquals(1, r.redirects.size)
            return server.seen.last().method + ":" + server.seen.last().text
        }
        assertEquals("GET:", finalMethod("/r301", HttpMethod.POST))
        assertEquals("GET:", finalMethod("/r303", HttpMethod.PUT))
        assertEquals("POST:body", finalMethod("/r307", HttpMethod.POST))
        assertEquals("PATCH:body", finalMethod("/r308", HttpMethod.PATCH))
        assertEquals("PUT:body", finalMethod("/r301", HttpMethod.PUT))
    }

    @Test
    fun `redirect chain and final url are reported`() {
        server.on("/a") { LocalServer.redirect(it, 302, "/b") }
        server.on("/b") { LocalServer.redirect(it, 302, "/echo") }
        val r = get("/a")
        assertEquals(2, r.redirects.size)
        assertEquals(server.url("/echo"), r.finalUrl)
    }

    @Test
    fun `redirect loop ends with too many redirects`() {
        server.on("/loop") { LocalServer.redirect(it, 302, "/loop") }
        val r = get("/loop", SendOptions(maxRedirects = 3))
        assertEquals(ErrorKind.TOO_MANY_REDIRECTS, r.error?.kind)
    }

    @Test
    fun `follow redirects off returns the 3xx`() {
        server.on("/a") { LocalServer.redirect(it, 302, "/echo") }
        val r = get("/a", SendOptions(followRedirects = false))
        assertEquals(302, r.status)
        assertEquals("/echo", r.header("location"))
    }

    @Test
    fun `cross origin redirect strips authorization cookie and custom auth headers`() {
        server.on("/x") { LocalServer.redirect(it, 302, other.url("/echo")) }
        val spec = RequestSpec(
            HttpMethod.GET, server.url("/x"),
            listOf(Header("Authorization", "Bearer T"), Header("Cookie", "s=1"), Header("X-Api-Key", "K"), Header("X-My-Secret", "M"), Header("X-Keep", "yes")),
        )
        val r = send(spec, SendOptions(sensitiveHeaders = setOf("x-my-secret")))
        assertEquals(200, r.status)
        val first = server.seen.first()
        assertEquals("Bearer T", first.headers["authorization"])
        val arrived = other.seen.single()
        assertNull(arrived.headers["authorization"])
        assertNull(arrived.headers["cookie"])
        assertNull(arrived.headers["x-api-key"])
        assertNull(arrived.headers["x-my-secret"])
        assertEquals("yes", arrived.headers["x-keep"])
    }

    @Test
    fun `same origin redirect keeps credentials`() {
        server.on("/a") { LocalServer.redirect(it, 302, "/echo") }
        send(RequestSpec(HttpMethod.GET, server.url("/a"), listOf(Header("Authorization", "Bearer T"))))
        assertEquals("Bearer T", server.seen.last().headers["authorization"])
    }

    @Test
    fun `cross origin stop policy returns the redirect`() {
        server.on("/x") { LocalServer.redirect(it, 302, other.url("/echo")) }
        val r = get("/x", SendOptions(crossOrigin = CrossOriginRedirect.STOP))
        assertEquals(302, r.status)
        assertTrue(other.seen.isEmpty())
    }

    @Test
    fun `redirect to a host that is not allowed is blocked`() {
        server.on("/x") { LocalServer.redirect(it, 302, other.url("/echo")) }
        val r = get("/x", SendOptions(redirectHostAllowed = { false }))
        assertEquals(ErrorKind.REDIRECT_BLOCKED, r.error?.kind)
        assertEquals(302, r.status)
        assertTrue(other.seen.isEmpty())
    }

    @Test
    fun `gzip and deflate bodies are decoded`() {
        server.on("/gz") { ex ->
            val data = LocalServer.gzip("hello gzip".toByteArray())
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
        server.on("/df") { ex ->
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(out).use { it.write("hello zlib".toByteArray()) }
            ex.responseHeaders.add("Content-Encoding", "deflate")
            ex.sendResponseHeaders(200, out.size().toLong())
            ex.responseBody.use { it.write(out.toByteArray()) }
        }
        assertEquals("hello gzip", get("/gz").bodyText())
        assertEquals("hello zlib", get("/df").bodyText())
        assertEquals("gzip, deflate", server.seen.first().headers["accept-encoding"])
    }

    @Test
    fun `empty gzip response does not fail`() {
        server.on("/e") { ex ->
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(204, -1)
        }
        assertEquals(204, get("/e").status)
    }

    @Test
    fun `headers timeout fires against a server that never answers`() {
        server.on("/slow") { Thread.sleep(3_000) }
        val t = System.nanoTime()
        val r = get("/slow", SendOptions(headersTimeoutMs = 300))
        assertEquals(ErrorKind.TIMEOUT_HEADERS, r.error?.kind)
        assertTrue(System.nanoTime() - t < 2_500_000_000L)
    }

    @Test
    fun `total deadline bounds a slowly dripping body`() {
        server.on("/drip") { ex ->
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            repeat(100) {
                out.write('x'.code)
                out.flush()
                Thread.sleep(100)
            }
        }
        val t = System.nanoTime()
        val r = get("/drip", SendOptions(totalTimeoutMs = 700))
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(ErrorKind.TIMEOUT_TOTAL, r.error?.kind)
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test
    fun `cancel mid flight ends promptly`() {
        server.on("/hang") { Thread.sleep(10_000) }
        val call = engine.execute(RequestSpec(HttpMethod.GET, server.url("/hang")), SendOptions())
        Thread.sleep(300)
        val t = System.nanoTime()
        call.cancel()
        val r = call.result.get(5, TimeUnit.SECONDS)
        assertEquals(ErrorKind.CANCELLED, r.error?.kind)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 1_000)
    }

    @Test
    fun `cancel during a body download ends promptly`() {
        server.on("/drip") { ex ->
            ex.sendResponseHeaders(200, 0)
            repeat(200) { ex.responseBody.write('x'.code); ex.responseBody.flush(); Thread.sleep(50) }
        }
        val call = engine.execute(RequestSpec(HttpMethod.GET, server.url("/drip")), SendOptions())
        Thread.sleep(400)
        val t = System.nanoTime()
        call.cancel()
        assertEquals(ErrorKind.CANCELLED, call.result.get(5, TimeUnit.SECONDS).error?.kind)
        assertTrue((System.nanoTime() - t) / 1_000_000 < 1_000)
    }

    @Test
    fun `oversize body keeps the head and flags the cut`() {
        server.on("/big") { ex ->
            ex.sendResponseHeaders(200, 0)
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            repeat(100) { ex.responseBody.write(chunk) }
            ex.responseBody.close()
        }
        val r = get("/big", SendOptions(displayCapBytes = 100_000))
        assertEquals(100_000, r.body.size)
        assertTrue(r.truncated)
        assertEquals(100L * 64 * 1024, r.bodySize)
        assertFalse(r.bodyTruncatedAtLimit)
        assertNotNull(r.bodyFile)
        r.release()
    }

    @Test
    fun `connection refused is mapped`() {
        val dead = LocalServer().also { it.close() }
        val r = send(RequestSpec(HttpMethod.GET, dead.url("/")))
        assertEquals(ErrorKind.CONNECTION_REFUSED, r.error?.kind)
    }

    @Test
    fun `unknown host is mapped to dns`() {
        val r = send(RequestSpec(HttpMethod.GET, "http://no-such-host.invalid/"))
        assertEquals(ErrorKind.DNS, r.error?.kind)
    }

    @Test
    fun `invalid urls are reported before sending`() {
        for (u in listOf("localhost:8000/x", "http://my_service:8000/x", "ftp://x/", "http://", "http://user:pw@host/")) {
            assertEquals(u, ErrorKind.INVALID_URL, send(RequestSpec(HttpMethod.GET, u)).error?.kind)
        }
    }

    @Test
    fun `raw spaces and braces in the url are encoded not rejected`() {
        server.on("/p q") { LocalServer.respond(it, 200, "ok") }
        val r = send(RequestSpec(HttpMethod.GET, server.url("/echo?f={\"a\":1}&s=x y")))
        assertEquals(200, r.status)
        assertTrue(server.seen.last().path.contains("%7B"))
    }

    @Test
    fun `insecure tls is refused for a remote host`() {
        val r = send(RequestSpec(HttpMethod.GET, "https://example.com/"), SendOptions(tls = TlsMode.INSECURE))
        assertEquals(ErrorKind.INSECURE_NOT_ALLOWED, r.error?.kind)
    }

    @Test
    fun `user agent and compressed toggle`() {
        JdkHttpEngine(spill = SpillDirectory(spillDir), userAgent = "RG-Test").use { e ->
            e.execute(RequestSpec(HttpMethod.GET, server.url("/echo")), SendOptions(compressed = false)).result.get(10, TimeUnit.SECONDS)
        }
        val s = server.seen.last()
        assertEquals("RG-Test", s.headers["user-agent"])
        assertNull(s.headers["accept-encoding"])
    }

    @Test
    fun `execute after close fails as cancelled`() {
        engine.close()
        assertEquals(ErrorKind.CANCELLED, get("/echo").error?.kind)
    }
}
