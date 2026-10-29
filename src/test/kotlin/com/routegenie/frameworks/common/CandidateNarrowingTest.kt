package com.routegenie.frameworks.common

import com.routegenie.Limits
import com.routegenie.frameworks.fastapi.FastApiCatalog
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskCatalog
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.testing.ProviderPipeline
import com.routegenie.testing.PythonPlatformTestCase

/**
 * What the candidate search finds and what it deliberately does not (research 06, Q2). The framework's name alone selects
 * nothing; routes are found by the constructors and attachment calls they use, or by the decorator index, which also
 * finds a route file that never mentions the framework because its router is imported from another module.
 */
class CandidateNarrowingTest : PythonPlatformTestCase() {
    private fun fastApiNames() = FastApiRouteProvider().findCandidates(project).map { it.path.substringAfter("/src/") }
    private fun flaskNames() = FlaskRouteProvider().findCandidates(project).map { it.path.substringAfter("/src/") }

    fun testTheFrameworkNameAloneSelectsNothing() {
        pythonFile("only_import.py", "import fastapi\nfrom fastapi import status\n")
        pythonFile("only_flask.py", "import flask\nfrom flask import request\n")
        pythonFile("mentions_route.py", "# the route of this word is a comment\nroute = 1\n")
        pythonFile("real.py", "from fastapi import FastAPI\napp = FastAPI()\n")
        assertEquals(listOf("real.py"), fastApiNames())
        assertEquals(emptyList<String>(), flaskNames())
    }

    // Resolves 06 recall gap T2: FastAPI routes in a file that imports its router and never names the framework.
    fun testARouteFileThatNeverMentionsTheFrameworkIsFoundThroughTheDecoratorIndex() {
        pythonFile("core.py", "from fastapi import APIRouter\nrouter = APIRouter(prefix='/c')\n")
        pythonFile("routes.py", "from core import router\n\n@router.get('/x')\ndef x(): ...\n")
        pythonFile("main.py", "from fastapi import FastAPI\nfrom core import router\nimport routes\napp = FastAPI()\napp.include_router(router)\n")
        assertTrue("routes.py" in fastApiNames())
        val result = FastApiRouteProvider().let { it.link(ProviderPipeline.collect(project, it)) }
        assertEquals(listOf("/c/x"), result.endpoints.map { it.path })
    }

    fun testFlaskViewClassFilesAreReadOnlyWhenFlaskIsMentioned() {
        val flask = FlaskRouteProvider()
        val views = pythonFile("views.py", "from flask.views import MethodView\nclass Users(MethodView):\n    def get(self): ...\n")
        val djangoViews = pythonFile("django_views.py", "from django.views import View\nclass Home(View):\n    pass\n")
        assertTrue("views.py" in flaskNames())
        assertNotNull(flask.extract(views))
        assertNull("a Django View must not be parsed as a Flask view", flask.extract(djangoViews))
    }

    fun testVirtualEnvironmentsGeneratedCodeMigrationsAndHugeFilesAreSkipped() {
        val router = "from fastapi import APIRouter\nrouter = APIRouter()\n"
        pythonFile("app/ok.py", router)
        pythonFile("venv/lib/site-packages/fastapi_like.py", router)
        pythonFile("app/migrations/0001_initial.py", router)
        pythonFile("app/api_pb2.py", router)
        pythonFile("node_modules/x/gen.py", router)
        myFixture.addFileToProject("custom_env/pyvenv.cfg", "home = /usr/bin\n")
        pythonFile("custom_env/lib/fake_pkg.py", router)
        val huge = router + "# " + "x".repeat(Limits.MAX_CANDIDATE_FILE_BYTES.toInt()) + "\n"
        pythonFile("app/huge.py", huge)
        assertEquals(listOf("app/ok.py"), fastApiNames().sorted())
    }

    fun testTestFilesAreDeferredAfterApplicationFiles() {
        val router = "from fastapi import APIRouter\nrouter = APIRouter()\n"
        pythonFile("a_tests/test_api.py", router)
        pythonFile("zz/app.py", router)
        pythonFile("tests/helpers.py", router)
        pythonFile("conftest.py", router)
        pythonFile("m/api.py", router)
        val names = fastApiNames()
        assertEquals("all are still read, test code last", 5, names.size)
        assertEquals(setOf("m/api.py", "zz/app.py"), names.take(2).toSet())
        assertEquals(setOf("a_tests/test_api.py", "tests/helpers.py", "conftest.py"), names.drop(2).toSet())
    }

    fun testPathRulesDoNotLookAtTheCheckoutLocation() {
        assertFalse(PyCandidateRules.isExcludedByName("app/api.py"))
        assertTrue(PyCandidateRules.isExcludedByName("app/migrations/0002_x.py"))
        assertTrue(PyCandidateRules.isTestPath("pkg/tests/test_x.py"))
        assertFalse(PyCandidateRules.isTestPath("pkg/contest/x.py"))
    }

    fun testThePrefilterAcceptsRouteTextAndRejectsEverythingElse() {
        fun passes(text: String, table: PrimitiveTable) = PyProviderSupport.passesPrefilter(pythonFile("p${text.hashCode()}.py", text), table)
        val fastApi = FastApiCatalog.table
        assertFalse(passes("x = 1\n", fastApi))
        assertFalse(passes("from fastapi.testclient import TestClient\n", fastApi))
        assertTrue(passes("@router.get('/a')\ndef a(): ...\n", fastApi))
        assertTrue(passes("app.include_router(r)\n", fastApi))
        assertTrue(passes("from fastapi import APIRouter\n", fastApi))
        val flask = FlaskCatalog.table
        assertFalse(passes("import flask\nx = 1\n", flask))
        assertTrue(passes("@bp.route('/a')\n", flask))
        assertTrue(passes("import flask\nclass V(flask.views.MethodView): ...\n", flask))
        assertFalse(passes("class V(View): ...\n", flask))
    }

    // Stated limit: a test that patches things with @mock.patch(...) is read (the decorator index cannot tell) but yields nothing.
    fun testAMockPatchDecoratorIsReadAndYieldsNoRoute() {
        pythonFile("tests/test_x.py", "from unittest import mock\n\n@mock.patch('a.b')\ndef test_it(m): ...\n")
        val provider = FlaskRouteProvider()
        assertTrue(provider.link(ProviderPipeline.collect(project, provider)).endpoints.isEmpty())
    }
}
