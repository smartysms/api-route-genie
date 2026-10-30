package com.routegenie.testmap

import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod.GET
import com.routegenie.model.HttpMethod.POST

/** The request a test call makes, read from literal arguments only; anything else is "not determined" and left empty. */
class RequestLiteralsTest : TestMapTestCase() {
    private val create = ep(POST, "/items/")
    private val items = ep(GET, "/items/")
    private var counter = 0

    private fun request(body: String, endpoint: com.routegenie.model.Endpoint = create): CallRequest {
        val index = link("tests/test_items_${counter++}.py", "def test_x(client):\n" + body.trimIndent().prependIndent("    ") + "\n", endpoint)
        return index.single().request
    }

    fun testJsonDictIsRenderedAsPrettyJson() {
        val r = request("""client.post("/items/", json={"name": "Foo", "price": 10.5, "tags": ["a", "b"], "ok": True, "none": None, "n": -3, "nested": {"k": 1}})""")
        assertEquals(
            BodyLiteral.Json(
                "{\n  \"name\": \"Foo\",\n  \"price\": 10.5,\n  \"tags\": [\n    \"a\",\n    \"b\"\n  ],\n  \"ok\": true,\n  \"none\": null,\n  \"n\": -3,\n  \"nested\": {\n    \"k\": 1\n  }\n}",
            ),
            r.body,
        )
        assertFalse(r.bodyNotDetermined)
    }

    fun testJsonStringsAreEscaped() {
        val r = request("""client.post("/items/", json={"a": "he said \"hi\"\n"})""")
        assertEquals(BodyLiteral.Json("{\n  \"a\": \"he said \\\"hi\\\"\\n\"\n}"), r.body)
    }

    fun testFStringAndConstantValuesInTheBodyEvaluate() {
        val index = link("tests/test_items.py", """
            NAME = "Foo"

            def test_x(client):
                client.post("/items/", json={"name": NAME, "label": f"{NAME}-1"})
        """, create)
        assertEquals(BodyLiteral.Json("{\n  \"name\": \"Foo\",\n  \"label\": \"Foo-1\"\n}"), index.single().request.body)
    }

    fun testJsonWithAVariableIsNotDetermined() {
        val r = request("""client.post("/items/", json={"name": some_name})""")
        assertNull(r.body)
        assertTrue(r.bodyNotDetermined)
    }

    fun testJsonFromAVariableIsNotDetermined() {
        val r = request("""
            payload = {"name": "x"}
            client.post("/items/", json=payload)
        """)
        assertNull(r.body)
        assertTrue(r.bodyNotDetermined)
    }

    fun testFormDataDictAndStringAndContent() {
        assertEquals(BodyLiteral.Form(listOf("a" to "1", "b" to "x")), request("""client.post("/items/", data={"a": 1, "b": "x"})""").body)
        assertEquals(BodyLiteral.Text("raw text"), request("""client.post("/items/", data="raw text")""").body)
        assertEquals(BodyLiteral.Text("<xml/>"), request("""client.post("/items/", content="<xml/>")""").body)
    }

    fun testJsonAndDataTogetherAreNotDetermined() {
        val r = request("""client.post("/items/", json={"a": 1}, data={"b": 2})""")
        assertNull(r.body)
        assertTrue(r.bodyNotDetermined)
    }

    fun testQueryFromParamsDictWithRepeatedValues() {
        val r = request("""client.get("/items/?x=1", params={"q": "a", "tag": ["t1", "t2"]})""", items)
        assertEquals(listOf("x" to "1", "q" to "a", "tag" to "t1", "tag" to "t2"), r.query)
    }

    fun testQueryFromANonLiteralDictIsNotDetermined() {
        assertNull(request("""client.get("/items/", params=query)""", items).query)
        assertNull(request("""client.get("/items/", params={"q": term})""", items).query)
    }

    fun testQueryWithAComputedUrlQueryIsNotDetermined() {
        assertNull(request("""client.get(f"/items/?q={term}")""", items).query)
    }

    fun testNoQueryAndNoHeadersAreEmptyNotUndetermined() {
        val r = request("""client.get("/items/")""", items)
        assertEquals(emptyList<Pair<String, String>>(), r.query)
        assertEquals(emptyList<Pair<String, String>>(), r.headers)
        assertNull(r.body)
        assertFalse(r.bodyNotDetermined)
    }

    fun testHeadersLiteralAndComputed() {
        assertEquals(listOf("X-Test" to "1"), request("""client.get("/items/", headers={"X-Test": "1"})""", items).headers)
        assertNull(request("""client.get("/items/", headers={"Authorization": f"Bearer {token}"})""", items).headers)
    }

    fun testDjangoHttpKeywordsBecomeHeaders() {
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class T(TestCase):
                def test_x(self):
                    self.client.get("/shop/", HTTP_X_API_KEY="k1", HTTP_ACCEPT="application/json")
        """, ep(GET, "/shop/", Framework.DJANGO))
        assertEquals(listOf("X-Api-Key" to "k1", "Accept" to "application/json"), index.single().request.headers)
    }

    fun testDjangoSecondPositionalIsQueryForGetAndBodyForPost() {
        val shop = ep(GET, "/shop/", Framework.DJANGO)
        val post = ep(POST, "/shop/", Framework.DJANGO)
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class T(TestCase):
                def test_get(self):
                    self.client.get("/shop/", {"page": 2})

                def test_post(self):
                    self.client.post("/shop/", {"name": "x"})
        """, shop, post)
        val byName = index.byEndpoint.values.flatten().associateBy { it.test.name }
        assertEquals(listOf("page" to "2"), byName.getValue("test_get").request.query)
        assertEquals(BodyLiteral.Form(listOf("name" to "x")), byName.getValue("test_post").request.body)
    }

    fun testDrfFormatJsonMakesTheDataJson() {
        val post = ep(POST, "/api/books/", Framework.DRF)
        val index = link("api/tests.py", """
            from rest_framework.test import APIClient

            def test_x():
                client = APIClient()
                client.post("/api/books/", {"title": "x"}, format="json")
        """, post)
        assertEquals(BodyLiteral.Json("{\n  \"title\": \"x\"\n}"), index.single().request.body)
    }

    fun testContentTypeIsKept() {
        val index = link("shop/tests.py", """
            from django.test import TestCase

            class T(TestCase):
                def test_x(self):
                    self.client.post("/shop/", data="a", content_type="text/plain")
        """, ep(POST, "/shop/", Framework.DJANGO))
        assertEquals("text/plain", index.single().request.contentType)
    }

    fun testHugeLiteralIsNotDetermined() {
        val items = (1..(com.routegenie.Limits.TEST_LITERAL_MAX_NODES + 10)).joinToString(", ") { "\"v$it\"" }
        val r = request("client.post(\"/items/\", json={\"a\": [$items]})")
        assertNull(r.body)
        assertTrue(r.bodyNotDetermined)
    }
}
