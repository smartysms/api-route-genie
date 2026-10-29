package com.routegenie.frameworks.common

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.model.StrValue
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Linking is pure, so its speed is measured without an IDE: 50 routers or blueprints with 100 routes each. */
class LinkPerfTest {
    private val routers = 50
    private val routesPerRouter = 100

    @Test
    fun `linking 5000 fastapi endpoints stays within the budget`() {
        val facts = fastApiFacts()
        val provider = FastApiRouteProvider()
        assertEquals(routers * routesPerRouter, provider.link(facts).endpoints.size)
        PerfBudget.assertWithin("link-5000-fastapi", Limits.BUDGET_LINK_5000_ENDPOINTS_MS) { provider.link(facts) }
    }

    @Test
    fun `linking 5000 flask endpoints stays within the budget`() {
        val facts = flaskFacts()
        val provider = FlaskRouteProvider()
        assertEquals(routers * routesPerRouter, provider.link(facts).endpoints.size)
        PerfBudget.assertWithin("link-5000-flask", Limits.BUDGET_LINK_5000_ENDPOINTS_MS) { provider.link(facts) }
    }

    private fun call(callee: String, vararg positional: ArgFact, keywords: Map<String, ArgFact> = emptyMap()) =
        CallFact(RefFact(callee.split(".")), positional.toList(), keywords)

    private fun str(text: String) = ArgFact(str = StrValue.Known(text))

    private fun fastApiFacts(): List<FileFacts> {
        val files = ArrayList<FileFacts>()
        val appImports = ArrayList<ImportFact>(listOf(ImportFact("FastAPI", "fastapi", "FastAPI")))
        val includes = ArrayList<CallStmtFact>()
        for (i in 0 until routers) {
            files += PyRouteFacts(
                path = "pkg/r$i.py", providerId = "fastapi", module = "pkg.r$i", isPackage = false,
                imports = listOf(ImportFact("APIRouter", "fastapi", "APIRouter")), starModules = emptyList(),
                assignments = listOf(AssignFact("router", "", call("APIRouter", keywords = mapOf("prefix" to str("/r$i"))), SourcePos(0, 1))),
                decorators = (0 until routesPerRouter).map {
                    DecoratorFact("", RefFact(listOf("router")), "get", call("router.get", str("/item$it/{id}")), "handler$it", SourcePos(it * 10, it + 2))
                },
                calls = emptyList(), classes = emptyList(),
            )
            appImports += ImportFact("r$i", "pkg.r$i", "router")
            includes += CallStmtFact(
                "", RefFact(listOf("app")), "include_router",
                call("app.include_router", ArgFact(ref = RefFact(listOf("r$i"))), keywords = mapOf("prefix" to str("/api/v$i"))),
                SourcePos(i, i + 3),
            )
        }
        files += PyRouteFacts(
            path = "pkg/main.py", providerId = "fastapi", module = "pkg.main", isPackage = false,
            imports = appImports, starModules = emptyList(),
            assignments = listOf(AssignFact("app", "", call("FastAPI"), SourcePos(0, 1))),
            decorators = emptyList(), calls = includes, classes = emptyList(),
        )
        return files
    }

    private fun flaskFacts(): List<FileFacts> {
        val files = ArrayList<FileFacts>()
        val appImports = ArrayList<ImportFact>(listOf(ImportFact("Flask", "flask", "Flask")))
        val registrations = ArrayList<CallStmtFact>()
        for (i in 0 until routers) {
            files += PyRouteFacts(
                path = "pkg/b$i.py", providerId = "flask", module = "pkg.b$i", isPackage = false,
                imports = listOf(ImportFact("Blueprint", "flask", "Blueprint")), starModules = emptyList(),
                assignments = listOf(
                    AssignFact("bp", "", call("Blueprint", str("b$i"), keywords = mapOf("url_prefix" to str("/b$i"))), SourcePos(0, 1)),
                ),
                decorators = (0 until routesPerRouter).map {
                    DecoratorFact("", RefFact(listOf("bp")), "route", call("bp.route", str("/item$it/<int:id>")), "handler$it", SourcePos(it * 10, it + 2))
                },
                calls = emptyList(), classes = emptyList(),
            )
            appImports += ImportFact("b$i", "pkg.b$i", "bp")
            registrations += CallStmtFact(
                "", RefFact(listOf("app")), "register_blueprint",
                call("app.register_blueprint", ArgFact(ref = RefFact(listOf("b$i"))), keywords = mapOf("url_prefix" to str("/api/v$i"))),
                SourcePos(i, i + 3),
            )
        }
        files += PyRouteFacts(
            path = "pkg/main.py", providerId = "flask", module = "pkg.main", isPackage = false,
            imports = appImports, starModules = emptyList(),
            assignments = listOf(AssignFact("app", "", call("Flask"), SourcePos(0, 1))),
            decorators = emptyList(), calls = registrations, classes = emptyList(),
        )
        return files
    }
}
