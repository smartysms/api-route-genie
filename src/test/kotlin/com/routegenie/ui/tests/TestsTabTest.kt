package com.routegenie.ui.tests

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.client.BodyTemplate
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.Environment
import com.routegenie.client.ResponseBody
import com.routegenie.client.ResponseData
import com.routegenie.client.ResponseMode
import com.routegenie.client.StreamEvent
import com.routegenie.client.StreamSummary
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.testmap.BodyLiteral
import com.routegenie.testmap.CallOrigin
import com.routegenie.testmap.CallRequest
import com.routegenie.testmap.IndexPhase
import com.routegenie.testmap.ProjectTest
import com.routegenie.testmap.Reach
import com.routegenie.testmap.TestCall
import com.routegenie.testmap.TestIndex
import com.routegenie.testmap.TestIndexSource
import com.routegenie.testmap.TestMark
import com.routegenie.testmap.TestStats
import com.routegenie.testmap.TestStyle
import com.routegenie.testmap.UnattributedCall
import com.routegenie.ui.request.BodyType
import com.routegenie.ui.request.FakeEngine
import com.routegenie.ui.request.ManualUi
import com.routegenie.ui.request.MapSecretStore
import com.routegenie.ui.request.RequestSection
import com.routegenie.ui.request.SendResult
import com.routegenie.ui.request.directExecutor
import com.routegenie.ui.request.endpoint
import kotlinx.coroutines.flow.MutableStateFlow

class FakeIndexSource(initial: TestIndex) : TestIndexSource {
    override val index = MutableStateFlow(initial)
    val activations = ArrayList<Boolean>()
    var refreshes = 0
    override fun setActive(active: Boolean) { activations += active }
    override fun requestRefresh() { refreshes++ }
}

/** The Tests tab on a fake index: what is listed, the empty states, navigation, the badge, and the quick-test execution page with a fake engine. */
class TestsTabTest : BasePlatformTestCase() {
    private val items = endpoint(HttpMethod.POST, "/items", emptyList())
    private val users = endpoint(HttpMethod.GET, "/users/{user_id}")
    private val navigated = ArrayList<SourceRef>()

    private fun pt(name: String, cls: String? = null, mark: TestMark = TestMark.NONE, style: TestStyle = TestStyle.PYTEST) = ProjectTest(
        "tests/test_items.py::" + (cls?.let { "$it::" } ?: "") + name, "tests/test_items.py", cls, name, style, mark, SourceRef("tests/test_items.py", 10, 3),
    )

    private fun tc(
        t: ProjectTest, verb: HttpMethod, path: String, line: Int, origin: CallOrigin = CallOrigin.DIRECT, expected: Int? = null,
        request: CallRequest = CallRequest.NONE, redirect: Boolean = false,
    ) = TestCall(t, verb, path, SourceRef(t.file, line * 10, line), origin, redirect, null, expected, request)

    private fun index(
        byEndpoint: Map<Endpoint, List<TestCall>>, files: Int = 1, phase: IndexPhase = IndexPhase.READY,
        unattributed: List<UnattributedCall> = emptyList(),
    ) = TestIndex(
        1, phase, TestStats(files, 5, byEndpoint.values.sumOf { it.size }, byEndpoint.values.sumOf { it.size }, unattributed.groupingBy { it.reason }.eachCount()),
        byEndpoint.mapKeys { it.key.key }, unattributed,
    )

    private val create = pt("test_create")
    private val createBody = tc(create, HttpMethod.POST, "/items", 5, expected = 201, request = CallRequest(listOf("dry" to "1"), listOf("X-Test" to "1"), BodyLiteral.Json("{\n  \"name\": \"Foo\"\n}"), false))
    private val helperCall = tc(pt("test_helper"), HttpMethod.POST, "/items", 9, CallOrigin.INDIRECT)
    private val skipped = tc(pt("test_old", mark = TestMark.SKIPPED), HttpMethod.POST, "/items", 14)
    private val user = tc(pt("test_user"), HttpMethod.GET, "/users/5", 20)

    private fun owner(): Disposable = Disposer.newDisposable("tests tab")

    private fun panel(owner: Disposable, source: TestIndexSource, badge: (Int) -> Unit = {}) =
        TestsPanel(project, owner, { source }, badge, navigate = { navigated += it })

    private fun fullIndex() = index(mapOf(items to listOf(createBody, helperCall, skipped), users to listOf(user)))

    // ---- list ------------------------------------------------------------------------------------------------------

    fun testSelectionShowsTheTestsOfThatEndpointOnly() {
        val owner = owner()
        try {
            val p = panel(owner, FakeIndexSource(fullIndex()))
            p.setShown(true)
            p.show(items)
            assertEquals(listOf("test_create", "test_helper", "test_old"), p.rowsForTest().map { it.test.name })
            p.show(users)
            assertEquals(listOf("test_user"), p.rowsForTest().map { it.test.name })
            p.show(null)
            assertEquals("select", p.cardName())
            assertTrue(p.rowsForTest().isEmpty())
        } finally { Disposer.dispose(owner) }
    }

    fun testSummaryCountsTestsAndLeavesOutSkippedOnes() {
        val owner = owner()
        try {
            val p = panel(owner, FakeIndexSource(fullIndex()))
            p.setShown(true)
            p.show(items)
            assertEquals("list", p.cardName())
            assertEquals("Reached by 2 tests (1 direct, 1 indirect)  -  1 skipped or expected to fail (not counted)", p.summaryForTest())
        } finally { Disposer.dispose(owner) }
    }

    fun testRowTextHasVerbTestFileLineAndMarks() {
        val owner = owner()
        try {
            val redirected = tc(pt("test_r", "TestItems", TestMark.XFAIL), HttpMethod.POST, "/items", 30, expected = 307, redirect = true)
            val p = panel(owner, FakeIndexSource(index(mapOf(items to listOf(createBody, redirected)))))
            p.setShown(true)
            p.show(items)
            assertEquals("POST test_create /items tests/test_items.py:5 direct  -  expects 201", p.rendererTextForTest(0))
            assertEquals("POST TestItems.test_r /items tests/test_items.py:30 direct  -  expected to fail  -  via redirect  -  expects 307", p.rendererTextForTest(1))
        } finally { Disposer.dispose(owner) }
    }

    // ---- empty states -------------------------------------------------------------------------------------------------

    fun testEmptyStatesAreDistinct() {
        val owner = owner()
        try {
            val source = FakeIndexSource(TestIndex.EMPTY)
            val p = panel(owner, source)
            p.setShown(true)
            p.show(items)
            assertEquals("indexing", p.cardName())

            source.index.value = index(emptyMap(), files = 0)
            p.show(items)
            assertEquals("nofiles", p.cardName())

            source.index.value = index(mapOf(users to listOf(user)))
            p.show(items)
            assertEquals("none", p.cardName())
            assertTrue("certain: nothing calls it and nothing is in doubt", p.untestedVisible())
            assertEquals("", p.doubtTextForTest())
        } finally { Disposer.dispose(owner) }
    }

    fun testUnattributedCallsTurnUntestedIntoNoTestFound() {
        val owner = owner()
        try {
            val maybe = UnattributedCall(SourceRef("tests/test_x.py", 1, 1), "tests/test_x.py::test_a", HttpMethod.POST, DiagnosticCode.TEST_AMBIGUOUS, "2", Reach.Endpoints(setOf(items.key)))
            val anywhere = UnattributedCall(SourceRef("tests/test_x.py", 5, 2), "tests/test_x.py::test_b", HttpMethod.GET, DiagnosticCode.TEST_CONSTANT_UNRESOLVED, "settings.X", Reach.Anywhere)
            val none = UnattributedCall(SourceRef("tests/test_x.py", 9, 3), "tests/test_x.py::test_c", HttpMethod.GET, DiagnosticCode.TEST_NO_ENDPOINT, "GET /z", Reach.Unrelated)
            val p = panel(owner, FakeIndexSource(index(mapOf(users to listOf(user)), unattributed = listOf(maybe, anywhere, none))))
            p.setShown(true)
            p.show(items)
            assertEquals("none", p.cardName())
            assertFalse("a call that might be about it exists", p.untestedVisible())
            assertEquals("3 test calls in the project could not be attributed to an endpoint", p.doubtTextForTest())
            // Another endpoint is only in doubt through the call whose URL is unknown.
            val other = endpoint(HttpMethod.GET, "/other", emptyList())
            p.show(other)
            assertFalse("an unresolved URL could be anything", p.untestedVisible())
            assertEquals("tests/test_x.py:1  POST  This test call matches 2 endpoints and cannot be told apart", p.unattributedTextForTest(maybe))
        } finally { Disposer.dispose(owner) }
    }

    // ---- navigation, badge, laziness -----------------------------------------------------------------------------------

    fun testNavigationGoesToTheCallPosition() {
        val owner = owner()
        try {
            val p = panel(owner, FakeIndexSource(fullIndex()))
            p.setShown(true)
            p.show(items)
            p.goToSelectedForTest()
            assertTrue("nothing selected, nowhere to go", navigated.isEmpty())
            p.selectRowForTest(1)
            p.goToSelectedForTest()
            assertEquals(listOf(SourceRef("tests/test_items.py", 90, 9)), navigated)
        } finally { Disposer.dispose(owner) }
    }

    fun testBadgeFollowsTheSelectionAndSkippedTestsDoNotCount() {
        val owner = owner()
        try {
            val badges = ArrayList<Int>()
            val p = panel(owner, FakeIndexSource(fullIndex()), badges::add)
            p.setShown(true)
            p.show(items)
            p.show(users)
            p.show(null)
            val changes = badges.fold(ArrayList<Int>()) { acc, v -> if (acc.lastOrNull() != v) acc.add(v); acc }
            assertEquals(listOf(0, 2, 1, 0), changes)
        } finally { Disposer.dispose(owner) }
    }

    fun testNothingIsReadWhileTheTabIsHidden() {
        val owner = owner()
        try {
            val source = FakeIndexSource(fullIndex())
            val p = panel(owner, source)
            p.show(items)
            assertTrue("never shown: the index is not asked to work", source.activations.isEmpty())
            assertEquals("indexing", p.cardName())
            p.setShown(true)
            p.setShown(false)
            assertEquals(listOf(true, false), source.activations)
        } finally { Disposer.dispose(owner) }
    }

    fun testTheTabIsAfterHistoryAndTheBadgeShowsOnTheTitle() {
        val owner = owner()
        try {
            val state = RouteGenieClientState()
            val source = FakeIndexSource(fullIndex())
            val s = RequestSection(
                project, owner, engine = { FakeEngine() }, bodyTemplates = { BodyTemplateSource { null } }, ui = ManualUi(),
                state = state, secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(),
                tests = { source },
            )
            val titles = s.responseForTest().tabTitles()
            assertEquals("History", titles[titles.size - 2])
            assertEquals("Tests", titles.last())
            s.show(items)
            s.responseForTest().testsPanelForTest()!!.setShown(true)
            assertEquals("Tests (2)", s.responseForTest().tabTitles().last())
            s.show(null)
            assertEquals("Tests", s.responseForTest().tabTitles().last())
        } finally { Disposer.dispose(owner) }
    }

    // ---- the execution page -----------------------------------------------------------------------------------------------

    private val engine = FakeEngine()
    private val ui = ManualUi()
    private val state = RouteGenieClientState()

    private fun section(owner: Disposable, source: TestIndexSource) = RequestSection(
        project, owner, engine = { engine }, bodyTemplates = { BodyTemplateSource { BodyTemplate("application/json", "{}") } }, ui = ui,
        state = state, secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(),
        tests = { source },
    )

    private fun openFirstTest(s: RequestSection): TestExecutionPanel {
        s.show(items)
        val tests = s.responseForTest().testsPanelForTest()!!
        tests.setShown(true)
        tests.selectRowForTest(0)
        return tests.executionForTest()!!
    }

    private fun answer(status: Int, reason: String): Array<StreamEvent> {
        val body = ResponseBody.inMemory("{}".toByteArray())
        val r = ResponseData(status, reason, listOf("content-type" to "application/json"), body, 5, 2, "http://127.0.0.1:8000/items", emptyList(), "HTTP/1.1")
        return arrayOf(
            StreamEvent.HeadersReceived(status, reason, r.headers, "HTTP/1.1", ResponseMode.BUFFERED, r.finalUrl),
            StreamEvent.BufferedResponse(r),
            StreamEvent.Completed(StreamSummary(r.bodySize, 0, 5)),
        )
    }

    fun testSelectingATestFillsTheEditableRequestAndSendsNothing() {
        val owner = owner()
        try {
            val exec = openFirstTest(section(owner, FakeIndexSource(fullIndex())))
            val form = exec.formForTest()!!
            assertEquals(HttpMethod.POST, form.method)
            assertEquals(listOf("dry" to "1"), form.query.map { it.name to it.value })
            assertEquals(listOf("X-Test" to "1"), form.headers.map { it.name to it.value })
            assertEquals(BodyType.JSON, form.bodyKind)
            assertEquals("{\n  \"name\": \"Foo\"\n}", form.bodyText)
            assertEquals("{\n  \"name\": \"Foo\"\n}", exec.formPanelForTest().bodyTextForTest())
            assertTrue(exec.noteTextForTest().contains("The test expects status 201"))
            assertTrue(exec.runButton().isEnabled)
            assertEquals("selecting is not sending", 0, engine.streams)
        } finally { Disposer.dispose(owner) }
    }

    fun testPartsTheCodeDoesNotSpellOutAreLeftEmptyAndSaid() {
        val owner = owner()
        try {
            val undetermined = tc(pt("test_u"), HttpMethod.GET, "/users/{?}", 3, request = CallRequest(null, null, null, true))
            val s = section(owner, FakeIndexSource(index(mapOf(users to listOf(undetermined)))))
            s.show(users)
            val tests = s.responseForTest().testsPanelForTest()!!
            tests.setShown(true)
            tests.selectRowForTest(0)
            val exec = tests.executionForTest()!!
            val form = exec.formForTest()!!
            assertEquals("", form.pathValues["user_id"])
            assertTrue(form.query.isEmpty() && form.headers.isEmpty() && form.bodyText.isEmpty())
            assertTrue(exec.noteTextForTest(), exec.noteTextForTest().contains("Not determined by the test code and left empty: path values, query, headers, body"))
        } finally { Disposer.dispose(owner) }
    }

    fun testPathValuesAreReadFromTheLiteralCall() {
        val owner = owner()
        try {
            val s = section(owner, FakeIndexSource(fullIndex()))
            s.show(users)
            val tests = s.responseForTest().testsPanelForTest()!!
            tests.setShown(true)
            tests.selectRowForTest(0)
            assertEquals("5", tests.executionForTest()!!.formForTest()!!.pathValues["user_id"])
        } finally { Disposer.dispose(owner) }
    }

    fun testRunQuickTestSendsTheEditedRequestAndShowsTheVerdict() {
        val owner = owner()
        try {
            val exec = openFirstTest(section(owner, FakeIndexSource(fullIndex())))
            exec.formForTest()!!.bodyText = "{\"name\": \"Edited\"}"
            exec.formPanelForTest().refresh(null)
            assertTrue(exec.runQuickTest() is SendResult.Started)
            assertEquals(1, engine.streams)
            assertTrue(engine.lastSpec!!.url, engine.lastSpec!!.url.startsWith("http://127.0.0.1:8000/items"))
            assertEquals(HttpMethod.POST, engine.lastSpec!!.method)
            assertTrue(engine.lastSpec!!.body.toString().contains("Edited"))
            engine.emit(*answer(201, "Created"))
            ui.runPending()
            assertEquals("Test expects 201 - got 201: matches", exec.verdictText())

            assertTrue(exec.runQuickTest() is SendResult.Started)
            engine.emit(*answer(422, "Unprocessable Entity"))
            ui.runPending()
            assertEquals("Test expects 201 - got 422: differs", exec.verdictText())
        } finally { Disposer.dispose(owner) }
    }

    fun testVerdictWithoutAnAssertedStatus() {
        val owner = owner()
        try {
            val s = section(owner, FakeIndexSource(fullIndex()))
            s.show(items)
            val tests = s.responseForTest().testsPanelForTest()!!
            tests.setShown(true)
            tests.selectRowForTest(1)
            val exec = tests.executionForTest()!!
            exec.runQuickTest()
            engine.emit(*answer(200, "OK"))
            ui.runPending()
            assertEquals("Got 200. The test asserts no status that the code spells out.", exec.verdictText())
        } finally { Disposer.dispose(owner) }
    }

    fun testNonLocalHostAsksOnceAndNothingIsSentWhenDeclined() {
        val owner = owner()
        try {
            state.save(Environment("prod", "https://api.example.com"))
            state.select("prod")
            val s = section(owner, FakeIndexSource(fullIndex()))
            val exec = openFirstTest(s)
            ui.allowHost = false
            assertTrue(exec.runQuickTest() is SendResult.Declined)
            assertEquals(0, engine.streams)
            ui.allowHost = true
            assertTrue(exec.runQuickTest() is SendResult.Started)
            engine.emit(*answer(201, "Created"))
            ui.runPending()
            assertTrue(exec.runQuickTest() is SendResult.Started)
            assertEquals("asked twice (declined, then allowed) and never again for the same host", listOf("api.example.com", "api.example.com"), ui.asked)
            // The Request page shares the confirmation.
            assertFalse(state.hostGuard.needsConfirmation("api.example.com"))
        } finally { Disposer.dispose(owner) }
    }

    fun testOpenInRequestTabMovesTheEditedRequestIntoTheRequestForm() {
        val owner = owner()
        try {
            val s = section(owner, FakeIndexSource(fullIndex()))
            val exec = openFirstTest(s)
            exec.formForTest()!!.bodyText = "{\"moved\": true}"
            exec.formForTest()!!.query = listOf(com.routegenie.ui.request.FormRow("page", "9"))
            exec.openRequestButton().doClick()
            val form = s.currentForm()!!
            assertEquals("{\"moved\": true}", form.bodyText)
            assertEquals(listOf("page" to "9"), form.query.map { it.name to it.value })
            assertNotSame("a copy: later edits of the test page do not change the Request page", exec.formForTest(), form)
            assertEquals(0, engine.streams)
        } finally { Disposer.dispose(owner) }
    }

    fun testEditsAreRememberedPerTestInMemoryOnly() {
        val owner = owner()
        try {
            val file = myFixture.addFileToProject("tests/test_items.py", "def test_create(client):\n    client.post('/items')\n")
            val before = file.text
            val s = section(owner, FakeIndexSource(fullIndex()))
            val exec = openFirstTest(s)
            exec.formForTest()!!.bodyText = "{\"edited\": 1}"
            val tests = s.responseForTest().testsPanelForTest()!!
            tests.selectRowForTest(1)
            assertEquals("a different test starts from its own call", "", exec.formForTest()!!.bodyText)
            tests.selectRowForTest(0)
            assertEquals("{\"edited\": 1}", exec.formForTest()!!.bodyText)
            assertEquals(2, exec.rememberedForms())
            assertEquals("the test file is never changed", before, file.text)
        } finally { Disposer.dispose(owner) }
    }

    fun testAnIndexUpdateKeepsTheVerdictOfTheSelectedTest() {
        val owner = owner()
        try {
            val source = FakeIndexSource(fullIndex())
            val exec = openFirstTest(section(owner, source))
            exec.runQuickTest()
            engine.emit(*answer(201, "Created"))
            ui.runPending()
            assertEquals("Test expects 201 - got 201: matches", exec.verdictText())
            source.index.value = fullIndex()
            repeat(20) {
                com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(20)
            }
            assertEquals("a refresh of the list does not reset the page", "Test expects 201 - got 201: matches", exec.verdictText())
        } finally { Disposer.dispose(owner) }
    }

    fun testCtrlEnterAndTheButtonShareOneRunPath() {
        val owner = owner()
        try {
            val exec = openFirstTest(section(owner, FakeIndexSource(fullIndex())))
            exec.runButton().doClick()
            assertEquals(1, engine.streams)
            assertFalse("a running request disables the button", exec.runButton().isEnabled)
        } finally { Disposer.dispose(owner) }
    }

    fun testCopyCommandAndTheIdeLauncherAreOfferedForEveryTest() {
        val owner = owner()
        try {
            val exec = openFirstTest(section(owner, FakeIndexSource(fullIndex())))
            assertTrue(exec.copyCommandButton().isEnabled)
            assertTrue(exec.ideRunButton().isEnabled)
            exec.copyCommandButton().doClick()
            val copied = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor)
            assertEquals("pytest tests/test_items.py::test_create", copied)
        } finally { Disposer.dispose(owner) }
    }
}
