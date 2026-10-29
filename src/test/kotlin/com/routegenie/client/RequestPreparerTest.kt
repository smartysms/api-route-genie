package com.routegenie.client

import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.SourceRef
import com.routegenie.testing.PerfBudget
import com.routegenie.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64

class RequestPreparerTest {
    private val env = Environment("dev", "http://127.0.0.1:8000", variables = mapOf("host" to "api.local", "v" to "v1", "id" to "42"))

    private fun endpoint(path: String, framework: Framework = Framework.FASTAPI, vararg params: PathParam, method: HttpMethod = HttpMethod.GET) =
        Endpoint(method, path, framework, Handler("h", "m.h"), SourceRef("a.py", 0, 1), pathParams = params.toList())

    private fun ready(result: PrepareResult): PreparedRequest {
        if (result is PrepareResult.Invalid) fail("expected ready, got ${result.errors}")
        return (result as PrepareResult.Ready).request
    }

    private fun errors(result: PrepareResult): List<FieldError> {
        if (result is PrepareResult.Ready) fail("expected errors, got ${result.request}")
        return (result as PrepareResult.Invalid).errors
    }

    private fun prep(
        ep: Endpoint,
        input: RequestInput = RequestInput(HttpMethod.GET),
        e: Environment = env,
        secrets: SecretLookup = SecretLookup.NONE,
    ) = RequestPreparer.prepare(ep, input, e, secrets, BuiltIns(now = { Instant.ofEpochSecond(1_700_000_000) }, uuid = { "U-1" }, randomInt = { 7 }))

    private fun url(ep: Endpoint, input: RequestInput = RequestInput(HttpMethod.GET), e: Environment = env) = ready(prep(ep, input, e)).spec.url

    // --- paths and converters ------------------------------------------------------------------------------------------

    @Test fun `plain path joins the base url`() = assertEquals("http://127.0.0.1:8000/items", url(endpoint("/items")))

    @Test fun `base url with trailing slash and prefix`() =
        assertEquals("http://h:1/api/items", url(endpoint("/items"), e = env.copy(baseUrl = "http://h:1/api/")))

    @Test fun `int accepts digits`() =
        assertEquals("http://127.0.0.1:8000/u/42", url(endpoint("/u/{id}", Framework.DJANGO, PathParam("id", "int")), RequestInput(HttpMethod.GET, mapOf("id" to "42"))))

    @Test fun `int rejects letters`() = assertEquals(
        "prepare.error.type_int",
        errors(prep(endpoint("/u/{id}", Framework.DJANGO, PathParam("id", "int")), RequestInput(HttpMethod.GET, mapOf("id" to "4x")))).single().bundleKey,
    )

    @Test fun `int rejects negative and decimal`() {
        val ep = endpoint("/u/{id}", Framework.FLASK, PathParam("id", "int"))
        listOf("-1", "1.5", " 1", "").forEach { assertTrue("'$it'", errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("id" to it)))).isNotEmpty()) }
    }

    @Test fun `uuid accepts canonical form`() {
        val ep = endpoint("/o/{u}", Framework.FASTAPI, PathParam("u", "uuid"))
        assertEquals("http://127.0.0.1:8000/o/123e4567-e89b-12d3-a456-426614174000", url(ep, RequestInput(HttpMethod.GET, mapOf("u" to "123e4567-e89b-12d3-a456-426614174000"))))
    }

    @Test fun `uuid rejects short and non hex`() {
        val ep = endpoint("/o/{u}", Framework.FASTAPI, PathParam("u", "uuid"))
        listOf("123", "zzzzzzzz-e89b-12d3-a456-426614174000", "123e4567e89b12d3a456426614174000").forEach {
            assertEquals("prepare.error.type_uuid", errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("u" to it)))).single().bundleKey)
        }
    }

    @Test fun `django uuid is lower case only`() {
        val ep = endpoint("/o/{u}", Framework.DJANGO, PathParam("u", "uuid"))
        val upper = "123E4567-E89B-12D3-A456-426614174000"
        assertTrue(errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("u" to upper)))).isNotEmpty())
        assertNotNull(ready(prep(endpoint("/o/{u}", Framework.FASTAPI, PathParam("u", "uuid")), RequestInput(HttpMethod.GET, mapOf("u" to upper)))))
    }

    @Test fun `slug accepts hyphen underscore and rejects space`() {
        val ep = endpoint("/p/{s}", Framework.DJANGO, PathParam("s", "slug"))
        assertEquals("http://127.0.0.1:8000/p/my-post_1", url(ep, RequestInput(HttpMethod.GET, mapOf("s" to "my-post_1"))))
        assertEquals("prepare.error.type_slug", errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("s" to "my post")))).single().bundleKey)
    }

    @Test fun `str rejects a slash`() {
        val ep = endpoint("/n/{name}", Framework.FASTAPI, PathParam("name", "str"))
        assertEquals("prepare.error.type_str", errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("name" to "a/b")))).single().bundleKey)
    }

    @Test fun `untyped param behaves like str`() {
        val ep = endpoint("/n/{name}", Framework.FASTAPI, PathParam("name"))
        assertEquals("http://127.0.0.1:8000/n/bob", url(ep, RequestInput(HttpMethod.GET, mapOf("name" to "bob"))))
        assertTrue(errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("name" to "a/b")))).isNotEmpty())
    }

    @Test fun `str value is percent encoded`() {
        val ep = endpoint("/n/{name}", Framework.FASTAPI, PathParam("name", "str"))
        assertEquals("http://127.0.0.1:8000/n/a%20b%26c%3Fd%23e", url(ep, RequestInput(HttpMethod.GET, mapOf("name" to "a b&c?d#e"))))
    }

    @Test fun `non ascii path value is utf8 encoded`() {
        val ep = endpoint("/n/{name}", Framework.FASTAPI, PathParam("name", "str"))
        assertEquals("http://127.0.0.1:8000/n/%C3%A9", url(ep, RequestInput(HttpMethod.GET, mapOf("name" to "é"))))
    }

    @Test fun `path converter keeps slashes but encodes segments`() {
        val ep = endpoint("/files/{p}", Framework.FASTAPI, PathParam("p", "path"))
        assertEquals("http://127.0.0.1:8000/files/a/b%20c/d.txt", url(ep, RequestInput(HttpMethod.GET, mapOf("p" to "a/b c/d.txt"))))
    }

    @Test fun `flask path value must not start with a slash`() {
        val ep = endpoint("/files/{p}", Framework.FLASK, PathParam("p", "path"))
        assertEquals("prepare.error.type_path", errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("p" to "/etc")))).single().bundleKey)
    }

    @Test fun `float accepts integer form on fastapi but flask needs a dot`() {
        assertNotNull(ready(prep(endpoint("/f/{x}", Framework.FASTAPI, PathParam("x", "float")), RequestInput(HttpMethod.GET, mapOf("x" to "3")))))
        assertTrue(errors(prep(endpoint("/f/{x}", Framework.FLASK, PathParam("x", "float")), RequestInput(HttpMethod.GET, mapOf("x" to "3")))).isNotEmpty())
        assertNotNull(ready(prep(endpoint("/f/{x}", Framework.FLASK, PathParam("x", "float")), RequestInput(HttpMethod.GET, mapOf("x" to "3.5")))))
    }

    @Test fun `any converter accepts everything`() =
        assertNotNull(ready(prep(endpoint("/a/{x}", Framework.FLASK, PathParam("x", "any")), RequestInput(HttpMethod.GET, mapOf("x" to "whatever")))))

    @Test fun `missing path value is a required error naming the field`() {
        val e = errors(prep(endpoint("/u/{id}", Framework.DJANGO, PathParam("id", "int")))).single()
        assertEquals(FieldLocation.PATH, e.location)
        assertEquals("id", e.name)
        assertEquals("prepare.error.required", e.bundleKey)
    }

    @Test fun `every bad path value is reported not just the first`() {
        val ep = endpoint("/u/{a}/{b}", Framework.DJANGO, PathParam("a", "int"), PathParam("b", "int"))
        assertEquals(setOf("a", "b"), errors(prep(ep, RequestInput(HttpMethod.GET, mapOf("a" to "x", "b" to "y")))).map { it.name }.toSet())
    }

    @Test fun `fastapi inline converter placeholder is replaced`() {
        val ep = endpoint("/files/{p:path}", Framework.FASTAPI, PathParam("p", "path"))
        assertEquals("http://127.0.0.1:8000/files/x/y", url(ep, RequestInput(HttpMethod.GET, mapOf("p" to "x/y"))))
    }

    @Test fun `same parameter twice is filled twice`() {
        val ep = endpoint("/a/{id}/b/{id}", Framework.FASTAPI, PathParam("id", "int"))
        assertEquals("http://127.0.0.1:8000/a/5/b/5", url(ep, RequestInput(HttpMethod.GET, mapOf("id" to "5"))))
    }

    // --- query ----------------------------------------------------------------------------------------------------------

    @Test fun `query is appended and encoded`() =
        assertEquals("http://127.0.0.1:8000/s?q=a%20b&n=1", url(endpoint("/s"), RequestInput(HttpMethod.GET, query = listOf(QueryParam("q", "a b"), QueryParam("n", "1")))))

    @Test fun `disabled query params are skipped`() =
        assertEquals("http://127.0.0.1:8000/s", url(endpoint("/s"), RequestInput(HttpMethod.GET, query = listOf(QueryParam("q", "x", enabled = false)))))

    @Test fun `repeated query keys stay repeated`() =
        assertEquals("http://127.0.0.1:8000/s?t=a&t=b", url(endpoint("/s"), RequestInput(HttpMethod.GET, query = listOf(QueryParam("t", "a"), QueryParam("t", "b")))))

    @Test fun `array style query name is encoded`() =
        assertEquals("http://127.0.0.1:8000/s?ids%5B%5D=1", url(endpoint("/s"), RequestInput(HttpMethod.GET, query = listOf(QueryParam("ids[]", "1")))))

    @Test fun `json braces in a query value are encoded`() =
        assertEquals("http://127.0.0.1:8000/s?f=%7B%22a%22%3A1%7D", url(endpoint("/s"), RequestInput(HttpMethod.GET, query = listOf(QueryParam("f", "{\"a\":1}")))))


    // --- variables ------------------------------------------------------------------------------------------------------

    @Test fun `variable in the base url`() =
        assertEquals("http://api.local/items", url(endpoint("/items"), e = env.copy(baseUrl = "http://{{host}}")))

    @Test fun `variable in query header and body`() {
        val r = ready(
            prep(
                endpoint("/s"),
                RequestInput(HttpMethod.POST, query = listOf(QueryParam("v", "{{v}}")), headers = listOf(Header("X-V", "{{v}}")), body = RequestBody.Text("text/plain", "id={{id}}")),
            ),
        )
        assertTrue(r.spec.url.endsWith("?v=v1"))
        assertEquals("v1", r.spec.headers.first { it.name == "X-V" }.value)
        assertEquals("id=42", (r.spec.body as RequestBody.Text).text)
    }

    @Test fun `variable in a path value is validated after substitution`() {
        val ep = endpoint("/u/{id}", Framework.DJANGO, PathParam("id", "int"))
        assertEquals("http://127.0.0.1:8000/u/42", url(ep, RequestInput(HttpMethod.GET, mapOf("id" to "{{id}}"))))
    }

    @Test fun `undefined variable blocks and names the variable`() {
        val e = errors(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("X", "{{nope}}"))))).single()
        assertEquals(FieldLocation.VARIABLE, e.location)
        assertEquals("nope", e.name)
    }

    @Test fun `variables are not expanded recursively`() {
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("X", "{{a}}"))), env.copy(variables = mapOf("a" to "{{b}}", "b" to "x"))))
        assertEquals("{{b}}", r.spec.headers.single().value)
    }

    @Test fun `builtin variables are filled`() {
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("A", "{{\$uuid}}"), Header("B", "{{\$timestamp}}"), Header("C", "{{\$randomInt}}")))))
        assertEquals(listOf("U-1", "1700000000", "7"), r.spec.headers.map { it.value })
    }

    @Test fun `unknown builtin is undefined`() =
        assertTrue(errors(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("A", "{{\$nope}}"))))).isNotEmpty())

    @Test fun `variable with spaces inside braces`() =
        assertEquals("http://api.local/items", url(endpoint("/items"), e = env.copy(baseUrl = "http://{{ host }}")))

    // --- base url and method -------------------------------------------------------------------------------------------

    @Test fun `empty base url is an error`() = assertEquals("prepare.error.no_base_url", errors(prep(endpoint("/s"), e = env.copy(baseUrl = ""))).first().bundleKey)

    @Test fun `base url without scheme is an url error`() =
        assertEquals("prepare.error.url", errors(prep(endpoint("/s"), e = env.copy(baseUrl = "localhost:8000"))).single().bundleKey)

    @Test fun `underscore host is reported`() {
        val e = errors(prep(endpoint("/s"), e = env.copy(baseUrl = "http://my_service:8000"))).single()
        assertTrue(e.args.single().contains("underscore"))
    }

    @Test fun `any is rejected by the request spec itself`() {
        try {
            RequestSpec(HttpMethod.ANY, "http://x/")
            fail("ANY must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("concrete"))
        }
    }

    @Test fun `any is rejected by the preparer without throwing`() =
        assertEquals(FieldLocation.METHOD, errors(prep(endpoint("/s", method = HttpMethod.ANY), RequestInput(HttpMethod.ANY))).single().location)

    // --- headers --------------------------------------------------------------------------------------------------------

    @Test fun `request header overrides environment default case insensitively`() {
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("accept", "text/plain"))), env.copy(defaultHeaders = listOf(Header("Accept", "application/json"), Header("X-Env", "1")))))
        assertEquals(listOf("X-Env" to "1", "accept" to "text/plain"), r.spec.headers.map { it.name to it.value })
    }

    @Test fun `disabled headers are dropped`() =
        assertTrue(ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("A", "1", enabled = false))))).spec.headers.isEmpty())

    @Test fun `header with a line break is refused`() =
        assertEquals("prepare.error.header_invalid", errors(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("A", "x\r\nB: y"))))).single().bundleKey)

    // --- auth -----------------------------------------------------------------------------------------------------------

    private fun secrets(vararg pairs: Pair<String, String>) = SecretLookup { pairs.toMap()[it] }

    @Test fun `bearer adds the header and marks it sensitive`() {
        val r = ready(prep(endpoint("/s"), e = env.copy(auth = AuthConfig.Bearer), secrets = secrets(SecretKeys.TOKEN to "tok123")))
        assertEquals("Bearer tok123", r.spec.headers.single { it.name == "Authorization" }.value)
        assertTrue("authorization" in r.sensitiveHeaders)
    }

    @Test fun `basic auth encodes user and password with colons`() {
        val r = ready(prep(endpoint("/s"), e = env.copy(auth = AuthConfig.Basic("user")), secrets = secrets(SecretKeys.PASSWORD to "p@ss:word")))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:p@ss:word".toByteArray()), r.spec.headers.single().value)
    }

    @Test fun `api key in a header`() {
        val r = ready(prep(endpoint("/s"), e = env.copy(auth = AuthConfig.ApiKey("X-Key")), secrets = secrets(SecretKeys.API_KEY to "k1")))
        assertEquals("k1", r.spec.headers.single { it.name == "X-Key" }.value)
        assertTrue("x-key" in r.sensitiveHeaders)
    }

    @Test fun `api key in the query`() {
        val r = ready(prep(endpoint("/s"), e = env.copy(auth = AuthConfig.ApiKey("key", ApiKeyLocation.QUERY)), secrets = secrets(SecretKeys.API_KEY to "k 1")))
        assertTrue(r.spec.url.endsWith("/s?key=k%201"))
        assertTrue("key" in r.sensitiveQuery)
        assertFalse(r.displayUrl.contains("k%201"))
    }

    @Test fun `missing secret blocks sending`() =
        assertEquals("prepare.error.secret_missing", errors(prep(endpoint("/s"), e = env.copy(auth = AuthConfig.Bearer))).single().bundleKey)

    @Test fun `explicit authorization header wins over the environment auth`() {
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("Authorization", "Custom x"))), env.copy(auth = AuthConfig.Bearer), secrets(SecretKeys.TOKEN to "t")))
        assertEquals("Custom x", r.spec.headers.single().value)
    }

    @Test fun `request auth overrides environment auth`() {
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, auth = AuthConfig.None), env.copy(auth = AuthConfig.Bearer)))
        assertTrue(r.spec.headers.isEmpty())
    }

    @Test fun `secret variable comes from the secret store and is sensitive`() {
        val e = env.copy(secretVariables = setOf("tok"))
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.GET, headers = listOf(Header("X-T", "{{tok}}"))), e, secrets(SecretKeys.variable("tok") to "S3CRET")))
        assertEquals("S3CRET", r.spec.headers.single().value)
        assertTrue("x-t" in r.sensitiveHeaders)
    }

    @Test fun `secrets do not show in text forms`() {
        val r = ready(prep(endpoint("/s"), e = env.copy(auth = AuthConfig.Bearer), secrets = secrets(SecretKeys.TOKEN to "TOPSECRET")))
        assertFalse(r.toString().contains("TOPSECRET"))
        assertFalse(r.spec.toString().contains("TOPSECRET"))
        assertFalse(r.redactedHeaders.toString().contains("TOPSECRET"))
    }

    // --- body -----------------------------------------------------------------------------------------------------------

    @Test fun `form and multipart values get variables`() {
        val r = ready(prep(endpoint("/s"), RequestInput(HttpMethod.POST, body = RequestBody.Form(listOf("a" to "{{v}}")))))
        assertEquals(listOf("a" to "v1"), (r.spec.body as RequestBody.Form).fields)
        val m = ready(prep(endpoint("/s"), RequestInput(HttpMethod.POST, body = RequestBody.Multipart(listOf(MultipartPart("p", value = "{{id}}"))))))
        assertEquals("42", (m.spec.body as RequestBody.Multipart).parts.single().value)
    }

    // --- speed ----------------------------------------------------------------------------------------------------------

    @Test fun `preparing a typical request is under the budget`() {
        val ep = endpoint("/users/{id}/posts/{slug}", Framework.DJANGO, PathParam("id", "int"), PathParam("slug", "slug"))
        val input = RequestInput(HttpMethod.GET, mapOf("id" to "42", "slug" to "hello-world"), query = listOf(QueryParam("limit", "10"), QueryParam("q", "a b")), headers = listOf(Header("Accept", "application/json")))
        val e = env.copy(auth = AuthConfig.Bearer)
        val s = secrets(SecretKeys.TOKEN to "t")
        PerfBudget.assertWithin("prepare-request", Limits.BUDGET_PREPARE_REQUEST_MS, warmups = 2000, runs = 31) { ready(prep(ep, input, e, s)) }
    }
}
