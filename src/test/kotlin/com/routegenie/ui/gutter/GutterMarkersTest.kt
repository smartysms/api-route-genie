package com.routegenie.ui.gutter

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.routegenie.Limits
import com.routegenie.discovery.ProjectPaths
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SourceRef
import com.routegenie.settings.RouteGenieSettings
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PlatformPerf
import com.routegenie.testing.ProviderPipeline
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.django.DjangoRouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.testing.PythonPlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.lang.ref.WeakReference

/** Markers against the real discovery of the fixtures, the activation rule, the settings switch, speed and release. */
class GutterMarkersTest : PythonPlatformTestCase() {
    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    private val index get() = RouteGutterIndex.getInstance(project)
    private val discovery get() = RouteDiscoveryService.getInstance(project)

    override fun setUp() {
        super.setUp()
        index.resetActivationForTests()
        index.installModelForTests(GutterModel.EMPTY)
        // The IDE running the tests may have the paid Python plugin, which turns the default off.
        RouteGenieSettings.getInstance().showGutterIcons = true
    }

    override fun tearDown() {
        try {
            discovery.setActive(false)
            RouteGenieSettings.getInstance().state.showGutterIcons = null
        } finally {
            super.tearDown()
        }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    /** Lines (1-based) of our markers after a highlighting pass of the open file. */
    private fun markerLines(): List<Int> {
        myFixture.doHighlighting()
        val document = myFixture.editor.document
        return DaemonCodeAnalyzerImpl.getLineMarkers(document, project).filterIsInstance<RouteMarkerInfo>()
            .map { document.getLineNumber(it.startOffset) + 1 }.sorted()
    }

    /** Runs the real provider over the copied fixture and installs the result as the gutter model. */
    private fun installFixture(fixture: String, provider: RouteProvider): List<Endpoint> {
        myFixture.copyDirectoryToProject(fixture, "")
        val endpoints = provider.link(ProviderPipeline.collect(project, provider)).endpoints
        index.installModelForTests(GutterModel.build(RouteSnapshot(1, endpoints)))
        return endpoints
    }

    private fun assertMarkersMatchEndpoints(fixture: String, provider: RouteProvider, openFile: String, anchorStarts: List<String>) {
        val endpoints = installFixture(fixture, provider)
        myFixture.configureFromTempProjectFile(openFile)

        val expected = endpoints.filter { it.source.path == openFile }.map { it.source.line }.distinct().sorted()
        assertTrue("fixture has endpoints in $openFile", expected.isNotEmpty())
        val actual = markerLines()
        assertEquals(expected, actual)
        val document = myFixture.editor.document
        for (line in actual) {
            val text = document.text.lines()[line - 1].trim()
            assertTrue("marker line '$text' starts a route declaration", anchorStarts.any { text.startsWith(it) })
        }
    }

    fun testFastApiMarkersSitOnTheDecoratorLines() =
        assertMarkersMatchEndpoints("fastapi/basic", FastApiRouteProvider(), "app/main.py", listOf("@app."))

    fun testFlaskMarkersSitOnTheDeclarationLines() =
        assertMarkersMatchEndpoints("flask/basic", FlaskRouteProvider(), "app/api.py", listOf("@", "bp.", "api.", "app."))

    fun testDjangoMarkersSitOnThePathLines() =
        assertMarkersMatchEndpoints("django/django_basic", DjangoRouteProvider(), "blog/urls.py", listOf("path(", "re_path(", "url("))

    fun testTooltipListsMethodsAndPath() {
        installFixture("fastapi/basic", FastApiRouteProvider())
        myFixture.configureFromTempProjectFile("app/main.py")
        myFixture.doHighlighting()
        val tooltips = DaemonCodeAnalyzerImpl.getLineMarkers(myFixture.editor.document, project).filterIsInstance<RouteMarkerInfo>()
            .map { it.lineMarkerTooltip }
        assertTrue(tooltips.toString(), tooltips.contains("GET /health"))
        assertTrue(tooltips.toString(), tooltips.contains("GET, POST /multi"))
    }

    fun testOpeningAPlainFileNeverStartsDiscovery() {
        val before = discovery.snapshot.value.generation
        myFixture.configureByText("plain.py", "import os\nd = {}\nvalue = d.get('a')\nprint(os.getcwd())\n")
        myFixture.doHighlighting()
        Thread.sleep(300)
        assertFalse(index.isActivated)
        assertEquals("no scan ran", before, discovery.snapshot.value.generation)
        assertTrue(markerLines().isEmpty())
    }

    fun testOpeningARouteFileActivatesDiscoveryOnce() {
        myFixture.copyDirectoryToProject("fastapi/basic", "")
        myFixture.configureFromTempProjectFile("app/main.py")
        assertFalse(index.isActivated)
        val before = discovery.snapshot.value.generation
        myFixture.doHighlighting()
        assertTrue(index.isActivated)
        waitUntil("scan after activation") { discovery.snapshot.value.generation > before }
    }

    fun testSettingOffRemovesMarkersAndOnBringsThemBack() {
        installFixture("fastapi/basic", FastApiRouteProvider())
        myFixture.configureFromTempProjectFile("app/main.py")
        assertFalse(markerLines().isEmpty())

        RouteGenieSettings.getInstance().showGutterIcons = false
        index.refreshMarkers()
        assertTrue("off means no markers", markerLines().isEmpty())
        RouteGenieSettings.getInstance().showGutterIcons = true
        index.refreshMarkers()
        assertFalse(markerLines().isEmpty())
    }

    private fun lines200(): Pair<String, Map<Int, LineEntry>> {
        val text = buildString {
            append("from fastapi import APIRouter\nrouter = APIRouter()\n")
            for (k in 0 until 200) append("@router.get(\"/r$k\")\ndef h$k():\n    return $k\n")
        }
        val endpoints = (0 until 200).map { k ->
            val line = 3 + k * 3
            Endpoint(HttpMethod.GET, "/r$k", Framework.FASTAPI, Handler("h$k", "m.h$k"), SourceRef("big.py", 0, line))
        }
        return text to GutterModel.build(RouteSnapshot(1, endpoints)).linesOf("big.py")!!
    }

    fun testMarkerComputationForTwoHundredRoutesIsWithinTheKeystrokeBudget() {
        val (text, lines) = lines200()
        val file = myFixture.configureByText("big.py", text)
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val elements = PsiTreeUtil.collectElements(file) { true }.toList()
        val sink = ArrayList<LineMarkerInfo<*>>()
        val result = PlatformPerf.assertWithinOnEdt("gutter-200-route-lines", Limits.BUDGET_FILTER_KEYSTROKE_MS, warmups = 5, runs = 15) {
            sink.clear()
            RouteLineMarkerProvider.collect(document, lines, elements, sink)
        }
        assertEquals(200, sink.size)
        println("gutter markers for 200 route lines (${elements.size} elements): median ${result.medianMs} ms, budget ${Limits.BUDGET_FILTER_KEYSTROKE_MS} ms")
    }

    fun testIndexIsReleasedWithItsOwnerAndStopsCollecting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var own: RouteGutterIndex? = RouteGutterIndex(project, scope)
        val ref = WeakReference(own)
        Disposer.dispose(own!!)
        assertTrue(own.model.isEmpty)
        scope.cancel()
        own = null
        LeakProbe.assertCollected("RouteGutterIndex", ref)
    }
}
