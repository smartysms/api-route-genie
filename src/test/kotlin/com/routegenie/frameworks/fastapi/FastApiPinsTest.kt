package com.routegenie.frameworks.fastapi

import com.routegenie.discovery.LinkResult
import com.routegenie.frameworks.common.RouteProviderTestCase
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.EndpointKeys
import com.routegenie.model.Framework
import com.routegenie.model.SuggestionKind
import com.routegenie.model.UnresolvedSetting

/**
 * Settings and pins for FastAPI: what is recognized as a setting, what is suggested (only literal defaults the code states),
 * what a pin changes, and what must stay exactly as before. Each test is a small realistic project.
 */
class FastApiPinsTest : RouteProviderTestCase(FastApiRouteProvider()) {
    private val settingsFile = "app/core/config.py" to """
        from pydantic_settings import BaseSettings

        class Settings(BaseSettings):
            API_V1_STR: str = "/api/v1"
            SECRET_KEY: str
            EMPTY_PREFIX: str = ""

        settings = Settings()
    """

    private val itemsFile = "app/routes/items.py" to """
        from fastapi import APIRouter

        router = APIRouter(prefix="/items")

        @router.get("/")
        def list_items(): ...

        @router.get("/{item_id}")
        def read_item(item_id: int): ...
    """

    private val usersFile = "app/routes/users.py" to """
        from fastapi import APIRouter

        router = APIRouter()

        @router.get("/users")
        def list_users(): ...
    """

    private fun main(prefixes: String) = "app/main.py" to """
        from fastapi import FastAPI
        from app.core.config import settings
        from app.routes import items, users

        app = FastAPI()
    """.trimIndent() + "\n" + prefixes.trimIndent() + "\n"

    private fun run(pins: Map<String, String>, vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> add(path, text) }
        return discoverAll(pins)
    }

    private fun LinkResult.setting(expression: String): UnresolvedSetting =
        settings.singleOrNull { it.expression == expression } ?: error("no $expression in ${settings.map { it.expression }}")

    // --- detection and suggestion ---------------------------------------------------------------------------------------

    fun testIncludeRouterPrefixFromAPydanticSettingsClassIsHiddenAndSuggestedWithItsDefault() {
        val result = run(emptyMap(), settingsFile, itemsFile, main("app.include_router(items.router, prefix=settings.API_V1_STR)"))
        assertTrue(result.endpoints.isEmpty())
        val setting = result.setting("settings.API_V1_STR")
        assertEquals(2, setting.routeCount)
        assertEquals("/api/v1", setting.suggestion?.value)
        assertEquals(SuggestionKind.CLASS_DEFAULT, setting.suggestion?.kind)
        assertEquals("app/core/config.py", setting.suggestion?.origin?.path)
        assertEquals("app/main.py", setting.use.path)
        assertEquals(Framework.FASTAPI, setting.framework)
        assertTrue(setting.needsLeadingSlash)
        assertEquals(listOf(DiagnosticCode.DYNAMIC_PREFIX), result.diagnostics.map { it.code })
    }

    fun testThePinResolvesEveryRouteBelowAndMarksThemAsPinned() {
        val result = run(
            mapOf("settings.API_V1_STR" to "/api/v1"),
            settingsFile, itemsFile, usersFile,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)\napp.include_router(users.router)"),
        )
        assertEquals(listOf("GET /api/v1/items/", "GET /api/v1/items/{item_id}", "GET /users"), routes(result))
        assertEquals(listOf("settings.API_V1_STR"), find(result, "GET", "/api/v1/items/").pins)
        assertTrue(find(result, "GET", "/users").pins.isEmpty())
        assertTrue(result.settings.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    fun testThePinnedValueNotTheWrittenDefaultDecidesThePath() {
        val result = run(
            mapOf("settings.API_V1_STR" to "/v2"), settingsFile, itemsFile,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)"),
        )
        assertEquals(listOf("GET /v2/items/", "GET /v2/items/{item_id}"), routes(result))
    }

    fun testWithoutAPinTheDefaultIsNeverApplied() {
        val result = run(emptyMap(), settingsFile, itemsFile, main("app.include_router(items.router, prefix=settings.API_V1_STR)"))
        assertEquals(emptyList<String>(), routes(result))
    }

    fun testARouterConstructorPrefixFromASettingIsDetected() {
        val result = run(
            emptyMap(), settingsFile,
            "app/routes/items.py" to """
                from fastapi import APIRouter
                from app.core.config import settings
                router = APIRouter(prefix=settings.API_V1_STR)
                @router.get("/a")
                def a(): ...
            """,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.routes import items
                app = FastAPI()
                app.include_router(items.router)
            """,
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(1, result.setting("settings.API_V1_STR").routeCount)
        assertEquals("app/routes/items.py", result.setting("settings.API_V1_STR").use.path)
    }

    fun testARoutePathBuiltFromASettingIsDetectedAndPinnedAtTheDecorator() {
        val files = arrayOf(
            settingsFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.core.config import settings
                app = FastAPI()
                @app.get(f"{settings.API_V1_STR}/ping")
                def ping(): ...
            """,
        )
        val hidden = run(emptyMap(), *files)
        assertTrue(hidden.endpoints.isEmpty())
        assertEquals(1, hidden.setting("settings.API_V1_STR").routeCount)
        val pinned = discoverAll(mapOf("settings.API_V1_STR" to "/api/v1"))
        assertEquals(listOf("GET /api/v1/ping"), routes(pinned))
    }

    fun testASettingsFactoryFunctionIsFollowedToTheClass() {
        val result = run(
            emptyMap(),
            "app/core/config.py" to """
                from functools import lru_cache
                from pydantic_settings import BaseSettings

                class Settings(BaseSettings):
                    API_V1_STR: str = "/from-factory"

                @lru_cache
                def get_settings():
                    return Settings()
            """,
            "app/main.py" to """
                from fastapi import FastAPI, APIRouter
                from app.core.config import get_settings
                settings = get_settings()
                app = FastAPI()
                r = APIRouter()
                @r.get("/x")
                def x(): ...
                app.include_router(r, prefix=settings.API_V1_STR)
            """,
        )
        assertEquals("/from-factory", result.setting("settings.API_V1_STR").suggestion?.value)
    }

    fun testASettingsFactoryThatCannotBeFollowedIsStillAskedAboutButNeverSuggested() {
        val result = run(
            emptyMap(),
            "app/main.py" to """
                from fastapi import FastAPI, APIRouter
                from vendor_lib import get_settings
                settings = get_settings()
                app = FastAPI()
                r = APIRouter()
                @r.get("/x")
                def x(): ...
                app.include_router(r, prefix=settings.API_V1_STR)
            """,
        )
        val setting = result.setting("settings.API_V1_STR")
        assertNull(setting.suggestion)
        assertEquals(1, setting.routeCount)
    }

    fun testFieldWithADefaultIsSuggestedAndDefaultFactoryIsNot() {
        val config = "app/core/config.py" to """
            from pydantic import Field
            from pydantic_settings import BaseSettings

            class Settings(BaseSettings):
                WITH_DEFAULT: str = Field(default="/api")
                WITH_FACTORY: str = Field(default_factory=lambda: "/computed")
                WITHOUT: str

            settings = Settings()
        """
        val result = run(
            emptyMap(), config,
            "app/main.py" to """
                from fastapi import FastAPI, APIRouter
                from app.core.config import settings
                app = FastAPI()
                a = APIRouter()
                b = APIRouter()
                c = APIRouter()
                @a.get("/a")
                def ra(): ...
                @b.get("/b")
                def rb(): ...
                @c.get("/c")
                def rc(): ...
                app.include_router(a, prefix=settings.WITH_DEFAULT)
                app.include_router(b, prefix=settings.WITH_FACTORY)
                app.include_router(c, prefix=settings.WITHOUT)
            """,
        )
        assertEquals("/api", result.setting("settings.WITH_DEFAULT").suggestion?.value)
        assertNull(result.setting("settings.WITH_FACTORY").suggestion)
        assertNull(result.setting("settings.WITHOUT").suggestion)
    }

    fun testAnEnvironmentDrivenClassDefaultIsSuggestedAsAnEnvironmentDefault() {
        val result = run(
            emptyMap(),
            "app/core/config.py" to """
                import os
                from pydantic_settings import BaseSettings

                class Settings(BaseSettings):
                    API: str = os.getenv("API_PREFIX", "/env-api")

                settings = Settings()
            """,
            itemsFile,
            main("app.include_router(items.router, prefix=settings.API)"),
        )
        val setting = result.setting("settings.API")
        assertEquals("/env-api", setting.suggestion?.value)
        assertEquals(SuggestionKind.ENV_DEFAULT, setting.suggestion?.kind)
    }

    fun testOsEnvironGetWithALiteralDefaultIsSuggestedAtTheUseSite() {
        val result = run(
            emptyMap(), itemsFile,
            "app/main.py" to """
                import os
                from fastapi import FastAPI
                from app.routes import items
                app = FastAPI()
                app.include_router(items.router, prefix=os.environ.get("URL_PREFIX", "/svc"))
            """,
        )
        val setting = result.setting("os.environ[\"URL_PREFIX\"]")
        assertEquals("/svc", setting.suggestion?.value)
        assertEquals(SuggestionKind.ENV_DEFAULT, setting.suggestion?.kind)
        assertEquals(setting.use, setting.suggestion?.origin)
        assertEquals(2, setting.routeCount)
    }

    fun testOsGetenvAndOsEnvironGetAndSubscriptShareOneExpression() {
        val result = run(
            emptyMap(), itemsFile, usersFile,
            "app/main.py" to """
                import os
                from fastapi import FastAPI
                from app.routes import items, users
                app = FastAPI()
                app.include_router(items.router, prefix=os.getenv("URL_PREFIX"))
                app.include_router(users.router, prefix=os.environ["URL_PREFIX"])
            """,
        )
        val setting = result.setting("os.environ[\"URL_PREFIX\"]")
        assertEquals(3, setting.routeCount)
        assertNull(setting.suggestion)
        val pinned = discoverAll(mapOf("os.environ[\"URL_PREFIX\"]" to "/p"))
        assertEquals(listOf("GET /p/items/", "GET /p/items/{item_id}", "GET /p/users"), routes(pinned))
    }

    fun testAnEnvironmentReadWithAComputedDefaultHasNoSuggestion() {
        val result = run(
            emptyMap(), itemsFile,
            "app/main.py" to """
                import os
                from fastapi import FastAPI
                from app.routes import items
                BASE = "/x"
                app = FastAPI()
                app.include_router(items.router, prefix=os.environ.get("URL_PREFIX", BASE + "/y"))
            """,
        )
        assertNull(result.setting("os.environ[\"URL_PREFIX\"]").suggestion)
    }

    fun testADataclassConfigObjectIsAlsoASettingWithASuggestion() {
        val result = run(
            emptyMap(),
            "app/conf.py" to """
                from dataclasses import dataclass

                @dataclass
                class Config:
                    API: str = "/dc"

                config = Config()
            """,
            itemsFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.conf import config
                from app.routes import items
                app = FastAPI()
                app.include_router(items.router, prefix=config.API)
            """,
        )
        assertEquals("/dc", result.setting("config.API").suggestion?.value)
    }

    fun testAnAttributeInheritedFromABaseSettingsClassIsFound() {
        val result = run(
            emptyMap(),
            "app/core/config.py" to """
                from pydantic_settings import BaseSettings

                class CommonSettings(BaseSettings):
                    API_V1_STR: str = "/common"

                class Settings(CommonSettings):
                    OTHER: str = "x"

                settings = Settings()
            """,
            itemsFile,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)"),
        )
        assertEquals("/common", result.setting("settings.API_V1_STR").suggestion?.value)
    }

    // --- composition and counts -----------------------------------------------------------------------------------------

    fun testAPrefixMadeOfTwoSettingsNeedsBothPinsAndShowsBoth() {
        val config = "app/core/config.py" to """
            from pydantic_settings import BaseSettings
            class Settings(BaseSettings):
                ROOT: str = "/api"
                VERSION: str = "/v1"
            settings = Settings()
        """
        val prefix = main("app.include_router(items.router, prefix=settings.ROOT + settings.VERSION)")
        val none = run(emptyMap(), config, itemsFile, prefix)
        assertEquals(setOf("settings.ROOT", "settings.VERSION"), none.settings.map { it.expression }.toSet())
        assertTrue(none.settings.all { it.routeCount == 2 })

        val one = discoverAll(mapOf("settings.ROOT" to "/api"))
        assertTrue(one.endpoints.isEmpty())
        assertEquals(listOf("settings.VERSION"), one.settings.map { it.expression })

        val both = discoverAll(mapOf("settings.ROOT" to "/api", "settings.VERSION" to "/v1"))
        assertEquals(listOf("GET /api/v1/items/", "GET /api/v1/items/{item_id}"), routes(both))
        assertEquals(listOf("settings.ROOT", "settings.VERSION"), find(both, "GET", "/api/v1/items/").pins)
    }

    fun testTheSameExpressionInManyFilesIsOneEntryWithTheRoutesAddedUp() {
        val result = run(
            emptyMap(), settingsFile, itemsFile,
            "app/routes/users.py" to """
                from fastapi import APIRouter
                from app.core.config import settings
                router = APIRouter(prefix=settings.API_V1_STR)
                @router.get("/u1")
                def u1(): ...
                @router.get("/u2")
                def u2(): ...
                @router.get("/u3")
                def u3(): ...
            """,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)\napp.include_router(users.router)"),
        )
        assertEquals(1, result.settings.size)
        assertEquals(5, result.setting("settings.API_V1_STR").routeCount)
    }

    fun testNestedRoutersAndSettingsAtEveryLevelAreAllCounted() {
        val config = "app/core/config.py" to """
            from pydantic_settings import BaseSettings
            class Settings(BaseSettings):
                OUTER: str = "/outer"
                INNER: str = "/inner"
            settings = Settings()
        """
        val files = arrayOf(
            config,
            "app/api.py" to """
                from fastapi import APIRouter
                from app.core.config import settings
                from app.routes import items
                api = APIRouter()
                api.include_router(items.router, prefix=settings.INNER)
            """,
            itemsFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.core.config import settings
                from app.api import api
                app = FastAPI()
                app.include_router(api, prefix=settings.OUTER)
            """,
        )
        val none = run(emptyMap(), *files)
        assertEquals(2, none.setting("settings.OUTER").routeCount)
        assertEquals(2, none.setting("settings.INNER").routeCount)
        val pinned = discoverAll(mapOf("settings.OUTER" to "/o", "settings.INNER" to "/i"))
        assertEquals(listOf("GET /o/i/items/", "GET /o/i/items/{item_id}"), routes(pinned))
    }

    fun testAMountPathFromASettingIsDetected() {
        val result = run(
            emptyMap(), settingsFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.core.config import settings
                app = FastAPI()
                sub = FastAPI()
                @sub.get("/health")
                def health(): ...
                app.mount(settings.API_V1_STR, sub)
            """,
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(1, result.setting("settings.API_V1_STR").routeCount)
        val pinned = discoverAll(mapOf("settings.API_V1_STR" to "/v1"))
        assertEquals(listOf("GET /v1/health"), routes(pinned))
    }

    // --- edge cases of values ------------------------------------------------------------------------------------------

    fun testAnEmptyStringPinIsARealPrefix() {
        val result = run(
            mapOf("settings.EMPTY_PREFIX" to ""), settingsFile, itemsFile,
            main("app.include_router(items.router, prefix=settings.EMPTY_PREFIX)"),
        )
        assertEquals(listOf("GET /items/", "GET /items/{item_id}"), routes(result))
        assertEquals(listOf("settings.EMPTY_PREFIX"), find(result, "GET", "/items/").pins)
    }

    fun testAPinWithATrailingSlashIsJoinedExactlyAsWritten() {
        // The pin rules refuse such a value for FastAPI before it is stored; a hand edited env is joined literally, never repaired.
        val result = run(
            mapOf("settings.API_V1_STR" to "/api/"), settingsFile, itemsFile,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)"),
        )
        assertEquals(listOf("GET /api//items/", "GET /api//items/{item_id}"), routes(result))
    }

    fun testTwoPinsWithTheSameValueGiveIdenticalRoutesThatKeepTheirOrdinals() {
        val config = "app/core/config.py" to """
            from pydantic_settings import BaseSettings
            class Settings(BaseSettings):
                A: str = "/x"
                B: str = "/x"
            settings = Settings()
        """
        // One function registered on two routers: before the pins it is two different paths, after them the same path twice.
        val twice = "app/routes/twice.py" to """
            from fastapi import APIRouter
            from app.core.config import settings

            r1 = APIRouter(prefix=settings.A)
            r2 = APIRouter(prefix=settings.B)

            @r1.get("/dup")
            @r2.get("/dup")
            def dup(): ...
        """
        val result = run(
            mapOf("settings.A" to "/same", "settings.B" to "/same"), config, twice,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.routes import twice
                app = FastAPI()
                app.include_router(twice.r1)
                app.include_router(twice.r2)
            """,
        )
        assertEquals(listOf("GET /same/dup", "GET /same/dup"), routes(result))
        val numbered = EndpointKeys.disambiguate(result.endpoints)
        assertEquals(2, numbered.map { it.key }.toSet().size)
        assertEquals(setOf(0, 1), numbered.map { it.ordinal }.toSet())
    }

    fun testThePinDoesNotChangeTheKeyOfAnEndpointThatIsOtherwiseTheSame() {
        val pinned = run(
            mapOf("settings.API_V1_STR" to "/api/v1"), settingsFile, itemsFile,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)"),
        ).endpoints.first()
        assertEquals(listOf("settings.API_V1_STR"), pinned.pins)
        // The identity is the resolved path and the declaration, nothing about how the path was obtained.
        assertEquals(pinned.copy(pins = emptyList()).key, pinned.key)
        assertEquals(pinned.copy(pins = emptyList()).declarationKey, pinned.declarationKey)
        assertTrue(pinned.key.contains("/api/v1/items/"))
    }

    // --- what must not change -------------------------------------------------------------------------------------------

    fun testAPinForSomethingTheCodeNoLongerUsesIsHarmless() {
        val result = run(
            mapOf("settings.GONE" to "/gone", "os.environ[\"NOPE\"]" to "/nope"), settingsFile, itemsFile,
            main("app.include_router(items.router, prefix=\"/fixed\")"),
        )
        assertEquals(listOf("GET /fixed/items/", "GET /fixed/items/{item_id}"), routes(result))
        assertTrue(result.endpoints.all { it.pins.isEmpty() })
        assertTrue(result.settings.isEmpty())
    }

    fun testALiteralPrefixIsUntouchedByPinsAndSettingsDetection() {
        val result = run(
            emptyMap(), itemsFile, usersFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.routes import items, users
                app = FastAPI()
                app.include_router(items.router, prefix="/v1")
                app.include_router(users.router)
            """,
        )
        assertEquals(listOf("GET /users", "GET /v1/items/", "GET /v1/items/{item_id}"), routes(result))
        assertTrue(result.settings.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    fun testAConstantInAnotherModuleStillResolvesAndIsNotASetting() {
        val result = run(
            emptyMap(), itemsFile,
            "app/consts.py" to "API = '/v9'\n",
            "app/main.py" to """
                from fastapi import FastAPI
                from app import consts
                from app.routes import items
                app = FastAPI()
                app.include_router(items.router, prefix=consts.API)
            """,
        )
        assertEquals(listOf("GET /v9/items/", "GET /v9/items/{item_id}"), routes(result))
        assertTrue(result.settings.isEmpty())
    }

    fun testASettingsNamedModuleOfTheProjectResolvesAsBefore() {
        val result = run(
            emptyMap(), itemsFile,
            "app/settings.py" to "API_V1_STR = '/modsettings'\n",
            "app/main.py" to """
                from fastapi import FastAPI
                from app import settings
                from app.routes import items
                app = FastAPI()
                app.include_router(items.router, prefix=settings.API_V1_STR)
            """,
        )
        assertEquals(listOf("GET /modsettings/items/", "GET /modsettings/items/{item_id}"), routes(result))
        assertTrue(result.settings.isEmpty())
    }

    fun testAComputedPrefixThatIsNotASettingStaysAnOrdinaryDiagnostic() {
        val result = run(
            emptyMap(), itemsFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.routes import items
                from somewhere import compute
                app = FastAPI()
                app.include_router(items.router, prefix=compute())
            """,
        )
        assertTrue(result.endpoints.isEmpty())
        assertTrue(result.settings.isEmpty())
        assertEquals(listOf(DiagnosticCode.DYNAMIC_PREFIX), result.diagnostics.map { it.code })
    }

    fun testASettingUsedOutsideAnyPathIsNotReported() {
        val result = run(
            emptyMap(), settingsFile, itemsFile,
            "app/main.py" to """
                from fastapi import FastAPI
                from app.core.config import settings
                from app.routes import items
                app = FastAPI(title=settings.API_V1_STR, openapi_url=f"{settings.API_V1_STR}/openapi.json")
                app.include_router(items.router, tags=[settings.API_V1_STR])
            """,
        )
        assertEquals(listOf("GET /items/", "GET /items/{item_id}"), routes(result))
        assertTrue(result.settings.isEmpty())
    }

    fun testARouterThatIsNeverIncludedDoesNotAskForAPin() {
        val result = run(
            emptyMap(), settingsFile,
            "app/orphan.py" to """
                from fastapi import APIRouter
                from app.core.config import settings
                router = APIRouter(prefix=settings.API_V1_STR)
                @router.get("/dead")
                def dead(): ...
            """,
        )
        assertTrue(result.endpoints.isEmpty())
        assertTrue("dead code must not nag: ${result.settings}", result.settings.isEmpty())
    }

    fun testAnIncludedRouterWithoutRoutesDoesNotAskForAPin() {
        val result = run(
            emptyMap(), settingsFile,
            "app/routes/items.py" to "from fastapi import APIRouter\nrouter = APIRouter()\n",
            main("app.include_router(items.router, prefix=settings.API_V1_STR)"),
        )
        assertTrue(result.settings.none { it.routeCount > 0 })
    }

    fun testAPinOnlyAppliesToTheExactExpressionText() {
        val result = run(
            mapOf("config.API_V1_STR" to "/other"), settingsFile, itemsFile,
            main("app.include_router(items.router, prefix=settings.API_V1_STR)"),
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals("settings.API_V1_STR", result.settings.single().expression)
    }

    fun testSeveralSettingsAreReportedMostRoutesFirst() {
        val config = "app/core/config.py" to """
            from pydantic_settings import BaseSettings
            class Settings(BaseSettings):
                BIG: str = "/big"
                SMALL: str = "/small"
            settings = Settings()
        """
        val result = run(
            emptyMap(), config, itemsFile, usersFile,
            main("app.include_router(users.router, prefix=settings.SMALL)\napp.include_router(items.router, prefix=settings.BIG)"),
        )
        assertEquals(listOf("settings.BIG", "settings.SMALL"), result.settings.map { it.expression })
        assertEquals(listOf(2, 1), result.settings.map { it.routeCount })
    }

    fun testPinsPassedInAsPlainDataDoNotMakeTheLinkerDependOnTheIde() {
        // The same facts with and without pins link independently: nothing is cached or leaked between calls.
        add(settingsFile.first, settingsFile.second)
        add(itemsFile.first, itemsFile.second)
        add("app/main.py", main("app.include_router(items.router, prefix=settings.API_V1_STR)").second)
        val hidden = discoverAll()
        val pinned = discoverAll(mapOf("settings.API_V1_STR" to "/p"))
        val hiddenAgain = discoverAll()
        assertTrue(hidden.endpoints.isEmpty())
        assertEquals(2, pinned.endpoints.size)
        assertTrue(hiddenAgain.endpoints.isEmpty())
        assertEquals(hidden.settings, hiddenAgain.settings)
    }
}
