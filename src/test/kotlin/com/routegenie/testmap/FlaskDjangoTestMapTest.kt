package com.routegenie.testmap

import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod.ANY
import com.routegenie.model.HttpMethod.DELETE
import com.routegenie.model.HttpMethod.GET
import com.routegenie.model.HttpMethod.HEAD
import com.routegenie.model.HttpMethod.POST

/** Flask clients, Django and DRF test cases, and name-based resolution (reverse, url_for, url_path_for). */
class FlaskDjangoTestMapTest : TestMapTestCase() {
    private val flaskHome = ep(GET, "/", Framework.FLASK, "index")
    private val flaskUser = ep(GET, "/users/{id}", Framework.FLASK, "users.show", types = mapOf("id" to "int"))
    private val flaskMe = ep(GET, "/users/me", Framework.FLASK, "users.me")

    // ---- Flask -----------------------------------------------------------------------------------------------------

    fun testFlaskFixtureReturningTestClientFactory() {
        val index = link(
            listOf(
                "tests/conftest.py" to """
                    import pytest

                    @pytest.fixture
                    def browser(app):
                        return app.test_client()
                """,
                "tests/test_home.py" to """
                    def test_home(browser):
                        browser.get("/")
                """,
            ),
            listOf(flaskHome),
        )
        assertEquals(listOf("GET / <- test_home"), index.mappedLines())
    }

    fun testFlaskWithBlockAndOpenWithMethodKeyword() {
        val index = link("tests/test_home.py", """
            def test_home(app):
                with app.test_client() as client:
                    client.open("/", method="GET")
        """, flaskHome)
        assertEquals(listOf("GET / <- test_home"), index.mappedLines())
    }

    fun testFlaskIntConverterAndStaticBeatsDynamic() {
        val index = link("tests/test_users.py", """
            def test_users(client):
                client.get("/users/me")
                client.get("/users/7")
        """, flaskUser, flaskMe)
        assertEquals(listOf("GET /users/me <- test_users", "GET /users/{id} <- test_users"), index.mappedLines())
    }

    fun testFlaskHeadIsAnsweredByTheGetRule() {
        val index = link("tests/test_home.py", """
            def test_home(client):
                client.head("/")
        """, flaskHome)
        assertEquals(listOf("HEAD / <- test_home"), index.mappedLines())
    }

    fun testFlaskClientDoesNotReachFastApiEndpoints() {
        val index = link("tests/test_home.py", """
            client = app.test_client()

            def test_home():
                client.get("/items/")
        """, ep(GET, "/items/", Framework.FASTAPI))
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("NO_ENDPOINT:GET /items/"), index.reasons())
    }

    fun testUrlForResolvesTheEndpointName() {
        val index = link("tests/test_users.py", """
            from flask import url_for

            def test_user(client):
                client.get(url_for("users.show", id=3))
        """, flaskUser, flaskMe)
        assertEquals(listOf("GET /users/{id} <- test_user"), index.mappedLines())
        assertEquals("users.show", index.single().routeName)
    }

    fun testUrlForWithMissingArgumentDoesNotFit() {
        val index = link("tests/test_users.py", """
            from flask import url_for

            def test_user(client):
                client.get(url_for("users.show"))
        """, flaskUser)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("NAME_UNRESOLVED:users.show"), index.reasons())
        assertEquals(1, index.doubtFor(flaskUser))
    }

    fun testUrlForRelativeEndpointIsNotGuessed() {
        val index = link("tests/test_users.py", """
            from flask import url_for

            def test_user(client):
                client.get(url_for(".show", id=3))
        """, flaskUser)
        assertEquals(listOf("NAME_UNRESOLVED:.show"), index.reasons())
    }

    fun testUrlForThatIsNotFlasksIsNotFollowed() {
        val index = link("tests/test_users.py", """
            from myhelpers import url_for

            def test_user(client):
                client.get(url_for("users.show", id=3))
        """, flaskUser)
        assertEquals(listOf("URL_DYNAMIC:url_for"), index.reasons())
    }

    fun testUrlPathForOnTheApplication() {
        val read = ep(GET, "/items/{item_id}", Framework.FASTAPI, "read_item")
        val index = link("tests/test_items.py", """
            from fastapi.testclient import TestClient
            from app.main import app

            client = TestClient(app)

            def test_read():
                client.get(app.url_path_for("read_item", item_id=2))
        """, read)
        assertEquals(listOf("GET /items/{item_id} <- test_read"), index.mappedLines())
    }

    fun testNameSharedByTwoFastApiRoutesIsAmbiguous() {
        val index = link("tests/test_items.py", """
            def test_read(client):
                client.get(app.url_path_for("read", item_id=2))
        """, ep(GET, "/a/{item_id}", name = "read"), ep(GET, "/b/{item_id}", name = "read"))
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("AMBIGUOUS:2"), index.reasons())
    }

    // ---- Django and DRF ------------------------------------------------------------------------------------------------

    private val shopIndex = ep(ANY, "/shop/", Framework.DJANGO, "shop:index")
    private val shopDetail = ep(GET, "/shop/item/{pk}/", Framework.DJANGO, "shop:detail", types = mapOf("pk" to "int"))
    private val shopDetailByName = ep(GET, "/shop/item/{slug}/{page}/", Framework.DJANGO, "shop:detail")

    fun testDjangoTestCaseSelfClientWithLiteralPath() {
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class ShopTests(TestCase):
                def test_index(self):
                    response = self.client.get("/shop/")
                    self.assertEqual(response.status_code, 200)
        """, shopIndex)
        assertEquals(listOf("GET /shop/ <- ShopTests.test_index"), index.mappedLines())
        assertEquals(TestStyle.DJANGO_TESTCASE, index.single().test.style)
        assertEquals(200, index.single().expectedStatus)
    }

    fun testDjangoAnyEndpointTakesAnyVerb() {
        val index = link("shop/tests.py", """
            from django.test import SimpleTestCase

            class ShopTests(SimpleTestCase):
                def test_index(self):
                    self.client.post("/shop/")
                    self.client.get("/shop/")
        """, shopIndex)
        assertEquals(listOf("GET /shop/ <- ShopTests.test_index", "POST /shop/ <- ShopTests.test_index"), index.mappedLines())
    }

    fun testReverseWithArgs() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_detail(self):
                    self.client.get(reverse("shop:detail", args=[1]))
        """, shopIndex, shopDetail)
        assertEquals(listOf("GET /shop/item/{pk}/ <- ShopTests.test_detail"), index.mappedLines())
        assertEquals("shop:detail", index.single().routeName)
    }

    fun testReverseWithKwargs() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_detail(self):
                    self.client.get(reverse("shop:detail", kwargs={"pk": 1}))
        """, shopDetail, shopDetailByName)
        assertEquals(listOf("GET /shop/item/{pk}/ <- ShopTests.test_detail"), index.mappedLines())
    }

    fun testRouteNameFromAClassAttributeOfTheTestCaseOrItsBase() {
        val detail = ep(GET, "/api/orgs/{org}/", Framework.DRF, "org-details")
        val index = link(
            listOf(
                "api/base.py" to """
                    from rest_framework.test import APITestCase

                    class Base(APITestCase):
                        endpoint = "org-details"
                """,
                "api/tests.py" to """
                    from django.urls import reverse
                    from .base import Base

                    class OrgTests(Base):
                        def test_get(self):
                            self.client.get(reverse(self.endpoint, args=["acme"]))

                        def test_missing(self):
                            self.client.get(reverse(self.other, args=["acme"]))
                """,
            ),
            listOf(detail),
        )
        assertEquals(listOf("GET /api/orgs/{org}/ <- OrgTests.test_get"), index.mappedLines())
        assertEquals(listOf("NAME_UNRESOLVED:self.other"), index.reasons())
    }

    fun testAliasedReverseIsStillReverse() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse as rev

            class ShopTests(TestCase):
                def test_detail(self):
                    self.client.get(rev("shop:detail", args=[1]))
        """, shopIndex, shopDetail)
        assertEquals(listOf("GET /shop/item/{pk}/ <- ShopTests.test_detail"), index.mappedLines())
    }

    fun testReverseNarrowsSameNameByArgumentShape() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_detail(self):
                    self.client.get(reverse("shop:detail", args=[1, 2]))
        """, shopDetail, shopDetailByName)
        assertEquals(listOf("GET /shop/item/{slug}/{page}/ <- ShopTests.test_detail"), index.mappedLines())
    }

    fun testReverseOfAnUnknownName() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_detail(self):
                    self.client.get(reverse("shop:missing"))
        """, shopIndex)
        assertEquals(listOf("NAME_UNRESOLVED:shop:missing"), index.reasons())
        assertTrue(index.isUntested(shopIndex))
    }

    fun testReverseFromAnotherModuleIsNotFollowed() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from shop.utils import reverse

            class ShopTests(TestCase):
                def test_detail(self):
                    self.client.get(reverse("shop:index"))
        """, shopIndex)
        assertEquals(listOf("URL_DYNAMIC:reverse"), index.reasons())
    }

    fun testReverseWithNameFromAVariable() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_detail(self, name):
                    self.client.get(reverse(name))
        """, shopIndex)
        assertEquals(listOf("NAME_UNRESOLVED:name"), index.reasons())
    }

    fun testReverseStoredInAVariableAndInSetUp() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def setUp(self):
                    self.url = reverse("shop:index")

                def test_index(self):
                    self.client.get(self.url)

                def test_local(self):
                    url = reverse("shop:detail", args=[3])
                    self.client.get(url)
        """, shopIndex, shopDetail)
        assertEquals(
            listOf("GET /shop/ <- ShopTests.test_index", "GET /shop/item/{pk}/ <- ShopTests.test_local"),
            index.mappedLines(),
        )
    }

    fun testClassAttributeUrlAndInheritedFromAnotherFile() {
        val index = link(
            listOf(
                "shop/base.py" to """
                    from django.test import TestCase

                    class ShopBase(TestCase):
                        url = "/shop/"
                """,
                "shop/tests.py" to """
                    from .base import ShopBase

                    class ShopTests(ShopBase):
                        def test_index(self):
                            self.client.get(self.url)
                """,
            ),
            listOf(shopIndex),
        )
        assertEquals(listOf("GET /shop/ <- ShopTests.test_index"), index.mappedLines())
    }

    fun testQuerySuffixOfAReversedUrl() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_a(self):
                    self.client.get(reverse("shop:index") + "?page=2")

                def test_b(self):
                    self.client.get(f"{reverse('shop:index')}?page=3")
        """, shopIndex)
        assertEquals(listOf("GET /shop/ <- ShopTests.test_a", "GET /shop/ <- ShopTests.test_b"), index.mappedLines())
    }

    fun testSuffixThatIsNotAQueryIsNotGuessed() {
        val index = link("shop/tests.py", """
            from django.test import TestCase
            from django.urls import reverse

            class ShopTests(TestCase):
                def test_a(self):
                    self.client.get(reverse("shop:index") + "extra/")
        """, shopIndex)
        assertEquals(listOf("URL_DYNAMIC:reverse"), index.reasons())
    }

    fun testDrfApiTestCaseWithRouterNames() {
        val list = ep(GET, "/api/books/", Framework.DRF, "book-list")
        val create = ep(POST, "/api/books/", Framework.DRF, "book-list")
        val detail = ep(DELETE, "/api/books/{pk}/", Framework.DRF, "book-detail")
        val index = link("api/tests.py", """
            from rest_framework.test import APITestCase
            from rest_framework.reverse import reverse

            class BookTests(APITestCase):
                def test_crud(self):
                    self.client.get(reverse("book-list"))
                    self.client.post(reverse("book-list"), {"title": "x"}, format="json")
                    self.client.delete(reverse("book-detail", kwargs={"pk": 1}))
        """, list, create, detail)
        assertEquals(
            listOf("DELETE /api/books/{pk}/ <- BookTests.test_crud", "GET /api/books/ <- BookTests.test_crud", "POST /api/books/ <- BookTests.test_crud"),
            index.mappedLines(),
        )
    }

    fun testApiClientConstructedInTheTest() {
        val list = ep(GET, "/api/books/", Framework.DRF, "book-list")
        val index = link("api/test_books.py", """
            from rest_framework.test import APIClient

            def test_list():
                client = APIClient()
                client.get("/api/books/")
        """, list)
        assertEquals(listOf("GET /api/books/ <- test_list"), index.mappedLines())
    }

    fun testDjangoClientReachesDrfAndDjangoEndpointsButNotFlask() {
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class ShopTests(TestCase):
                def test_x(self):
                    self.client.get("/users/me")
        """, flaskMe)
        assertEquals(listOf("NO_ENDPOINT:GET /users/me"), index.reasons())
    }

    fun testProjectBaseClassWithUnresolvableParentStillSaysWhy() {
        val index = link("shop/tests.py", """
            from shop.testutils import BaseCase

            class ShopTests(BaseCase):
                def test_index(self):
                    self.client.get("/shop/")
        """, shopIndex)
        assertEquals(0, index.stats.clientCalls)
    }

    fun testHeadOnADjangoGetEndpointIsNotGuessed() {
        // Django lists HEAD explicitly where it exists; a HEAD call on a GET-only endpoint matches nothing.
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class ShopTests(TestCase):
                def test_x(self):
                    self.client.head("/shop/item/1/")
        """, shopDetail)
        assertEquals(listOf("NO_ENDPOINT:HEAD /shop/item/1/"), index.reasons())
        val withHead = link("shop/tests_head.py", """
            from django.test import TestCase

            class ShopTests(TestCase):
                def test_x(self):
                    self.client.head("/shop/item/1/")
        """, shopDetail, ep(HEAD, "/shop/item/{pk}/", Framework.DJANGO, types = mapOf("pk" to "int")))
        assertEquals(listOf("HEAD /shop/item/{pk}/ <- ShopTests.test_x"), withHead.mappedLines())
    }

    fun testMissingModulesNameProjectBaseClassesOnly() {
        val located = facts(
            "tests/test_x.py" to """
                import unittest
                from django.test import TestCase
                from rest_framework.test import APITestCase
                from app.testutils import Base
                from .support import Mixin
                from vendor import lib

                class A(unittest.TestCase): pass
                class B(TestCase): pass
                class C(APITestCase): pass
                class D(Base): pass
                class E(Mixin): pass
                class F(lib.Thing): pass
                class G(D): pass
            """,
        )
        assertEquals(setOf("app.testutils", "tests.support", "vendor", "vendor.lib"), TestLinker.missingModules(located))
    }

    fun testCallInSetUpIsAHelperCall() {
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class ShopTests(TestCase):
                def setUp(self):
                    self.client.post("/shop/")

                def test_x(self):
                    pass
        """, shopIndex)
        assertEquals(listOf("HELPER_OPAQUE:setUp"), index.reasons())
    }
}
