package com.routegenie.frameworks.common

import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskPaths
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.PathParam
import com.routegenie.model.StrValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests with hand-built facts: no IDE, so they run in milliseconds. */
class PyLinkUnitTest {
    @Test
    fun `flask converters are normalised with their type`() {
        val (path, params) = FlaskPaths.normalize("/a/<int:id>/<path:rest>/<plain>/<any(x,y):kind>")
        assertEquals("/a/{id}/{rest}/{plain}/{kind}", path)
        assertEquals(listOf(PathParam("id", "int"), PathParam("rest", "path"), PathParam("plain"), PathParam("kind", "any")), params)
    }

    @Test
    fun `an unknown segment is not mistaken for a converter`() {
        val (path, params) = FlaskPaths.normalize("/{?}/x")
        assertEquals("/{?}/x", path)
        assertTrue(params.isEmpty())
    }

    @Test
    fun `scope chain walks from the innermost scope to the module`() {
        assertEquals(listOf("a.b", "a", ""), PyModuleIndex.scopeChain("a.b"))
        assertEquals(listOf(""), PyModuleIndex.scopeChain(""))
    }

    @Test
    fun `module lookup matches by suffix and prefers the exact name`() {
        val deep = facts("backend/app/users.py", "backend.app.users")
        val exact = facts("users.py", "users")
        val index = PyModuleIndex(listOf(deep, exact))
        assertEquals("users.py", index.findModule("users")?.path)
        assertEquals("backend/app/users.py", index.findModule("app.users")?.path)
        assertNull(index.findModule("missing"))
    }

    @Test
    fun `origin of a name comes from the import table only`() {
        val file = facts("m.py", "m").copy(
            imports = listOf(ImportFact("F", "fastapi", "FastAPI"), ImportFact("fa", "fastapi", null)),
        )
        val index = PyModuleIndex(listOf(file))
        assertEquals("fastapi.FastAPI", index.originOf(file, RefFact(listOf("F"))))
        assertEquals("fastapi.APIRouter", index.originOf(file, RefFact(listOf("fa", "APIRouter"))))
        assertNull(index.originOf(file, RefFact(listOf("unknown"))))
    }

    @Test
    fun `facts of another provider are ignored by the linker`() {
        val foreign = facts("m.py", "m").copy(providerId = "somebody-else")
        assertTrue(FastApiRouteProvider().link(listOf(foreign)).endpoints.isEmpty())
    }

    @Test
    fun `string joins keep unknown parts visible`() {
        val joined = StrValue.Known("/a") + StrValue.Unknown()
        assertEquals("/a{?}", joined.text)
        assertEquals(DiagnosticCode.DYNAMIC_VALUE, joined.reason)
    }

    private fun facts(path: String, module: String) = PyRouteFacts(
        path = path, providerId = "fastapi", module = module, isPackage = false,
        imports = emptyList(), starModules = emptyList(), assignments = emptyList(),
        decorators = emptyList(), calls = emptyList(), classes = emptyList(),
    )
}
