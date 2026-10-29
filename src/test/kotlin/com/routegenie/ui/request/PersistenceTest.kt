package com.routegenie.ui.request

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.routegenie.Limits
import com.routegenie.client.AuthConfig
import com.routegenie.client.Environment
import com.routegenie.client.Header
import com.routegenie.client.HistoryEntry as StoredEntry
import com.routegenie.client.QueryParam
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestInput
import com.routegenie.client.ResponseSlot
import com.routegenie.client.SecretKeys
import com.routegenie.clientide.ClientStateData
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.model.HttpMethod

/** The window remembers environments, per-endpoint inputs, history lines and confirmed hosts; secrets only in the credential store. */
class PersistenceTest : BasePlatformTestCase() {
    private val engine = FakeEngine()
    private val ui = ManualUi()
    private var state = RouteGenieClientState()
    private val store = MapSecretStore()

    private fun section(owner: Disposable, withState: RouteGenieClientState = state, withStore: MapSecretStore = store) = RequestSection(
        project, owner, engine = { engine }, bodyTemplates = { com.routegenie.client.BodyTemplateSource { null } }, ui = ui,
        state = withState, secretStore = withStore, secretExecutor = directExecutor, responses = ResponseSlot(),
    )

    private fun owner() = Disposer.newDisposable("persistence test")

    private fun xmlOf(s: RouteGenieClientState): String = JDOMUtil.write(XmlSerializer.serialize(s.state))

    private fun reloaded(s: RouteGenieClientState): RouteGenieClientState {
        val element = JDOMUtil.load(xmlOf(s))
        return RouteGenieClientState().also { it.loadState(XmlSerializer.deserialize(element, ClientStateData::class.java)) }
    }

    fun testStateRoundTripsThroughTheSerializerWithoutAnySecret() {
        state.save(Environment("Staging", "https://staging.example.com", mapOf("region" to "eu"), setOf("apiToken"), AuthConfig.Basic("bob"), listOf(Header("Authorization", "Bearer TOPSECRET"), Header("X-Id", "7"))))
        state.select("Staging")
        state.lastInputs.put(
            "k1",
            RequestInput(
                HttpMethod.POST, mapOf("id" to "5"), listOf(QueryParam("api_key", "TOPSECRET"), QueryParam("q", "x")),
                listOf(Header("Authorization", "Bearer TOPSECRET"), Header("X-Id", "9")), RequestBody.Form(listOf("password" to "TOPSECRET", "name" to "n")),
            ),
        )
        state.history.add(StoredEntry(1L, HttpMethod.GET, "http://h/x?api_key=<redacted>", 200, 5, 10, "Staging", null, "k1"))
        state.hostGuard.confirm("api.example.org")
        val xml = xmlOf(state)
        assertFalse(xml, xml.contains("TOPSECRET"))
        val fresh = reloaded(state)
        assertEquals("Staging", fresh.selected()?.name)
        val env = fresh.selected()!!
        assertEquals(mapOf("region" to "eu"), env.variables)
        assertEquals(setOf("apiToken"), env.secretVariables)
        assertEquals(AuthConfig.Basic("bob"), env.auth)
        assertEquals("", env.defaultHeaders.first { it.name == "Authorization" }.value)
        val input = fresh.lastInputs.get("k1")!!
        assertEquals(HttpMethod.POST, input.method)
        assertEquals("", input.query.first { it.name == "api_key" }.value)
        assertEquals("x", input.query.first { it.name == "q" }.value)
        assertEquals("k1", fresh.history.snapshot().single().endpointKey)
        assertTrue(fresh.hostGuard.isConfirmed("api.example.org"))
    }

    fun testEverythingIsBounded() {
        repeat(Limits.ENVIRONMENTS_MAX + 7) { state.save(Environment("e$it", "http://h$it")) }
        assertEquals(Limits.ENVIRONMENTS_MAX, state.environments().size)
        repeat(Limits.LAST_INPUT_MAX_ENDPOINTS + 9) { state.lastInputs.put("k$it", RequestInput(HttpMethod.GET)) }
        repeat(Limits.HISTORY_MAX_ENTRIES + 9) { state.history.add(StoredEntry(it.toLong(), HttpMethod.GET, "u", 200, 1, 1, "e")) }
        repeat(Limits.CONFIRMED_HOSTS_MAX + 9) { state.hostGuard.confirm("h$it.example.com") }
        val fresh = reloaded(state)
        assertEquals(Limits.ENVIRONMENTS_MAX, fresh.environments().size)
        assertEquals(Limits.LAST_INPUT_MAX_ENDPOINTS, fresh.lastInputs.size())
        assertEquals(Limits.HISTORY_MAX_ENTRIES, fresh.history.snapshot().size)
        assertEquals(Limits.CONFIRMED_HOSTS_MAX, fresh.hostGuard.snapshot().size)
    }

    fun testPanelRestoresEnvironmentMethodAndInputsOnCreation() {
        val ep = endpoint(HttpMethod.ANY, "/items", emptyList())
        state.save(Environment("Staging", "https://staging.example.com"))
        state.select("Staging")
        state.lastInputs.put(ep.key, RequestInput(HttpMethod.PUT, query = listOf(QueryParam("page", "3")), body = RequestBody.Text("application/json", "{\"a\":1}")))
        val restored = reloaded(state)
        val owner = owner()
        try {
            val s = section(owner, restored)
            s.show(ep)
            assertEquals(HttpMethod.PUT, s.currentForm()!!.method)
            assertEquals("3", s.currentForm()!!.query.single { it.name == "page" }.value)
            assertEquals(BodyType.JSON, s.currentForm()!!.bodyKind)
            assertEquals("https://staging.example.com", s.formForTest().baseUrlTextForTest())
            assertEquals(listOf("Staging"), s.environmentNames())
        } finally { Disposer.dispose(owner) }
    }

    fun testFirstEnvironmentFollowsTheFrameworkDefaultsAndEditsPersist() {
        val ep = endpoint(HttpMethod.ANY, "/ping", emptyList())
        val owner = owner()
        try {
            val s = section(owner)
            assertTrue(state.environments().isEmpty())
            s.show(ep)
            assertEquals("http://127.0.0.1:8000", state.selected()!!.baseUrl)
            s.currentForm()!!.method = HttpMethod.DELETE
            s.currentForm()!!.query = listOf(FormRow("force", "1"))
            s.rememberNowForTest()
            assertEquals(HttpMethod.DELETE, state.lastInputs.get(ep.key)!!.method)
        } finally { Disposer.dispose(owner) }
        val again = owner()
        try {
            val s = section(again, reloaded(state))
            s.show(ep)
            assertEquals(HttpMethod.DELETE, s.currentForm()!!.method)
            assertEquals("force", s.currentForm()!!.query.single().name)
        } finally { Disposer.dispose(again) }
    }

    fun testSecretsGoToTheCredentialStoreOnlyAndComeBackInANewWindow() {
        val ep = endpoint(HttpMethod.GET, "/me", emptyList())
        val owner = owner()
        try {
            val s = section(owner)
            s.show(ep)
            s.formForTest().chooseAuthForTest(AuthKind.BEARER)
            s.formForTest().typeSecretForTest("s3cr3t-token")
            val scope = state.selected()!!.name
            assertEquals("s3cr3t-token", store.get(scope, SecretKeys.TOKEN))
            s.rememberNowForTest()
            val xml = xmlOf(state)
            assertFalse(xml, xml.contains("s3cr3t-token"))
        } finally { Disposer.dispose(owner) }
        val second = owner()
        try {
            val s = section(second, reloaded(state))
            s.show(ep)
            s.formForTest().chooseAuthForTest(AuthKind.BEARER)
            assertEquals("s3cr3t-token", s.secretsForTest().get(SecretKeys.TOKEN))
            assertTrue(s.formForTest().secretHintForTest().contains("Saved"))
            assertTrue(s.send() is SendResult.Started)
            val header = engine.lastSpec!!.headers.first { it.name.equals("Authorization", true) }
            assertEquals("Bearer s3cr3t-token", header.value)
        } finally { Disposer.dispose(second) }
    }

    fun testAConfirmedHostIsNotAskedAgainAfterARestart() {
        val ep = endpoint(HttpMethod.GET, "/ping", emptyList())
        state.save(Environment("Remote", "https://api.example.org"))
        state.select("Remote")
        val owner = owner()
        try {
            val s = section(owner)
            s.show(ep)
            assertTrue(s.send() is SendResult.Started)
            assertEquals(listOf("api.example.org"), ui.asked)
        } finally { Disposer.dispose(owner) }
        val again = owner()
        try {
            val s = section(again, reloaded(state))
            s.show(ep)
            ui.asked.clear()
            assertTrue(s.send() is SendResult.Started)
            assertTrue("confirmed hosts are remembered", ui.asked.isEmpty())
        } finally { Disposer.dispose(again) }
    }

    fun testHistoryLinesSurviveButCarryNoBodyOrCredential() {
        val ep = endpoint(HttpMethod.GET, "/users", emptyList())
        val owner = owner()
        try {
            val s = section(owner)
            s.show(ep)
            s.send()
            engine.emit(*bufferedEvents(jsonResponse("""{"password":"TOPSECRET"}""")))
            ui.runPending()
            val xml = xmlOf(state)
            assertFalse(xml, xml.contains("TOPSECRET"))
            assertEquals(1, state.history.snapshot().size)
        } finally { Disposer.dispose(owner) }
        val again = owner()
        try {
            val s = section(again, reloaded(state))
            assertEquals(1, s.responseForTest().historyCount())
            assertTrue(s.responseForTest().historyTexts().single().contains("body not kept"))
        } finally { Disposer.dispose(again) }
    }

    // ---- the environment editor -----------------------------------------------------------------------------------------

    fun testEnvironmentEditorCreatesAnEnvironmentWithSecretsInTheStoreOnly() {
        val ep = endpoint(HttpMethod.GET, "/me", emptyList())
        val owner = owner()
        try {
            val s = section(owner)
            s.show(ep)
            s.showEnvironmentEditor(true)
            val editor = s.environmentEditorForTest()!!
            editor.startNewForTest()
            editor.setFieldsForTest("Staging", "https://staging.example.com", EnvAuthKind.BEARER, "", "env-token-123")
            editor.addVariableForTest(VarRow("tenant", "acme", false))
            editor.addVariableForTest(VarRow("apiKey", "var-secret-456", true))
            assertTrue(editor.save())
            val saved = state.environments().first { it.name == "Staging" }
            assertEquals(mapOf("tenant" to "acme"), saved.variables)
            assertEquals(setOf("apiKey"), saved.secretVariables)
            assertEquals(AuthConfig.Bearer, saved.auth)
            assertEquals("Staging", state.selected()!!.name)
            assertEquals("env-token-123", store.get("Staging", SecretKeys.TOKEN))
            assertEquals("var-secret-456", store.get("Staging", SecretKeys.variable("apiKey")))
            val xml = xmlOf(state)
            assertFalse(xml, xml.contains("env-token-123") || xml.contains("var-secret-456"))
            assertEquals("https://staging.example.com", s.formForTest().baseUrlTextForTest())
            assertTrue(s.environmentNames().containsAll(listOf("Staging")))
            assertEquals("env-token-123", s.secretsForTest().get(SecretKeys.TOKEN))
        } finally { Disposer.dispose(owner) }
    }

    fun testEnvironmentEditorRefusesBlankAndDuplicateNames() {
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint())
            s.showEnvironmentEditor(true)
            val editor = s.environmentEditorForTest()!!
            editor.startNewForTest()
            editor.setFieldsForTest("", "http://x", EnvAuthKind.NONE, "", "")
            assertFalse(editor.save())
            assertNotNull(editor.errorText())
            editor.setFieldsForTest("default", "http://x", EnvAuthKind.NONE, "", "")
            assertFalse("names are unique ignoring case", editor.save())
            assertEquals(1, state.environments().size)
        } finally { Disposer.dispose(owner) }
    }

    fun testSwitchingEnvironmentChangesBaseUrlAndSecretScope() {
        state.save(Environment("A", "http://a.local"))
        state.save(Environment("B", "http://b.local"))
        state.select("A")
        store.set("A", SecretKeys.TOKEN, "token-a")
        store.set("B", SecretKeys.TOKEN, "token-b")
        val owner = owner()
        try {
            val s = section(owner)
            s.show(endpoint(HttpMethod.GET, "/me", emptyList()))
            assertEquals("token-a", s.secretsForTest().get(SecretKeys.TOKEN))
            s.chooseEnvironmentForTest("B")
            assertEquals("http://b.local", s.formForTest().baseUrlTextForTest())
            assertEquals("token-b", s.secretsForTest().get(SecretKeys.TOKEN))
            assertEquals("B", state.selected()!!.name)
        } finally { Disposer.dispose(owner) }
    }

    fun testDraftRulesAreEnforcedWithoutAnyUi() {
        val draft = EnvironmentDraft("E", " http://x ", EnvAuthKind.API_KEY, "", "typed", ArrayList())
        repeat(Limits.ENVIRONMENT_VARIABLES_MAX + 20) { draft.vars.add(VarRow("v$it", "x", it % 2 == 0)) }
        val env = draft.toEnvironment()
        assertEquals("http://x", env.baseUrl)
        assertEquals(Limits.ENVIRONMENT_VARIABLES_MAX, env.variables.size + env.secretVariables.size)
        assertTrue("a secret variable never keeps its value", env.variables.none { it.key in env.secretVariables })
        assertEquals(AuthConfig.ApiKey("X-API-Key"), env.auth)
        assertTrue(draft.secretWrites().containsKey(SecretKeys.API_KEY))
        draft.authSecret = ""
        assertFalse("an empty secret field keeps what is stored", draft.secretWrites().containsKey(SecretKeys.API_KEY))
        assertNotNull(EnvironmentDraft("E", "u", vars = mutableListOf(VarRow("a"), VarRow("a"))).validationError(emptyList(), false))
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------------------

    fun testDisposeRemembersTheOpenFormAndLeavesNoBodyBehind() {
        val ep = endpoint(HttpMethod.POST, "/items", emptyList())
        val owner = owner()
        val s = section(owner)
        s.show(ep)
        s.currentForm()!!.apply { bodyKind = BodyType.JSON; bodyText = "{\"x\":1}" }
        Disposer.dispose(owner)
        val input = state.lastInputs.get(ep.key)!!
        assertEquals(RequestBody.Text("application/json", "{\"x\":1}"), input.body)
        assertTrue(s.historyForTest().withBodies().isEmpty())
        assertNull(s.secretsForTest().get(SecretKeys.TOKEN))
    }
}
