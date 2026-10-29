package com.routegenie.bench

import com.intellij.openapi.application.runWriteAction
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.django.DjangoRouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.common.PyModuleFiles
import com.routegenie.frameworks.common.PyProviderSupport
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.EndpointKeys
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.testing.ProviderPipeline
import com.routegenie.testing.PythonPlatformTestCase

/**
 * Regression tests for the defects the real-repository benchmark found (docs/BENCHMARKS.md). Each test failed before its fix.
 * Fixtures are ASCII and unique per test, because the light project is shared inside one test class.
 */
class BenchFixesTest : PythonPlatformTestCase() {
    private fun run(provider: RouteProvider, vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        return provider.link(ProviderPipeline.collect(project, provider))
    }

    private fun routes(result: LinkResult) = result.endpoints.map { "${it.method} ${it.path}" }.sorted()

    private fun codes(result: LinkResult) = result.diagnostics.map { it.code }

    // --- 1. ROOT_URLCONF modules that only re-export urlpatterns ----------------------------------------------------

    fun testRootThatImportsUrlpatternsByName() {
        val result = run(
            DjangoRouteProvider(),
            "p1/settings.py" to "ROOT_URLCONF = 'p1.urls'\n",
            "p1/urls.py" to "from a1.urls import urlpatterns\n",
            "a1/urls.py" to "from django.urls import path\nfrom a1 import views\nurlpatterns = [path('a/', views.a)]\n",
        )
        assertEquals(listOf("ANY /a/"), routes(result))
        assertTrue(codes(result).toString(), result.diagnostics.isEmpty())
    }

    fun testRootThatStarImportsUrlpatterns() {
        val result = run(
            DjangoRouteProvider(),
            "p2/settings.py" to "ROOT_URLCONF = 'p2.urls'\n",
            "p2/urls.py" to "from .real import *\n",
            "p2/real.py" to "from django.urls import path\nfrom p2 import views\nurlpatterns = [path('b/', views.b)]\n",
        )
        assertEquals(listOf("ANY /b/"), routes(result))
    }

    fun testRootThatAliasesAnotherModulesUrlpatterns() {
        val result = run(
            DjangoRouteProvider(),
            "p3/settings.py" to "ROOT_URLCONF = 'p3.urls'\n",
            "p3/urls.py" to "from a3 import urls as other\nurlpatterns = other.urlpatterns\n",
            "a3/urls.py" to "from django.urls import path\nfrom a3 import views\nurlpatterns = [path('c/', views.c)]\n",
        )
        assertEquals(listOf("ANY /c/"), routes(result))
    }

    fun testReExportedListExtendedWithAugmentedAssignment() {
        val result = run(
            DjangoRouteProvider(),
            "p4/settings.py" to "ROOT_URLCONF = 'p4.urls'\n",
            "p4/urls.py" to "from django.urls import path\nfrom a4.urls import urlpatterns\nurlpatterns += [path('extra/', handler)]\n",
            "a4/urls.py" to "from django.urls import path\nfrom a4 import views\nurlpatterns = [path('d/', views.d)]\n",
        )
        assertEquals(listOf("ANY /d/", "ANY /extra/"), routes(result))
    }

    fun testRootThatOffersNoUrlpatternsAtAllIsReported() {
        val result = run(
            DjangoRouteProvider(),
            "p5/settings.py" to "ROOT_URLCONF = 'p5.urls'\n",
            "p5/urls.py" to "from somewhere_else import things\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertTrue("silent miss: ${codes(result)}", DiagnosticCode.UNRESOLVED_INCLUDE in codes(result))
    }

    // --- 2. module name index ---------------------------------------------------------------------------------------

    private fun module(name: String) = PyProviderSupport.findModuleFile(project, name)

    fun testModuleLookupFollowsAddRenameMoveAndDeleteWithoutRebuilding() {
        val index = PyModuleFiles.getInstance(project)
        myFixture.addFileToProject("mi/pkg/__init__.py", "")
        myFixture.addFileToProject("mi/pkg/one.py", "X = 1\n")
        assertEquals("one.py", module("pkg.one")?.name)
        assertEquals("__init__.py", module("pkg")?.name)
        assertNull(module("pkg.two"))
        val builds = index.builds.get()

        myFixture.addFileToProject("mi/pkg/two.py", "X = 2\n")
        assertEquals("two.py", module("pkg.two")?.name)

        val two = myFixture.findFileInTempDir("mi/pkg/two.py")
        runWriteAction { two.rename(this, "three.py") }
        assertNull(module("pkg.two"))
        assertEquals("three.py", module("pkg.three")?.name)

        val dir = myFixture.findFileInTempDir("mi/pkg")
        runWriteAction { dir.rename(this, "renamed") }
        assertNull(module("pkg.one"))
        assertEquals("one.py", module("renamed.one")?.name)
        assertEquals("__init__.py", module("renamed")?.name)

        runWriteAction { myFixture.findFileInTempDir("mi/renamed").delete(this) }
        assertNull(module("renamed.one"))
        assertNull(module("renamed"))
        assertEquals("every change was applied incrementally", builds, index.builds.get())
    }

    fun testModuleLookupMatchesBySuffixAndPrefersApplicationCode() {
        myFixture.addFileToProject("ms/src/app/routers/items.py", "X = 1\n")
        myFixture.addFileToProject("ms/tests/app/routers/items.py", "X = 2\n")
        val found = module("app.routers.items")
        assertTrue(found?.path.toString(), found?.path?.endsWith("/ms/src/app/routers/items.py") == true)
        assertNull(module("routers.nothing"))
    }

    // --- 3. candidate selection -------------------------------------------------------------------------------------

    fun testDecoratorsWithoutAReceiverOrFromMockAreNotRouteCandidates() {
        myFixture.addFileToProject("cand/test_patch.py", "from unittest.mock import patch\n\n\n@patch('a.b')\ndef test_a(x):\n    pass\n")
        myFixture.addFileToProject("cand/test_mock.py", "from unittest import mock\n\n\n@mock.patch('a.b')\ndef test_a(x):\n    pass\n")
        myFixture.addFileToProject("cand/real.py", "import r\n\n\n@r.router.get('/x')\ndef x():\n    pass\n")
        val names = FastApiRouteProvider().findCandidates(project).map { it.name }
        assertTrue(names.toString(), "real.py" in names)
        assertFalse(names.toString(), "test_patch.py" in names)
        assertFalse(names.toString(), "test_mock.py" in names)
    }

    // --- 5. identical routes in different scopes --------------------------------------------------------------------

    private val twoLocalApps = "from fastapi import FastAPI\n\n\ndef test_one():\n    app = FastAPI()\n\n" +
        "    @app.get('/x')\n    def read():\n        return 1\n\n\ndef test_two():\n    app = FastAPI()\n\n" +
        "    @app.get('/x')\n    def read():\n        return 2\n"

    fun testIdenticalRoutesOfFunctionLocalAppsAreBothListed() {
        val result = run(FastApiRouteProvider(), "k1/test_local.py" to twoLocalApps)
        assertEquals(listOf("GET /x", "GET /x"), routes(result))
        val numbered = EndpointKeys.disambiguate(result.endpoints)
        assertEquals(2, numbered.map { it.key }.toSet().size)
        assertEquals("first keeps the plain key", numbered[0].baseKey, numbered.minByOrNull { it.source.offset }!!.key)
    }

    fun testIdenticalFlaskRoutesOfFunctionLocalAppsAreBothListed() {
        val text = twoLocalApps.replace("from fastapi import FastAPI", "from flask import Flask").replace("FastAPI()", "Flask(__name__)")
            .replace("@app.get('/x')", "@app.route('/x')")
        val result = run(FlaskRouteProvider(), "k2/test_local.py" to text)
        assertEquals(listOf("GET /x", "GET /x"), routes(result))
    }

    fun testOrdinalsFollowSourceOrderNotListOrder() {
        val a = Endpoint(HttpMethod.GET, "/x", Framework.FASTAPI, Handler("h", "m.h"), SourceRef("m.py", 300, 9))
        val b = a.copy(source = SourceRef("m.py", 100, 3))
        val numbered = EndpointKeys.disambiguate(listOf(a, b))
        assertEquals(1, numbered[0].ordinal)
        assertEquals(0, numbered[1].ordinal)
        assertEquals(b.baseKey, numbered[1].key)
        assertEquals("${a.baseKey}|#1", numbered[0].key)
        // A line shift elsewhere in the file moves offsets but not the order, so the keys stay.
        val shifted = EndpointKeys.disambiguate(listOf(a.copy(source = SourceRef("m.py", 320, 10)), b.copy(source = SourceRef("m.py", 120, 4))))
        assertEquals(numbered.map { it.key }, shifted.map { it.key })
    }

    // --- 6. apps that another app mounts or wraps -------------------------------------------------------------------

    private val wsgiExample = "from fastapi import FastAPI\nfrom fastapi.middleware.wsgi import WSGIMiddleware\nfrom flask import Flask\n\n" +
        "flask_app = Flask(__name__)\n\n\n@flask_app.route('/')\ndef flask_main():\n    return 'x'\n\n\napp = FastAPI()\n\n\n" +
        "@app.get('/v2')\ndef read_main():\n    return {}\n\n\napp.mount('/v1', WSGIMiddleware(flask_app))\n"

    fun testFlaskAppWrappedIntoAnotherAppIsNotListedUnderItsOwnPath() {
        val flask = run(FlaskRouteProvider(), "w1/main.py" to wsgiExample)
        assertTrue("wrong path listed: ${routes(flask)}", flask.endpoints.isEmpty())
        // The FastAPI route in the same file is also seen by Flask's `get` shortcut and reported as having no Flask receiver.
        assertTrue(codes(flask).toString(), DiagnosticCode.MOUNTED_APP in codes(flask))
        val fastapi = FastApiRouteProvider().let { it.link(ProviderPipeline.collect(project, it)) }
        assertEquals(listOf("GET /v2"), routes(fastapi))
    }

    fun testFlaskAppMountedFromAnotherFileIsNotListedUnderItsOwnPath() {
        val flask = run(
            FlaskRouteProvider(),
            "w2/flask_part.py" to "from flask import Flask\n\nflask_app = Flask(__name__)\n\n\n@flask_app.route('/')\ndef flask_main():\n    return 'x'\n",
            "w2/host.py" to "from fastapi import FastAPI\nfrom fastapi.middleware.wsgi import WSGIMiddleware\nfrom w2.flask_part import flask_app\n\n" +
                "app = FastAPI()\napp.mount('/v1', WSGIMiddleware(flask_app))\n",
        )
        assertTrue("wrong path listed: ${routes(flask)}", flask.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.MOUNTED_APP), codes(flask))
    }

    fun testFastApiSubAppWrappedByMiddlewareIsNotListedAtTheRoot() {
        val result = run(
            FastApiRouteProvider(),
            "w3/main.py" to "from fastapi import FastAPI\nfrom w3.mw import Wrap\n\napp = FastAPI()\nsub = FastAPI()\n\n\n@sub.get('/x')\ndef x():\n    return 1\n\n\n" +
                "app.mount('/sub', Wrap(sub))\n",
        )
        assertTrue("sub app listed without its mount: ${routes(result)}", result.endpoints.isEmpty())
        assertTrue(DiagnosticCode.MOUNTED_APP in codes(result))
    }

    fun testSubAppMountedDirectlyKeepsItsExactPrefix() {
        val result = run(
            FastApiRouteProvider(),
            "w4/main.py" to "from fastapi import FastAPI\n\napp = FastAPI()\nsub = FastAPI()\n\n\n@sub.get('/x')\ndef x():\n    return 1\n\n\napp.mount('/sub', sub)\n",
        )
        assertEquals(listOf("GET /sub/x"), routes(result))
    }

    // --- 7. diagnostics: one per include, and gaps that are counted ---------------------------------------------------

    fun testDynamicIncludePrefixIsReportedOncePerInclude() {
        val result = run(
            FastApiRouteProvider(),
            "d1/routes.py" to "from fastapi import APIRouter\n\nrouter = APIRouter(prefix='/items')\n\n\n@router.get('/a')\ndef a():\n    return 1\n\n\n" +
                "@router.get('/b')\ndef b():\n    return 1\n\n\n@router.get('/c')\ndef c():\n    return 1\n",
            "d1/main.py" to "from fastapi import FastAPI\nfrom d1.routes import router\nfrom d1.config import settings\n\napp = FastAPI()\n" +
                "app.include_router(router, prefix=settings.API_PREFIX)\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.DYNAMIC_PREFIX), codes(result))
        assertEquals("d1/main.py", result.diagnostics.single().source?.path)
    }

    fun testDynamicBlueprintPrefixIsReportedOncePerRegistration() {
        val result = run(
            FlaskRouteProvider(),
            "d2/views.py" to "from flask import Blueprint\n\nbp = Blueprint('v', __name__)\n\n\n@bp.route('/a')\ndef a():\n    return 'a'\n\n\n@bp.route('/b')\ndef b():\n    return 'b'\n",
            "d2/main.py" to "from flask import Flask\nfrom d2.views import bp\nimport os\n\napp = Flask(__name__)\napp.register_blueprint(bp, url_prefix=os.environ['P'])\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.DYNAMIC_PREFIX), codes(result))
    }

    fun testStarletteStyleRouterRouteIsCountedNotListed() {
        val result = run(
            FastApiRouteProvider(),
            "u1/r.py" to "from fastapi import APIRouter, FastAPI\n\napp = FastAPI()\nrouter = APIRouter()\n\n\n@router.route('/x')\ndef x(request):\n    return 1\n\n\napp.include_router(router)\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.UNSUPPORTED_PATTERN), codes(result))
    }

    fun testFlaskRouteFilesAreNotFastApiCandidates() {
        myFixture.addFileToProject("u2/views.py", "from flask import Blueprint\n\nbp = Blueprint('v', __name__)\n\n\n@bp.route('/a')\ndef a():\n    return 'a'\n")
        assertFalse(FastApiRouteProvider().findCandidates(project).any { it.path.endsWith("u2/views.py") })
    }

    fun testUrlpatternsBuiltInAFunctionAreCounted() {
        val result = run(
            DjangoRouteProvider(),
            "u3/test_urls.py" to "from django.urls import path\n\n\ndef test_it():\n    urlpatterns = [path('a/', view)]\n    return urlpatterns\n",
        )
        assertTrue(DiagnosticCode.UNSUPPORTED_PATTERN in codes(result))
        assertTrue(result.endpoints.isEmpty())
    }

    fun testUrlconfsThatOnlyIncludeEachOtherAreCounted() {
        val result = run(
            DjangoRouteProvider(),
            "u4/a.py" to "from django.urls import path, include\nurlpatterns = [path('x/', include('u4.b'))]\n",
            "u4/b.py" to "from django.urls import path, include\nurlpatterns = [path('y/', include('u4.a'))]\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(2, result.diagnostics.count { it.code == DiagnosticCode.UNSUPPORTED_PATTERN })
    }
}
