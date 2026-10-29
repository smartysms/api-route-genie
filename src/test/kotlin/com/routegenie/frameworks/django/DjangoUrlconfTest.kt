package com.routegenie.frameworks.django

import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam

/** path(), re_path(), include() and view method semantics, one small project per case. */
class DjangoUrlconfTest : DjangoTestBase() {
    private val imports = "from django.urls import include, path, re_path\nfrom django.http import HttpResponse\n"

    fun testFunctionViewAcceptsAnyMethodAndKeepsItsName() {
        val result = link("urls.py" to imports + "def hello(request):\n    return HttpResponse('x')\n\nurlpatterns = [path('hello/', hello, name='hello')]\n")
        val e = result.endpoints.single()
        assertEquals(HttpMethod.ANY, e.method)
        assertEquals("/hello/", e.path)
        assertEquals("hello", e.routeName)
        assertEquals(Framework.DJANGO, e.framework)
        assertEquals("urls.hello", e.handler.qualifiedName)
        assertFalse(e.isPartial)
    }

    fun testConvertersBecomeTypedParameters() {
        val result = link("urls.py" to imports + "urlpatterns = [path('a/<int:pk>/<slug:s>/<uuid:u>/<path:p>', v)]\n")
        val e = result.endpoints.single()
        assertEquals("/a/{pk}/{s}/{u}/{p}", e.path)
        assertEquals(listOf(PathParam("pk", "int"), PathParam("s", "slug"), PathParam("u", "uuid"), PathParam("p", "path")), e.pathParams)
    }

    fun testConverterlessParameterIsAString() {
        val e = link("urls.py" to imports + "urlpatterns = [path('u/<name>/', v)]\n").endpoints.single()
        assertEquals("/u/{name}/", e.path)
        assertEquals(listOf(PathParam("name", "str")), e.pathParams)
    }

    fun testCustomConverterNameIsKeptVerbatim() {
        val e = link("urls.py" to imports + "urlpatterns = [path('y/<yyyy:year>/', v)]\n").endpoints.single()
        assertEquals(PathParam("year", "yyyy"), e.pathParams.single())
    }

    fun testIncludePrefixesAreConcatenatedParentFirst() {
        val result = link(
            "proj/urls.py" to imports + "urlpatterns = [path('a/', include('app.urls'))]\n",
            "app/urls.py" to imports + "urlpatterns = [path('b/', include('app.inner')), path('own/', v)]\n",
            "app/inner.py" to imports + "urlpatterns = [path('c/<int:n>/', v)]\n",
        )
        assertEquals(listOf("ANY /a/b/c/{n}/", "ANY /a/own/"), brief(result))
    }

    fun testRootUrlconfSettingSelectsTheRootAndOtherModulesAreNotListed() {
        val result = link(
            "proj/settings.py" to "ROOT_URLCONF = 'proj.urls'\n",
            "proj/urls.py" to imports + "urlpatterns = [path('main/', v)]\n",
            "proj/other_urls.py" to imports + "urlpatterns = [path('unrouted/', v)]\n",
        )
        assertEquals(listOf("ANY /main/"), brief(result))
        assertTrue(result.diagnostics.none { it.code == DiagnosticCode.GUESSED_ROOT })
    }

    fun testWithoutSettingsUnreferencedUrlModulesAreGuessedRootsAndReported() {
        val result = link(
            "proj/urls.py" to imports + "urlpatterns = [path('a/', include('app.urls'))]\n",
            "app/urls.py" to imports + "urlpatterns = [path('b/', v)]\n",
        )
        assertEquals(listOf("ANY /a/b/"), brief(result))
        assertEquals(listOf(DiagnosticCode.GUESSED_ROOT), result.diagnostics.map { it.code })
    }

    fun testAppNameBecomesTheNamespaceOfAnIncludedModule() {
        val result = link(
            "urls.py" to imports + "urlpatterns = [path('shop/', include('shop.urls'))]\n",
            "shop/urls.py" to imports + "app_name = 'shop'\nurlpatterns = [path('', v, name='index')]\n",
        )
        assertEquals("shop:index", result.endpoints.single().routeName)
    }

    fun testExplicitNamespaceWinsOverAppName() {
        val result = link(
            "urls.py" to imports + "urlpatterns = [path('shop/', include('shop.urls', namespace='s'))]\n",
            "shop/urls.py" to imports + "app_name = 'shop'\nurlpatterns = [path('', v, name='index')]\n",
        )
        assertEquals("s:index", result.endpoints.single().routeName)
    }

    fun testNestedNamespacesAreJoinedWithColons() {
        val result = link(
            "urls.py" to imports + "urlpatterns = [path('sports/', include('sports.urls'))]\n",
            "sports/urls.py" to imports + "app_name = 'sports'\nurlpatterns = [path('polls/', include('polls.urls'))]\n",
            "polls/urls.py" to imports + "app_name = 'polls'\nurlpatterns = [path('', v, name='index')]\n",
        )
        assertEquals("sports:polls:index", result.endpoints.single().routeName)
    }

    fun testIncludeTupleSetsTheApplicationNamespace() {
        val result = link("urls.py" to imports + "urlpatterns = [path('x/', include(([path('y/', v, name='y')], 'xapp')))]\n")
        assertEquals("xapp:y", result.endpoints.single().routeName)
        assertEquals("/x/y/", result.endpoints.single().path)
    }

    fun testIncludeOfInlineListAndOfAVariable() {
        val result = link(
            "urls.py" to imports + "extra = [path('e/', v)]\nurlpatterns = [path('i/', include([path('a/', v)])), path('v/', include(extra))]\n",
        )
        assertEquals(listOf("ANY /i/a/", "ANY /v/e/"), brief(result))
    }

    /** UNVERIFIED in research 02: include(module_object) works like include("dotted.name"). Observed: it does. */
    fun testIncludeOfAModuleObjectFollowsTheImport() {
        val result = link(
            "urls.py" to imports + "from shop import urls as shop_urls\nurlpatterns = [path('s/', include(shop_urls))]\n",
            "shop/urls.py" to imports + "urlpatterns = [path('p/', v)]\n",
        )
        assertEquals(listOf("ANY /s/p/"), brief(result))
    }

    fun testRelativeImportedViewsResolveToTheirModule() {
        val result = link(
            "shop/urls.py" to imports + "from . import views\nfrom .other import thing\nurlpatterns = [path('a/', views.a), path('b/', thing)]\n",
            "shop/views.py" to "from django.http import HttpResponse\n\ndef a(request):\n    return HttpResponse('a')\n",
        )
        assertEquals(listOf("shop.views.a", "shop.other.thing"), result.endpoints.map { it.handler.qualifiedName })
    }

    fun testReRouteNamedGroupsBecomeParameters() {
        val e = link("urls.py" to imports + "urlpatterns = [re_path(r'^archive/(?P<year>[0-9]{4})/(?P<month>[0-9]{2})/\$', v)]\n").endpoints.single()
        assertEquals("/archive/{year}/{month}/", e.path)
        assertEquals(listOf("year", "month"), e.pathParams.map { it.name })
        assertFalse(e.isPartial)
    }

    /** UNVERIFIED in research 02: django.conf.urls.url() is a re_path alias. Observed: handled as a regex route. */
    fun testLegacyUrlFunctionIsARegexRoute() {
        val e = link("urls.py" to "from django.conf.urls import url\nurlpatterns = [url(r'^old/(?P<slug>[\\w-]+)/\$', v, name='old')]\n").endpoints.single()
        assertEquals("/old/{slug}/", e.path)
        assertEquals("old", e.routeName)
    }

    fun testRegexThatCannotBeATemplateIsOmittedAndReported() {
        val result = link("urls.py" to imports + "urlpatterns = [re_path(r'^(en|fr)/about/\$', v), re_path(r'^opt/?\$', v), path('ok/', v)]\n")
        assertEquals(listOf("ANY /ok/"), brief(result))
        assertEquals(listOf(DiagnosticCode.UNSUPPORTED_REGEX, DiagnosticCode.UNSUPPORTED_REGEX), warnings(result).map { it.code })
    }

    fun testComputedPrefixOmitsEverythingBelowItAndIsReported() {
        val result = link(
            "urls.py" to imports + "from django.conf import settings\nurlpatterns = [path(settings.PREFIX + 'api/', include('app.urls')), path('fine/', v)]\n",
            "app/urls.py" to imports + "urlpatterns = [path('x/', v)]\n",
        )
        assertEquals(listOf("ANY /fine/"), brief(result))
        assertEquals(listOf(DiagnosticCode.DYNAMIC_PREFIX), warnings(result).map { it.code })
    }

    fun testComputedRouteIsOmittedAndReported() {
        val result = link("urls.py" to imports + "urlpatterns = [path('a/' + SUFFIX, v), path('b/', v)]\n")
        assertEquals(listOf("ANY /b/"), brief(result))
        assertEquals(listOf(DiagnosticCode.DYNAMIC_VALUE), warnings(result).map { it.code })
    }

    fun testComputedRouteNameIsDroppedButTheEndpointStaysExact() {
        val result = link("urls.py" to imports + "urlpatterns = [path('a/', v, name=NAME)]\n")
        assertNull(result.endpoints.single().routeName)
        assertEquals(listOf(DiagnosticCode.DYNAMIC_VALUE), warnings(result).map { it.code })
    }

    fun testApiViewWithAComputedMethodListIsOmitted() {
        val result = link(
            "urls.py" to "from django.urls import path\nfrom rest_framework.decorators import api_view\n\n@api_view(METHODS)\ndef a(request):\n    pass\n\nurlpatterns = [path('a/', a)]\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.DYNAMIC_VALUE), warnings(result).map { it.code })
    }

    fun testNoEndpointIsEverApproximate() {
        val result = link("urls.py" to imports + "urlpatterns = [path('ok/', v), re_path(r'^(a|b)/\$', v), path(X + 'y/', v), path('z/', lambda r: 1)]\n")
        assertTrue(result.endpoints.all { !it.isPartial && !it.path.contains("{?}") })
    }

    fun testUnresolvedIncludeIsReportedNotInvented() {
        val result = link("urls.py" to imports + "urlpatterns = [path('a/', include('missing.urls')), path('b/', v)]\n")
        assertEquals(listOf("ANY /b/"), brief(result))
        assertEquals(DiagnosticCode.UNRESOLVED_INCLUDE, warnings(result).single().code)
    }

    fun testImplicitStringConcatenationInARoute() {
        val e = link("urls.py" to imports + "urlpatterns = [path('ab/' 'cd/', v)]\n").endpoints.single()
        assertEquals("/ab/cd/", e.path)
    }

    fun testAugmentedAssignmentAppendExtendAndRebindingAreAllSeen() {
        val result = link(
            "urls.py" to imports + """
urlpatterns = [path('one/', v)]
urlpatterns += [path('two/', v)]
urlpatterns = urlpatterns + [path('three/', v)]
urlpatterns.append(path('four/', v))
urlpatterns.extend([path('five/', v)])
""",
        )
        assertEquals(listOf("/one/", "/two/", "/three/", "/four/", "/five/"), result.endpoints.map { it.path })
    }

    fun testStarSpliceAndPatternWrappersAreTransparent() {
        val result = link(
            "urls.py" to imports + "from django.conf.urls.i18n import i18n_patterns\nfrom rest_framework.urlpatterns import format_suffix_patterns\n" +
                "more = [path('m/', v)]\nurlpatterns = [path('a/', v), *more]\nurlpatterns = format_suffix_patterns(urlpatterns)\n",
        )
        assertEquals(listOf("/a/", "/m/"), result.endpoints.map { it.path })
    }

    fun testStaticHelperIsNotAnEndpointButAdminIsAMountPoint() {
        val result = link(
            "urls.py" to imports + "from django.contrib import admin\nfrom django.conf.urls.static import static\n" +
                "urlpatterns = [path('admin/', admin.site.urls)] + static('/m/', document_root='x')\n",
        )
        val e = result.endpoints.single()
        assertEquals("/admin/", e.path)
        assertEquals(HttpMethod.ANY, e.method)
        assertEquals("django.contrib.admin.site.urls", e.handler.qualifiedName)
        assertTrue(warnings(result).isEmpty())
    }

    fun testIncludeCycleIsCutAndReported() {
        val result = link(
            "a/urls.py" to imports + "urlpatterns = [path('x/', include('b.urls')), path('own/', v)]\n",
            "b/urls.py" to imports + "urlpatterns = [path('y/', include('a.urls'))]\n",
            "root/urls.py" to imports + "urlpatterns = [path('r/', include('a.urls'))]\n",
        )
        assertEquals(listOf("ANY /r/own/"), brief(result))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.UNRESOLVED_INCLUDE })
    }

    fun testStarImportFromDjangoUrlsIsRecognized() {
        val e = link("urls.py" to "from django.urls import *\nurlpatterns = [path('s/', v)]\n").endpoints.single()
        assertEquals("/s/", e.path)
    }

    fun testAliasedImportIsRecognized() {
        val e = link("urls.py" to "from django.urls import path as p\nurlpatterns = [p('al/', v)]\n").endpoints.single()
        assertEquals("/al/", e.path)
    }

    fun testAPathFunctionThatIsNotDjangosIsIgnored() {
        val result = link("urls.py" to "from django.http import HttpResponse\nfrom mylib import path\nurlpatterns = [path('x/', v)]\n")
        assertTrue(result.endpoints.isEmpty())
    }

    fun testViewThatIsNotAReferenceIsOmittedAndReported() {
        val result = link("urls.py" to imports + "urlpatterns = [path('l/', lambda request: HttpResponse('x'))]\n")
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.UNRESOLVED_VIEW), warnings(result).map { it.code })
    }

    fun testIncludeUnderConditionalBlockIsStillListed() {
        val result = link("urls.py" to imports + "DEBUG = True\nurlpatterns = [path('a/', v)]\nif DEBUG:\n    urlpatterns += [path('dbg/', v)]\n")
        assertEquals(listOf("/a/", "/dbg/"), result.endpoints.map { it.path })
    }

    fun testSettingsWithSeveralRootsListsEachModuleOnce() {
        val result = link(
            "s/dev.py" to "ROOT_URLCONF = 'proj.urls'\n",
            "s/prod.py" to "ROOT_URLCONF = 'proj.urls'\n",
            "proj/urls.py" to imports + "urlpatterns = [path('a/', v)]\n",
        )
        assertEquals(listOf("ANY /a/"), brief(result))
    }

    fun testUnknownListElementIsReportedAsADiagnostic() {
        val result = link("urls.py" to imports + "urlpatterns = [path('a/', v)] + build_more()\n")
        assertEquals(listOf("ANY /a/"), brief(result))
        assertEquals(DiagnosticCode.DYNAMIC_VALUE, warnings(result).single().code)
    }
}
