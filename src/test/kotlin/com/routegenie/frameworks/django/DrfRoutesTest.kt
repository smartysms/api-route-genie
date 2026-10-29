package com.routegenie.frameworks.django

import com.routegenie.discovery.LinkResult
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam

/** Django REST Framework: routers, viewsets, actions, API views. Each case builds a tiny project. */
class DrfRoutesTest : DjangoTestBase() {
    private val head = "from django.urls import include, path\nfrom rest_framework import generics, mixins, viewsets\n" +
        "from rest_framework.decorators import action, api_view\nfrom rest_framework.routers import DefaultRouter, SimpleRouter\n" +
        "from rest_framework.views import APIView\n"

    private fun api(viewset: String, router: String = "router = DefaultRouter()\nrouter.register('items', ItemViewSet, basename='item')\n", urls: String = "urlpatterns = [path('api/', include(router.urls))]\n"): LinkResult =
        link("urls.py" to head + viewset + "\n" + router + urls)

    fun testModelViewSetProducesTheStandardListAndDetailRoutes() {
        val result = api("class ItemViewSet(viewsets.ModelViewSet):\n    pass\n")
        assertEquals(
            listOf("GET /api/items/", "POST /api/items/", "GET /api/items/{pk}/", "PUT /api/items/{pk}/", "PATCH /api/items/{pk}/", "DELETE /api/items/{pk}/", "GET /api/"),
            brief(result),
        )
        assertTrue(result.endpoints.all { it.framework == Framework.DRF })
        assertEquals("item-list", find(result, "GET", "/api/items/").routeName)
        assertEquals("item-detail", find(result, "DELETE", "/api/items/{pk}/").routeName)
        assertEquals("api-root", find(result, "GET", "/api/").routeName)
        assertEquals("urls.ItemViewSet.partial_update", find(result, "PATCH", "/api/items/{pk}/").handler.qualifiedName)
    }

    fun testSimpleRouterHasNoRootView() {
        val result = api("class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n", "router = SimpleRouter()\nrouter.register('items', ItemViewSet, basename='item')\n")
        assertEquals(listOf("GET /api/items/", "GET /api/items/{pk}/"), brief(result))
    }

    fun testTrailingSlashFalseRemovesTheSlash() {
        val result = api("class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n", "router = SimpleRouter(trailing_slash=False)\nrouter.register('items', ItemViewSet, basename='item')\n")
        assertEquals(listOf("GET /api/items", "GET /api/items/{pk}"), brief(result))
    }

    fun testDefaultRouterWithoutRootViewAndWithFormatSuffixesDisabled() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n",
            "router = DefaultRouter(include_root_view=False)\nrouter.register('items', ItemViewSet, basename='item')\n",
        )
        assertEquals(listOf("GET /api/items/", "GET /api/items/{pk}/"), brief(result))
    }

    /** UNVERIFIED in research 02: mixins add exactly the standard actions they are named for. Observed: confirmed by rows. */
    fun testListAndCreateMixinsGiveOnlyTheListRoute() {
        val result = api("class ItemViewSet(mixins.ListModelMixin, mixins.CreateModelMixin, viewsets.GenericViewSet):\n    pass\n")
        assertEquals(listOf("GET /api/items/", "POST /api/items/", "GET /api/"), brief(result))
    }

    fun testRetrieveAndDestroyMixinsGiveOnlyTheDetailRoute() {
        val result = api("class ItemViewSet(mixins.RetrieveModelMixin, mixins.DestroyModelMixin, viewsets.GenericViewSet):\n    pass\n")
        assertEquals(listOf("GET /api/items/{pk}/", "DELETE /api/items/{pk}/", "GET /api/"), brief(result))
    }

    fun testUpdateMixinGivesPutAndPatch() {
        val result = api("class ItemViewSet(mixins.UpdateModelMixin, viewsets.GenericViewSet):\n    pass\n")
        assertEquals(listOf("PUT /api/items/{pk}/", "PATCH /api/items/{pk}/", "GET /api/"), brief(result))
    }

    fun testAViewSetThatDefinesTheMethodItselfGetsTheRoute() {
        val result = api("class ItemViewSet(viewsets.ViewSet):\n    def list(self, request):\n        pass\n\n    def destroy(self, request, pk=None):\n        pass\n")
        assertEquals(listOf("GET /api/items/", "DELETE /api/items/{pk}/", "GET /api/"), brief(result))
    }

    fun testDetailActionWithUrlPathAndMethods() {
        val result = api(
            "class ItemViewSet(viewsets.GenericViewSet):\n    @action(detail=True, methods=['post', 'delete'], url_path='reset-copies')\n    def reset(self, request, pk=None):\n        pass\n",
        )
        assertEquals(listOf("POST /api/items/{pk}/reset-copies/", "DELETE /api/items/{pk}/reset-copies/", "GET /api/"), brief(result))
        assertEquals("item-reset", find(result, "POST", "/api/items/{pk}/reset-copies/").routeName)
    }

    fun testActionDefaultsAreGetAndTheMethodNameAndAHyphenatedUrlName() {
        val result = api("class ItemViewSet(viewsets.GenericViewSet):\n    @action(detail=False)\n    def recent_items(self, request):\n        pass\n")
        val e = find(result, "GET", "/api/items/recent_items/")
        assertEquals("item-recent-items", e.routeName)
        assertEquals("urls.ItemViewSet.recent_items", e.handler.qualifiedName)
    }

    fun testActionRowsAreOrderedByMethodNameLikeDrfDoes() {
        val result = api(
            "class ItemViewSet(viewsets.GenericViewSet):\n    @action(detail=False)\n    def zeta(self, request):\n        pass\n\n    @action(detail=False)\n    def alpha(self, request):\n        pass\n",
        )
        assertEquals(listOf("GET /api/items/alpha/", "GET /api/items/zeta/", "GET /api/"), brief(result))
    }

    fun testLookupFieldAndLookupUrlKwargNameTheParameter() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    lookup_field = 'isbn'\n    lookup_url_kwarg = 'book_id'\n\n" +
                "class OtherViewSet(viewsets.ReadOnlyModelViewSet):\n    lookup_field = 'isbn'\n",
            "router = SimpleRouter()\nrouter.register('items', ItemViewSet, basename='i')\nrouter.register('others', OtherViewSet, basename='o')\n",
        )
        assertEquals(listOf("GET /api/items/", "GET /api/items/{book_id}/", "GET /api/others/", "GET /api/others/{isbn}/"), brief(result))
        assertEquals(listOf(PathParam("book_id")), find(result, "GET", "/api/items/{book_id}/").pathParams)
    }

    fun testBasenameDefaultsToTheLowerCasedQuerysetModel() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    queryset = Widget.objects.all()\n",
            "router = SimpleRouter()\nrouter.register('items', ItemViewSet)\n",
        )
        assertEquals("widget-list", find(result, "GET", "/api/items/").routeName)
    }

    fun testRouterIncludedWithAnApplicationNamespace() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n",
            urls = "urlpatterns = [path('v2/', include((router.urls, 'v2'), namespace='n2'))]\n",
        )
        assertEquals("n2:item-list", find(result, "GET", "/v2/items/").routeName)
    }

    fun testFormatSuffixVariantsAreCollapsedByDefaultAndAvailableOnRequest() {
        val files = listOf("urls.py" to head + "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n\nrouter = DefaultRouter()\nrouter.register('items', ItemViewSet, basename='i')\nurlpatterns = [path('', include(router.urls))]\n")
        val facts = files.mapNotNull { (p, t) -> provider.extract(myFixture.addFileToProject(p, t)) }
        val plain = DjangoLinker().link(facts)
        val suffixed = DjangoLinker(DjangoLinker.Options(emitFormatSuffixVariants = true)).link(facts)
        assertEquals(listOf("GET /items/", "GET /items/{pk}/", "GET /"), brief(plain))
        assertEquals(
            listOf("GET /items/", "GET /items.{format}", "GET /items/{pk}/", "GET /items/{pk}.{format}", "GET /", "GET /.{format}"),
            brief(suffixed),
        )
    }

    fun testEmptyPrefixDoesNotProduceADoubleSlash() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n",
            "router = SimpleRouter()\nrouter.register('', ItemViewSet, basename='i')\n",
        )
        assertEquals(listOf("GET /api/", "GET /api/{pk}/"), brief(result))
    }

    fun testNestedRouterPrefixWithANamedGroup() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n",
            "router = SimpleRouter()\nrouter.register(r'books/(?P<book_pk>[^/.]+)/chapters', ItemViewSet, basename='c')\n",
        )
        assertEquals(listOf("GET /api/books/{book_pk}/chapters/", "GET /api/books/{book_pk}/chapters/{pk}/"), brief(result))
        assertEquals(listOf("book_pk"), result.endpoints.first().pathParams.map { it.name })
    }

    fun testRouterAndViewSetsInOtherModules() {
        val result = link(
            "app/routers.py" to "from rest_framework.routers import SimpleRouter\n\nrouter = SimpleRouter()\n",
            "app/viewsets.py" to "from rest_framework import viewsets\n\nclass BaseViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n\nclass ItemViewSet(BaseViewSet):\n    pass\n",
            "app/register.py" to "from .routers import router\nfrom .viewsets import ItemViewSet\n\nrouter.register('items', ItemViewSet, basename='item')\n",
            "app/urls.py" to "from django.urls import include, path\nfrom .routers import router\n\nurlpatterns = [path('api/', include(router.urls))]\n",
        )
        assertEquals(listOf("GET /api/items/", "GET /api/items/{pk}/"), brief(result))
        assertEquals("app/register.py", result.endpoints.first().source.path)
    }

    fun testRouterUrlsAddedWithAugmentedAssignmentAndConcatenation() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n",
            "router = SimpleRouter()\nrouter.register('a', ItemViewSet, basename='a')\n",
            "urlpatterns = [path('x/', v)] + router.urls\nurlpatterns += router.urls\n",
        )
        assertEquals(listOf("ANY /x/", "GET /a/", "GET /a/{pk}/", "GET /a/", "GET /a/{pk}/"), brief(result))
    }

    fun testViewSetThatCannotBeResolvedIsReported() {
        val result = link("urls.py" to head + "router = SimpleRouter()\nrouter.register('items', mystery.ItemViewSet, basename='i')\nurlpatterns = [path('', include(router.urls))]\n")
        assertEquals(DiagnosticCode.UNRESOLVED_VIEW, warnings(result).single().code)
    }

    fun testComputedRouterPrefixOmitsTheRegistrationAndIsReported() {
        val result = api(
            "class ItemViewSet(viewsets.ReadOnlyModelViewSet):\n    pass\n",
            "router = SimpleRouter()\nrouter.register(PREFIX, ItemViewSet, basename='i')\n",
        )
        assertTrue(result.endpoints.isEmpty())
        assertTrue(result.endpoints.none { it.path.contains("{?}") })
        assertEquals(listOf(DiagnosticCode.DYNAMIC_VALUE), warnings(result).map { it.code })
    }

    fun testAdminRegisterIsNotARouterRegistration() {
        val result = link("urls.py" to head + "from django.contrib import admin\nadmin.site.register(Thing, ThingAdmin)\nurlpatterns = [path('a/', v)]\n")
        assertEquals(listOf("ANY /a/"), brief(result))
        assertTrue(warnings(result).isEmpty())
    }

    // --- API views --------------------------------------------------------------------------------------------------

    fun testApiViewDefaultsToGetAndOtherwiseUsesTheListedMethods() {
        val result = link(
            "urls.py" to head + "@api_view()\ndef a(request):\n    pass\n\n@api_view(['POST', 'PUT'])\ndef b(request):\n    pass\n\nurlpatterns = [path('a/', a), path('b/', b)]\n",
        )
        assertEquals(listOf("GET /a/", "POST /b/", "PUT /b/"), brief(result))
        assertTrue(result.endpoints.all { it.framework == Framework.DRF })
    }

    fun testApiViewClassGetsItsDefinedHandlers() {
        val result = link("urls.py" to head + "class V(APIView):\n    def get(self, request):\n        pass\n\n    def delete(self, request):\n        pass\n\nurlpatterns = [path('v/', V.as_view())]\n")
        assertEquals(listOf("GET /v/", "DELETE /v/"), brief(result))
        assertEquals(Framework.DRF, result.endpoints.first().framework)
        assertEquals("urls.V", result.endpoints.first().handler.qualifiedName)
    }

    fun testGenericApiViewsContributeTheirMethods() {
        val result = link(
            "urls.py" to head + "class A(generics.ListCreateAPIView):\n    pass\n\nclass B(generics.RetrieveUpdateDestroyAPIView):\n    pass\n\nclass C(generics.UpdateAPIView):\n    pass\n\n" +
                "urlpatterns = [path('a/', A.as_view()), path('b/', B.as_view()), path('c/', C.as_view())]\n",
        )
        assertEquals(
            listOf("GET /a/", "POST /a/", "GET /b/", "PUT /b/", "PATCH /b/", "DELETE /b/", "PUT /c/", "PATCH /c/"),
            brief(result),
        )
    }

    fun testViewSetAsViewWithAnActionMap() {
        val result = link(
            "urls.py" to head + "class M(viewsets.ViewSet):\n    pass\n\nurlpatterns = [path('m/', M.as_view({'get': 'list', 'post': 'create'}))]\n",
        )
        assertEquals(listOf("GET /m/", "POST /m/"), brief(result))
        assertEquals("urls.M.create", find(result, "POST", "/m/").handler.qualifiedName)
        assertEquals(HttpMethod.GET, result.endpoints.first().method)
    }

    fun testSubclassOfAnApiViewInAnotherFileIsStillDrf() {
        val result = link(
            "base.py" to "from rest_framework.views import APIView\n\nclass Base(APIView):\n    def get(self, request):\n        pass\n",
            "urls.py" to "from django.urls import path\nfrom base import Base\n\nclass Child(Base):\n    def post(self, request):\n        pass\n\nurlpatterns = [path('c/', Child.as_view())]\n",
        )
        assertEquals(listOf("GET /c/", "POST /c/"), brief(result))
        assertEquals(Framework.DRF, result.endpoints.first().framework)
    }
}
