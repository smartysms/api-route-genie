package com.routegenie.client

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.routegenie.Limits
import com.routegenie.clientide.ClientStateData
import com.routegenie.clientide.ClientStateMapper
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClientSecurityTest {
    private val secret = "S3CR3T-VALUE-9f2c"

    @Test fun `redactor masks userinfo and sensitive query values only`() {
        assertEquals("http://<redacted>@h/p?a=1&api_key=<redacted>&token=<redacted>", Redactor.url("http://u:pw@h/p?a=1&api_key=XYZ&token=abc"))
        assertEquals("http://h/p?custom=<redacted>&b=2", Redactor.url("http://h/p?custom=zzz&b=2", setOf("custom")))
        assertEquals("http://h/p", Redactor.url("http://h/p"))
    }

    @Test fun `headers are redacted by name`() {
        assertEquals(Redactor.PLACEHOLDER, Redactor.header("Authorization", "Bearer x"))
        assertEquals(Redactor.PLACEHOLDER, Redactor.header("x-api-key", "x"))
        assertEquals(Redactor.PLACEHOLDER, Redactor.header("X-Mine", "x", setOf("x-mine")))
        assertEquals("application/json", Redactor.header("Accept", "application/json"))
    }

    @Test fun `host classification`() {
        for (h in listOf("localhost", "api.localhost", "127.0.0.1", "127.9.9.9", "::1", "[::1]", "::ffff:127.0.0.1")) assertEquals(h, HostClass.LOOPBACK, HostClasses.classify(h))
        for (h in listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.1.5", "169.254.1.1", "fd00::1", "fe80::1")) assertEquals(h, HostClass.PRIVATE, HostClasses.classify(h))
        for (h in listOf("example.com", "localhost.evil.com", "172.32.0.1", "8.8.8.8", "2001:db8::1", "127.0.0.1.evil.com")) assertEquals(h, HostClass.PUBLIC, HostClasses.classify(h))
    }

    @Test fun `guard asks once per public host`() {
        val g = HostGuard()
        val spec = RequestSpec(HttpMethod.GET, "https://API.Example.com/x")
        assertTrue(g.needsConfirmation(spec))
        g.confirm("api.example.com")
        assertFalse(g.needsConfirmation(spec))
        assertFalse(g.needsConfirmation(RequestSpec(HttpMethod.GET, "http://127.0.0.1:8000/x")))
        assertFalse(g.needsConfirmation(RequestSpec(HttpMethod.GET, "http://192.168.0.2/x")))
        assertFalse(g.needsConfirmation(RequestSpec(HttpMethod.GET, "garbage")))
        assertTrue(g.isAllowed("127.0.0.1"))
        assertFalse(g.isAllowed("other.example.org"))
    }

    @Test fun `confirmed hosts are bounded`() {
        val g = HostGuard()
        repeat(Limits.CONFIRMED_HOSTS_MAX + 20) { g.confirm("h$it.example.com") }
        assertEquals(Limits.CONFIRMED_HOSTS_MAX, g.snapshot().size)
        assertFalse(g.isConfirmed("h0.example.com"))
    }

    @Test fun `http export replaces credentials by placeholders`() {
        val spec = RequestSpec(
            HttpMethod.POST, "http://h/p?api_key=$secret&x=1",
            listOf(Header("Authorization", "Bearer $secret"), Header("X-Custom", secret), Header("Accept", "application/json")),
            RequestBody.Text("application/json", "{\"a\":1}"),
        )
        val text = HttpFileExport.export("create", spec, sensitiveHeaders = setOf("x-custom"))
        assertFalse(text, text.contains(secret))
        assertTrue(text.contains("Authorization: Bearer {{token}}"))
        assertTrue(text.contains("X-Custom: {{x_custom}}"))
        assertTrue(text.startsWith("### create\nPOST http://h/p?api_key={{api_key}}&x=1\n"))
        assertTrue(text.contains("{\"a\":1}"))
    }

    @Test fun `persisted xml of environments inputs and history contains no secret`() {
        val env = Environment(
            "dev", "http://127.0.0.1:8000", variables = mapOf("host" to "h"), secretVariables = setOf("tok"),
            auth = AuthConfig.Basic("alice"), defaultHeaders = listOf(Header("Authorization", "Bearer $secret"), Header("X-Ok", "fine")),
        )
        val input = LastInputs.sanitize(
            RequestInput(
                HttpMethod.POST, mapOf("id" to "5"),
                query = listOf(QueryParam("api_key", secret), QueryParam("page", "2")),
                headers = listOf(Header("Authorization", "Bearer $secret"), Header("X-Api-Key", secret), Header("X-Ok", "v")),
                body = RequestBody.Form(listOf("password" to secret, "name" to "n")),
            ),
        )
        val request = PreparedRequest(
            RequestSpec(HttpMethod.GET, "http://h/p?api_key=$secret&token=$secret&x=1", listOf(Header("Authorization", "Bearer $secret"))),
            setOf("authorization"), emptySet(), TlsMode.IDE_TRUST,
        )
        val history = RequestHistory()
        history.record(request, ResponseData(200, "OK", emptyList(), ResponseBody.EMPTY, 1, 1, "http://h/p?api_key=$secret", emptyList(), "HTTP/1.1"), "dev")
        val data = ClientStateData().apply {
            environments.add(ClientStateMapper.toData(env))
            lastInputs.add(ClientStateMapper.toData("k", input))
            this.history.addAll(history.snapshot().map { ClientStateMapper.toData(it) })
        }
        val xml = JDOMUtil.write(XmlSerializer.serialize(data))
        assertFalse(xml, xml.contains(secret))
        assertTrue(xml, xml.contains("alice"))
        assertTrue(xml, xml.contains("X-Ok"))
        // round trip keeps the non secret content
        val back = XmlSerializer.deserialize(JDOMUtil.load(xml), ClientStateData::class.java)
        assertEquals("dev", ClientStateMapper.fromData(back.environments.single()).name)
        val restored = ClientStateMapper.fromData(back.lastInputs.single())!!
        assertEquals(HttpMethod.POST, restored.method)
        assertEquals("2", restored.query.first { it.name == "page" }.value)
        assertEquals("", restored.query.first { it.name == "api_key" }.value)
        assertEquals(1, back.history.size)
        assertEquals("http://h/p?api_key=<redacted>&token=<redacted>&x=1", ClientStateMapper.fromData(back.history.single())!!.url)
    }

    @Test fun `no log call in the client packages prints request data`() {
        val offenders = listOf("client", "clientide").flatMap { ProjectFiles.kotlinSources(File(ProjectFiles.mainKotlin, "com/routegenie/$it")) }
            .filter { !it.path.replace('\\', '/').contains("/client/curl/") }
            .filter { f -> f.readLines().any { Regex("""\b(println|LOG\.|Logger|\.log\()""").containsMatchIn(it) && !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") } }
            .map { ProjectFiles.relative(it) }
        // The one allowed logger call writes only the class name of a failing listener.
        assertEquals(listOf("src/main/kotlin/com/routegenie/client/JdkHttpEngine.kt"), offenders)
    }

    @Test fun `every engine and prepare message exists in the bundle`() {
        val keys = ProjectFiles.properties(File(ProjectFiles.mainResources, "messages/RouteGenieBundle.properties")).stringPropertyNames()
        val missing = ErrorKind.entries.map { it.bundleKey }.filter { it !in keys }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `default base urls per framework`() {
        assertEquals("http://127.0.0.1:8000", DevServerDefaults.baseUrl(Framework.DJANGO))
        assertEquals("http://127.0.0.1:8000", DevServerDefaults.baseUrl(Framework.DRF))
        assertEquals("http://127.0.0.1:8000", DevServerDefaults.baseUrl(Framework.FASTAPI))
        assertEquals("http://127.0.0.1:5000", DevServerDefaults.baseUrl(Framework.FLASK))
    }

    @Test fun `url support parses redirects and rejects bad ones`() {
        val base = (UrlSupport.parse("http://127.0.0.1:8000/a/b") as UrlResult.Ok).url
        assertEquals("http://127.0.0.1:8000/c", (UrlSupport.resolve(base, "/c") as UrlResult.Ok).url.text)
        assertEquals("http://127.0.0.1:8000/a/d", (UrlSupport.resolve(base, "d") as UrlResult.Ok).url.text)
        assertEquals("http://other:1/x", (UrlSupport.resolve(base, "http://other:1/x") as UrlResult.Ok).url.text)
        assertEquals("http://127.0.0.1:8000", base.origin.replace(":8000", ":8000"))
        assertTrue(UrlSupport.parse("http://xn--bcher-kva.example/").let { it is UrlResult.Ok })
        assertEquals("http://xn--bcher-kva.example/", (UrlSupport.parse("http://bücher.example/") as UrlResult.Ok).url.text)
    }
}
