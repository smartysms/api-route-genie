package com.routegenie.discovery.core

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.routegenie.Limits
import com.routegenie.testing.FakeRouteProvider
import com.routegenie.testing.PerfBudget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch

/**
 * Behavior of the engine above the sizes the first version was built for: more candidate files than the old 5,000 entry
 * cache could hold, concurrent extraction, and passes that must cost nothing because nothing changed.
 */
class EngineScaleTest : EngineTestCase() {
    private fun createFiles(count: Int, directory: String = "bulk") {
        runWriteAction {
            val root = myFixture.tempDirFixture.findOrCreateDir(directory)
            repeat(count) { i ->
                val file = root.createChildData(this, "f$i.py")
                VfsUtil.saveText(file, "# route /r$i\n")
            }
        }
    }

    fun testMoreCandidatesThanTheOldCacheCapIsNotReparsedOnANoOpRefresh() {
        val count = OLD_CAP + 100
        createFiles(count)
        val provider = FakeRouteProvider()
        val engine = engine(provider)
        engine.refreshBlocking()
        assertEquals(count, engine.snapshot.value.endpoints.size)
        assertEquals("every candidate fits in the weight-bounded cache", count, engine.cachedEntries)
        assertEquals(count, provider.extractCount.get())

        val links = provider.linkCount.get()
        val generation = engine.snapshot.value.generation
        engine.refreshBlocking()

        assertEquals("a no-op refresh reads no file", count, provider.extractCount.get())
        assertEquals("a no-op refresh links nothing", links, provider.linkCount.get())
        assertEquals("a no-op refresh publishes nothing", generation, engine.snapshot.value.generation)
    }

    fun testNoOpRefreshOver5000CachedFilesIsWithinTheBudget() {
        createFiles(5_000)
        val provider = FakeRouteProvider()
        val engine = engine(provider)
        engine.refreshBlocking()
        assertEquals(5_000, engine.cachedEntries)

        val result = PerfBudget.assertWithin("noop-refresh-5000-cached-files", Limits.BUDGET_NOOP_REFRESH_5000_FILES_MS, warmups = 2, runs = 7) {
            engine.refreshBlocking()
        }
        println("no-op refresh over 5000 cached files: median ${result.medianMs} ms")
    }

    fun testCacheIsBoundedByEstimatedWeightNotByCount() {
        addSyntheticFiles(40, routesPerFile = 1)
        val perEntry = Limits.FACT_ENTRY_BYTES + com.routegenie.testing.FakeSource.WEIGHT
        val engine = engine(FakeRouteProvider(), cacheWeightLimit = 12L * perEntry)
        engine.refreshBlocking()
        assertEquals(12, engine.cachedEntries)
        assertTrue(engine.cachedWeight <= 12L * perEntry)
        assertEquals("the snapshot is complete although the cache is smaller", 40, engine.snapshot.value.endpoints.size)
    }

    fun testAnEditThatLeavesTheFactsEqualNeitherRelinksNorPublishes() {
        addSyntheticFiles(6, routesPerFile = 2)
        val provider = FakeRouteProvider()
        val engine = engine(provider)
        engine.refreshBlocking()
        val links = provider.linkCount.get()
        val generation = engine.snapshot.value.generation
        val extracted = provider.extractCount.get()

        // The comment changes the text, not what the file contributes.
        val original = VfsUtil.loadText(myFixture.findFileInTempDir("pkg0/module0.py"))
        editFile("pkg0/module0.py", original + "# a note\n")
        engine.refreshBlocking()

        assertEquals("the edited file was read again", extracted + 1, provider.extractCount.get())
        assertEquals("equal facts: no relink", links, provider.linkCount.get())
        assertEquals("equal facts: no publish", generation, engine.snapshot.value.generation)
    }

    fun testExtractionNeverRunsMoreThreadsThanTheCapAndNeverOnTheUiThread() {
        createFiles(120)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val provider = FakeRouteProvider().apply {
            onExtract = {
                val now = running.incrementAndGet()
                peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                Thread.sleep(2)
                running.decrementAndGet()
            }
        }
        val engine = engine(provider, parallelism = 8)
        engine.refreshBlocking()

        assertEquals(120, engine.snapshot.value.endpoints.size)
        assertFalse("extraction must not run on the UI thread", provider.sawEdt)
        assertTrue("at most ${Limits.EXTRACT_PARALLELISM_CAP} files at once, saw ${peak.get()}", peak.get() <= Limits.EXTRACT_PARALLELISM_CAP)
    }

    // The old design read 50 files in one read action and restarted all of them when a write action arrived.
    fun testAWriteActionDuringTheScanRestartsOnlyTheFilesInFlight() {
        val files = 300
        createFiles(files)
        val started = AtomicInteger()
        val release = java.util.concurrent.atomic.AtomicBoolean(false)
        val provider = FakeRouteProvider().apply {
            onExtract = {
                started.incrementAndGet()
                while (!release.get()) {
                    com.intellij.openapi.progress.ProgressManager.checkCanceled()
                    Thread.sleep(2)
                }
            }
        }
        val engine = engine(provider)
        val scan = scope.launch { engine.refreshNow() }
        waitUntil("the scan is reading files") { started.get() > 0 }

        editFile("bulk/f5.py", "# route /edited\n")
        release.set(true)
        waitUntil("the scan finished") { scan.isCompleted }

        val extractions = provider.extractCount.get()
        assertTrue(
            "the typing restarted more than the files in flight: $extractions extractions for $files files",
            extractions <= files + RESTARTS_PER_EDIT * Limits.EXTRACT_PARALLELISM_CAP,
        )
        assertTrue("the edit is part of the result", engine.snapshot.value.endpoints.any { it.path == "/edited" })
    }

    fun testParallelismIsHalfTheCoresClampedToTheCap() {
        assertEquals(1, Limits.extractParallelism(1))
        assertEquals(1, Limits.extractParallelism(2))
        assertEquals(Limits.EXTRACT_PARALLELISM_CAP, Limits.extractParallelism(64))
        assertTrue(Limits.EXTRACT_PARALLELISM_CAP <= 2)
    }

    private companion object {
        /** An edit is a write action and a commit: each can cancel the file every worker is reading. */
        const val RESTARTS_PER_EDIT = 4

        /** The size of the count-bounded cache this one replaced. */
        const val OLD_CAP = 5_000
    }
}
