package com.routegenie.ui.request

import com.routegenie.client.ResponseBody
import com.routegenie.client.ResponseData
import com.routegenie.client.ResponseMode
import com.routegenie.client.SpillDirectory
import com.routegenie.client.StreamEvent
import com.routegenie.client.StreamSummary
import com.routegenie.clientide.SecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** A credential store that lives in a map; records every write so tests can see what was (not) stored. */
class MapSecretStore : SecretStore {
    val values = ConcurrentHashMap<String, String>()
    private fun id(scope: String, key: String) = "$scope|$key"
    override fun get(scope: String, key: String): String? = values[id(scope, key)]
    override fun set(scope: String, key: String, value: String?) {
        if (value == null) values.remove(id(scope, key)) else values[id(scope, key)] = value
    }
}

/** Runs background work at once on the calling thread, so tests need no waiting. */
val directExecutor: (Runnable) -> Unit = { it.run() }

/** A spill folder in the system temp area that tests can inspect and delete. */
class TestSpill : AutoCloseable {
    val dir: Path = Files.createTempDirectory("routegenie-test-spill")
    val spill = SpillDirectory(dir)

    /** A JSON response whose complete body is in a spill file while only [headBytes] of it are in memory. */
    fun spilled(text: String, headBytes: Int = 64): ResponseData {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val file = spill.create()
        Files.write(file, bytes)
        val body = ResponseBody(bytes.copyOf(minOf(headBytes, bytes.size)), file, bytes.size.toLong(), false, spill)
        return response(body)
    }

    fun liveFiles(): List<Path> = Files.newDirectoryStream(dir).use { it.toList() }

    override fun close() {
        spill.deleteAll()
        runCatching { Files.deleteIfExists(dir) }
    }
}

fun response(body: ResponseBody, contentType: String = "application/json"): ResponseData = ResponseData(
    200, "OK", listOf("content-type" to contentType), body, 5, 2, "http://127.0.0.1:8000/x", emptyList(), "HTTP/1.1",
)

fun jsonResponse(text: String): ResponseData = response(ResponseBody.inMemory(text.toByteArray(Charsets.UTF_8)))

fun textResponse(text: String): ResponseData = response(ResponseBody.inMemory(text.toByteArray(Charsets.UTF_8)), "text/plain")

/** What the engine delivers for a buffered answer; feed it to FakeEngine.emit and then run the UI queue. */
fun bufferedEvents(r: ResponseData): Array<StreamEvent> = arrayOf(
    StreamEvent.HeadersReceived(r.status, r.reason, r.headers, "HTTP/1.1", ResponseMode.BUFFERED, r.finalUrl),
    StreamEvent.BufferedResponse(r),
    StreamEvent.Completed(StreamSummary(r.bodySize, 0, 5)),
)
