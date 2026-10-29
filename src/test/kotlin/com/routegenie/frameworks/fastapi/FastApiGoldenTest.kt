package com.routegenie.frameworks.fastapi

import com.routegenie.frameworks.common.RouteProviderTestCase

/** Whole-project golden test: a small app with nested routers across files, compared with expected.txt exactly. */
class FastApiGoldenTest : RouteProviderTestCase(FastApiRouteProvider()) {
    fun testBasicProjectMatchesTheGoldenList() {
        val result = discoverFixture("fastapi/basic")
        assertEquals(expectedLines("fastapi/basic/expected.txt"), golden(result))
        assertTrue("no diagnostics expected: ${result.diagnostics}", result.diagnostics.isEmpty())
        assertTrue(result.endpoints.none { it.isPartial })
    }
}
