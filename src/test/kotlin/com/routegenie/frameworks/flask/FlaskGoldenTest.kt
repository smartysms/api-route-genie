package com.routegenie.frameworks.flask

import com.routegenie.frameworks.common.RouteProviderTestCase

/** Whole-project golden test: an app factory with blueprints, a nested blueprint and a MethodView, compared exactly. */
class FlaskGoldenTest : RouteProviderTestCase(FlaskRouteProvider()) {
    fun testBasicProjectMatchesTheGoldenList() {
        val result = discoverFixture("flask/basic")
        assertEquals(expectedLines("flask/basic/expected.txt"), golden(result))
        assertTrue("no diagnostics expected: ${result.diagnostics}", result.diagnostics.isEmpty())
        assertTrue(result.endpoints.none { it.isPartial })
    }
}
