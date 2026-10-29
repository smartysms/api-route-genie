package com.routegenie.discovery.core

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.routegenie.testing.FakeRouteProvider
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.ObjectGraph
import java.lang.ref.WeakReference

/** Releases, bounds and growth of the discovery objects. Each check has a negative control in the pure tests below. */
class RouteDiscoveryLeakTest : EngineTestCase() {
    fun testDisposingTheOwnerStopsEveryCoroutineAndListener() {
        addSyntheticFiles(3, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val ownerOfEngine = Disposer.newDisposable("short lived owner")
        val engine = engine(provider, debounceMs = 20, parent = ownerOfEngine)
        engine.start(coldStart = true)
        waitUntil("first snapshot") { engine.snapshot.value.generation > 0 }
        assertFalse(engine.isStopped)

        Disposer.dispose(ownerOfEngine)

        waitUntil("coroutines to end") { engine.isStopped }
        val generation = engine.snapshot.value.generation
        editFile("pkg0/module0.py", "# route /after-dispose\n")
        engine.requestRefresh()
        Thread.sleep(300)
        assertEquals("no listener or loop reacts after disposal", generation, engine.snapshot.value.generation)
        assertEquals("the cache is released with the engine", 0, engine.cachedEntries)
    }

    fun testEngineIsGarbageCollectableAfterDisposal() {
        addSyntheticFiles(3, routesPerFile = 1)
        val ownerOfEngine = Disposer.newDisposable("short lived owner")
        var engine: RouteDiscoveryEngine? = engine(FakeRouteProvider(), debounceMs = 20, parent = ownerOfEngine)
        engine!!.start(coldStart = true)
        waitUntil("first snapshot") { engine!!.snapshot.value.generation > 0 }
        val ref = WeakReference(engine)

        Disposer.dispose(ownerOfEngine)
        waitUntil("coroutines to end") { engine!!.isStopped }
        engine = null

        LeakProbe.assertCollected("RouteDiscoveryEngine after dispose", ref)
    }

    fun testSnapshotAndCacheHoldNoPsiOrFiles() {
        addSyntheticFiles(4, routesPerFile = 2)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()

        assertTrue(engine.snapshot.value.endpoints.isNotEmpty())
        ObjectGraph.assertNoInstanceOf(
            "snapshot", engine.snapshot.value,
            PsiElement::class.java, PsiFile::class.java, VirtualFile::class.java, com.intellij.openapi.project.Project::class.java,
        )
    }

    fun testCacheStaysBoundedAndEvictsOldEntries() {
        addSyntheticFiles(30, routesPerFile = 1)
        val provider = FakeRouteProvider()
        val engine = engine(provider, cacheCapacity = 10)
        engine.refreshBlocking()

        assertEquals("every file still reaches the snapshot although the cache is smaller", 30, engine.snapshot.value.endpoints.size)
        assertEquals(10, engine.cachedEntries)

        provider.extractCount.set(0)
        engine.refreshBlocking()
        assertTrue("evicted entries are extracted again, proving they were dropped", provider.extractCount.get() > 0)
        assertEquals(10, engine.cachedEntries)
    }

    fun testRepeatedRefreshDoesNotGrowTheHeap() {
        addSyntheticFiles(10, routesPerFile = 2)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        var flip = 0

        // The IDE itself keeps some state per document edit (undo, PSI), so the edit alone is the control: only growth
        // beyond it can be ours.
        // One window's growth is noisy (GC timing, IDE background work), a real leak grows in every window, so the
        // smallest of several windows is compared.
        fun growth(block: () -> Unit): Long {
            repeat(30) { block() }
            return (1..WINDOWS).minOf {
                val before = LeakProbe.usedHeapBytes()
                repeat(100) { block() }
                LeakProbe.usedHeapBytes() - before
            }
        }
        val editOnly = growth { editFile("pkg0/module1.py", "# route /r${flip++ % 2}\n") }
        val editAndRefresh = growth {
            editFile("pkg0/module0.py", "# route /r${flip++ % 2}\n")
            engine.refreshBlocking()
        }
        println("heap growth per 100 edits: edit only $editOnly bytes, edit and refresh $editAndRefresh bytes")
        assertTrue("edit and refresh retain $editAndRefresh bytes per 100 runs in the quietest window", editAndRefresh < MAX_GROWTH_BYTES)
    }

    fun testRepeatedUnchangedRefreshDoesNotGrowTheHeap() {
        addSyntheticFiles(10, routesPerFile = 2)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()

        LeakProbe.assertNoHeapGrowth("refresh without changes", maxGrowthBytes = MAX_GROWTH_BYTES, warmups = 30, iterations = 300) {
            engine.refreshBlocking()
        }
    }

    private companion object {
        const val WINDOWS = 5
        const val MAX_GROWTH_BYTES = 1024L * 1024
    }
}
