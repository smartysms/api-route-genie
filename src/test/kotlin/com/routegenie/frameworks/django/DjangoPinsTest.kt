package com.routegenie.frameworks.django

import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.core.PinEnv
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Framework
import com.routegenie.model.UnresolvedSetting
import com.routegenie.testing.ProviderPipeline

/** Settings and pins for Django: `settings.X` prefixes (NetBox style), nested includes, DRF routers, and ROOT_URLCONF left alone. */
class DjangoPinsTest : DjangoTestBase() {
    private val imports = "from django.urls import include, path, re_path\nfrom django.conf import settings\n"

    private fun linkPinned(pins: Map<String, String>, vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        return provider.link(ProviderPipeline.collect(project, provider) + PinEnv(pins))
    }

    private fun LinkResult.setting(expression: String): UnresolvedSetting =
        settings.singleOrNull { it.expression == expression } ?: error("no $expression in ${settings.map { it.expression }}")

    private fun paths(result: LinkResult) = result.endpoints.map { it.path }.sorted()

    private val apiUrls = "api/urls.py" to """
        from django.urls import path
        from . import views

        urlpatterns = [
            path("items/", views.items),
            path("items/<int:pk>/", views.item),
            path("health/", views.health),
        ]
    """.trimIndent() + "\n"

    private val rootWithSetting = "proj/urls.py" to imports + """
        urlpatterns = [path(settings.API_PREFIX, include("api.urls"))]
    """.trimIndent() + "\n"

    fun testASettingsPrefixOnAnIncludeHidesEverythingBelowAndCountsIt() {
        val result = link(apiUrls, rootWithSetting)
        assertTrue(result.endpoints.isEmpty())
        val setting = result.setting("settings.API_PREFIX")
        assertEquals(3, setting.routeCount)
        assertEquals(Framework.DJANGO, setting.framework)
        assertFalse("Django route text has no leading slash", setting.needsLeadingSlash)
        assertEquals("proj/urls.py", setting.use.path)
        assertNull("the plugin takes no value from a settings module", setting.suggestion)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.DYNAMIC_PREFIX })
    }

    fun testThePinGivesTheExactListAndMarksIt() {
        val result = linkPinned(mapOf("settings.API_PREFIX" to "api/v2/"), apiUrls, rootWithSetting)
        assertEquals(listOf("/api/v2/health/", "/api/v2/items/", "/api/v2/items/{pk}/"), paths(result))
        assertTrue(result.endpoints.all { it.pins == listOf("settings.API_PREFIX") })
        assertTrue(result.settings.isEmpty())
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.DYNAMIC_PREFIX })
    }

    fun testAnEmptyPinIsTheRootLikeNetBoxBasePath() {
        val result = linkPinned(
            mapOf("settings.BASE_PATH" to ""),
            apiUrls.first to apiUrls.second,
            "proj/urls.py" to imports + "urlpatterns = [path(settings.BASE_PATH, include('api.urls'))]\n",
        )
        assertEquals(listOf("/health/", "/items/", "/items/{pk}/"), paths(result))
    }

    fun testASettingJoinedWithLiteralTextInOneRoute() {
        val urls = "proj/urls.py" to imports + "urlpatterns = [path(settings.PREFIX + 'ping/', v)]\n"
        val hidden = link(urls)
        assertTrue(hidden.endpoints.isEmpty())
        assertEquals(1, hidden.setting("settings.PREFIX").routeCount)
        val pinned = linkPinned(mapOf("settings.PREFIX" to "svc/"))
        assertEquals(listOf("/svc/ping/"), paths(pinned))
    }

    fun testTwoSettingsInNestedIncludesAreBothReported() {
        val result = link(
            "proj/urls.py" to imports + "urlpatterns = [path(settings.OUTER, include('mid.urls'))]\n",
            "mid/urls.py" to imports + "urlpatterns = [path(settings.INNER, include('api.urls'))]\n",
            apiUrls,
        )
        assertEquals(setOf("settings.OUTER"), result.settings.map { it.expression }.toSet())
        // The inner setting becomes visible once the outer one is pinned: the walk below an unpinned include is only counted.
        val outerOnly = provider.link(ProviderPipeline.collect(project, provider) + PinEnv(mapOf("settings.OUTER" to "o/")))
        assertEquals(setOf("settings.INNER"), outerOnly.settings.map { it.expression }.toSet())
        assertEquals(3, outerOnly.setting("settings.INNER").routeCount)
        val both = provider.link(ProviderPipeline.collect(project, provider) + PinEnv(mapOf("settings.OUTER" to "o/", "settings.INNER" to "i/")))
        assertEquals(listOf("/o/i/health/", "/o/i/items/", "/o/i/items/{pk}/"), paths(both))
        assertEquals(listOf("settings.OUTER", "settings.INNER"), both.endpoints.first().pins)
    }

    fun testASettingAtTheTopOfAnIncludeChainCountsEveryRouteBelowEvenThroughLiteralIncludes() {
        val result = link(
            "proj/urls.py" to imports + "urlpatterns = [path(settings.ROOT, include('mid.urls'))]\n",
            "mid/urls.py" to imports + "urlpatterns = [path('v1/', include('api.urls')), path('v2/', include('api.urls'))]\n",
            apiUrls,
        )
        assertEquals(6, result.setting("settings.ROOT").routeCount)
    }

    fun testRootUrlconfSettingIsNotAPinAndKeepsItsOwnLogic() {
        val result = linkPinned(
            mapOf("settings.ROOT_URLCONF" to "proj.urls"),
            "proj/settings.py" to "ROOT_URLCONF = 'proj.urls'\n",
            "proj/urls.py" to imports + "urlpatterns = [path('main/', v)]\n",
            "proj/other_urls.py" to imports + "urlpatterns = [path('unrouted/', v)]\n",
        )
        assertEquals(listOf("/main/"), paths(result))
        assertTrue(result.endpoints.all { it.pins.isEmpty() })
        assertTrue(result.settings.isEmpty())
    }

    fun testASettingInTheRootUrlconfValueIsLeftAlone() {
        val result = link(
            "proj/settings.py" to "from django.conf import settings\nROOT_URLCONF = settings.URLCONF\n",
            "proj/urls.py" to imports + "urlpatterns = [path('main/', v)]\n",
        )
        assertTrue("ROOT_URLCONF is not a path piece: ${result.settings}", result.settings.isEmpty())
    }

    fun testAnIncludeByModuleNameFromASettingIsNotAPin() {
        val result = linkPinned(
            mapOf("settings.PLUGIN_URLS" to "api.urls"),
            "proj/settings.py" to "ROOT_URLCONF = 'proj.urls'\n",
            apiUrls,
            "proj/urls.py" to imports + "urlpatterns = [path('p/', include(settings.PLUGIN_URLS))]\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertTrue(result.settings.isEmpty())
    }

    fun testReRoutePatternsFromSettingsAreDetectedToo() {
        val result = link("proj/urls.py" to imports + "urlpatterns = [re_path(settings.PATTERN, v)]\n")
        assertEquals(1, result.setting("settings.PATTERN").routeCount)
    }

    fun testADrfRouterRegistrationPrefixFromASettingIsHiddenAndPinnable() {
        val files = arrayOf(
            "api/views.py" to """
                from rest_framework import viewsets
                class UserViewSet(viewsets.ModelViewSet):
                    queryset = None
            """.trimIndent() + "\n",
            "proj/urls.py" to imports + """
                from rest_framework.routers import DefaultRouter
                from api.views import UserViewSet
                router = DefaultRouter()
                router.register(settings.USERS_PREFIX, UserViewSet, basename="user")
                urlpatterns = [path("api/", include(router.urls))]
            """.trimIndent() + "\n",
        )
        val hidden = link(*files)
        assertTrue("only the router's own root view may be listed: ${paths(hidden)}", hidden.endpoints.none { it.path.startsWith("/api/users") })
        assertEquals(1, hidden.setting("settings.USERS_PREFIX").routeCount)
        val pinned = provider.link(ProviderPipeline.collect(project, provider) + PinEnv(mapOf("settings.USERS_PREFIX" to "users")))
        assertTrue(pinned.endpoints.isNotEmpty())
        assertTrue(pinned.endpoints.filter { it.path.startsWith("/api/users") }.all { it.pins == listOf("settings.USERS_PREFIX") })
        assertTrue(pinned.endpoints.any { it.path == "/api/users/" })
    }

    fun testALiteralProjectIsUnchangedAndHasNoSettings() {
        val result = link(
            apiUrls,
            "proj/urls.py" to imports + "urlpatterns = [path('api/', include('api.urls'))]\n",
        )
        assertEquals(listOf("/api/health/", "/api/items/", "/api/items/{pk}/"), paths(result))
        assertTrue(result.settings.isEmpty())
        assertTrue(result.endpoints.all { it.pins.isEmpty() })
    }

    fun testAModuleOfTheProjectNamedSettingsResolvesAsBefore() {
        val result = link(
            "proj/settings.py" to "API_PREFIX = 'api/'\n",
            apiUrls,
            "proj/urls.py" to "from django.urls import include, path\nfrom proj import settings\nurlpatterns = [path(settings.API_PREFIX, include('api.urls'))]\n",
        )
        assertEquals(listOf("/api/health/", "/api/items/", "/api/items/{pk}/"), paths(result))
        assertTrue(result.settings.isEmpty())
    }

    fun testASettingUsedOnlyInTheNameOfARouteIsNotReportedAsHiddenRoutes() {
        val result = link("proj/urls.py" to imports + "urlpatterns = [path('ok/', v, name=settings.NAME)]\n")
        assertTrue(result.settings.isEmpty())
    }

    fun testPinnedProjectDoesNotLeakPinsIntoALaterUnpinnedLink() {
        val files = arrayOf(apiUrls, rootWithSetting)
        files.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        val input = ProviderPipeline.collect(project, provider)
        val pinned = provider.link(input + PinEnv(mapOf("settings.API_PREFIX" to "a/")))
        val unpinned = provider.link(input)
        assertEquals(3, pinned.endpoints.size)
        assertTrue(unpinned.endpoints.isEmpty())
        assertEquals(3, unpinned.setting("settings.API_PREFIX").routeCount)
    }
}
