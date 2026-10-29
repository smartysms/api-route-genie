package com.routegenie.frameworks.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.ObjectGraph
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PythonPlatformTestCase
import java.lang.ref.WeakReference

/** Facts and snapshots must be PSI-free and releasable; one-file refresh must stay inside its budget. */
class FactsLeakAndPerfTest : PythonPlatformTestCase() {
    private val fastApi = FastApiRouteProvider()
    private val flask = FlaskRouteProvider()

    private val fastApiText = """
        from fastapi import FastAPI, APIRouter
        app = FastAPI()
        router = APIRouter(prefix="/p", tags=["t"])
        @router.get("/a/{id}")
        def a(id: int): ...
        app.include_router(router, prefix="/v1")
    """.trimIndent() + "\n"

    private val flaskText = """
        from flask import Flask, Blueprint
        app = Flask(__name__)
        bp = Blueprint("b", __name__, url_prefix="/b")
        @bp.route("/a/<int:id>")
        def a(id): ...
        app.register_blueprint(bp)
    """.trimIndent() + "\n"

    private fun file(path: String, text: String): PsiFile = pythonFile(path, text)

    private fun forbidden() = arrayOf(PsiElement::class.java, VirtualFile::class.java, Project::class.java)

    fun testFastApiFactsAndSnapshotHoldNoPsi() {
        val facts = fastApi.extract(file("main.py", fastApiText))!!
        ObjectGraph.assertNoInstanceOf("fastapi facts", facts, *forbidden())
        ObjectGraph.assertNoInstanceOf("fastapi link result", fastApi.link(listOf(facts)), *forbidden())
    }

    fun testFlaskFactsAndSnapshotHoldNoPsi() {
        val facts = flask.extract(file("app.py", flaskText))!!
        ObjectGraph.assertNoInstanceOf("flask facts", facts, *forbidden())
        ObjectGraph.assertNoInstanceOf("flask link result", flask.link(listOf(facts)), *forbidden())
    }

    fun testFactsAndLinkResultsAreCollectedWhenDropped() {
        val psi = file("main.py", fastApiText)
        var facts: FileFacts? = fastApi.extract(psi)
        var result: Any? = fastApi.link(listOf(facts!!))
        val factsRef = WeakReference(facts)
        val resultRef = WeakReference(result)
        facts = null
        result = null
        LeakProbe.assertCollected("fastapi facts", factsRef)
        LeakProbe.assertCollected("fastapi link result", resultRef)
    }

    fun testRepeatedExtractionDoesNotGrowTheHeap() {
        val fastApiPsi = file("main.py", fastApiText)
        val flaskPsi = file("app.py", flaskText)
        LeakProbe.assertNoHeapGrowth("fastapi extract+link", maxGrowthBytes = HEAP_TOLERANCE_BYTES, warmups = 30, iterations = 300) {
            fastApi.link(listOfNotNull(fastApi.extract(fastApiPsi)))
        }
        LeakProbe.assertNoHeapGrowth("flask extract+link", maxGrowthBytes = HEAP_TOLERANCE_BYTES, warmups = 30, iterations = 300) {
            flask.link(listOfNotNull(flask.extract(flaskPsi)))
        }
    }

    fun testOneFileRefreshOfAHundredRoutesStaysWithinTheBudget() {
        measureRefresh(
            fastApi, "fastapi-refresh-100-routes", "r.py",
            hundredRoutes("from fastapi import FastAPI, APIRouter\napp = FastAPI()\nrouter = APIRouter(prefix=\"/big\")\n", "router.get", "/item@/{id}", "app.include_router(router)\n"),
        )
        measureRefresh(
            flask, "flask-refresh-100-routes", "bp.py",
            hundredRoutes("from flask import Flask, Blueprint\napp = Flask(__name__)\nbp = Blueprint(\"big\", __name__, url_prefix=\"/big\")\n", "bp.route", "/item@/<int:id>", "app.register_blueprint(bp)\n"),
        )
    }

    /** [path] holds the placeholder `@` where the route number goes. */
    private fun hundredRoutes(header: String, decorator: String, path: String, footer: String): String = buildString {
        append(header)
        for (i in 0 until ROUTES) {
            append("@$decorator(\"${path.replace("@", i.toString())}\")\n")
            append("def handler$i(id): ...\n")
        }
        append(footer)
    }

    private fun measureRefresh(provider: RouteProvider, label: String, name: String, text: String) {
        val edited = file(name, text)
        // More files stand for the rest of the project, already extracted and cached.
        val others: List<FileFacts> = (0 until OTHER_FILES).mapNotNull { i ->
            provider.extract(file("$label-other$i.py", text.replace("/big", "/o$i")))
        }
        PerfBudget.assertWithin(label, Limits.BUDGET_REFRESH_ONE_FILE_MS) {
            val facts = provider.extract(edited)!!
            provider.link(others + facts)
        }
        val total = provider.link(others + provider.extract(edited)!!).endpoints.size
        assertTrue("expected the routes of every file, got $total", total >= ROUTES * (OTHER_FILES + 1))
    }

    private companion object {
        const val ROUTES = 100
        const val OTHER_FILES = 4

        /** Allocation noise of the IDE between the two heap samples; a real per-run leak would be far larger. */
        const val HEAP_TOLERANCE_BYTES = 8L * 1024 * 1024
    }
}
