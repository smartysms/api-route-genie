package com.routegenie.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun endpoint(offset: Int = 10, line: Int = 3, path: String = "/users/{id}") = Endpoint(
        method = HttpMethod.GET,
        path = path,
        framework = Framework.FASTAPI,
        handler = Handler("get_user", "app.users.get_user"),
        source = SourceRef("app/users.py", offset, line),
    )

    @Test
    fun `http verbs parse case-insensitively and ANY is not a verb`() {
        assertEquals(HttpMethod.PATCH, HttpMethod.parse(" patch "))
        assertNull(HttpMethod.parse("ANY"))
        assertNull(HttpMethod.parse("FETCH"))
        assertFalse(HttpMethod.ANY.isConcrete)
        assertTrue(HttpMethod.GET.isConcrete)
    }

    @Test
    fun `an endpoint key ignores offsets and lines but not the path`() {
        assertEquals(endpoint(offset = 10, line = 3).key, endpoint(offset = 900, line = 40).key)
        assertFalse(endpoint().key == endpoint(path = "/other").key)
    }

    @Test
    fun `an endpoint with problems is partial`() {
        assertFalse(endpoint().isPartial)
        val partial = endpoint().copy(problems = listOf(Diagnostic(Severity.WARNING, DiagnosticCode.DYNAMIC_PREFIX)))
        assertTrue(partial.isPartial)
    }

    @Test
    fun `a source position is validated`() {
        try {
            SourceRef("a.py", -1, 1)
            throw AssertionError("negative offset accepted")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        assertEquals("a.py:7", SourceRef("a.py", 0, 7).toString())
    }

    @Test
    fun `known strings concatenate exactly`() {
        val joined = StrValue.Known("/api") + StrValue.Known("/users")
        assertEquals(StrValue.Known("/api/users"), joined)
        assertTrue(joined.isExact)
        assertNull(joined.reason)
    }

    @Test
    fun `an unknown part is kept visible instead of dropped`() {
        val joined = StrValue.Known("/api") + StrValue.Unknown() + StrValue.Known("/users")
        assertEquals("/api{?}/users", joined.text)
        assertFalse(joined.isExact)
        assertEquals(DiagnosticCode.DYNAMIC_VALUE, joined.reason)
    }

    @Test
    fun `the first reason is kept`() {
        val joined = StrValue.Partial("/v{?}", DiagnosticCode.DYNAMIC_PREFIX) + StrValue.Unknown(DiagnosticCode.UNRESOLVED_INCLUDE)
        assertEquals(DiagnosticCode.DYNAMIC_PREFIX, joined.reason)
    }

    @Test
    fun `two unknowns stay unknown`() {
        val joined = StrValue.Unknown() + StrValue.Unknown()
        assertTrue(joined is StrValue.Unknown)
        assertEquals("{?}", joined.text)
    }

    @Test
    fun `a snapshot copies its lists and compares by identity`() {
        val source = mutableListOf(endpoint())
        val snapshot = RouteSnapshot(1, source)
        source.clear()
        assertEquals(1, snapshot.endpoints.size)
        assertNotSame(RouteSnapshot(1, listOf(endpoint())), snapshot)
        assertEquals(0, RouteSnapshot.EMPTY.endpoints.size)
    }

    @Test
    fun `every diagnostic code maps to a distinct bundle key`() {
        val keys = DiagnosticCode.entries.map { it.bundleKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("diagnostic.") })
    }
}
