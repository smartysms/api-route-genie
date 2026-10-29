package com.routegenie.discovery.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.routegenie.Limits
import com.routegenie.discovery.RouteProvider
import com.routegenie.testing.FakeRouteProvider
import com.routegenie.testing.PythonPlatformTestCase
import com.routegenie.testing.SyntheticProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Shared setup: a scope owned by the test, engines tied to a per-test Disposable, and helpers to edit files. */
abstract class EngineTestCase : PythonPlatformTestCase() {
    protected lateinit var scope: CoroutineScope
    protected lateinit var owner: Disposable

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        owner = Disposer.newDisposable("engine test owner")
    }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    protected fun engine(
        vararg providers: RouteProvider,
        debounceMs: Long = Limits.REFRESH_DEBOUNCE_MS,
        cacheWeightLimit: Long = Limits.MAX_FACTS_CACHE_BYTES,
        parent: Disposable = owner,
        active: Boolean = true,
        parallelism: Int = Limits.extractParallelism(Runtime.getRuntime().availableProcessors()),
    ) = RouteDiscoveryEngine(project, scope, parent, { providers.toList() }, debounceMs, cacheWeightLimit, active, parallelism)

    /** Runs the suspend refresh on a pool thread, the way the service does, so the test thread (the UI thread) is free. */
    protected fun RouteDiscoveryEngine.refreshBlocking() {
        runBlocking(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { refreshNow() } }
    }

    protected fun addSyntheticFiles(count: Int, routesPerFile: Int = 2) {
        SyntheticProject.files(count, routesPerFile).forEach { myFixture.addFileToProject(it.path, it.text) }
    }

    /** Replaces a file's text through its document, as typing would, and commits so the PSI stamp changes. */
    protected fun editFile(path: String, text: String) {
        val psi = myFixture.findFileInTempDir(path)?.let { com.intellij.psi.PsiManager.getInstance(project).findFile(it) }
            ?: error("no such file: $path")
        val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: error("no document: $path")
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /** Pumps UI events while waiting, because platform tests run on the UI thread that background work may need. */
    protected fun waitUntil(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for: $what")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
