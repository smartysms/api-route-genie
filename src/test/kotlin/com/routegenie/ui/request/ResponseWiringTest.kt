package com.routegenie.ui.request

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.Limits
import com.routegenie.client.ResponseBody
import com.routegenie.client.ResponseData
import com.routegenie.client.ResponseSlot
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.json.JsonContent
import com.routegenie.model.HttpMethod
import java.nio.file.Files

/** JSON visualizer, spill-file lifetime and Compare inside the window, with a fake engine and fake secret store. */
class ResponseWiringTest : BasePlatformTestCase() {
    private val engine = FakeEngine()
    private val ui = ManualUi()
    private val state = RouteGenieClientState()
    private val slot = ResponseSlot()

    private fun section(owner: Disposable) = RequestSection(
        project, owner, engine = { engine }, bodyTemplates = { com.routegenie.client.BodyTemplateSource { null } }, ui = ui,
        state = state, secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = slot,
    )

    private fun owner() = Disposer.newDisposable("response wiring test")

    private fun answer(s: RequestSection, r: ResponseData) {
        assertTrue(s.send() is SendResult.Started)
        engine.emit(*bufferedEvents(r))
        ui.runPending()
    }

    private fun waitFor(condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out", condition, 30)

    private fun indexed(s: RequestSection): Boolean = s.responseForTest().jsonForTest().let { it.currentIndex() != null && !it.isIndexing() }

    private fun bigJson(n: Int): String = buildString {
        append("{\"items\":[")
        repeat(n) { if (it > 0) append(','); append("{\"id\":$it,\"name\":\"item $it\"}") }
        append("]}")
    }

    fun testJsonGoesToTheVisualizerAndOtherTextStaysInThePlainViewer() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/users", emptyList()))
            answer(s, jsonResponse("""{"a":[1,2,3]}"""))
            val r = s.responseForTest()
            assertTrue(r.jsonIsShown())
            waitFor { indexed(s) }
            answer(s, textResponse("hello plain"))
            assertFalse(r.jsonIsShown())
            waitFor { r.viewerForTest().text.contains("hello plain") }
        } finally { Disposer.dispose(owner) }
    }

    fun testSpilledBodyIsPassedAsItsFileAndTheFileLivesExactlyAsLongAsNeeded() {
        TestSpill().use { sp ->
            val owner = owner()
            try {
                val s = section(owner)
                s.show(endpoint(HttpMethod.GET, "/big", emptyList()))
                val first = sp.spilled(bigJson(2000))
                assertTrue("a spilled body reaches the visualizer as a file", ResponseJson.content(first) is JsonContent.File)
                answer(s, first)
                val file1 = first.bodyFile!!
                assertTrue("exists while shown", Files.exists(file1))
                waitFor { indexed(s) }
                val second = sp.spilled(bigJson(2100))
                answer(s, second)
                val third = sp.spilled(bigJson(2200))
                answer(s, third)
                // History keeps the newest spilled bodies only (current plus one for comparison); the oldest one is released.
                waitFor { !Files.exists(file1) }
                assertTrue(Files.exists(second.bodyFile!!))
                assertTrue(Files.exists(third.bodyFile!!))
                assertEquals(Limits.HISTORY_SPILLED_BODIES_KEPT, sp.liveFiles().size)
                waitFor { indexed(s) }
                Disposer.dispose(owner)
                waitFor { sp.liveFiles().isEmpty() }
                assertTrue("nothing is left behind after dispose", sp.liveFiles().isEmpty())
            } finally { Disposer.dispose(owner) }
        }
    }

    fun testClearingTheResponseDeletesItsSpillFile() {
        TestSpill().use { sp ->
            val owner = owner()
            try {
                val s = section(owner)
                s.show(endpoint(HttpMethod.GET, "/big", emptyList()))
                val only = sp.spilled(bigJson(500))
                answer(s, only)
                val file = only.bodyFile!!
                s.controller.clear()
                // The history still holds its lease, so the file goes when the entry loses its body.
                s.historyForTest().releaseBodies()
                assertFalse(Files.exists(file))
            } finally { Disposer.dispose(owner) }
        }
    }

    fun testCompareWithPreviousShowsTheDifferenceInsideTheWindow() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/users", emptyList()))
            val r = s.responseForTest()
            answer(s, jsonResponse("""{"a":1,"b":2}"""))
            assertFalse("nothing to compare with yet", r.comparePreviousForTest())
            assertNotNull(r.noticeText())
            answer(s, jsonResponse("""{"a":1,"b":3,"c":true}"""))
            assertTrue(r.comparePreviousForTest())
            assertTrue(r.diffIsShown())
            waitFor { r.diffForTest().differences() != null }
            assertEquals(2, r.diffForTest().differences()!!.totalDifferences)
        } finally { Disposer.dispose(owner) }
    }

    fun testCompareWithAnyKeptEntryAndBodyNotKeptForOldOnes() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/users", emptyList()))
            val total = Limits.HISTORY_BODIES_KEPT + 2
            repeat(total) { answer(s, jsonResponse("""{"n":$it}""")) }
            val log = s.historyForTest()
            assertEquals(Limits.HISTORY_BODIES_KEPT, log.withBodies().size)
            val texts = s.responseForTest().historyTexts()
            assertFalse(texts.first().contains("body not kept"))
            assertTrue(texts.last().contains("body not kept"))
            val oldest = log.withBodies().last()
            assertTrue(s.responseForTest().compareWith(oldest))
            waitFor { s.responseForTest().diffForTest().differences() != null }
            val dropped = log.all().last()
            assertNull(dropped.body)
            assertFalse(s.responseForTest().compareWith(dropped))
        } finally { Disposer.dispose(owner) }
    }

    fun testHistoryKeepsNoBodyOfNonJsonResponsesAndReleasesEverythingOnDispose() {
        val owner = owner()
        val s = section(owner)
        s.show(endpoint(HttpMethod.GET, "/users", emptyList()))
        answer(s, textResponse("plain"))
        answer(s, jsonResponse("""{"k":1}"""))
        val log = s.historyForTest()
        assertEquals(1, log.withBodies().size)
        Disposer.dispose(owner)
        assertTrue(log.withBodies().isEmpty())
    }

    fun testStreamingKeepsThePlainLiveViews() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/events", emptyList()))
            answer(s, jsonResponse("""{"a":1}"""))
            assertTrue(s.responseForTest().jsonIsShown())
            assertTrue(s.send() is SendResult.Started)
            assertFalse("a new send leaves the visualizer", s.responseForTest().jsonIsShown())
            engine.emit(
                com.routegenie.client.StreamEvent.HeadersReceived(200, "OK", listOf("content-type" to "text/plain"), "HTTP/1.1", com.routegenie.client.ResponseMode.RAW_STREAM, "u"),
                com.routegenie.client.StreamEvent.Line("tick 1"),
            )
            ui.runPending()
            assertFalse(s.responseForTest().jsonIsShown())
            assertTrue(s.responseForTest().viewerForTest().text.contains("tick 1"))
        } finally { Disposer.dispose(owner) }
    }

    fun testResponseBodyLeaseKeepsTheFileUntilItIsClosed() {
        TestSpill().use { sp ->
            val r = sp.spilled(bigJson(10))
            val file = r.bodyFile!!
            val lease = r.responseBody.retain()
            r.release()
            assertTrue("a lease keeps the file", Files.exists(file))
            lease.close()
            lease.close()
            assertFalse(Files.exists(file))
            val again = ResponseBody.inMemory(ByteArray(3))
            again.retain().close()
            again.release()
        }
    }
}
