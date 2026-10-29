package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.BodyTemplate
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.EngineError
import com.routegenie.client.Environment
import com.routegenie.client.ErrorKind
import com.routegenie.client.FieldLocation
import com.routegenie.client.HostGuard
import com.routegenie.client.HttpCall
import com.routegenie.client.HttpEngine
import com.routegenie.client.ModeOverride
import com.routegenie.client.ProgressListener
import com.routegenie.client.RequestSpec
import com.routegenie.client.ResponseMode
import com.routegenie.client.SendOptions
import com.routegenie.client.StreamCall
import com.routegenie.client.StreamEvent
import com.routegenie.client.StreamListener
import com.routegenie.client.StreamOptions
import com.routegenie.client.StreamSummary
import com.routegenie.client.SecretLookup
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.SourceRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class FakeEngine : HttpEngine {
    var lastSpec: RequestSpec? = null
    var lastStream: StreamOptions? = null
    var listener: StreamListener? = null
    var streams = 0
    var cancelled = false

    override fun execute(spec: RequestSpec, options: SendOptions, progress: ProgressListener?): HttpCall = error("not used")

    override fun stream(spec: RequestSpec, options: SendOptions, stream: StreamOptions, listener: StreamListener): StreamCall {
        streams++
        lastSpec = spec
        lastStream = stream
        this.listener = listener
        return object : StreamCall {
            override val finished = CompletableFuture<StreamEvent>()
            override fun cancel() {
                cancelled = true
                listener.onEvents(listOf(StreamEvent.Failed(EngineError(ErrorKind.CANCELLED))))
            }
        }
    }

    fun emit(vararg e: StreamEvent) = listener!!.onEvents(e.toList())

    override fun close() = Unit
}

/** Runs everything at once; delayed tasks wait in [pending] until [runPending]. */
class ManualUi(var allowHost: Boolean = true) : UiHooks {
    val pending = ArrayList<Runnable>()
    val asked = ArrayList<String>()
    override fun post(r: Runnable) = r.run()
    override fun postDelayed(ms: Long, r: Runnable): () -> Unit { pending += r; return { pending.remove(r) } }
    override fun confirmHost(host: String): Boolean { asked += host; return allowHost }
    fun runPending() { val copy = ArrayList(pending); pending.clear(); copy.forEach { it.run() } }
}

fun endpoint(method: HttpMethod = HttpMethod.GET, path: String = "/users/{user_id}", params: List<PathParam> = listOf(PathParam("user_id", "int"))) =
    Endpoint(method, path, Framework.FASTAPI, Handler("get_user", "app.get_user"), SourceRef("app.py", 0, 1), pathParams = params)

class SendControllerTest {
    private val engine = FakeEngine()
    private val ui = ManualUi()
    private val history = HistoryLog()
    private val local = Environment("local", "http://127.0.0.1:8000")

    private fun controller() = SendController(
        { engine }, HostGuard(), SecretLookup.NONE, ui, history,
    )

    private fun headers(mode: ResponseMode, type: String) = StreamEvent.HeadersReceived(
        200, "OK", listOf("content-type" to type), "HTTP/1.1", mode, "http://127.0.0.1:8000/users/1",
    )

    @Test
    fun `an ANY endpoint defaults to GET, offers the choice and never yields ANY`() {
        val form = RequestForm(endpoint(HttpMethod.ANY, "/ping", emptyList()))
        assertTrue(form.methodIsChoosable)
        assertEquals(HttpMethod.GET, form.method)
        form.method = HttpMethod.POST
        assertEquals(HttpMethod.POST, form.toInput().method)
        assertFalse(RequestForm(endpoint(HttpMethod.PUT)).methodIsChoosable)
        assertEquals(HttpMethod.PUT, RequestForm(endpoint(HttpMethod.PUT)).method)
    }

    @Test
    fun `validation errors block the send and nothing reaches the engine`() {
        val form = RequestForm(endpoint())
        form.pathValues["user_id"] = "abc"
        val c = controller()
        val r = c.send(form.endpoint, form, local) as SendResult.Invalid
        assertTrue(r.errors.any { it.location == FieldLocation.PATH && it.name == "user_id" })
        assertEquals(0, engine.streams)
        assertFalse(c.state.running)
        c.close()
    }

    @Test
    fun `send streams SSE events, stays bounded and ends in history`() {
        val form = RequestForm(endpoint()).also { it.pathValues["user_id"] = "1" }
        val c = controller()
        val seen = ArrayList<LiveDrain?>()
        c.listener = object : SendController.Listener { override fun changed(state: ResponseState, drain: LiveDrain?) { seen += drain } }
        assertEquals(SendResult.Started, c.send(form.endpoint, form, local))
        assertEquals("http://127.0.0.1:8000/users/1", engine.lastSpec!!.url)
        assertEquals(SendResult.Busy, c.send(form.endpoint, form, local))
        assertEquals(1, engine.streams)

        engine.emit(headers(ResponseMode.SSE, "text/event-stream"))
        repeat(5_000) { engine.emit(StreamEvent.SseEvent("$it", "tick", "{\"n\":$it}", null)) }
        engine.emit(StreamEvent.Completed(StreamSummary(100, 5_000, 10)))
        var updates = 0
        var rows = 0
        while (ui.pending.isNotEmpty()) {
            ui.runPending()
            updates++
            rows += seen.filterNotNull().lastOrNull()?.rows?.size ?: 0
            if (updates > 1_000) break
        }
        assertEquals(Phase.DONE, c.state.phase)
        assertEquals(5_000L, c.state.events)
        assertTrue("batching: a few UI updates, not one per event ($updates)", updates < 50)
        assertEquals(1, history.size)
        assertEquals(200, history.all().single().status)
        c.close()
    }

    @Test
    fun `cancel ends the stream as cancelled`() {
        val form = RequestForm(endpoint()).also { it.pathValues["user_id"] = "1" }
        val c = controller()
        c.send(form.endpoint, form, local)
        engine.emit(headers(ResponseMode.RAW_STREAM, "text/plain"))
        ui.runPending()
        assertEquals(Phase.STREAMING, c.state.phase)
        c.cancel()
        while (ui.pending.isNotEmpty() && c.state.running) ui.runPending()
        assertTrue(engine.cancelled)
        assertEquals(Phase.CANCELLED, c.state.phase)
        c.close()
    }

    @Test
    fun `a public host needs the confirmation and a refusal sends nothing`() {
        val form = RequestForm(endpoint()).also { it.pathValues["user_id"] = "1" }
        ui.allowHost = false
        val c = controller()
        val remote = Environment("prod", "https://api.example.com")
        assertEquals(SendResult.Declined, c.send(form.endpoint, form, remote))
        assertEquals(listOf("api.example.com"), ui.asked)
        assertEquals(0, engine.streams)
        ui.allowHost = true
        assertEquals(SendResult.Started, c.send(form.endpoint, form, remote))
        c.close()
    }

    @Test
    fun `the override reaches the engine and nothing is sent before send is called`() {
        val c = controller()
        c.override = ModeOverride.FORCE_STREAM
        assertEquals(0, engine.streams)
        val form = RequestForm(endpoint()).also { it.pathValues["user_id"] = "1" }
        c.send(form.endpoint, form, local)
        assertEquals(ModeOverride.FORCE_STREAM, engine.lastStream!!.mode)
        c.close()
    }

    @Test
    fun `closing mid-stream cancels the call and ignores late events`() {
        val form = RequestForm(endpoint()).also { it.pathValues["user_id"] = "1" }
        val c = controller()
        c.send(form.endpoint, form, local)
        c.close()
        assertTrue(engine.cancelled)
        engine.emit(StreamEvent.Line("late"))
        ui.runPending()
        assertTrue(ui.pending.isEmpty())
    }

    @Test
    fun `generate body uses the source off the UI path and never invents`() = runBlocking {
        val e = endpoint(HttpMethod.POST, "/items", emptyList())
        val none = BodyTemplateSource { null }
        assertEquals(BodyGeneration.Unavailable, BodyGenerator.generate(none, e, ""))
        val some = BodyTemplateSource { BodyTemplate("application/json", "{\"name\": \"\"}") }
        assertTrue(BodyGenerator.generate(some, e, "  ") is BodyGeneration.Ready)
        assertTrue(BodyGenerator.generate(some, e, "{\"mine\":1}") is BodyGeneration.NeedsConfirmation)
        val form = RequestForm(e)
        BodyGenerator.apply(form, BodyTemplate("application/json", "{}"))
        assertEquals(BodyType.JSON, form.bodyKind)
        assertEquals("{}", form.bodyText)
    }

    @Test
    fun `history is bounded and form copies redact credentials`() {
        val log = HistoryLog(max = 2)
        val form = RequestForm(endpoint()).also { it.headers = listOf(FormRow("Authorization", "Bearer secret"), FormRow("X-Id", "7")) }
        val copy = form.copy(redact = true)
        assertEquals("<redacted>", copy.headers[0].value)
        assertEquals("7", copy.headers[1].value)
        repeat(5) { log.add(HistoryEntry("k", it.toLong(), HttpMethod.GET, "u", 200, null, 1, 1, copy)) }
        assertEquals(2, log.size)
        assertEquals(4L, log.all().first().atMillis)
    }

    @Test
    fun `limits sanity`() {
        assertTrue(Limits.LIVE_APPEND_MAX_CHARS > 0)
    }
}
