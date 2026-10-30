package com.routegenie.frameworks.fastapi

import com.routegenie.frameworks.common.RouteProviderTestCase
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.SuggestionKind

/**
 * A mini copy of the official full-stack-fastapi-template layout (`settings.API_V1_STR` taken from a pydantic Settings
 * class with a default): nothing is listed without a pin, the exact list with one.
 */
class FastApiPinsGoldenTest : RouteProviderTestCase(FastApiRouteProvider()) {
    private val pin = mapOf("settings.API_V1_STR" to "/api/v1")

    fun testNoEndpointsWithoutAPinButTheSettingIsReportedWithItsDefault() {
        val result = discoverFixture("pins/fastapi_template")
        assertEquals(emptyList<String>(), routes(result))
        val setting = result.settings.single()
        assertEquals("settings.API_V1_STR", setting.expression)
        assertEquals(11, setting.routeCount)
        assertEquals("/api/v1", setting.suggestion?.value)
        assertEquals(SuggestionKind.CLASS_DEFAULT, setting.suggestion?.kind)
        assertEquals("backend/app/core/config.py", setting.suggestion?.origin?.path)
        assertEquals("backend/app/main.py", setting.use.path)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.DYNAMIC_PREFIX })
    }

    fun testThePinGivesTheExactList() {
        val result = discoverFixture("pins/fastapi_template") // unpinned first: the fixture is copied once
        assertEquals(emptyList<String>(), routes(result))
        val pinned = discoverAll(pin)
        assertEquals(expectedLines("pins/fastapi_template.expected.txt"), pinnedGolden(pinned))
        assertTrue(pinned.settings.isEmpty())
        assertTrue(pinned.endpoints.all { it.pins == listOf("settings.API_V1_STR") })
    }

    private fun pinnedGolden(result: com.routegenie.discovery.LinkResult) = result.endpoints.map { "${it.method} ${it.path} -> ${it.handler.qualifiedName}" }.sorted()
}
