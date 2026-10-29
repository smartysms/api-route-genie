package com.routegenie.frameworks.fastapi

import com.routegenie.frameworks.common.RouteProviderTestCase
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Framework
import com.routegenie.model.PathParam

/**
 * FastAPI semantics, each observed against fastapi 0.162 (see the notes per test). Test names state the observed truth.
 * Methods are named testXxx because the platform base class is JUnit 3 style.
 */
class FastApiRoutesTest : RouteProviderTestCase(FastApiRouteProvider()) {
    /** Same indentation as the raw strings of the tests, so the shared trimIndent leaves the combined text valid Python. */
    private val appFile = "from fastapi import FastAPI\napp = FastAPI()".prependIndent(" ".repeat(12))

    /** Correct or absent: no endpoint, and exactly one diagnostic with [code] that says why. */
    private fun assertOmitted(result: com.routegenie.discovery.LinkResult, code: DiagnosticCode) {
        assertTrue("no endpoint expected: ${routes(result)}", result.endpoints.isEmpty())
        assertEquals(listOf(code), result.diagnostics.map { it.code })
    }

    fun testAllEightVerbDecoratorsOnApp() {
        val result = discover("main.py" to appFile + """
            @app.get("/a")
            def a(): ...
            @app.post("/a")
            def b(): ...
            @app.put("/a")
            def c(): ...
            @app.patch("/a")
            def d(): ...
            @app.delete("/a")
            def e(): ...
            @app.head("/a")
            def f(): ...
            @app.options("/a")
            def g(): ...
            @app.trace("/a")
            def h(): ...
        """)
        assertEquals(
            listOf("DELETE /a", "GET /a", "HEAD /a", "OPTIONS /a", "PATCH /a", "POST /a", "PUT /a", "TRACE /a"),
            routes(result),
        )
        assertTrue(result.endpoints.all { it.framework == Framework.FASTAPI && !it.isPartial })
    }

    fun testRouterConstructorPrefixIsApplied() {
        // APIRouter(prefix="/items") + "/{item_id}" -> "/items/{item_id}" once the router is included.
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            router = APIRouter(prefix="/items")
            @router.get("/{item_id}")
            def read(item_id: int): ...
            app.include_router(router)
        """)
        assertEquals(listOf("GET /items/{item_id}"), routes(result))
        assertEquals(listOf(PathParam("item_id")), result.endpoints.single().pathParams)
    }

    fun testNestedIncludePrefixAndTagOrderMatchesRealFastApi() {
        // Observed with fastapi 0.162: outer include + mid prefix + inner include + inner prefix + path, and tags in
        // the same order (include tags, router tags, include tags, router tags, route tags). Deprecated propagates.
        val result = discover(
            "app/__init__.py" to "",
            "app/main.py" to """
                from fastapi import FastAPI
                from app.mid import mid
                app = FastAPI()
                app.include_router(mid, prefix="/outer", tags=["inc1"])
            """,
            "app/mid.py" to """
                from fastapi import APIRouter
                from app.inner import inner
                mid = APIRouter(prefix="/mid", tags=["mid"])
                mid.include_router(inner, prefix="/i2", tags=["inc2"], deprecated=True)
                @mid.get("/late")
                def late(): ...
            """,
            "app/inner.py" to """
                from fastapi import APIRouter
                inner = APIRouter(prefix="/inner", tags=["inner"])
                @inner.get("/x", tags=["rt"])
                def x(): ...
            """,
        )
        assertEquals(listOf("GET /outer/mid/i2/inner/x", "GET /outer/mid/late"), routes(result))
        val x = find(result, "GET", "/outer/mid/i2/inner/x")
        assertEquals(listOf("inc1", "mid", "inc2", "inner", "rt"), x.tags)
        assertTrue(x.deprecated)
        assertEquals("app.inner.x", x.handler.qualifiedName)
        val late = find(result, "GET", "/outer/mid/late")
        assertEquals(listOf("inc1", "mid"), late.tags)
        assertFalse(late.deprecated)
    }

    fun testRouteAddedAfterIncludeIsStillListed() {
        // fastapi 0.162 includes lazily: a route declared on the router after include_router is part of the app.
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            r = APIRouter(prefix="/r")
            app.include_router(r)
            @r.get("/after")
            def after(): ...
        """)
        assertEquals(listOf("GET /r/after"), routes(result))
    }

    fun testApiRouteWithoutMethodsDefaultsToGet() {
        val result = discover("main.py" to appFile + """
            @app.api_route("/ar")
            def ar(): ...
        """)
        assertEquals(listOf("GET /ar"), routes(result))
    }

    fun testApiRouteMethodsListIsUppercasedAndExpanded() {
        val result = discover("main.py" to appFile + """
            @app.api_route("/arm", methods=["POST", "put"])
            def arm(): ...
        """)
        assertEquals(listOf("POST /arm", "PUT /arm"), routes(result))
    }

    fun testApiRouteWithComputedMethodsIsOmittedWithADiagnostic() {
        val result = discover("main.py" to appFile + """
            VERBS = get_verbs()
            @app.api_route("/dyn", methods=VERBS)
            def dyn(): ...
        """)
        assertOmitted(result, DiagnosticCode.DYNAMIC_VALUE)
    }

    fun testAddApiRouteImperativeFormDefaultsToGetAndNamesHandler() {
        val result = discover("main.py" to appFile + """
            def imp(): ...
            def imp2(): ...
            app.add_api_route("/imp", imp)
            app.add_api_route("/imp2", imp2, methods=["POST"])
        """)
        assertEquals(listOf("GET /imp", "POST /imp2"), routes(result))
        assertEquals("main.imp", find(result, "GET", "/imp").handler.qualifiedName)
    }

    fun testIncludeThroughRelativeImportOfModule() {
        val result = discover(
            "app/__init__.py" to "",
            "app/main.py" to """
                from fastapi import FastAPI
                from .routers import items
                app = FastAPI()
                app.include_router(items.router, prefix="/api/v1")
            """,
            "app/routers/__init__.py" to "",
            "app/routers/items.py" to """
                from fastapi import APIRouter
                router = APIRouter(prefix="/items")
                @router.get("/")
                def list_items(): ...
            """,
        )
        assertEquals(listOf("GET /api/v1/items/"), routes(result))
        assertEquals("app.routers.items.list_items", result.endpoints.single().handler.qualifiedName)
    }

    fun testIncludeThroughImportAsModuleAlias() {
        val result = discover(
            "main.py" to """
                from fastapi import FastAPI
                import routers.items as it
                app = FastAPI()
                app.include_router(it.router)
            """,
            "routers/items.py" to """
                from fastapi import APIRouter
                router = APIRouter(prefix="/items")
                @router.get("/x")
                def x(): ...
            """,
        )
        assertEquals(listOf("GET /items/x"), routes(result))
    }

    fun testIncludeThroughFromImportWithAlias() {
        val result = discover(
            "main.py" to """
                from fastapi import FastAPI
                from routers.users import router as users_router
                app = FastAPI()
                app.include_router(users_router, prefix="/u")
            """,
            "routers/users.py" to """
                from fastapi import APIRouter
                router = APIRouter()
                @router.get("/me")
                def me(): ...
            """,
        )
        assertEquals(listOf("GET /u/me"), routes(result))
    }

    fun testIncludeThroughPackageReExport() {
        val result = discover(
            "main.py" to """
                from fastapi import FastAPI
                from routers import router
                app = FastAPI()
                app.include_router(router)
            """,
            "routers/__init__.py" to "from .items import router\n",
            "routers/items.py" to """
                from fastapi import APIRouter
                router = APIRouter(prefix="/re")
                @router.get("/x")
                def x(): ...
            """,
        )
        assertEquals(listOf("GET /re/x"), routes(result))
    }

    fun testRouterIncludedTwiceYieldsTwoSetsOfEndpoints() {
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            r = APIRouter(prefix="/items")
            @r.get("/")
            def items(): ...
            app.include_router(r, prefix="/api/v1")
            app.include_router(r, prefix="/api/latest")
        """)
        assertEquals(listOf("GET /api/latest/items/", "GET /api/v1/items/"), routes(result))
    }

    fun testPathParametersWithPathConverter() {
        val result = discover("main.py" to appFile + """
            @app.get("/files/{file_path:path}/v/{version}")
            def files(file_path: str, version: int): ...
        """)
        val endpoint = result.endpoints.single()
        assertEquals("/files/{file_path:path}/v/{version}", endpoint.path)
        assertEquals(listOf(PathParam("file_path", "path"), PathParam("version")), endpoint.pathParams)
    }

    fun testDeprecatedOnRouteRouterAndInclude() {
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            old = APIRouter(prefix="/old", deprecated=True)
            mixed = APIRouter(prefix="/mixed")
            @old.get("/a")
            def a(): ...
            @mixed.get("/b", deprecated=True)
            def b(): ...
            @mixed.get("/c")
            def c(): ...
            app.include_router(old)
            app.include_router(mixed)
            app.include_router(mixed, prefix="/inc", deprecated=True)
        """)
        assertTrue(find(result, "GET", "/old/a").deprecated)
        assertTrue(find(result, "GET", "/mixed/b").deprecated)
        assertFalse(find(result, "GET", "/mixed/c").deprecated)
        assertTrue(find(result, "GET", "/inc/mixed/c").deprecated)
    }

    fun testEmptyPathOnPrefixedRouterIsThePrefixAndSlashKeepsTheSlash() {
        // Observed: "" -> /inner, "/" -> /inner/ (no normalisation of slashes).
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            r = APIRouter(prefix="/inner")
            @r.get("")
            def empty(): ...
            @r.get("/")
            def slash(): ...
            app.include_router(r)
        """)
        assertEquals(listOf("GET /inner", "GET /inner/"), routes(result))
    }

    fun testPrefixFromEnvironmentOmitsTheEndpointWithADiagnostic() {
        val result = discover("main.py" to """
            import os
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            r = APIRouter(prefix=os.environ["API_PREFIX"])
            @r.get("/x")
            def x(): ...
            app.include_router(r)
        """)
        assertOmitted(result, DiagnosticCode.DYNAMIC_PREFIX)
    }

    fun testPrefixFromImportedConstantAndConcatenationIsResolved() {
        val result = discover(
            "consts.py" to "API = '/api'\nV1 = API + '/v1'\n",
            "main.py" to """
                from fastapi import FastAPI, APIRouter
                from consts import V1
                app = FastAPI()
                r = APIRouter(prefix=V1)
                @r.get("/x")
                def x(): ...
                app.include_router(r)
            """,
        )
        assertEquals(listOf("GET /api/v1/x"), routes(result))
        assertFalse(result.endpoints.single().isPartial)
    }

    fun testPrefixFromFStringWithKnownConstantIsResolved() {
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            VERSION = "v2"
            app = FastAPI()
            r = APIRouter(prefix=f"/api/{VERSION}")
            @r.get("/x")
            def x(): ...
            app.include_router(r)
        """)
        assertEquals(listOf("GET /api/v2/x"), routes(result))
    }

    fun testDynamicPathInDecoratorIsOmitted() {
        val result = discover("main.py" to appFile + """
            @app.get(make_path())
            def dyn(): ...
        """)
        assertOmitted(result, DiagnosticCode.DYNAMIC_VALUE)
    }

    fun testRouterThatIsNeverIncludedHasNoFullPathAndIsOmitted() {
        val result = discover("main.py" to """
            from fastapi import APIRouter
            lonely = APIRouter(prefix="/lonely")
            @lonely.get("/x")
            def x(): ...
        """)
        assertOmitted(result, DiagnosticCode.UNATTACHED_ROUTER)
    }

    fun testStackedDecoratorsGiveTwoEndpointsForOneHandler() {
        val result = discover("main.py" to appFile + """
            @app.get("/a")
            @app.get("/b")
            def both(): ...
        """)
        assertEquals(listOf("GET /a", "GET /b"), routes(result))
        assertEquals(setOf("main.both"), result.endpoints.map { it.handler.qualifiedName }.toSet())
    }

    fun testAliasedImportAndModuleAttributeConstructorsAreRecognized() {
        val result = discover(
            "a.py" to """
                from fastapi import FastAPI as F, APIRouter as AR
                app = F()
                r = AR(prefix="/r")
                @r.get("/x")
                def x(): ...
                app.include_router(r)
            """,
            "b.py" to """
                import fastapi
                import fastapi as fa
                app2 = fastapi.FastAPI()
                r2 = fa.APIRouter(prefix="/r2")
                @r2.post("/y")
                def y(): ...
                app2.include_router(r2)
            """,
        )
        assertEquals(listOf("GET /r/x", "POST /r2/y"), routes(result))
    }

    fun testDefiningModuleSpellingOfClassesIsRecognized() {
        val result = discover("main.py" to """
            from fastapi.applications import FastAPI
            from fastapi.routing import APIRouter
            app = FastAPI()
            r = APIRouter(prefix="/m")
            @r.get("/x")
            def x(): ...
            app.include_router(r)
        """)
        assertEquals(listOf("GET /m/x"), routes(result))
    }

    fun testDecoratorsOnObjectsThatAreNotFastApiAreIgnored() {
        // aiohttp style route tables share the decorator names; without a FastAPI import nothing may be reported.
        val result = discover("web.py" to """
            from aiohttp import web
            routes = web.RouteTableDef()
            @routes.get("/ping")
            def ping(): ...
        """)
        assertTrue(result.endpoints.isEmpty())
    }

    fun testReceiverThatIsAFunctionParameterIsOmitted() {
        val result = discover("main.py" to """
            from fastapi import FastAPI
            def setup(app):
                @app.get("/param")
                def p(): ...
        """)
        assertOmitted(result, DiagnosticCode.UNRESOLVED_RECEIVER)
    }

    fun testIncludeCycleIsCutOffAndReported() {
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            a = APIRouter(prefix="/a")
            b = APIRouter(prefix="/b")
            @a.get("/x")
            def x(): ...
            a.include_router(b)
            b.include_router(a)
            app.include_router(a)
        """)
        assertEquals(listOf("GET /a/x"), routes(result))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCLUDE_CYCLE })
    }

    fun testMountedSubApplicationIsListedUnderTheMountPathOnly() {
        val result = discover(
            "main.py" to """
                from fastapi import FastAPI
                from sub import sub
                app = FastAPI()
                app.mount("/sub", sub)
                @app.get("/root")
                def root(): ...
            """,
            "sub.py" to """
                from fastapi import FastAPI
                sub = FastAPI()
                @sub.get("/ping")
                def ping(): ...
            """,
        )
        assertEquals(listOf("GET /root", "GET /sub/ping"), routes(result))
    }

    fun testApplicationCreatedInsideAFactoryFunction() {
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            def create_app():
                app = FastAPI()
                r = APIRouter(prefix="/f")
                @r.get("/x")
                def x(): ...
                app.include_router(r)
                @app.get("/health")
                def health(): ...
                return app
        """)
        assertEquals(listOf("GET /f/x", "GET /health"), routes(result))
    }

    fun testTagsFromRouterIncludeAndRouteAreCombinedWithoutDuplicates() {
        val result = discover("main.py" to """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            r = APIRouter(prefix="/r", tags=["items"])
            @r.get("/x", tags=["items", "extra"])
            def x(): ...
            app.include_router(r, tags=["v1"])
        """)
        assertEquals(listOf("v1", "items", "extra"), result.endpoints.single().tags)
    }

    fun testIncludeOfSomethingThatIsNotARouterNameIsReported() {
        val result = discover("main.py" to """
            from fastapi import FastAPI
            app = FastAPI()
            app.include_router(build_router())
        """)
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.UNRESOLVED_INCLUDE), result.diagnostics.map { it.code })
    }

    fun testWebsocketRoutesAreNotListedAsHttpEndpoints() {
        val result = discover("main.py" to appFile + """
            @app.websocket("/ws")
            async def ws(websocket): ...
            @app.get("/http")
            def http(): ...
        """)
        assertEquals(listOf("GET /http"), routes(result))
    }

    fun testRouteNameKeywordBecomesTheRouteName() {
        val result = discover("main.py" to appFile + """
            @app.get("/n", name="custom_name")
            def n(): ...
            @app.get("/m")
            def m(): ...
        """)
        assertEquals("custom_name", find(result, "GET", "/n").routeName)
        assertEquals("m", find(result, "GET", "/m").routeName)
    }

    fun testSourcePointsAtTheDecoratorLine() {
        val result = discover("main.py" to appFile + """
            @app.get("/line")
            def line(): ...
        """)
        val source = result.endpoints.single().source
        assertEquals(3, source.line)
        assertTrue(source.path.endsWith("main.py"))
    }

    fun testRoutesInAClassBodyKeepTheClassInTheHandlerName() {
        val result = discover("main.py" to appFile + """
            class Handlers:
                @app.get("/in-class")
                def method(self): ...
        """)
        assertEquals("main.Handlers.method", result.endpoints.single().handler.qualifiedName)
    }

    fun testFilesWithoutFrameworkWordsAreNotCandidates() {
        add("plain.py", "def f():\n    return 1\n")
        add("main.py", "from fastapi import FastAPI\napp = FastAPI()\n@app.get('/x')\ndef x(): ...\n")
        val names = FastApiRouteProvider().findCandidates(project).map { it.name }
        assertTrue("main.py" in names)
        assertFalse("plain.py" in names)
    }

    fun testSrcLayoutImportsResolveBySuffix() {
        // The importing code names the module from the package root while the file lives one folder deeper.
        val result = discover(
            "backend/app/__init__.py" to "",
            "backend/app/main.py" to """
                from fastapi import FastAPI
                from app.users import router
                app = FastAPI()
                app.include_router(router, prefix="/s")
            """,
            "backend/app/users.py" to """
                from fastapi import APIRouter
                router = APIRouter(prefix="/users")
                @router.get("/")
                def users(): ...
            """,
        )
        assertEquals(listOf("GET /s/users/"), routes(result))
    }

    fun testStarArgumentsDoNotBreakExtraction() {
        val result = discover("main.py" to appFile + """
            OPTS = {}
            @app.get("/star", **OPTS)
            def star(): ...
        """)
        assertEquals(listOf("GET /star"), routes(result))
    }
}
