package com.routegenie.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** A request as the server saw it: what really arrived on the wire. */
class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray, val protocol: String) {
    val text: String get() = String(body, Charsets.UTF_8)
}

/** JDK HttpServer on the loopback interface and a random port; the engine tests talk to it. */
class LocalServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "test-server").apply { isDaemon = true } }
    val seen = CopyOnWriteArrayList<Seen>()
    val active = AtomicInteger()
    val port: Int get() = server.address.port

    /** Set when a handler noticed that the client went away. */
    val clientGone = AtomicInteger()

    init {
        server.executor = pool
        server.createContext("/") { ex -> dispatch(ex) }
        server.start()
    }

    fun url(path: String = "/") = "http://127.0.0.1:$port$path"

    private val routes = HashMap<String, (HttpExchange) -> Unit>()

    fun on(path: String, handler: (HttpExchange) -> Unit) {
        routes[path] = handler
    }

    private fun dispatch(ex: HttpExchange) {
        active.incrementAndGet()
        try {
            val body = ex.requestBody.readAllBytes()
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(", ") }
            seen += Seen(ex.requestMethod, ex.requestURI.toString(), headers, body, ex.protocol)
            val handler = routes[ex.requestURI.path]
            if (handler == null) {
                respond(ex, 404, "not found")
            } else {
                handler(ex)
            }
        } catch (e: IOException) {
            clientGone.incrementAndGet()
        } finally {
            ex.close()
            active.decrementAndGet()
        }
    }

    init {
        on("/echo") { ex ->
            val last = seen.last()
            respond(ex, 200, "{\"method\":\"${last.method}\",\"protocol\":\"${last.protocol}\"}", "application/json")
        }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
        pool.awaitTermination(2, TimeUnit.SECONDS)
    }

    companion object {
        fun respond(ex: HttpExchange, status: Int, text: String, type: String = "text/plain") {
            val bytes = text.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        fun redirect(ex: HttpExchange, status: Int, location: String) {
            ex.responseHeaders.add("Location", location)
            ex.sendResponseHeaders(status, -1)
        }

        fun gzip(bytes: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        /** Deterministic content so a test can verify every byte without keeping the body in memory. */
        fun patternByte(index: Long): Int = (index % PATTERN_MOD).toInt()

        private const val PATTERN_MOD = 251L
    }
}
