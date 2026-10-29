package com.routegenie.frameworks.django

import com.routegenie.model.PathParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure text rules behind path() and re_path(); no IDE needed. */
class RouteTextTest {
    @Test
    fun `plain path text is unchanged`() {
        val piece = RouteText.fromPath("articles/2003/")
        assertEquals("articles/2003/", piece.text)
        assertTrue(piece.params.isEmpty())
    }

    @Test
    fun `an unclosed angle bracket is literal text`() {
        assertEquals("a<b", RouteText.fromPath("a<b").text)
    }

    @Test
    fun `unknown segments pass through a path piece`() {
        assertEquals("{?}items/{pk}", RouteText.fromPath("{?}items/<int:pk>").text)
    }

    @Test
    fun `anchors are removed from a regex`() {
        assertEquals("a/b/", RouteText.fromRegex("^a/b/$").text)
    }

    @Test
    fun `escaped punctuation is unescaped`() {
        val piece = RouteText.fromRegex("""^file\.txt$""")
        assertEquals("file.txt", piece.text)
        assertFalse(piece.unsupported)
    }

    @Test
    fun `named groups become parameters even with nested groups and classes`() {
        val piece = RouteText.fromRegex("""^(?P<year>(?:19|20)\d{2})/(?P<slug>[\w-]+)/$""")
        assertEquals("{year}/{slug}/", piece.text)
        assertEquals(listOf(PathParam("year"), PathParam("slug")), piece.params)
        assertFalse(piece.unsupported)
    }

    @Test
    fun `an unnamed group cannot be shown and is flagged`() {
        val piece = RouteText.fromRegex("""^(\d+)/x/$""")
        assertEquals("{?}/x/", piece.text)
        assertTrue(piece.unsupported)
    }

    @Test
    fun `alternation in a group is flagged`() {
        val piece = RouteText.fromRegex("^(en|fr)/about/$")
        assertEquals("{?}/about/", piece.text)
        assertTrue(piece.unsupported)
    }

    @Test
    fun `an optional trailing slash is approximated`() {
        val piece = RouteText.fromRegex("^opt/?$")
        assertEquals("opt/", piece.text)
        assertTrue(piece.unsupported)
    }

    @Test
    fun `an optional named group is flagged but keeps its parameter`() {
        val piece = RouteText.fromRegex("^p/(?P<page>[0-9]+)?$")
        assertEquals("p/{page}", piece.text)
        assertTrue(piece.unsupported)
    }

    @Test
    fun `a character class and a wildcard become unknown segments`() {
        val piece = RouteText.fromRegex("^a/[a-z]+/.*$")
        assertEquals("a/{?}/{?}", piece.text)
        assertTrue(piece.unsupported)
    }

    @Test
    fun `a shorthand class outside a group is flagged`() {
        assertEquals("n/{?}", RouteText.fromRegex("""^n/\d+$""").text)
    }

    @Test
    fun `malformed input terminates`() {
        listOf("(", "((", "[", "[a", "{", "a{2", "\\", "(?P<x", ")", "^$").forEach { RouteText.fromRegex(it) }
    }

    @Test
    fun `the router lookup group is recognized as one parameter`() {
        val piece = RouteText.fromRegex("^items/(?P<pk>[^/.]+)/$")
        assertEquals("items/{pk}/", piece.text)
        assertFalse(piece.unsupported)
    }

    @Test
    fun `module names follow the path`() {
        assertEquals("shop.views", DjangoModules.moduleOf("shop/views.py"))
        assertEquals("shop", DjangoModules.moduleOf("shop/__init__.py"))
        assertEquals("shop", DjangoModules.packageOf("shop/views.py"))
        assertEquals("shop", DjangoModules.packageOf("shop/__init__.py"))
        assertEquals("", DjangoModules.packageOf("urls.py"))
    }
}
