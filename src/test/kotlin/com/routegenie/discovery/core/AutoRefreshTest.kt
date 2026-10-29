package com.routegenie.discovery.core

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.routegenie.Limits
import com.routegenie.testing.FakeRouteProvider

/** The list must follow the code without a refresh button: typing, outside changes, hidden window, deletes, noise. */
class AutoRefreshTest : EngineTestCase() {
    private fun srcRoot() = myFixture.findFileInTempDir("pkg0/module0.py").parent.parent

    fun testUnsavedEditUpdatesTheSnapshotWithinDebouncePlusBudget() {
        addSyntheticFiles(20, routesPerFile = 1)
        val engine = engine(FakeRouteProvider())
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val before = engine.snapshot.value.generation

        val start = System.nanoTime()
        editFile("pkg0/module2.py", "# route /typed\n")
        waitUntil("snapshot after typing") { engine.snapshot.value.generation > before }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        println("typing to snapshot: $elapsedMs ms (debounce ${Limits.REFRESH_DEBOUNCE_MS} ms + budget ${Limits.BUDGET_REFRESH_ONE_FILE_MS} ms)")
        assertTrue("/typed" in engine.snapshot.value.endpoints.map { it.path })
        assertTrue("took $elapsedMs ms", elapsedMs <= Limits.REFRESH_DEBOUNCE_MS + Limits.BUDGET_REFRESH_ONE_FILE_MS + SCHEDULING_SLACK_MS)
    }

    fun testOutsideBatchOfManyFilesCausesOneRelink() {
        addSyntheticFiles(3, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, debounceMs = 200)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val links = provider.linkCount.get()

        // One write action = one batch of VFS events, like a checkout that touches many files.
        runWriteAction {
            val dir = VfsUtil.createDirectoryIfMissing(srcRoot(), "checkout")
            repeat(150) { n -> VfsUtil.saveText(dir.createChildData(this, "f$n.py"), "# route /co$n\n") }
        }

        waitUntil("batch picked up") { engine.snapshot.value.endpoints.size == 3 + 150 }
        Thread.sleep(500)
        assertEquals("one relink for the whole batch", links + 1, provider.linkCount.get())
    }

    fun testManySeparateOutsideChangesInsideTheQuietPeriodCauseOneRelink() {
        addSyntheticFiles(2, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, debounceMs = 300)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val links = provider.linkCount.get()

        repeat(30) { n -> runWriteAction { VfsUtil.saveText(srcRoot().createChildData(this, "x$n.py"), "# route /x$n\n") } }

        waitUntil("changes picked up") { engine.snapshot.value.endpoints.size == 2 + 30 }
        Thread.sleep(600)
        assertTrue("expected at most 2 relinks, got ${provider.linkCount.get() - links}", provider.linkCount.get() - links <= 2)
    }

    fun testRenameAndMoveAreFollowed() {
        addSyntheticFiles(2, routesPerFile = 1)
        val engine = engine(FakeRouteProvider(), debounceMs = 50)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val oldPath = engine.snapshot.value.endpoints.first().source.path

        runWriteAction { myFixture.findFileInTempDir("pkg0/module0.py").rename(this, "renamed.py") }

        waitUntil("rename seen") { engine.snapshot.value.endpoints.none { it.source.path == oldPath } }
        assertTrue(engine.snapshot.value.endpoints.any { it.source.path.endsWith("renamed.py") })
        assertEquals(2, engine.snapshot.value.endpoints.size)
    }

    fun testDirectoryDeleteIsFollowed() {
        addSyntheticFiles(2, routesPerFile = 1)
        myFixture.addFileToProject("gone/a.py", "# route /gone\n")
        val engine = engine(FakeRouteProvider(), debounceMs = 50)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        assertEquals(3, engine.snapshot.value.endpoints.size)

        runWriteAction { myFixture.findFileInTempDir("gone").delete(this) }

        waitUntil("directory delete seen") { engine.snapshot.value.endpoints.size == 2 }
    }

    fun testDeletingAFileRemovesItsEndpointsAndEvictsItsCacheEntry() {
        addSyntheticFiles(4, routesPerFile = 1)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        assertEquals(4, engine.cachedEntries)

        runWriteAction { myFixture.findFileInTempDir("pkg0/module1.py").delete(this) }
        engine.refreshBlocking()

        assertEquals(3, engine.snapshot.value.endpoints.size)
        assertEquals("the deleted file's facts are evicted", 3, engine.cachedEntries)
    }

    fun testHiddenWindowMarksStaleAndDoesNoWork() {
        addSyntheticFiles(3, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, debounceMs = 30, active = false)
        engine.start(coldStart = false)

        editFile("pkg0/module0.py", "# route /hidden\n")
        runWriteAction { VfsUtil.saveText(srcRoot().createChildData(this, "new.py"), "# route /new\n") }
        Thread.sleep(400)

        assertEquals("no pass while hidden", 0, engine.runCount)
        assertEquals(0, provider.extractCount.get())
        assertTrue(engine.isStale)
        assertEquals(0L, engine.snapshot.value.generation)
    }

    fun testFirstShowScansAndLaterShowsRefreshOnlyWhenStale() {
        addSyntheticFiles(3, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, debounceMs = 30, active = false)
        engine.start(coldStart = false)

        engine.setActive(true)
        waitUntil("scan on first show") { engine.snapshot.value.endpoints.size == 3 }
        assertFalse(engine.isStale)

        engine.setActive(false)
        val runs = engine.runCount
        engine.setActive(true)
        Thread.sleep(300)
        assertEquals("shown again with nothing changed: no work", runs, engine.runCount)

        engine.setActive(false)
        editFile("pkg0/module0.py", "# route /while-hidden\n")
        Thread.sleep(200)
        assertEquals("still hidden: still no work", runs, engine.runCount)
        assertTrue(engine.isStale)

        engine.setActive(true)
        waitUntil("refresh on show") { "/while-hidden" in engine.snapshot.value.endpoints.map { it.path } }
        assertFalse(engine.isStale)
    }

    fun testEditInAFileOutsideTheCandidatesCostsNoExtraction() {
        addSyntheticFiles(5, routesPerFile = 1)
        myFixture.addFileToProject("util/helpers.py", "def helper():\n    return 1\n")
        val provider = FakeRouteProvider().apply { candidateFilter = { !it.path.contains("/util/") } }
        val engine = engine(provider, debounceMs = 30)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val extracted = provider.extractCount.get()
        val generation = engine.snapshot.value.generation
        val runs = engine.runCount

        editFile("util/helpers.py", "def helper():\n    return 2\n")
        waitUntil("a pass ran") { engine.runCount > runs }
        Thread.sleep(100)

        assertEquals("the unrelated file is never extracted", extracted, provider.extractCount.get())
        assertEquals("nothing changed, nothing published", generation, engine.snapshot.value.generation)
    }

    fun testUnrelatedPassIsCheap() {
        addSyntheticFiles(600, routesPerFile = 3)
        myFixture.addFileToProject("util/helpers.py", "x = 1\n")
        val provider = FakeRouteProvider().apply { candidateFilter = { !it.path.contains("/util/") } }
        val engine = engine(provider)
        engine.refreshBlocking()
        var flip = 0
        val samples = ArrayList<Double>()
        repeat(15) {
            editFile("util/helpers.py", "x = ${flip++}\n")
            val start = System.nanoTime()
            engine.refreshBlocking()
            samples += (System.nanoTime() - start) / 1_000_000.0
        }
        val median = samples.drop(3).sorted().let { it[it.size / 2] }
        println("pass after an edit in a non-framework file, 600 candidates: median ${"%.2f".format(median)} ms")
        assertTrue("median $median ms", median <= Limits.BUDGET_REFRESH_ONE_FILE_MS)
    }

    fun testEditsOutsidePythonDoNotEvenStartAPass() {
        addSyntheticFiles(2, routesPerFile = 1)
        val doc = myFixture.addFileToProject("notes.txt", "hello\n")
        val engine = engine(FakeRouteProvider(), debounceMs = 30)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        val runs = engine.runCount

        val document = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(doc)!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) { document.setText("changed\n") }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        Thread.sleep(300)

        assertEquals(runs, engine.runCount)
    }

    fun testNoListenerReactsAfterDisposal() {
        addSyntheticFiles(2, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val short = Disposer.newDisposable("short lived")
        val engine = engine(provider, debounceMs = 20, parent = short)
        engine.start(coldStart = false)
        engine.refreshBlocking()
        Disposer.dispose(short)
        val runs = engine.runCount

        editFile("pkg0/module0.py", "# route /psi-after\n")
        runWriteAction { VfsUtil.saveText(srcRoot().createChildData(this, "late.py"), "# route /vfs-after\n") }
        engine.setActive(true)
        Thread.sleep(300)

        assertEquals("neither the PSI nor the VFS listener nor setActive starts work after disposal", runs, engine.runCount)
    }

    private companion object {
        /** Thread hand-offs and the test's own event pumping; not part of any product budget. */
        const val SCHEDULING_SLACK_MS = 700L
    }
}
