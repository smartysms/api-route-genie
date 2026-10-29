package com.routegenie.frameworks.common

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.core.Add
import com.routegenie.discovery.core.ConstFact
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.Lit
import com.routegenie.discovery.core.Ref
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Linking 5,000 FastAPI endpoints when every prefix and every route path is a constant from another file, so each endpoint
 * costs a cross-file evaluation inside the linker. The same size as LinkPerfTest, which uses literals only.
 */
class LinkerSideEvaluationPerfTest {
    private val routers = 50
    private val routesPerRouter = 100

    private fun call(callee: String, vararg positional: ArgFact, keywords: Map<String, ArgFact> = emptyMap()) =
        CallFact(RefFact(callee.split(".")), positional.toList(), keywords)

    private fun facts(): List<FileFacts> {
        val files = ArrayList<FileFacts>()
        files += LocatedFacts(
            "pkg/settings.py",
            PyFileFacts(
                "fastapi", emptyList(), emptyList(), listOf(ConstFact("BASE", Lit("/api")), ConstFact("ITEM", Lit("/item"))),
                emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        val appImports = ArrayList<ImportBinding>(listOf(ImportBinding("FastAPI", "fastapi", "FastAPI"), ImportBinding("BASE", "settings", "BASE", level = 1)))
        val includes = ArrayList<CallStmtFact>()
        for (i in 0 until routers) {
            files += LocatedFacts(
                "pkg/r$i.py",
                PyFileFacts(
                    providerId = "fastapi",
                    imports = listOf(ImportBinding("APIRouter", "fastapi", "APIRouter"), ImportBinding("ITEM", "settings", "ITEM", level = 1)),
                    stars = emptyList(),
                    consts = listOf(ConstFact("PREFIX", Lit("/r$i"))),
                    assignments = listOf(
                        AssignFact("router", "", call("APIRouter", keywords = mapOf("prefix" to ArgFact(expr = Ref(listOf("PREFIX"))))), SourcePos(0, 1)),
                    ),
                    decorators = (0 until routesPerRouter).map {
                        DecoratorFact(
                            "", RefFact(listOf("router")), "get",
                            call("router.get", ArgFact(expr = Add(Ref(listOf("ITEM")), Lit("$it/{id}")))),
                            "handler$it", SourcePos(it * 10, it + 2),
                        )
                    },
                    calls = emptyList(),
                    classes = emptyList(),
                ),
            )
            appImports += ImportBinding("r$i", "pkg.r$i", "router")
            includes += CallStmtFact(
                "", RefFact(listOf("app")), "include_router",
                call("app.include_router", ArgFact(ref = RefFact(listOf("r$i"))), keywords = mapOf("prefix" to ArgFact(expr = Add(Ref(listOf("BASE")), Lit("/v$i"))))),
                SourcePos(i, i + 3),
            )
        }
        files += LocatedFacts(
            "pkg/main.py",
            PyFileFacts(
                "fastapi", appImports, emptyList(), emptyList(),
                listOf(AssignFact("app", "", call("FastAPI"), SourcePos(0, 1))), emptyList(), includes, emptyList(),
            ),
        )
        return files
    }

    @Test
    fun `linking 5000 fastapi endpoints with cross-file constants stays within the budget`() {
        val facts = facts()
        val provider = FastApiRouteProvider()
        val result = provider.link(facts)
        assertEquals(routers * routesPerRouter, result.endpoints.size)
        assertEquals("/api/v0/r0/item0/{id}", result.endpoints.first().path)
        val measured = PerfBudget.assertWithin("link-5000-fastapi-linker-side-evaluation", Limits.BUDGET_LINK_5000_ENDPOINTS_MS) { provider.link(facts) }
        println("link 5000 endpoints with linker-side evaluation: median ${measured.medianMs} ms")
    }
}
