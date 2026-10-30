package com.routegenie.testmap

import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests (no IDE): path unification, the sink table, the conventions. */
class EndpointMatcherTest {
    private fun ep(method: HttpMethod, path: String, framework: Framework = Framework.FASTAPI, vararg params: PathParam, name: String? = null) =
        Endpoint(method, path, framework, Handler("h", "app.h"), SourceRef("app.py", 0, 1), routeName = name, pathParams = params.toList())

    private fun t(path: String) = UrlTemplate.parse(path)!!

    private fun mapped(matcher: EndpointMatcher, framework: Framework, verb: HttpMethod, path: String, sinks: List<ClientSink> = ClientSinks.all): String? =
        (matcher.match(sinks, verb, t(path), setOf(framework)) as? MatchResult.Mapped)?.endpoint?.path

    @Test
    fun `templates split into literal, hole and pattern segments`() {
        val template = t("/a/{?}/x{?}y/")
        assertEquals(listOf(CallSeg.Lit("a"), CallSeg.Hole, CallSeg.Pattern(listOf("x", null, "y"))), template.segments)
        assertTrue(template.trailingSlash)
        assertEquals("/a/{?}/x{?}y/", template.display)
        assertEquals("/", t("/").display)
    }

    @Test
    fun `a path with an empty middle segment or without a leading slash is not a template`() {
        assertNull(UrlTemplate.parse("/a//b"))
        assertNull(UrlTemplate.parse("a/b"))
    }

    @Test
    fun `a computed segment matches a parameter and never a literal`() {
        val m = EndpointMatcher(listOf(ep(HttpMethod.GET, "/items/{id}", params = arrayOf(PathParam("id"))), ep(HttpMethod.GET, "/items/new")))
        assertEquals("/items/{id}", mapped(m, Framework.FASTAPI, HttpMethod.GET, "/items/{?}"))
        assertEquals("/items/{id}", mapped(m, Framework.FASTAPI, HttpMethod.GET, "/items/a{?}"))
    }

    @Test
    fun `converters decide literal values`() {
        val m = EndpointMatcher(
            listOf(
                ep(HttpMethod.GET, "/n/{id}", Framework.DJANGO, PathParam("id", "int")),
                ep(HttpMethod.GET, "/u/{id}", Framework.DJANGO, PathParam("id", "uuid")),
            ),
        )
        assertEquals("/n/{id}", mapped(m, Framework.DJANGO, HttpMethod.GET, "/n/12"))
        assertNull(mapped(m, Framework.DJANGO, HttpMethod.GET, "/n/abc"))
        assertEquals("/u/{id}", mapped(m, Framework.DJANGO, HttpMethod.GET, "/u/123e4567-e89b-12d3-a456-426614174000"))
        assertNull(mapped(m, Framework.DJANGO, HttpMethod.GET, "/u/12"))
    }

    @Test
    fun `a path parameter takes one segment or more`() {
        val m = EndpointMatcher(listOf(ep(HttpMethod.GET, "/files/{p}", params = arrayOf(PathParam("p", "path"))), ep(HttpMethod.GET, "/docs/{p}/edit", params = arrayOf(PathParam("p", "path")))))
        assertEquals("/files/{p}", mapped(m, Framework.FASTAPI, HttpMethod.GET, "/files/a/b/c"))
        assertEquals("/files/{p}", mapped(m, Framework.FASTAPI, HttpMethod.GET, "/files/a"))
        assertNull(mapped(m, Framework.FASTAPI, HttpMethod.GET, "/files"))
        assertEquals("/docs/{p}/edit", mapped(m, Framework.FASTAPI, HttpMethod.GET, "/docs/x/y/edit"))
    }

    @Test
    fun `a segment mixing text and a parameter is matched by its text`() {
        val m = EndpointMatcher(listOf(ep(HttpMethod.GET, "/files/{name}.{ext}", params = arrayOf(PathParam("name"), PathParam("ext")))))
        assertEquals("/files/{name}.{ext}", mapped(m, Framework.FASTAPI, HttpMethod.GET, "/files/a.txt"))
        assertNull(mapped(m, Framework.FASTAPI, HttpMethod.GET, "/files/atxt"))
    }

    @Test
    fun `frameworks are kept apart`() {
        val m = EndpointMatcher(listOf(ep(HttpMethod.GET, "/x", Framework.FLASK)))
        assertNull(mapped(m, Framework.FASTAPI, HttpMethod.GET, "/x"))
        assertNotNull(mapped(m, Framework.FLASK, HttpMethod.GET, "/x"))
    }

    @Test
    fun `any endpoints take every verb and werkzeug answers head from get`() {
        val m = EndpointMatcher(listOf(ep(HttpMethod.ANY, "/any", Framework.DJANGO), ep(HttpMethod.GET, "/f", Framework.FLASK)))
        assertNotNull(mapped(m, Framework.DJANGO, HttpMethod.PATCH, "/any"))
        assertNotNull(mapped(m, Framework.FLASK, HttpMethod.HEAD, "/f"))
        assertNull(mapped(m, Framework.FLASK, HttpMethod.POST, "/f"))
    }

    @Test
    fun `static segments win only under the flask policy`() {
        val endpoints = listOf(ep(HttpMethod.GET, "/u/me", Framework.FLASK), ep(HttpMethod.GET, "/u/{id}", Framework.FLASK, PathParam("id")))
        val m = EndpointMatcher(endpoints)
        assertEquals("/u/me", mapped(m, Framework.FLASK, HttpMethod.GET, "/u/me"))
        val ambiguous = EndpointMatcher(endpoints.map { it.copy(framework = Framework.FASTAPI) })
        assertTrue(ambiguous.match(ClientSinks.all, HttpMethod.GET, t("/u/me"), setOf(Framework.FASTAPI)) is MatchResult.Ambiguous)
    }

    @Test
    fun `names are looked up per framework`() {
        val m = EndpointMatcher(listOf(ep(HttpMethod.GET, "/a", Framework.DJANGO, name = "ns:a"), ep(HttpMethod.GET, "/b", Framework.FLASK, name = "ns:a")))
        assertEquals(listOf("/a"), m.named("ns:a", setOf(Framework.DJANGO)).map { it.path })
        assertTrue(m.named("missing", Framework.entries.toSet()).isEmpty())
    }

    @Test
    fun `the sink table is consistent`() {
        assertTrue(ClientSinks.candidateWords.size <= Limits.TEST_CLIENT_WORDS_MAX)
        assertEquals(ClientSinks.all.size, ClientSinks.all.map { it.id }.toSet().size)
        // A name is never recognized by the method it calls: every sink needs an import origin, a factory, a fixture or a base class.
        ClientSinks.all.forEach { sink ->
            assertTrue(sink.id, sink.constructorOrigins.isNotEmpty() || sink.factoryMethods.isNotEmpty())
            assertTrue(sink.id, sink.targets.isNotEmpty())
        }
        assertEquals(setOf("get", "post", "put", "patch", "delete", "head", "options", "trace", "request", "generic", "open"), ClientSinks.callMethodNames)
    }

    @Test
    fun `the owner's scope for FastAPI is pytest and unittest only, kept as data`() {
        assertEquals(setOf(TestStyle.PYTEST, TestStyle.UNITTEST), TestConventions.stylesByFramework.getValue(Framework.FASTAPI))
        assertEquals(setOf(TestStyle.PYTEST, TestStyle.UNITTEST), TestConventions.stylesByFramework.getValue(Framework.FLASK))
        assertTrue(TestConventions.allows(Framework.DJANGO, TestStyle.DJANGO_TESTCASE))
        assertTrue(!TestConventions.allows(Framework.FASTAPI, TestStyle.DJANGO_TESTCASE))
    }

    @Test
    fun `test files and functions follow the runner defaults`() {
        assertTrue(TestConventions.isTestFile("a/test_x.py"))
        assertTrue(TestConventions.isTestFile("a/x_test.py"))
        assertTrue(TestConventions.isTestFile("shop/tests.py"))
        assertTrue(!TestConventions.isTestFile("a/conftest.py"))
        assertTrue(!TestConventions.isTestFile("a/helpers.py"))
        assertTrue(TestConventions.isPytestClass("TestItems", false))
        assertTrue(!TestConventions.isPytestClass("TestItems", true))
        assertTrue(!TestConventions.isPytestClass("Items", false))
        assertEquals(TestMark.SKIPPED, TestConventions.markOf(com.routegenie.frameworks.common.RefFact(listOf("pytest", "mark", "skip"))))
        assertEquals(TestMark.NONE, TestConventions.markOf(com.routegenie.frameworks.common.RefFact(listOf("app", "skip"))))
    }
}
