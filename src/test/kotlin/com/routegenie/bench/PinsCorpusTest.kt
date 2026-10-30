package com.routegenie.bench

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.core.PinEnv
import com.routegenie.discovery.core.RouteDiscoveryEngine
import com.routegenie.model.RouteSnapshot
import com.routegenie.testing.ProjectFiles
import com.routegenie.testing.PythonPlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Pins on real repositories, opt-in like [CorpusBenchmarkTest] (skipped unless [BenchSettings.CORPUS_ENV] is set): without a pin the
 * counts must equal the numbers in docs/BENCHMARKS.md (nothing regresses); on the official FastAPI template the suggested pin
 * (the default the code states) is applied and the endpoints that appear are recorded. Results go to
 * `build/reports/routegenie/bench/pins-<repo>.txt`. The code of the repositories is read, never executed.
 */
class PinsCorpusTest : PythonPlatformTestCase() {
    private lateinit var scope: CoroutineScope
    private lateinit var owner: Disposable
    private val out = StringBuilder()
    private val contentRoots = ArrayList<VirtualFile>()
    private val pins = AtomicReference(PinEnv.EMPTY)

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        owner = Disposer.newDisposable("pins corpus owner")
    }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            scope.cancel()
            contentRoots.forEach { runCatching { PsiTestUtil.removeContentEntry(module, it) } }
        } finally {
            try {
                super.tearDown()
            } catch (e: com.intellij.util.lang.CompoundRuntimeException) {
                // Same harness quirk as in CorpusBenchmarkTest: platform pointers to a repository's front end package.json.
                if (!e.message.orEmpty().contains(WEB_MARKER)) throw e
            }
        }
    }

    fun testFullStackFastapiTemplate() = run("full-stack-fastapi-template") { snapshot ->
        // The point of the feature: 0 endpoints without a pin, and the exact list once the user accepts the suggestion.
        val setting = snapshot.settings.firstOrNull { it.expression == TEMPLATE_SETTING }
        line("template_setting_found", setting != null)
        line("template_setting_routes", setting?.routeCount)
        line("template_suggestion", setting?.suggestion?.value)
        line("template_suggestion_origin", setting?.suggestion?.origin)
        val suggested = setting?.suggestion?.value ?: return@run
        pins.set(PinEnv(mapOf(TEMPLATE_SETTING to suggested)))
        val pinned = refresh(requireNotNull(engineRef))
        line("pinned_endpoints", pinned.endpoints.size)
        line("pinned_settings_left", pinned.settings.size)
        line("pinned_by_method", pinned.endpoints.groupingBy { it.method.name }.eachCount().toSortedMap().toString())
        line("pinned_with_pin_marker", pinned.endpoints.count { it.pins.isNotEmpty() })
        line("pinned_diagnostics", pinned.diagnostics.size)
        assertTrue("the suggested pin must list endpoints", pinned.endpoints.isNotEmpty())
        assertTrue(pinned.endpoints.all { it.path.startsWith("$suggested/") || it.path == suggested })
    }

    fun testFastapi() = run("fastapi")
    fun testFlask() = run("flask")
    fun testSuperset() = run("superset")
    fun testDjango() = run("django")
    fun testDjangoRestFramework() = run("django-rest-framework")
    fun testSentry() = run("sentry")

    fun testNetbox() = run("netbox") { snapshot ->
        // NetBox takes its root from settings.BASE_PATH (empty on a root deployment). No literal default exists, so nothing is
        // suggested; the number below is what an owner who answers "empty" would see.
        line("netbox_settings", snapshot.settings.joinToString(";") { "${it.expression}=${it.routeCount}" })
        val base = snapshot.settings.firstOrNull { it.expression.endsWith("BASE_PATH") } ?: return@run
        line("netbox_base_path_suggestion", base.suggestion?.value)
        pins.set(PinEnv(mapOf(base.expression to "")))
        val pinned = refresh(requireNotNull(engineRef))
        line("netbox_pinned_empty_endpoints", pinned.endpoints.size)
        line("netbox_pinned_settings_left", pinned.settings.joinToString(";") { "${it.expression}=${it.routeCount}" })
    }

    private var engineRef: RouteDiscoveryEngine? = null

    private fun line(key: String, value: Any?) {
        out.append(key).append('=').append(value).append('\n')
        println("PINSCORPUS $key=$value")
    }

    private fun refresh(engine: RouteDiscoveryEngine): RouteSnapshot {
        val job = scope.async { engine.refreshNow() }
        while (!job.isCompleted) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(PUMP_SLEEP_MS)
        }
        kotlinx.coroutines.runBlocking { job.await() }
        return engine.snapshot.value
    }

    private fun run(repo: String, extra: (RouteSnapshot) -> Unit = {}) {
        val corpus = System.getenv(BenchSettings.CORPUS_ENV) ?: return
        val dir = File(corpus, repo)
        if (!dir.isDirectory) return
        val reportDir = File(ProjectFiles.root, BenchSettings.REPORT_DIR).also { it.mkdirs() }
        try {
            line("repo", repo)
            val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir) ?: error("cannot open $dir")
            VfsUtil.markDirtyAndRefresh(false, true, true, root)
            PsiTestUtil.addContentRoot(module, root)
            contentRoots += root
            BenchSettings.SOURCE_ROOTS[repo].orEmpty().forEach { rel -> root.findFileByRelativePath(rel)?.let { PsiTestUtil.addSourceRoot(module, it) } }
            excludeWebFolders(root, dir)
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            val providers = RouteProvider.EP_NAME.extensionList.sortedBy { it.id }
            val engine = RouteDiscoveryEngine(project, scope, owner, { providers }, active = false, pins = { pins.get() })
            engineRef = engine
            val started = System.nanoTime()
            val snapshot = refresh(engine)
            line("cold_ms", String.format(Locale.ROOT, "%.0f", (System.nanoTime() - started) / 1e6))
            line("unpinned_endpoints", snapshot.endpoints.size)
            line("unpinned_diagnostics", snapshot.diagnostics.size)
            line("unresolved_settings", snapshot.settings.joinToString(";") { "${it.expression}=${it.routeCount}" })
            line("expected_endpoints_before_pins", EXPECTED[repo])
            val expected = EXPECTED[repo]
            if (expected != null) assertEquals("$repo: the count without a pin must equal the count before the pins work", expected, snapshot.endpoints.size)
            extra(snapshot)
        } finally {
            File(reportDir, "pins-$repo.txt").writeText(out.toString())
        }
    }

    private fun excludeWebFolders(root: VirtualFile, dir: File) {
        val found = dir.walkTopDown().onEnter { it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.name == WEB_MARKER && it.parentFile != dir }.map { it.parentFile }.toList()
        val excluded = ArrayList<String>()
        for (folder in found) {
            val rel = folder.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (excluded.any { rel.startsWith("$it/") }) continue
            root.findFileByRelativePath(rel)?.let { PsiTestUtil.addExcludedRoot(module, it) }
            excluded += rel
        }
    }

    private companion object {
        const val WEB_MARKER = "package.json"
        const val TEMPLATE_SETTING = "settings.API_V1_STR"
        const val PUMP_SLEEP_MS = 2L

        /**
         * Endpoint counts of the commit before the pins work, measured with the same engine and corpora (docs/BENCHMARKS.md, section
         * "Pins"). The older table of that file predates commit 7f46319 (fastapi 1471, flask 115, sentry 0 then); these are the
         * numbers of HEAD, so a change means the pins work changed discovery without a pin.
         */
        val EXPECTED = mapOf(
            "full-stack-fastapi-template" to 0,
            "fastapi" to 1779,
            "flask" to 152,
            "superset" to 11,
            "django" to 740,
            "netbox" to 0,
            "django-rest-framework" to 139,
            "sentry" to 1222,
        )
    }
}
