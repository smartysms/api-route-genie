package com.routegenie.discovery.core

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.openapi.project.Project
import com.routegenie.frameworks.common.PyFileFacts
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.testing.ObjectGraph
import com.routegenie.testing.RecordingProvider

/**
 * The correctness blocker of the old design: facts of file Y used to depend on file X (constants, imports, module names)
 * while the cache validated Y by its own stamp only. Facts are now context-free, so these tests pin down both halves: the
 * facts do not depend on other files, and the endpoints still follow a change of X without Y being read again.
 */
class ContextFreeFactsTest : EngineTestCase() {
    private val routes = """
        from fastapi import FastAPI, APIRouter
        from consts import API

        app = FastAPI()
        router = APIRouter(prefix=API)

        @router.get("/items")
        def items(): ...

        app.include_router(router)
    """.trimIndent() + "\n"

    private fun paths(engine: RouteDiscoveryEngine) = engine.snapshot.value.endpoints.map { it.path }.sorted()

    fun testFactsOfAFileDoNotDependOnOtherFiles() {
        val provider = FastApiRouteProvider()
        val psi = myFixture.addFileToProject("routes.py", routes)
        val alone = provider.extract(psi) as PyFileFacts

        myFixture.addFileToProject("consts.py", "API = '/v1'\n")
        myFixture.addFileToProject("sub/consts.py", "API = '/elsewhere'\n")
        myFixture.addFileToProject("other.py", "from routes import router\n")
        val withNeighbours = provider.extract(psi) as PyFileFacts
        assertEquals("the same text gives equal facts whatever else is in the project", alone, withNeighbours)
        assertEquals(alone.hashCode(), withNeighbours.hashCode())

        val moved = provider.extract(myFixture.addFileToProject("deep/er/moved_routes.py", routes)) as PyFileFacts
        assertEquals("the location of a file is not part of its facts", alone, moved)
    }

    fun testFactsHoldNoPsiAndNoVirtualFiles() {
        val forbidden = arrayOf(PsiElement::class.java, VirtualFile::class.java, Project::class.java)
        val fastApi = FastApiRouteProvider().extract(myFixture.addFileToProject("routes.py", routes))!!
        ObjectGraph.assertNoInstanceOf("fastapi facts", fastApi, *forbidden)
        val dependency = FastApiRouteProvider().extractDependency(myFixture.addFileToProject("consts.py", "API = '/v1'\n"))!!
        ObjectGraph.assertNoInstanceOf("dependency facts", dependency, *forbidden)
        val flask = FlaskRouteProvider().extract(myFixture.addFileToProject("app.py", "from flask import Flask\napp = Flask(__name__)\n@app.route('/a')\ndef a(): ...\n"))!!
        ObjectGraph.assertNoInstanceOf("flask facts", flask, *forbidden)
    }

    fun testEditingTheConstantsFileChangesTheRoutesWithoutReadingTheRoutesFileAgain() {
        myFixture.addFileToProject("consts.py", "API = '/v1'\n")
        myFixture.addFileToProject("routes.py", routes)
        val provider = RecordingProvider(FastApiRouteProvider())
        val engine = engine(provider)
        engine.refreshBlocking()
        assertEquals(listOf("/v1/items"), paths(engine))
        assertTrue("consts.py was read as a dependency", "consts.py" in provider.extracted)
        val generation = engine.snapshot.value.generation

        provider.reset()
        editFile("consts.py", "API = '/v2'\n")
        engine.refreshBlocking()

        assertEquals("the endpoints follow the constant", listOf("/v2/items"), paths(engine))
        assertEquals("only the changed file was read again", listOf("consts.py"), provider.extracted.toList())
        assertTrue(engine.snapshot.value.generation > generation)
    }

    // The stale-cache regression of research 06 (T5): the cached facts of the route file would have kept "/v1".
    fun testStaleCacheRegressionForEveryWayToReachAConstant() {
        myFixture.addFileToProject("pkg/__init__.py", "")
        myFixture.addFileToProject("pkg/consts.py", "API = '/v1'\n")
        myFixture.addFileToProject("pkg/star.py", "from .consts import *\n")
        myFixture.addFileToProject(
            "pkg/routes.py",
            """
            from fastapi import FastAPI, APIRouter
            from . import consts
            from .consts import API
            from .star import API as VIA_STAR

            app = FastAPI()
            direct = APIRouter(prefix=API)
            module = APIRouter(prefix=consts.API)
            starred = APIRouter(prefix=VIA_STAR)

            @direct.get("/d")
            def d(): ...

            @module.get("/m")
            def m(): ...

            @starred.get("/s")
            def s(): ...

            app.include_router(direct)
            app.include_router(module, prefix="/mod")
            app.include_router(starred, prefix="/star")
            """.trimIndent() + "\n",
        )
        val provider = RecordingProvider(FastApiRouteProvider())
        val engine = engine(provider)
        engine.refreshBlocking()
        assertEquals(listOf("/mod/v1/m", "/star/v1/s", "/v1/d"), paths(engine))

        provider.reset()
        editFile("pkg/consts.py", "API = '/v9'\n")
        engine.refreshBlocking()
        assertEquals(listOf("/mod/v9/m", "/star/v9/s", "/v9/d"), paths(engine))
        assertFalse("routes.py was not read again", "routes.py" in provider.extracted)
    }

    fun testARenamedRouteFileNeedsARelinkAndNoNewExtraction() {
        myFixture.addFileToProject("consts.py", "API = '/v1'\n")
        myFixture.addFileToProject("routes.py", routes)
        val provider = RecordingProvider(FastApiRouteProvider())
        val engine = engine(provider)
        engine.refreshBlocking()
        assertEquals("routes.py", engine.snapshot.value.endpoints.single().source.path)

        provider.reset()
        runWriteAction { myFixture.findFileInTempDir("routes.py").rename(this, "renamed_routes.py") }
        engine.refreshBlocking()

        assertEquals("renamed_routes.py", engine.snapshot.value.endpoints.single().source.path)
        assertTrue("nothing was parsed again: ${provider.extracted}", provider.extracted.isEmpty())
    }

    fun testADeletedConstantsFileOmitsTheRoutesInsteadOfGuessing() {
        myFixture.addFileToProject("consts.py", "API = '/v1'\n")
        myFixture.addFileToProject("routes.py", routes)
        val engine = engine(FastApiRouteProvider())
        engine.refreshBlocking()
        assertEquals(listOf("/v1/items"), paths(engine))

        runWriteAction { myFixture.findFileInTempDir("consts.py").delete(this) }
        engine.refreshBlocking()
        assertEquals("with the constants gone the route is omitted, never guessed", emptyList<String>(), paths(engine))
        assertTrue(engine.snapshot.value.diagnostics.isNotEmpty())
    }
}
