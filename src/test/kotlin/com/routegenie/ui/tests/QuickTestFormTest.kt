package com.routegenie.ui.tests

import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.SourceRef
import com.routegenie.testmap.BodyLiteral
import com.routegenie.testmap.CallOrigin
import com.routegenie.testmap.CallRequest
import com.routegenie.testmap.ProjectTest
import com.routegenie.testmap.TestCall
import com.routegenie.testmap.TestCommands
import com.routegenie.testmap.TestMark
import com.routegenie.testmap.TestStyle
import com.routegenie.ui.model.TestVerdict
import com.routegenie.ui.model.Verdict
import com.routegenie.ui.request.BodyType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests of the quick-test form, the verdict and the command line. */
class QuickTestFormTest {
    private fun endpoint(method: HttpMethod, path: String, vararg params: PathParam, framework: Framework = Framework.FASTAPI) =
        Endpoint(method, path, framework, Handler("h", "app.h"), SourceRef("app.py", 0, 1), pathParams = params.toList())

    private fun test(name: String = "test_x", cls: String? = null, style: TestStyle = TestStyle.PYTEST, file: String = "tests/test_items.py") =
        ProjectTest(file + "::" + (cls?.let { "$it::" } ?: "") + name, file, cls, name, style, TestMark.NONE, SourceRef(file, 0, 1))

    private fun call(path: String, verb: HttpMethod = HttpMethod.GET, request: CallRequest = CallRequest.NONE, expected: Int? = null) =
        TestCall(test(), verb, path, SourceRef("tests/test_items.py", 5, 2), CallOrigin.DIRECT, false, null, expected, request)

    @Test
    fun `path values come from the literal path and a computed segment stays empty`() {
        val e = endpoint(HttpMethod.GET, "/users/{user_id}/posts/{post_id}", PathParam("user_id", "int"), PathParam("post_id"))
        val built = QuickTestForm.build(e, call("/users/5/posts/{?}"))
        assertEquals("5", built.form.pathValues["user_id"])
        assertEquals("", built.form.pathValues["post_id"])
        assertEquals(listOf(NotDetermined.PATH), built.notDetermined)
    }

    @Test
    fun `a call by route name leaves every path value empty`() {
        val e = endpoint(HttpMethod.GET, "/shop/item/{pk}/", PathParam("pk", "int"), framework = Framework.DJANGO)
        val built = QuickTestForm.build(e, call("/shop/item/{pk}/"))
        assertEquals("", built.form.pathValues["pk"])
    }

    @Test
    fun `values are percent decoded and a trailing slash difference does not hide them`() {
        val e = endpoint(HttpMethod.GET, "/tags/{name}/", PathParam("name"))
        assertEquals("a b", QuickTestForm.build(e, call("/tags/a%20b/")).form.pathValues["name"])
        assertEquals("x", QuickTestForm.build(e, call("/tags/x")).form.pathValues["name"])
    }

    @Test
    fun `a path parameter can span segments`() {
        val e = endpoint(HttpMethod.GET, "/files/{p}", PathParam("p", "path"))
        assertEquals("a/b/c.txt", QuickTestForm.build(e, call("/files/a/b/c.txt")).form.pathValues["p"])
    }

    @Test
    fun `an endpoint that takes any verb gets the verb of the call`() {
        val e = endpoint(HttpMethod.ANY, "/ping")
        assertEquals(HttpMethod.PATCH, QuickTestForm.build(e, call("/ping", HttpMethod.PATCH)).form.method)
        val fixed = endpoint(HttpMethod.POST, "/ping")
        assertEquals(HttpMethod.POST, QuickTestForm.build(fixed, call("/ping", HttpMethod.POST)).form.method)
    }

    @Test
    fun `query headers and a json body fill the form`() {
        val request = CallRequest(listOf("q" to "1"), listOf("X-A" to "b"), BodyLiteral.Json("{\n  \"a\": 1\n}"), false)
        val form = QuickTestForm.build(endpoint(HttpMethod.POST, "/items"), call("/items", HttpMethod.POST, request)).form
        assertEquals(listOf("q" to "1"), form.query.map { it.name to it.value })
        assertEquals(listOf("X-A" to "b"), form.headers.map { it.name to it.value })
        assertEquals(BodyType.JSON, form.bodyKind)
        assertEquals("{\n  \"a\": 1\n}", form.bodyText)
    }

    @Test
    fun `a form body and a text body fill their own kinds`() {
        val form = QuickTestForm.build(
            endpoint(HttpMethod.POST, "/items"),
            call("/items", HttpMethod.POST, CallRequest(emptyList(), emptyList(), BodyLiteral.Form(listOf("a" to "1", "b" to "2")), false)),
        ).form
        assertEquals(BodyType.FORM, form.bodyKind)
        assertEquals(listOf("a" to "1", "b" to "2"), form.formFields.map { it.name to it.value })
        val text = QuickTestForm.build(endpoint(HttpMethod.POST, "/items"), call("/items", HttpMethod.POST, CallRequest(emptyList(), emptyList(), BodyLiteral.Text("raw"), false))).form
        assertEquals(BodyType.TEXT, text.bodyKind)
        assertEquals("raw", text.bodyText)
    }

    @Test
    fun `parts that are not determined are named and left empty`() {
        val built = QuickTestForm.build(endpoint(HttpMethod.POST, "/items"), call("/items", HttpMethod.POST, CallRequest(null, null, null, true)))
        assertEquals(listOf(NotDetermined.QUERY, NotDetermined.HEADERS, NotDetermined.BODY), built.notDetermined)
        assertEquals(BodyType.NONE, built.form.bodyKind)
        assertEquals("", built.form.bodyText)
    }

    @Test
    fun `the verdict compares the answer with what the test asserts`() {
        assertEquals(Verdict.MATCHES, TestVerdict.of(201, 201))
        assertEquals(Verdict.DIFFERS, TestVerdict.of(201, 422))
        assertEquals(Verdict.UNASSERTED, TestVerdict.of(null, 200))
        assertEquals(Verdict.NO_ANSWER, TestVerdict.of(200, null))
        assertEquals(Verdict.NO_ANSWER, TestVerdict.of(200, 0))
    }

    @Test
    fun `commands follow the style of the test`() {
        assertEquals("pytest tests/test_items.py::TestItems::test_a", TestCommands.of(test("test_a", "TestItems")))
        assertEquals("pytest tests/test_items.py::test_a", TestCommands.of(test("test_a", style = TestStyle.UNITTEST)))
        assertEquals("pytest \"tests/my tests/test_a.py::test_a\"", TestCommands.of(test("test_a", file = "tests/my tests/test_a.py")))
        val django = test("test_index", "ShopTests", TestStyle.DJANGO_TESTCASE, "shop/tests.py")
        assertEquals("python manage.py test shop.tests.ShopTests.test_index", TestCommands.of(django))
    }
}
