package com.routegenie.discovery.core

import com.intellij.openapi.util.Disposer
import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.testing.FakeFacts
import com.routegenie.testing.FakeRouteProvider
import com.routegenie.testing.PerfBudget

/** Speed gates for the discovery pipeline, with a trivial provider so only the pipeline itself is measured. */
class RouteDiscoveryPerfTest : EngineTestCase() {
    fun testColdDiscoveryOf600FilesIsWithinBudget() {
        addSyntheticFiles(600, routesPerFile = 3)
        val provider = FakeRouteProvider()

        PerfBudget.assertWithin("cold-discovery-600-files", Limits.BUDGET_COLD_DISCOVERY_MS, warmups = 1, runs = 5) {
            // A fresh engine per run, so every run starts with an empty cache.
            val run = Disposer.newDisposable("cold run")
            try {
                val engine = engine(provider, parent = run)
                engine.refreshBlocking()
                assertEquals(1800, engine.snapshot.value.endpoints.size)
            } finally {
                Disposer.dispose(run)
            }
        }
        assertFalse(provider.sawEdt)
    }

    fun testEditPlusRefreshOfOneFileIsWithinBudget() {
        addSyntheticFiles(600, routesPerFile = 3)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        var flip = 0

        // Strictest reading of the budget: the document edit itself (a write action) is inside the timed block too.
        PerfBudget.assertWithin("edit-and-refresh-one-file-of-600", Limits.BUDGET_REFRESH_ONE_FILE_MS, warmups = 5, runs = 15) {
            editFile("pkg3/module70.py", "# route /v${flip++ % 2}\n")
            engine.refreshBlocking()
        }
    }

    fun testRefreshOnlyAfterOneFileEditIsWithinBudget() {
        addSyntheticFiles(600, routesPerFile = 3)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        var flip = 0
        val samples = ArrayList<Double>()
        repeat(20) {
            editFile("pkg3/module70.py", "# route /w${flip++ % 2}\n")
            val start = System.nanoTime()
            engine.refreshBlocking()
            samples += (System.nanoTime() - start) / 1_000_000.0
        }
        val median = samples.drop(5).sorted().let { it[it.size / 2] }
        println("refresh after a real edit, 600 files: median ${"%.2f".format(median)} ms")
        assertTrue("median $median ms exceeds ${Limits.BUDGET_REFRESH_ONE_FILE_MS} ms", median <= Limits.BUDGET_REFRESH_ONE_FILE_MS)
    }

    fun testPureLinkingOf5000EndpointsIsFast() {
        val provider = FakeRouteProvider()
        val facts: List<FileFacts> = (0 until 5000).map { FakeFacts("f$it.py", listOf("/r$it")) }

        val result = PerfBudget.assertWithin("link-5000-endpoints", Limits.BUDGET_REFRESH_ONE_FILE_MS, warmups = 5, runs = 15) {
            assertEquals(5000, provider.link(facts).endpoints.size)
        }
        println("pure link of 5000 endpoints (fake provider): median ${result.medianMs} ms")
    }

    fun testWarmRefreshWith5000EndpointsIsWithinBudget() {
        addSyntheticFiles(100, routesPerFile = 50)
        val engine = engine(FakeRouteProvider())
        engine.refreshBlocking()
        assertEquals(5000, engine.snapshot.value.endpoints.size)

        PerfBudget.assertWithin("warm-refresh-5000-endpoints", Limits.BUDGET_REFRESH_ONE_FILE_MS, warmups = 5, runs = 15) {
            engine.refreshBlocking()
        }
    }
}
