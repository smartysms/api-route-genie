package com.routegenie.ui.request

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.Limits
import com.routegenie.client.BodyTemplate
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.ResponseMode
import com.routegenie.client.StreamEvent
import com.routegenie.client.StreamSummary
import com.routegenie.client.curl.CurlShell
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PlatformLeaks

/** Panel construction and state handling with a FAKE engine: no network, nothing sent unless the test presses Send. */
class RequestSectionTest : BasePlatformTestCase() {
    private val engine = FakeEngine()
    private val ui = ManualUi()
    private var template: BodyTemplate? = null
    private val templates = BodyTemplateSource { template }

    private val state = com.routegenie.clientide.RouteGenieClientState()
    private val store = MapSecretStore()

    private fun section(owner: com.intellij.openapi.Disposable) = RequestSection(
        project, owner, engine = { engine }, bodyTemplates = { templates }, ui = ui,
        state = state, secretStore = store, secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(),
    )

    private fun owner() = Disposer.newDisposable("request section test")

    fun testAnyEndpointShowsTheMethodDropdownAndFixedOnesDoNot() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.ANY, "/ping", emptyList()))
            assertTrue(s.formForTest().methodComboVisible())
            assertEquals(HttpMethod.GET, s.currentForm()!!.method)
            s.show(endpoint(HttpMethod.PUT))
            assertFalse(s.formForTest().methodComboVisible())
            assertEquals(HttpMethod.PUT, s.currentForm()!!.method)
        } finally { Disposer.dispose(owner) }
    }

    fun testBodyEditorIsTheVisibleCardForJsonAndTextAndHiddenForNone() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/items", emptyList()))
            val form = s.formForTest()
            form.selectFormTabForTest("Body")
            assertFalse("no body: the editor is hidden", form.bodyEditorIsOnScreen())
            // A new endpoint starts at type None; "Generate body" must be reachable there, or the user never finds it.
            assertTrue("no body: Generate body is still offered", form.generateIsOnScreen())
            for (kind in listOf(BodyType.JSON, BodyType.TEXT)) {
                s.currentForm()!!.bodyKind = kind
                form.refresh(null)
                assertTrue("$kind shows the editor", form.bodyEditorIsOnScreen())
                assertTrue("$kind offers Generate body", form.generateIsOnScreen())
            }
            s.currentForm()!!.bodyKind = BodyType.FORM
            form.refresh(null)
            assertFalse(form.bodyEditorIsOnScreen())
            assertFalse("form types are not filled by Generate body", form.generateIsOnScreen())
        } finally { Disposer.dispose(owner) }
    }

    fun testValidationErrorsBlockSendAndAreShownInline() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/users/{user_id}", listOf(PathParam("user_id", "int"))))
            s.currentForm()!!.pathValues["user_id"] = "abc"
            val r = s.send()
            assertTrue(r is SendResult.Invalid)
            assertEquals(0, engine.streams)
            assertNotNull(s.formForTest().pathErrorText("user_id"))
            assertTrue(s.sendButton().isEnabled)
        } finally { Disposer.dispose(owner) }
    }

    fun testRunningDisablesSendAndShowsCancelAndTheStreamIsLive() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/events", emptyList()))
            assertTrue(s.send() is SendResult.Started)
            assertFalse(s.sendButton().isEnabled)
            assertTrue(s.cancelButton().isVisible)
            engine.emit(
                StreamEvent.HeadersReceived(200, "OK", listOf("content-type" to "text/event-stream"), "HTTP/1.1", ResponseMode.SSE, "http://127.0.0.1:8000/events"),
                StreamEvent.SseEvent("1", "tick", "{\"n\":1}", null),
                StreamEvent.SseEvent("2", "tick", "{\"n\":2}", null),
            )
            ui.runPending()
            val r = s.responseForTest()
            assertTrue(r.tabTitles().contains("Events"))
            assertEquals(2, r.eventsForTest().rowCount)
            assertTrue(r.viewerForTest().text.contains("data: {\"n\":2}"))
            s.controller.cancel()
            while (ui.pending.isNotEmpty() && s.controller.state.running) ui.runPending()
            assertTrue(s.sendButton().isEnabled)
            assertFalse(s.cancelButton().isVisible)
            assertEquals(1, r.historyCount())
        } finally { Disposer.dispose(owner) }
    }

    fun testGenerateBodyAsksBeforeOverwritingAndExplainsWhenThereIsNoTemplate() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/items", emptyList()))
            val form = s.formForTest()
            template = null
            form.generateBody()
            waitFor { form.generateNoteText() != null }
            assertEquals("", form.bodyTextForTest())

            template = BodyTemplate("application/json", "{\"name\": \"\"}")
            form.generateBody()
            waitFor { form.bodyTextForTest().isNotEmpty() }
            assertEquals("{\"name\": \"\"}", form.bodyTextForTest())
            assertEquals(BodyType.JSON, s.currentForm()!!.bodyKind)

            // Now the editor holds text: nothing is replaced until the user confirms.
            template = BodyTemplate("application/json", "{\"other\": 1}")
            s.currentForm()!!.bodyText = "{\"mine\": true}"
            form.refresh(null)
            form.generateBody()
            waitFor { form.replaceBarVisible() }
            assertEquals("{\"mine\": true}", form.bodyTextForTest())
            form.confirmReplaceForTest()
            assertEquals("{\"other\": 1}", form.bodyTextForTest())
        } finally { Disposer.dispose(owner) }
    }

    fun testCurlViewFollowsTheFormAndTheShellChoice() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/items", emptyList()))
            s.currentForm()!!.apply { bodyKind = BodyType.JSON; bodyText = "{\"a\":1}" }
            val r = s.responseForTest()
            r.setShellForTest(CurlShell.BASH)
            assertTrue(r.curlTextForTest(), r.curlTextForTest().contains("-X POST"))
            r.setShellForTest(CurlShell.CMD)
            assertTrue(r.curlTextForTest().startsWith("curl.exe"))
            r.importForTest("curl -X PUT 'http://localhost:9000/items' -H 'X-A: 1' -d x=1")
            assertEquals(HttpMethod.PUT, s.currentForm()!!.method.takeIf { s.currentForm()!!.methodIsChoosable } ?: HttpMethod.PUT)
            assertTrue(r.importResultText(), r.importResultText().isNotEmpty())
            r.importForTest("wget http://x")
            assertTrue(r.importResultText().isNotEmpty())
        } finally { Disposer.dispose(owner) }
    }

    fun testLayoutFollowsTheShapeOfTheWindow() {
        val owner = owner()
        try {
            val s = section(owner)
            s.component.setSize(Limits.WORKSPACE_SIDE_BY_SIDE_MIN_WIDTH * 2, 600)
            s.applyOrientation()
            assertFalse("wide window puts the response beside the request", s.splitterVertical())
            s.component.setSize(Limits.WORKSPACE_SIDE_BY_SIDE_MIN_WIDTH / 2, 600)
            s.applyOrientation()
            assertTrue(s.splitterVertical())
        } finally { Disposer.dispose(owner) }
    }

    fun testAFreshPostFillsItsBodyFromTheCodeAndSendsNothing() {
        val owner = owner()
        try {
            template = BodyTemplate("application/json", "{\"name\": \"string\"}")
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/items", emptyList()))
            val form = s.formForTest()
            waitFor { form.bodyTextForTest().isNotEmpty() }
            assertEquals("{\"name\": \"string\"}", form.bodyTextForTest())
            assertEquals(BodyType.JSON, s.currentForm()!!.bodyKind)
            assertNotNull("the user is told where the text came from", form.generateNoteText())
            assertEquals("nothing is sent by filling the body", 0, engine.streams)
        } finally { Disposer.dispose(owner) }
    }

    fun testOnlyPostPutAndPatchAreFilledAndOnlyWhenTheCodeDeclaresABody() {
        val owner = owner()
        try {
            template = BodyTemplate("application/json", "{\"a\": 1}")
            val s = section(owner)
            for (method in listOf(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.ANY)) {
                s.show(endpoint(method, "/x-$method", emptyList()))
                Thread.sleep(150)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                assertEquals("$method is not filled", "", s.formForTest().bodyTextForTest())
            }
            template = null
            s.show(endpoint(HttpMethod.PATCH, "/none", emptyList()))
            Thread.sleep(150)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("no declared body: nothing is invented", "", s.formForTest().bodyTextForTest())
            assertNull(s.formForTest().generateNoteText())
        } finally { Disposer.dispose(owner) }
    }

    fun testTheUsersOwnBodyIsNeverOverwrittenAndANoneChoiceIsKeptForTheSession() {
        val owner = owner()
        try {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            template = BodyTemplate("application/json", "{\"generated\": true}")
            val slow = BodyTemplateSource { gate.await(); template }
            val s = RequestSection(
                project, owner, engine = { engine }, bodyTemplates = { slow }, ui = ui,
                state = state, secretStore = store, secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(),
            )
            s.show(endpoint(HttpMethod.PUT, "/items", emptyList()))
            // The user starts typing before the answer arrives.
            s.currentForm()!!.bodyKind = BodyType.TEXT
            s.currentForm()!!.bodyText = "mine"
            s.formForTest().refresh(null)
            gate.complete(Unit)
            Thread.sleep(200)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("mine", s.formForTest().bodyTextForTest())
            assertEquals(BodyType.TEXT, s.currentForm()!!.bodyKind)
        } finally { Disposer.dispose(owner) }
    }

    /** A source that counts: the fixed sample is always the same, the random one is different every time. */
    private class CountingSource : BodyTemplateSource {
        var randoms = 0
        override suspend fun templateFor(endpoint: com.routegenie.model.Endpoint) = BodyTemplate("application/json", "{\"sample\": true}")
        override suspend fun randomTemplateFor(endpoint: com.routegenie.model.Endpoint) = BodyTemplate("application/json", "{\"random\": ${++randoms}}")
    }

    private fun sectionWith(owner: com.intellij.openapi.Disposable, source: BodyTemplateSource) = RequestSection(
        project, owner, engine = { engine }, bodyTemplates = { source }, ui = ui,
        state = state, secretStore = store, secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(),
    )

    fun testRandomBodyButtonGivesAnotherSetEachTimeAndAsksBeforeReplacingTheUsersText() {
        val owner = owner()
        val settings = com.routegenie.settings.RouteGenieSettings.getInstance()
        val before = settings.bodyFillMode
        try {
            settings.bodyFillMode = com.routegenie.settings.BodyFillMode.OFF
            val s = sectionWith(owner, CountingSource())
            s.show(endpoint(HttpMethod.POST, "/items", emptyList()))
            val form = s.formForTest()
            assertTrue("offered at body type None", form.randomButtonForTest().isVisible)
            form.randomButtonForTest().doClick()
            waitFor { form.bodyTextForTest() == "{\"random\": 1}" }
            assertEquals(BodyType.JSON, s.currentForm()!!.bodyKind)
            // Pressing again replaces what the button wrote, without a question.
            form.randomButtonForTest().doClick()
            waitFor { form.bodyTextForTest() == "{\"random\": 2}" }
            assertFalse(form.replaceBarVisible())
            // Text the user wrote is protected.
            s.currentForm()!!.bodyText = "{\"mine\": true}"
            form.refresh(null)
            form.randomButtonForTest().doClick()
            waitFor { form.replaceBarVisible() }
            assertEquals("{\"mine\": true}", form.bodyTextForTest())
            assertEquals("nothing is sent by any of this", 0, engine.streams)
        } finally {
            settings.bodyFillMode = before
            Disposer.dispose(owner)
        }
    }

    fun testTheFillModeDecidesWhatOpeningAnEndpointDoes() {
        val settings = com.routegenie.settings.RouteGenieSettings.getInstance()
        val before = settings.bodyFillMode
        try {
            for ((mode, expected) in listOf(
                com.routegenie.settings.BodyFillMode.SAMPLE to "{\"sample\": true}",
                com.routegenie.settings.BodyFillMode.RANDOM to "{\"random\": 1}",
                com.routegenie.settings.BodyFillMode.OFF to "",
            )) {
                val owner = owner()
                try {
                    settings.bodyFillMode = mode
                    val s = sectionWith(owner, CountingSource())
                    s.show(endpoint(HttpMethod.POST, "/items-$mode", emptyList()))
                    if (expected.isEmpty()) {
                        Thread.sleep(150)
                        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                    } else {
                        waitFor { s.formForTest().bodyTextForTest().isNotEmpty() }
                    }
                    assertEquals("mode $mode", expected, s.formForTest().bodyTextForTest())
                } finally { Disposer.dispose(owner) }
            }
        } finally {
            settings.bodyFillMode = before
        }
    }

    fun testFocusOnResponseHidesTheFormAndTheListAndRestoresThemAsTheyWere() {
        val owner = owner()
        try {
            val listShown = mutableListOf<Boolean>()
            val s = RequestSection(
                project, owner, setListVisible = { listShown += it }, engine = { engine }, bodyTemplates = { templates }, ui = ui,
                state = state, secretStore = store, secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(),
            )
            assertTrue(s.requestSideShown())
            s.focusOnResponse(true)
            assertTrue(s.isFocusOnResponse())
            assertFalse("the response gets the whole page", s.requestSideShown())
            assertEquals("the list goes away too", listOf(false), listShown)
            s.focusOnResponse(false)
            assertTrue(s.requestSideShown())
            assertEquals("and comes back, because the user had not hidden it", listOf(false, true), listShown)
            s.focusOnResponse(false)
            assertEquals("switching off twice changes nothing", listOf(false, true), listShown)
        } finally { Disposer.dispose(owner) }
    }

    fun testCreatingTheSectionIsWithinTheWindowOpenBudget() {
        PerfBudget.assertWithin("request-section-create", Limits.BUDGET_WINDOW_OPEN_MS, warmups = 3, runs = 7) {
            val owner = owner()
            section(owner)
            Disposer.dispose(owner)
        }
    }

    // ---- leaks -------------------------------------------------------------------------------------------------

    fun testCreateAndDisposeLeavesNoEditorAndNoDisposedProjectReachable() {
        PlatformLeaks.assertScenarioClean("request section create/dispose") {
            repeat(5) {
                val owner = owner()
                val s = section(owner)
                s.show(endpoint(HttpMethod.POST, "/items", emptyList()))
                s.responseForTest().viewerForTest().showText("x", "json", true, false)
                Disposer.dispose(owner)
            }
        }
    }

    fun testDisposingMidStreamCancelsTheCallAndReleasesTheViewer() {
        val before = EditorFactory.getInstance().allEditors.toSet()
        val owner = owner()
        val s = section(owner)
        s.show(endpoint(HttpMethod.GET, "/events", emptyList()))
        assertTrue(s.send() is SendResult.Started)
        engine.emit(StreamEvent.HeadersReceived(200, "OK", listOf("content-type" to "text/plain"), "HTTP/1.1", ResponseMode.RAW_STREAM, "u"))
        engine.emit(StreamEvent.Line("hello"))
        ui.runPending()
        Disposer.dispose(owner)
        assertTrue("the call is cancelled when the panel goes", engine.cancelled)
        engine.emit(StreamEvent.Line("late"))
        engine.emit(StreamEvent.Completed(StreamSummary(1, 1, 1)))
        ui.runPending()
        assertTrue(EditorFactory.getInstance().allEditors.none { it !in before })
        PlatformLeaks.assertNoDisposedEditorReachable("after dispose mid-stream")
    }

    fun testAStoppedStreamLeavesNothingBehind() {
        PlatformLeaks.assertScenarioClean("stopped stream") {
            val owner = owner()
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/events", emptyList()))
            s.send()
            engine.emit(StreamEvent.HeadersReceived(200, "OK", listOf("content-type" to "text/plain"), "HTTP/1.1", ResponseMode.RAW_STREAM, "u"))
            repeat(50) { engine.emit(StreamEvent.Line("line $it")) }
            ui.runPending()
            s.controller.cancel()
            while (ui.pending.isNotEmpty() && s.controller.state.running) ui.runPending()
            Disposer.dispose(owner)
        }
    }

    private fun waitFor(condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out", condition, 30)
}
