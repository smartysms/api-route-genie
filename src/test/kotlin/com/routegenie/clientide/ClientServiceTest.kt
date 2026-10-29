package com.routegenie.clientide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.client.AuthConfig
import com.routegenie.client.Environment
import com.routegenie.client.Header
import com.routegenie.client.LocalServer
import com.routegenie.client.PreparedRequest
import com.routegenie.client.RequestInput
import com.routegenie.client.RequestSpec
import com.routegenie.client.TlsMode
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class ClientServiceTest : BasePlatformTestCase() {
    private fun prepared(url: String, auth: Boolean = false) = PreparedRequest(
        RequestSpec(HttpMethod.GET, url, if (auth) listOf(Header("Authorization", "Bearer TOPSECRET")) else emptyList()),
        if (auth) setOf("authorization") else emptySet(), emptySet(), TlsMode.IDE_TRUST,
    )

    fun testStateKeepsEnvironmentsDefaultsAndRoundTrips() {
        // A fresh instance: the project's own state may already hold the Default environment a request panel created in an earlier test.
        val state = RouteGenieClientState()
        val env = state.ensureDefault(Framework.FLASK, "local")
        assertEquals("http://127.0.0.1:5000", env.baseUrl)
        state.save(Environment("staging", "https://s.example.com", auth = AuthConfig.Bearer, secretVariables = setOf("t")))
        state.select("staging")
        val data = state.getState()
        val fresh = RouteGenieClientState()
        fresh.loadState(data)
        assertEquals(setOf("local", "staging"), fresh.environments().map { it.name }.toSet())
        assertEquals("staging", fresh.selected()?.name)
        assertEquals(AuthConfig.Bearer, fresh.environments().first { it.name == "staging" }.auth)
        assertEquals(setOf("t"), fresh.environments().first { it.name == "staging" }.secretVariables)
    }

    fun testSendRecordsRedactedHistoryAndDisposeDeletesSpillFiles() {
        LocalServer().use { server ->
            server.on("/big") { ex ->
                ex.sendResponseHeaders(200, 0)
                val chunk = ByteArray(64 * 1024)
                repeat(120) { ex.responseBody.write(chunk) }
                ex.responseBody.close()
            }
            val service = RouteGenieClientService(project, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
            val state = RouteGenieClientState.getInstance(project)
            val before = state.history.snapshot().size
            val response = runBlocking { service.send(prepared(server.url("/big?api_key=TOPSECRET"), auth = true), "dev") }
            assertEquals(200, response.status)
            val file = response.bodyFile
            assertNotNull(file)
            assertTrue(Files.exists(file!!))
            service.responses.set(response)
            val entries = state.history.snapshot()
            assertEquals(before + 1, entries.size)
            assertFalse(entries.first().url.contains("TOPSECRET"))
            service.dispose()
            assertFalse(Files.exists(file))
            // The engine is closed: a later send fails as cancelled instead of reviving threads.
            val after = runBlocking { service.send(prepared(server.url("/big")), "dev") }
            assertNotNull(after.error)
        }
    }
}
