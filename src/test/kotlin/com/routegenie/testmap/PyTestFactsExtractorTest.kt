package com.routegenie.testmap

import com.routegenie.Limits
import com.routegenie.discovery.core.Lit
import com.routegenie.discovery.core.Opaque
import com.routegenie.frameworks.common.RefFact
import com.routegenie.testmap.psi.PyTestFactsExtractor
import com.routegenie.testing.PythonPlatformTestCase

/** What the extractor records for each form, before anything is resolved: the facts are a function of the text of one file. */
class PyTestFactsExtractorTest : PythonPlatformTestCase() {
    private var n = 0

    private fun extract(text: String): TestFileFacts? = PyTestFactsExtractor.extract(pythonFile("tests/test_f${n++}.py", text.trimIndent() + "\n"))

    private fun ref(vararg parts: String) = RefFact(parts.toList())

    fun testAFileWithoutClientsOrTestsYieldsNothing() {
        assertNull(extract("x = 1\n\ndef helper():\n    return 2\n"))
    }

    fun testCallsKeepReceiverMethodUrlScopeAndAssignment() {
        val facts = extract("""
            def test_a(client):
                r = client.get("/items/")
                assert r.status_code == 200
        """)!!
        val call = facts.calls.single()
        assertEquals(ref("client"), call.receiver)
        assertNull(call.inlineCtor)
        assertEquals("get", call.method)
        assertEquals(UrlFact.Text(Lit("/items/")), call.url)
        assertEquals("test_a", call.scope)
        assertEquals("r", call.assignedTo)
        assertEquals(listOf(StatusAssertFact(call.pos.offset, 200, "test_a", facts.asserts.single().pos)), facts.asserts)
    }

    fun testUrlFactKinds() {
        val facts = extract("""
            def test_a(client, x):
                client.get("/a")
                client.get(url)
                client.get(self.url)
                client.get(reverse("n:m", args=[1, 2]))
                client.get(url_for("bp.view", id=1, _external=True))
                client.get(app.url_path_for("name", **kw))
                client.get(helper("x"))
                client.get(3)
                client.get(f"{reverse('n')}?a=1")
                client.get(reverse("n") + "?b=2")
        """)!!
        val urls = facts.calls.map { it.url }
        assertEquals(UrlFact.Text(Lit("/a")), urls[0])
        assertEquals(UrlFact.Name(ref("url")), urls[1])
        assertEquals(UrlFact.Name(ref("self", "url")), urls[2])
        val reverse = urls[3] as UrlFact.Named
        assertEquals(ref("reverse"), reverse.function)
        assertEquals(Lit("n:m"), reverse.name)
        assertEquals(2, reverse.positional)
        assertEquals(emptyList<String>(), reverse.keys)
        val urlFor = urls[4] as UrlFact.Named
        assertEquals(listOf("id"), urlFor.keys)
        assertNull("**kw makes the names unknown", (urls[5] as UrlFact.Named).keys)
        assertEquals(UrlFact.Text(Opaque), urls[6])
        assertEquals(UrlFact.Dynamic, urls[7])
        assertEquals(Lit("n"), (urls[8] as UrlFact.Named).name)
        assertNotNull((urls[8] as UrlFact.Named).suffix)
        assertNotNull((urls[9] as UrlFact.Named).suffix)
    }

    fun testReverseKwargsKeysAndNonLiteralArguments() {
        val facts = extract("""
            def test_a(self):
                self.client.get(reverse("a", kwargs={"pk": 1, "slug": "x"}))
                self.client.get(reverse("a", kwargs=kw))
                self.client.get(reverse("a", args=ids))
        """)!!
        val named = facts.calls.map { it.url as UrlFact.Named }
        assertEquals(listOf("pk", "slug"), named[0].keys)
        assertNull(named[1].keys)
        assertEquals(-1, named[2].positional)
    }

    fun testInlineConstructorAndChainedReceivers() {
        val facts = extract("""
            def test_a(self):
                TestClient(app).get("/a")
                self.client.post("/b")
                self.api.session.get("/c")
                make().get("/d")
        """)!!
        assertEquals(listOf("get", "post", "get"), facts.calls.map { it.method })
        assertEquals(ref("TestClient"), facts.calls[0].inlineCtor)
        assertEquals(ref("self", "client"), facts.calls[1].receiver)
        assertEquals(ref("self", "api", "session"), facts.calls[2].receiver)
    }

    fun testGenericRequestKeepsVerbAndUrlSlots() {
        val facts = extract("""
            def test_a(client):
                client.request("PUT", "/a")
                client.request(method="DELETE", url="/b")
                client.open("/c", method="PATCH")
                client.generic("HEAD", "/d")
        """)!!
        assertEquals(listOf(Lit("PUT"), Lit("DELETE"), Lit("PATCH"), Lit("HEAD")), facts.calls.map { it.verb })
        assertEquals(listOf("/a", "/b", "/c", "/d"), facts.calls.map { ((it.url as UrlFact.Text).expr as Lit).text })
    }

    fun testACallWithoutAUrlIsNoRequest() {
        val facts = extract("""
            def test_a(client, d):
                d.get()
                client.get()
                client.post(json={"a": 1})
        """)
        assertTrue(facts == null || facts.calls.isEmpty())
    }

    fun testBindingsOfEveryForm() {
        val facts = extract("""
            client = TestClient(app)

            def test_a(tmp):
                with TestClient(app, base_url="http://x/api") as c:
                    pass
                async with AsyncClient(transport=ASGITransport(app=app)) as ac:
                    pass
                local = app.test_client()
                plain = dict()

            class T(TestCase):
                api = APIClient()

                def setUp(self):
                    self.client = Client()

                def test_b(self):
                    self.other = APIClient()
        """)!!
        val byName = facts.bindings.associateBy { it.name }
        assertEquals("", byName.getValue("client").scope)
        assertEquals("test_a", byName.getValue("c").scope)
        assertEquals(ref("TestClient"), byName.getValue("c").ctor)
        assertNotNull(byName.getValue("c").baseUrl)
        assertEquals(setOf("transport"), byName.getValue("ac").keywords)
        assertEquals(ref("ASGITransport"), byName.getValue("ac").keywordCallees["transport"])
        assertEquals(ref("app", "test_client"), byName.getValue("local").ctor)
        assertFalse("plain" in byName)
        assertEquals("T", byName.getValue("self.api").scope)
        assertEquals("T", byName.getValue("self.client").scope)
        assertEquals("T.test_b", byName.getValue("self.other").scope)
    }

    fun testClassesFunctionsMarksAndFixtures() {
        val facts = extract("""
            import pytest

            @pytest.mark.skip
            class TestA(Base, unittest.TestCase):
                def __init__(self):
                    pass

                @pytest.mark.xfail
                def test_x(self, client):
                    client.get("/a")

            @pytest.fixture(scope="module")
            def web():
                c = TestClient(app)
                yield c

            @pytest.fixture
            def other():
                return app.test_client()

            def test_y(client):
                client.get("/b")
        """)!!
        val cls = facts.classes.single()
        assertEquals(listOf(ref("Base"), ref("unittest", "TestCase")), cls.bases)
        assertTrue(cls.hasInit)
        assertEquals(TestMark.SKIPPED, cls.mark)
        val byName = facts.functions.associateBy { it.qualified }
        assertEquals(TestMark.XFAIL, byName.getValue("TestA.test_x").mark)
        assertEquals("TestA", byName.getValue("TestA.test_x").className)
        assertTrue(byName.getValue("web").isFixture)
        assertEquals(listOf("c"), byName.getValue("web").returned)
        assertEquals(listOf(ref("app", "test_client")), byName.getValue("other").returnedCtors)
        assertEquals(listOf("client"), byName.getValue("test_y").params)
        assertEquals(2, facts.testLikeFunctions)
    }

    fun testLiteralRequestPartsAreKept() {
        val facts = extract("""
            def test_a(client):
                client.post("/a", json={"k": [1, 2.5, None, True]}, params={"q": "x"}, headers={"H": "v"}, content_type="text/plain", HTTP_X="1")
                client.post("/b", {"form": 1})
        """)!!
        val parts = facts.calls[0].parts.associateBy { it.name }
        assertEquals(setOf("json", "params", "headers", "content_type", "HTTP_X"), parts.keys)
        val json = parts.getValue("json").value as LitValue.Dict
        assertEquals(LitValue.Seq(listOf(LitValue.Num("1"), LitValue.Num("2.5"), LitValue.None, LitValue.Bool(true))), json.entries.single().second)
        assertEquals("", facts.calls[1].parts.single().name)
    }

    fun testNonLiteralValuesAreUnknownNotGuessed() {
        val facts = extract("""
            def test_a(client):
                client.post("/a", json={"k": compute(), "m": [x for x in y]}, headers=dict(a=1))
        """)!!
        val parts = facts.calls.single().parts.associateBy { it.name }
        val entries = (parts.getValue("json").value as LitValue.Dict).entries
        assertEquals(LitValue.Unknown, entries[0].second)
        assertEquals(LitValue.Unknown, entries[1].second)
        assertEquals(LitValue.Unknown, parts.getValue("headers").value)
    }

    fun testAssignmentsRecordBranchAndClassScope() {
        val facts = extract("""
            class T(TestCase):
                url = "/a/"

                def setUp(self):
                    self.second = reverse("n")

                def test_x(self, flag):
                    u = "/b/"
                    if flag:
                        u = "/c/"
                    self.client.get(u)
        """)!!
        val byKey = facts.assigns.groupBy { it.scope to it.name }
        assertEquals(1, byKey.getValue("T" to "self.url").size)
        assertEquals(1, byKey.getValue("T" to "self.second").size)
        val local = byKey.getValue("T.test_x" to "u")
        assertEquals(listOf(false, true), local.map { it.branch })
    }

    fun testStatusAssertionForms() {
        val facts = extract("""
            from starlette import status

            def test_a(client):
                assert client.get("/a").status_code == 404
                r = client.get("/b")
                assert status.HTTP_201_CREATED == r.status_code
                self.assertEqual(r.status_code, 500)
                assert r.status_code >= 400
                assert r.status_code == code
        """)!!
        assertEquals(listOf(404, 201, 500), facts.asserts.map { it.code })
        assertEquals(facts.calls[0].pos.offset, facts.asserts[0].callOffset)
        assertEquals(facts.calls[1].pos.offset, facts.asserts[1].callOffset)
    }

    fun testCallsPerFileAreCapped() {
        val body = (0 until Limits.TEST_CALLS_MAX_PER_FILE + 20).joinToString("\n") { "    client.get(\"/a$it\")" }
        val facts = extract("def test_many(client):\n$body\n")!!
        assertEquals(Limits.TEST_CALLS_MAX_PER_FILE, facts.calls.size)
    }

    fun testNestedFunctionsBelongToTheOutermostOne() {
        val facts = extract("""
            def test_a(client):
                def inner():
                    return client.get("/a")
                inner()
        """)!!
        assertEquals("test_a", facts.calls.single().scope)
        assertEquals(listOf("test_a"), facts.functions.map { it.qualified })
    }

    fun testFactsAreEqualForEqualText() {
        val text = "def test_a(client):\n    client.get(\"/a\")\n"
        assertEquals(extract(text), extract(text))
    }
}
