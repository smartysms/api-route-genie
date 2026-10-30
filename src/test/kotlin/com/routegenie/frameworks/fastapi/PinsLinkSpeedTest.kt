package com.routegenie.frameworks.fastapi

import com.routegenie.Limits
import com.routegenie.discovery.core.PinEnv
import com.routegenie.frameworks.common.RouteProviderTestCase
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.ProviderPipeline

/**
 * Applying a pin is a relink and nothing else, and noticing settings must not slow the normal path: a project of a few hundred
 * route files whose every include takes its prefix from one setting.
 */
class PinsLinkSpeedTest : RouteProviderTestCase(FastApiRouteProvider()) {
    private val routers = 300

    private fun build(prefix: String) {
        add(
            "app/core/config.py",
            """
            from pydantic_settings import BaseSettings
            class Settings(BaseSettings):
                API_V1_STR: str = "/api/v1"
            settings = Settings()
            """,
        )
        for (i in 0 until routers) {
            add(
                "app/routes/r$i.py",
                """
                from fastapi import APIRouter
                router = APIRouter(prefix="/r$i")
                @router.get("/")
                def list_$i(): ...
                @router.get("/{id}")
                def read_$i(id: int): ...
                @router.post("/")
                def create_$i(): ...
                """,
            )
        }
        val imports = (0 until routers).joinToString("\n") { "from app.routes import r$it" }
        val includes = (0 until routers).joinToString("\n") { "app.include_router(r$it.router, prefix=$prefix)" }
        add("app/main.py", "from fastapi import FastAPI\nfrom app.core.config import settings\n$imports\napp = FastAPI()\n$includes\n")
    }

    fun testApplyingAPinIsARelinkWithinTheBudgetAndDetectionAddsLittle() {
        build("settings.API_V1_STR")
        val facts = ProviderPipeline.collect(project, FastApiRouteProvider())
        val provider = FastApiRouteProvider()
        val pinned = facts + PinEnv(mapOf("settings.API_V1_STR" to "/api/v1"))

        assertEquals(routers * 3, provider.link(pinned).endpoints.size)
        val unresolved = provider.link(facts)
        assertEquals(0, unresolved.endpoints.size)
        assertEquals(routers * 3, unresolved.settings.single().routeCount)

        PerfBudget.assertWithin("pins-apply-300-routers", Limits.BUDGET_PINS_APPLY_MS, warmups = 3, runs = 9) { provider.link(pinned) }
        PerfBudget.assertWithin("pins-unresolved-300-routers", Limits.BUDGET_PINS_APPLY_MS, warmups = 3, runs = 9) { provider.link(facts) }
    }

    fun testNoticingSettingsCostsLittleOnAProjectThatHasNone() {
        build("\"/literal\"")
        val facts = ProviderPipeline.collect(project, FastApiRouteProvider())
        val provider = FastApiRouteProvider()
        assertEquals(routers * 3, provider.link(facts).endpoints.size)
        assertTrue(provider.link(facts).settings.isEmpty())
        PerfBudget.assertWithin("pins-detect-none-300-routers", Limits.BUDGET_PINS_APPLY_MS, warmups = 3, runs = 9) { provider.link(facts) }
    }
}
