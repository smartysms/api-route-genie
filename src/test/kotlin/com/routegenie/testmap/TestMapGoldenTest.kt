package com.routegenie.testmap

import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.django.DjangoRouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.model.Endpoint
import com.routegenie.testing.ProviderPipeline
import java.io.File

/**
 * Whole projects: the real route providers find the endpoints of a fixture application, the real extractor reads the tests
 * written next to it, and the result is compared with a golden file. A missing expected.txt is written to
 * build/golden-actual/ and fails the test, so a new golden is always reviewed by a person before it is committed.
 */
class TestMapGoldenTest : TestMapTestCase() {
    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    private fun endpointsOf(provider: RouteProvider, fixture: String): List<Endpoint> {
        myFixture.copyDirectoryToProject(fixture, "")
        return provider.link(ProviderPipeline.collect(project, provider)).endpoints
    }

    private fun assertGolden(name: String, actual: String) {
        val expectedFile = File(testDataPath, "tests/$name/expected.txt")
        if (!expectedFile.exists()) {
            File("build/golden-actual/tests-$name.txt").also { it.parentFile.mkdirs() }.writeText(actual + "\n")
            fail("No expected.txt for $name; review build/golden-actual/tests-$name.txt and copy it")
        }
        assertEquals(expectedFile.readText().replace("\r\n", "\n").trimEnd(), actual.trimEnd())
    }

    private fun render(index: TestIndex): String = buildString {
        val keyed = index.byEndpoint.flatMap { (key, calls) -> calls.map { key to it } }
        keyed.map { (key, c) ->
            val where = key.split('|')
            buildString {
                append("MAPPED ").append(c.verb).append(' ').append(where[2]).append("  <-  ").append(c.test.id).append(" @").append(c.source.line)
                c.expectedStatus?.let { append(" expects=").append(it) }
                if (c.test.mark != TestMark.NONE) append(" mark=").append(c.test.mark)
                if (c.viaRedirect) append(" via-redirect")
                c.routeName?.let { append(" name=").append(it) }
            }
        }.sorted().forEach { appendLine(it) }
        index.unattributed.map { u ->
            "NOT ATTRIBUTED ${u.source}  ${u.reason.name.removePrefix("TEST_")}${u.detail?.let { " ($it)" } ?: ""}  reach=${u.reach.javaClass.simpleName}"
        }.sorted().forEach { appendLine(it) }
        append("STATS files=${index.stats.testFiles} functions=${index.stats.testFunctions} calls=${index.stats.clientCalls} mapped=${index.stats.mappedCalls} unattributed=${index.stats.unattributedCalls}")
    }

    fun testFastApiApplicationWithItsTests() {
        val endpoints = endpointsOf(FastApiRouteProvider(), "fastapi/basic")
        val index = TestLinker.link(
            facts(
                "tests/conftest.py" to """
                    import pytest
                    from fastapi.testclient import TestClient

                    from app.main import app


                    @pytest.fixture(name="client")
                    def client_fixture():
                        with TestClient(app) as c:
                            yield c
                """,
                "tests/test_health.py" to """
                    def test_health(client):
                        response = client.get("/health")
                        assert response.status_code == 200
                """,
                "tests/test_items.py" to """
                    import pytest
                    from starlette import status

                    PREFIX = "/api/v1"


                    def test_list(client):
                        r = client.get(f"{PREFIX}/items/")
                        assert r.status_code == 200


                    def test_read(client):
                        item_id = 4
                        assert client.get(f"{PREFIX}/items/{item_id}").status_code == 200


                    def test_create(client):
                        r = client.post(PREFIX + "/items/", json={"name": "x", "price": 3})
                        assert r.status_code == status.HTTP_201_CREATED


                    @pytest.mark.skip(reason="not yet")
                    def test_latest(client):
                        client.get("/api/latest/items/")


                    def test_delete_has_no_route(client):
                        client.delete("/api/v1/items/1")


                    def test_trailing_slash_redirect(client):
                        client.get("/api/v1/items")


                    class TestUsers:
                        def test_me(self, client):
                            r = client.get("/api/v1/users/me")
                            assert r.status_code == 200

                        def test_admin_delete(self, client):
                            client.delete("/api/v1/users/admin/7")
                """,
                "tests/test_misc.py" to """
                    import httpx
                    from app.core.config import settings


                    def test_settings_prefix(client):
                        client.get(f"{settings.API_V1_STR}/items/")


                    def test_external(client):
                        client.get("https://example.org/health")


                    def test_multi(client):
                        client.request("POST", "/multi")
                        client.get("/multi")


                    def test_cache_is_not_a_client(cache):
                        cache.get("/health")
                        httpx.get("/health")
                """,
            ),
            endpoints,
        )
        assertGolden("fastapi_basic", render(index))
    }

    fun testDjangoApplicationWithNamesNamespacesAndLiterals() {
        val endpoints = endpointsOf(DjangoRouteProvider(), "django/django_basic")
        val index = TestLinker.link(
            facts(
                "shop/tests.py" to """
                    from django.test import TestCase
                    from django.urls import reverse


                    class ShopTests(TestCase):
                        def setUp(self):
                            self.index_url = reverse("s:index")

                        def test_index(self):
                            response = self.client.get(self.index_url)
                            self.assertEqual(response.status_code, 200)

                        def test_detail_by_name(self):
                            self.client.get(reverse("shop:detail", args=[3]))

                        def test_detail_by_literal(self):
                            self.client.get("/store/item/3/")

                        def test_remove(self):
                            self.client.delete(reverse("shop:remove", kwargs={"pk": 2}))

                        def test_form_post(self):
                            self.client.post("/store/form/", {"a": 1})

                        def test_missing_name(self):
                            self.client.get(reverse("shop:nope"))

                        def test_literal_without_route(self):
                            self.client.get("/nowhere/")

                        def test_admin(self):
                            self.client.get("/admin/")
                """,
            ),
            endpoints,
        )
        assertGolden("django_basic", render(index))
    }
}
