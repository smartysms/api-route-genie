package com.routegenie.testmap

import com.routegenie.model.HttpMethod.DELETE
import com.routegenie.model.HttpMethod.GET
import com.routegenie.model.HttpMethod.HEAD
import com.routegenie.model.HttpMethod.OPTIONS
import com.routegenie.model.HttpMethod.PATCH
import com.routegenie.model.HttpMethod.POST
import com.routegenie.model.HttpMethod.PUT

/** FastAPI and Starlette test clients: each pattern of the plan's catalog is a fixture, each negative too. */
class FastApiTestMapTest : TestMapTestCase() {
    private val items = ep(GET, "/items/")
    private val item = ep(GET, "/items/{item_id}", name = "read_item")
    private val create = ep(POST, "/items/", name = "create_item")

    fun testModuleLevelClientAndLiteralPath() {
        val index = link("tests/test_items.py", """
            from fastapi.testclient import TestClient
            from app.main import app

            client = TestClient(app)

            def test_list():
                response = client.get("/items/")
                assert response.status_code == 200
        """, items)
        assertEquals(listOf("GET /items/ <- test_list"), index.mappedLines())
        assertEquals(200, index.single().expectedStatus)
        assertEquals(CallOrigin.DIRECT, index.single().origin)
        assertEquals("tests/test_items.py::test_list", index.single().test.id)
    }

    fun testStarletteImportOrigin() {
        val index = link("tests/test_items.py", """
            from starlette.testclient import TestClient
            client = TestClient(app)
            def test_list():
                client.get("/items/")
        """, items)
        assertEquals(listOf("GET /items/ <- test_list"), index.mappedLines())
    }

    fun testAliasedClientImportIsStillTheClient() {
        val index = link("tests/test_items.py", """
            from fastapi.testclient import TestClient as TC

            web = TC(app)

            def test_list():
                web.get("/items/")
                TC(app).get("/items/")
        """, items)
        assertEquals(listOf("GET /items/ <- test_list", "GET /items/ <- test_list"), index.mappedLines())
    }

    fun testFixtureParameterNamedClientWithoutDefinition() {
        val index = link("tests/test_items.py", """
            def test_list(client):
                client.get("/items/")
        """, items)
        assertEquals(listOf("GET /items/ <- test_list"), index.mappedLines())
    }

    fun testConftestFixtureWithYieldUnderAnotherName() {
        val index = link(
            listOf(
                "tests/conftest.py" to """
                    import pytest
                    from fastapi.testclient import TestClient

                    @pytest.fixture(scope="module")
                    def web():
                        with TestClient(app) as c:
                            yield c
                """,
                "tests/test_items.py" to """
                    def test_list(web):
                        web.get("/items/")
                """,
            ),
            listOf(items),
        )
        assertEquals(listOf("GET /items/ <- test_list"), index.mappedLines())
    }

    fun testFixtureThatIsNotAClientIsNotACall() {
        val index = link(
            listOf(
                "tests/conftest.py" to """
                    import pytest

                    @pytest.fixture
                    def client():
                        return {"a": 1}
                """,
                "tests/test_items.py" to """
                    def test_list(client):
                        client.get("/items/")
                """,
            ),
            listOf(items),
        )
        assertEquals(0, index.stats.clientCalls)
        assertTrue(index.mappedLines().isEmpty())
    }

    fun testInlineConstructor() {
        val index = link("tests/test_items.py", """
            from fastapi.testclient import TestClient
            def test_list():
                assert TestClient(app).get("/items/").status_code == 200
        """, items)
        assertEquals(listOf("GET /items/ <- test_list"), index.mappedLines())
        assertEquals(200, index.single().expectedStatus)
    }

    fun testFStringHoleMatchesPathParameter() {
        val index = link("tests/test_items.py", """
            def test_read(client):
                item_id = 5
                client.get(f"/items/{item_id}")
        """, items, item)
        assertEquals(listOf("GET /items/{item_id} <- test_read"), index.mappedLines())
    }

    fun testComputedSegmentIsNotCutAtItsQuestionMark() {
        // The marker of an unknown part is "{?}"; the "?" in it is not the start of a query string.
        val typed = ep(GET, "/items/{item_id}", types = mapOf("item_id" to "int"))
        val index = link("tests/test_items.py", """
            def test_read(client, item_id):
                client.get(f"/items/{item_id}?verbose=1")
        """, typed)
        assertEquals(listOf("GET /items/{item_id} <- test_read"), index.mappedLines())
        assertEquals(listOf("verbose" to "1"), index.single().request.query)
    }

    fun testLiteralValueAgainstPathParameter() {
        val index = link("tests/test_items.py", """
            def test_read(client):
                client.get("/items/42")
        """, items, item)
        assertEquals(listOf("GET /items/{item_id} <- test_read"), index.mappedLines())
    }

    fun testIntConverterRejectsNonNumber() {
        val typed = ep(GET, "/items/{item_id}", types = mapOf("item_id" to "int"))
        val index = link("tests/test_items.py", """
            def test_read(client):
                client.get("/items/me")
        """, typed)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("NO_ENDPOINT:GET /items/me"), index.reasons())
    }

    fun testAllVerbs() {
        val all = listOf(
            ep(GET, "/a"), ep(POST, "/a"), ep(PUT, "/a"), ep(PATCH, "/a"), ep(DELETE, "/a"), ep(HEAD, "/a"), ep(OPTIONS, "/a"),
        )
        val index = link(listOf("tests/test_a.py" to """
            def test_verbs(client):
                client.get("/a")
                client.post("/a")
                client.put("/a")
                client.patch("/a")
                client.delete("/a")
                client.head("/a")
                client.options("/a")
        """), all)
        assertEquals(
            listOf("DELETE /a", "GET /a", "HEAD /a", "OPTIONS /a", "PATCH /a", "POST /a", "PUT /a").map { "$it <- test_verbs" },
            index.mappedLines(),
        )
    }

    fun testGenericRequestWithLiteralVerb() {
        val index = link("tests/test_items.py", """
            def test_put(client):
                client.request("POST", "/items/")
        """, items, create)
        assertEquals(listOf("POST /items/ <- test_put"), index.mappedLines())
    }

    fun testGenericRequestWithComputedVerbIsNotAttributed() {
        val index = link("tests/test_items.py", """
            def test_put(client, verb):
                client.request(verb, "/items/")
        """, items, create)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("URL_DYNAMIC:method"), index.reasons())
    }

    fun testStaticAndParameterSegmentAreAmbiguousForFastApi() {
        val me = ep(GET, "/users/me", name = "me")
        val byId = ep(GET, "/users/{user_id}", name = "by_id")
        val index = link("tests/test_users.py", """
            def test_me(client):
                client.get("/users/me")
        """, me, byId)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("AMBIGUOUS:2"), index.reasons())
    }

    fun testDuplicateEndpointsWithOrdinalsAreAmbiguous() {
        val index = link("tests/test_items.py", """
            def test_list(client):
                client.get("/items/")
        """, ep(GET, "/items/", ordinal = 0), ep(GET, "/items/", ordinal = 1))
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("AMBIGUOUS:2"), index.reasons())
    }

    fun testNoEndpointAndWrongVerb() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                client.get("/nope/")
                client.delete("/items/")
        """, items)
        assertEquals(listOf("NO_ENDPOINT:DELETE /items/", "NO_ENDPOINT:GET /nope/"), index.reasons())
    }

    fun testLocalHostUrlIsRoutedByPathAndExternalHostIsIgnored() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                client.get("http://testserver/items/")
                client.get("https://example.com/items/")
        """, items)
        assertEquals(listOf("GET /items/ <- test_x"), index.mappedLines())
        assertEquals(listOf("EXTERNAL_URL:example.com"), index.reasons())
    }

    fun testQueryStringAndFragmentAreStripped() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                client.get("/items/?q=1&limit=5")
        """, items)
        assertEquals(listOf("GET /items/ <- test_x"), index.mappedLines())
        assertEquals(listOf("q" to "1", "limit" to "5"), index.single().request.query)
    }

    fun testComputedUrlIsNotAttributed() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                client.get(make_url("items"))
        """, items)
        assertEquals(listOf("URL_DYNAMIC"), index.reasons())
    }

    fun testSettingsPrefixIsReportedWithItsName() {
        val index = link("tests/test_items.py", """
            from app.core.config import settings

            def test_x(client):
                client.get(f"{settings.API_V1_STR}/items/")
        """, items)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("CONSTANT_UNRESOLVED:settings.API_V1_STR"), index.reasons())
    }

    fun testConstantOfTheSameFileIsEvaluated() {
        val index = link("tests/test_items.py", """
            PREFIX = "/api"

            def test_x(client):
                client.get(PREFIX + "/items/")
        """, ep(GET, "/api/items/"))
        assertEquals(listOf("GET /api/items/ <- test_x"), index.mappedLines())
    }

    fun testConstantOfAFileThatIsNotReadIsReported() {
        // Constants of other files need the dependency rounds of a later slice; until then they are named, never guessed.
        val index = link("tests/test_items.py", """
            from .urls import ITEMS

            def test_x(client):
                client.get(ITEMS)
        """, items)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("CONSTANT_UNRESOLVED:ITEMS"), index.reasons())
    }

    fun testReceiversThatAreNotClientsAreIgnored() {
        val index = link("tests/test_items.py", """
            import requests

            def test_x(cache, session, redis_client, mock):
                cache.get("/items/")
                session.get("/items/")
                redis_client.get("/items/")
                mock.get("/items/")
                requests.get("/items/")
                {"a": 1}.get("/items/")
                os.environ.get("/items/")
        """, items)
        assertEquals(0, index.stats.clientCalls)
        assertTrue(index.reasons().isEmpty())
    }

    fun testTrailingSlashDifferenceIsMappedViaRedirect() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                client.get("/items")
        """, items)
        assertEquals(listOf("GET /items/ <- test_x (redirect)"), index.mappedLines())
        assertTrue(index.single().viaRedirect)
    }

    fun testExactMatchWinsOverRedirectCandidate() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                client.get("/items")
        """, ep(GET, "/items"), ep(GET, "/items/"))
        assertEquals(listOf("GET /items <- test_x"), index.mappedLines())
    }

    fun testRootPath() {
        val index = link("tests/test_root.py", """
            def test_root(client):
                client.get("/")
        """, ep(GET, "/"))
        assertEquals(listOf("GET / <- test_root"), index.mappedLines())
    }

    fun testSkipAndXfailMarks() {
        val index = link("tests/test_items.py", """
            import pytest

            @pytest.mark.skip(reason="later")
            def test_a(client):
                client.get("/items/")

            @pytest.mark.xfail
            def test_b(client):
                client.get("/items/")

            @pytest.mark.skipif(True, reason="x")
            def test_c(client):
                client.get("/items/")

            def test_d(client):
                client.get("/items/")
        """, items)
        val marks = index.byEndpoint.values.single().associate { it.test.name to it.test.mark }
        assertEquals(
            mapOf("test_a" to TestMark.SKIPPED, "test_b" to TestMark.XFAIL, "test_c" to TestMark.CONDITIONAL_SKIP, "test_d" to TestMark.NONE),
            marks,
        )
        assertEquals(2, index.reachedBy(items))
    }

    fun testClassMarkAppliesToMethods() {
        val index = link("tests/test_items.py", """
            import pytest

            @pytest.mark.skip
            class TestItems:
                def test_a(self, client):
                    client.get("/items/")
        """, items)
        assertEquals(TestMark.SKIPPED, index.single().test.mark)
        assertEquals("TestItems.test_a", index.single().test.displayName)
        assertEquals("tests/test_items.py::TestItems::test_a", index.single().test.id)
        assertEquals(0, index.reachedBy(items))
    }

    fun testPytestClassWithInitIsNotCollected() {
        val index = link("tests/test_items.py", """
            class TestItems:
                def __init__(self):
                    pass

                def test_a(self, client):
                    client.get("/items/")
        """, items)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("HELPER_OPAQUE:test_a"), index.reasons())
    }

    fun testAsyncClientWithAsgiTransport() {
        val index = link("tests/test_items.py", """
            import pytest
            from httpx import ASGITransport, AsyncClient

            @pytest.mark.anyio
            async def test_list():
                async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
                    response = await ac.get("/items/")
                assert response.status_code == 200
        """, items)
        assertEquals(listOf("GET /items/ <- test_list"), index.mappedLines())
        assertEquals(200, index.single().expectedStatus)
    }

    fun testAsyncClientWithoutTransportTalksToARunningServer() {
        val index = link("tests/test_items.py", """
            from httpx import AsyncClient

            async def test_list():
                async with AsyncClient(base_url="http://localhost:8000") as ac:
                    await ac.get("/items/")
        """, items)
        assertEquals(0, index.stats.clientCalls)
    }

    fun testUnittestCaseWithClientInSetUp() {
        val index = link("tests/test_items.py", """
            import unittest
            from fastapi.testclient import TestClient

            class ItemsTest(unittest.TestCase):
                def setUp(self):
                    self.client = TestClient(app)

                def test_list(self):
                    self.client.get("/items/")
        """, items)
        assertEquals(listOf("GET /items/ <- ItemsTest.test_list"), index.mappedLines())
        assertEquals(TestStyle.UNITTEST, index.single().test.style)
    }

    fun testIsolatedAsyncioTestCase() {
        val index = link("tests/test_items.py", """
            import unittest
            from httpx import ASGITransport, AsyncClient

            class ItemsTest(unittest.IsolatedAsyncioTestCase):
                async def asyncSetUp(self):
                    self.client = AsyncClient(transport=ASGITransport(app=app), base_url="http://test")

                async def test_list(self):
                    await self.client.get("/items/")
        """, items)
        assertEquals(listOf("GET /items/ <- ItemsTest.test_list"), index.mappedLines())
    }

    fun testDjangoTestCaseDoesNotCountForFastApi() {
        val index = link("tests/test_items.py", """
            from django.test import TestCase
            from fastapi.testclient import TestClient

            class ItemsTest(TestCase):
                def test_list(self):
                    client = TestClient(app)
                    client.get("/items/")
        """, items)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("STYLE_UNSUPPORTED:DJANGO_TESTCASE"), index.reasons())
    }

    fun testHelperAndFixtureCallsBelongToNoTest() {
        val index = link("tests/test_items.py", """
            from fastapi.testclient import TestClient
            client = TestClient(app)

            def get_token():
                return client.post("/items/")

            def test_a():
                get_token()
        """, items, create)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("HELPER_OPAQUE:get_token"), index.reasons())
        // The helper call is about POST /items/, so it casts doubt on that endpoint only.
        assertEquals(1, index.doubtFor(create))
        assertEquals(0, index.doubtFor(items))
        assertFalse(index.isUntested(create))
        assertTrue(index.isUntested(items))
    }

    fun testOneStepVariable() {
        val index = link("tests/test_items.py", """
            def test_x(client):
                url = "/items/"
                client.get(url)
        """, items)
        assertEquals(listOf("GET /items/ <- test_x"), index.mappedLines())
    }

    fun testReassignedVariableInABranchIsNotGuessed() {
        val index = link("tests/test_items.py", """
            def test_x(client, flag):
                url = "/items/"
                if flag:
                    url = "/other/"
                client.get(url)
        """, items)
        assertTrue(index.mappedLines().isEmpty())
        assertEquals(listOf("URL_DYNAMIC:url"), index.reasons())
    }

    fun testParameterAsUrlIsNotAttributed() {
        val index = link("tests/test_items.py", """
            import pytest

            @pytest.mark.parametrize("path", ["/items/"])
            def test_x(client, path):
                client.get(path)
        """, items)
        assertEquals(listOf("URL_DYNAMIC:path"), index.reasons())
    }

    fun testStarArgumentsBeforeTheUrlAreNotGuessed() {
        val index = link("tests/test_items.py", """
            def test_x(client, args):
                client.get(*args)
        """, items)
        assertEquals(listOf("URL_DYNAMIC"), index.reasons())
    }

    fun testRouteDecoratorsInTestsAreNotCalls() {
        val index = link("tests/test_items.py", """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get("/items/")
            def read():
                return []

            def test_x(client):
                client.get("/items/")
        """, items)
        assertEquals(1, index.stats.clientCalls)
    }

    fun testStatusAssertionForms() {
        val index = link("tests/test_items.py", """
            from starlette import status
            from starlette.testclient import TestClient
            import unittest

            def test_a(client):
                r = client.get("/items/")
                assert r.status_code == status.HTTP_201_CREATED

            def test_b(client):
                r = client.get("/items/")
                assert 404 == r.status_code

            class T(unittest.TestCase):
                def setUp(self):
                    self.client = TestClient(app)

                def test_c(self):
                    r = self.client.get("/items/")
                    self.assertEqual(r.status_code, 422)
        """, items)
        val byTest = index.byEndpoint.values.single().associate { it.test.name to it.expectedStatus }
        assertEquals(201, byTest["test_a"])
        assertEquals(404, byTest["test_b"])
        assertEquals(422, byTest["test_c"])
    }

    fun testTwoDifferentAssertsOnOneCallGiveNoExpectation() {
        val index = link("tests/test_items.py", """
            def test_a(client):
                r = client.get("/items/")
                assert r.status_code == 200
                assert r.status_code == 201
        """, items)
        assertNull(index.single().expectedStatus)
    }

    fun testReassignedResponseVariableBindsNothing() {
        val index = link("tests/test_items.py", """
            def test_a(client):
                r = client.get("/items/")
                r = helper()
                assert r.status_code == 200
        """, items)
        assertNull(index.single().expectedStatus)
    }

    fun testWordsMakeTestFilesCandidatesWithoutParsing() {
        // Spike S0: the word table covers every constructor, fixture, class attribute and base class of the sink table.
        val words = ClientSinks.candidateWords
        listOf("TestClient", "AsyncClient", "APIClient", "Client", "test_client", "client", "async_client", "api_client", "APITestCase", "TestCase")
            .forEach { assertTrue("$it must be a candidate word", it in words) }
        assertTrue(words.size <= com.routegenie.Limits.TEST_CLIENT_WORDS_MAX)
    }
}
