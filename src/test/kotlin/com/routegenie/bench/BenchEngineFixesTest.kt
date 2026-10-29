package com.routegenie.bench

import com.routegenie.discovery.core.EngineTestCase
import com.routegenie.frameworks.fastapi.FastApiRouteProvider

/** Engine level regression tests for the benchmark findings: what the published snapshot (the UI's input) looks like. */
class BenchEngineFixesTest : EngineTestCase() {
    private val twoLocalApps = "from fastapi import FastAPI\n\n\ndef test_one():\n    app = FastAPI()\n\n" +
        "    @app.get('/x')\n    def read():\n        return 1\n\n\ndef test_two():\n    app = FastAPI()\n\n" +
        "    @app.get('/x')\n    def read():\n        return 2\n"

    fun testIdenticalRoutesOfDifferentScopesGetUniqueStableKeys() {
        myFixture.addFileToProject("ek/test_local.py", twoLocalApps)
        val engine = engine(FastApiRouteProvider())
        engine.refreshBlocking()
        val first = engine.snapshot.value.endpoints
        assertEquals(2, first.size)
        assertEquals("keys must be unique", 2, first.map { it.key }.toSet().size)

        // Lines inserted above both declarations move every offset; the keys must not change.
        editFile("ek/test_local.py", "# a comment\n# another\n$twoLocalApps")
        engine.refreshBlocking()
        val second = engine.snapshot.value.endpoints
        assertEquals(first.map { it.key }.toSet(), second.map { it.key }.toSet())
    }
}
