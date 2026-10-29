package com.routegenie.discovery.core

import com.routegenie.discovery.SourceFacts
import com.routegenie.model.PathSyntax
import com.routegenie.model.StrValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests of the linker-side evaluation and of the dependency walk: hand-built facts, no IDE. */
class ExprEvaluatorUnitTest {
    private class Source(
        override val imports: List<ImportBinding> = emptyList(),
        override val stars: List<StarBinding> = emptyList(),
        override val consts: List<ConstFact> = emptyList(),
        override val definedNames: Set<String> = emptySet(),
        override val wantedRefs: List<List<String>> = emptyList(),
        override val wantedExprs: List<Expr> = emptyList(),
        override val wantedModules: List<String> = emptyList(),
    ) : ModuleSource, SourceFacts {
        override val weight: Int = 1
    }

    private fun entry(path: String, source: Source, root: String = "") =
        ModuleEntry(path, ModuleNames.moduleOf(path, root), ModuleNames.isPackage(path), source)

    private fun ref(vararg parts: String) = Ref(parts.toList())

    @Test
    fun `a constant in another file is evaluated when linking`() {
        val consts = entry("pkg/consts.py", Source(consts = listOf(ConstFact("API", Lit("/v1")))))
        val user = entry("pkg/routes.py", Source(imports = listOf(ImportBinding("API", "consts", "API", level = 1))))
        val value = ExprEvaluator(ModuleTable(listOf(consts, user))).evaluate(user, Add(ref("API"), Lit("/items")))
        assertEquals(StrValue.Known("/v1/items"), value)
    }

    @Test
    fun `the same facts give another value when the other file changes, with no new facts for the user`() {
        val user = entry("pkg/routes.py", Source(imports = listOf(ImportBinding("API", "consts", "API", level = 1))))
        fun valueWith(constant: String): StrValue {
            val consts = entry("pkg/consts.py", Source(consts = listOf(ConstFact("API", Lit(constant)))))
            return ExprEvaluator(ModuleTable(listOf(consts, user))).evaluate(user, ref("API"))
        }
        assertEquals(StrValue.Known("/v1"), valueWith("/v1"))
        assertEquals(StrValue.Known("/v2"), valueWith("/v2"))
    }

    @Test
    fun `a name that is not in the file set is unknown, never guessed`() {
        val user = entry("routes.py", Source(imports = listOf(ImportBinding("API", "missing", "API"))))
        val value = ExprEvaluator(ModuleTable(listOf(user))).evaluate(user, Add(Lit("/a"), ref("API")))
        assertEquals("/a${PathSyntax.UNKNOWN_SEGMENT}", value.text)
        assertTrue(!value.isExact)
    }

    @Test
    fun `module naming follows the source root and relative imports follow the package`() {
        assertEquals("routers.items", ModuleNames.moduleOf("src/app/routers/items.py", "src/app"))
        assertEquals("app.routers", ModuleNames.moduleOf("app/routers/__init__.py", ""))
        assertEquals("app.core", ModuleNames.absolute("app.routers.items", isPackage = false, level = 2, written = "core"))
        assertEquals("app.routers.sub", ModuleNames.absolute("app.routers", isPackage = true, level = 1, written = "sub"))
    }

    @Test
    fun `the walk asks for the module a looked up constant comes from`() {
        val user = entry(
            "pkg/routes.py",
            Source(
                imports = listOf(ImportBinding("API", "consts", "API", level = 1), ImportBinding("unused", "models", "User", level = 1)),
                wantedExprs = listOf(ref("API")),
            ),
        )
        val missing = ModuleWalk.missing(ModuleTable(listOf(user)), skipRoots = emptySet())
        assertTrue("pkg.consts" in missing)
        assertTrue("an import nobody looks up is not followed: $missing", missing.none { it.contains("models") })
    }

    @Test
    fun `the walk follows a re-export chain one round at a time and then stops`() {
        val user = entry("app/main.py", Source(imports = listOf(ImportBinding("API", "app.core", "API")), wantedExprs = listOf(ref("API"))))
        val core = entry("app/core/__init__.py", Source(imports = listOf(ImportBinding("API", "settings", "API", level = 1))))
        val settings = entry("app/core/settings.py", Source(consts = listOf(ConstFact("API", Lit("/x")))))

        assertEquals(setOf("app.core"), ModuleWalk.missing(ModuleTable(listOf(user)), emptySet()).filter { it == "app.core" }.toSet())
        assertTrue("app.core.settings" in ModuleWalk.missing(ModuleTable(listOf(user, core)), emptySet()))
        assertTrue(ModuleWalk.missing(ModuleTable(listOf(user, core, settings)), emptySet()).isEmpty())
        val value = ExprEvaluator(ModuleTable(listOf(user, core, settings))).evaluate(user, ref("API"))
        assertEquals(StrValue.Known("/x"), value)
    }

    @Test
    fun `framework roots are never requested as project files`() {
        val user = entry(
            "routes.py",
            Source(imports = listOf(ImportBinding("APIRouter", "fastapi", "APIRouter")), wantedRefs = listOf(listOf("APIRouter"))),
        )
        assertTrue(ModuleWalk.missing(ModuleTable(listOf(user)), skipRoots = setOf("fastapi")).isEmpty())
    }

    @Test
    fun `expression trees are values and count their nodes`() {
        assertEquals(Add(Lit("a"), ref("B")), Add(Lit("a"), ref("B")))
        assertEquals(Add(Lit("a"), ref("B")).hashCode(), Add(Lit("a"), ref("B")).hashCode())
        assertEquals(6, Add(Lit("a"), Percent(Lit("%s"), listOf(ref("B")))).nodeCount())
    }
}
