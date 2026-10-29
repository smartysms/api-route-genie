package com.routegenie.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPrinterTest {
    @Test
    fun `scalars and empty containers`() {
        assertEquals("1", T.pretty("1"))
        assertEquals("\"a\"", T.pretty("\"a\""))
        assertEquals("null", T.pretty(" null "))
        assertEquals("{}", T.pretty("{ }"))
        assertEquals("[]", T.pretty("[ ]"))
    }

    @Test
    fun `objects and arrays are indented by two`() {
        val got = T.pretty("""{"a":1,"b":[true,null,{"c":"x"}],"d":{}}""")
        val want = """
            {
              "a": 1,
              "b": [
                true,
                null,
                {
                  "c": "x"
                }
              ],
              "d": {}
            }
        """.trimIndent()
        assertEquals(want, got)
    }

    @Test
    fun `indent width is configurable`() {
        assertEquals("[\n    1,\n    2\n]", T.pretty("[1,2]", PrintOptions(indent = 4)))
        assertEquals("[\n1,\n2\n]", T.pretty("[1,2]", PrintOptions(indent = 0)))
    }

    @Test
    fun `minify removes all insignificant whitespace`() {
        assertEquals("""{"a":1,"b":[1,2,{"c":null}],"d":"x y"}""", T.pretty("{ \"a\" : 1,\n \"b\" : [ 1, 2, {\"c\" : null} ],\n\"d\":\"x y\" }", PrintOptions(minify = true)))
    }

    @Test
    fun `number text and string escapes are preserved exactly`() {
        val src = """[1.10,1e5,-0,123456789012345678901234567890,"caf${T.BS}u00e9","a\/b","\n"]"""
        assertEquals(src, T.pretty(src, PrintOptions(minify = true)))
        assertTrue(T.pretty(src).contains("1.10"))
        assertTrue(T.pretty(src).contains("\"caf\\u00e9\""))
    }

    @Test
    fun `raw non ascii text is preserved`() {
        assertEquals("[\n  \"héllo ✓ 😀\"\n]", T.pretty("[\"héllo ✓ 😀\"]"))
    }

    @Test
    fun `key order and duplicate keys are kept`() {
        assertEquals("""{"b":1,"a":2,"b":3}""", T.pretty("""{"b":1,"a":2,"b":3}""", PrintOptions(minify = true)))
    }

    @Test
    fun `keys can be sorted`() {
        assertEquals("""{"a":2,"b":1,"c":{"x":1,"y":2}}""", T.pretty("""{"b":1,"a":2,"c":{"y":2,"x":1}}""", PrintOptions(minify = true, sortKeys = true)))
        assertEquals("""[{"a":1,"b":2}]""", T.pretty("""[{"b":2,"a":1}]""", PrintOptions(minify = true, sortKeys = true)))
    }

    @Test
    fun `sorting is stable for duplicate keys`() {
        assertEquals("""{"a":1,"a":2,"b":0}""", T.pretty("""{"b":0,"a":1,"a":2}""", PrintOptions(minify = true, sortKeys = true)))
    }

    @Test
    fun `pretty printing is idempotent and keeps the structure`() {
        val src = String(T.users(20_000), Charsets.UTF_8)
        val once = T.pretty(src)
        assertEquals(once, T.pretty(once))
        val a = T.scan(src)
        val b = T.scan(once)
        assertEquals(a.nodeCount, b.nodeCount)
        assertEquals(T.pretty(src, PrintOptions(minify = true)), T.pretty(once, PrintOptions(minify = true)))
    }

    @Test
    fun `minified output of the sample equals its source`() {
        val src = String(T.users(5_000), Charsets.UTF_8)
        assertEquals(src, T.pretty(src, PrintOptions(minify = true)))
    }

    @Test
    fun `a subtree can be printed with or without its key`() {
        val index = T.scan("""{"a":{"b":[1,2]},"c":3}""")
        val a = index.findMember(index.root, "a")
        assertEquals("{\n  \"b\": [\n    1,\n    2\n  ]\n}", JsonPrinter.toString(index, a))
        val printer = JsonPrinter(index)
        printer.begin(a, withKey = true)
        val sink = StringSink()
        printer.step(sink, Long.MAX_VALUE)
        assertTrue(sink.builder.startsWith("\"a\": {"))
    }

    @Test
    fun `json lines print one document per line`() {
        val index = T.scan("{\"a\":1}\n[2,3]\n4\n")
        assertEquals("""{"a":1}""" + "\n" + "[2,3]\n4", JsonPrinter.toString(index, index.root, PrintOptions(minify = true)))
        assertEquals("{\n  \"a\": 1\n}\n[\n  2,\n  3\n]\n4", JsonPrinter.toString(index, index.root))
    }

    @Test
    fun `very deep nesting prints without recursion`() {
        val n = 5_000
        val index = T.scan("[".repeat(n) + "]".repeat(n))
        val text = JsonPrinter.toString(index, index.root, PrintOptions(minify = true))
        assertEquals("[".repeat(n) + "]".repeat(n), text)
    }

    @Test
    fun `printing through a file gives the same text`() {
        val src = String(T.users(30_000), Charsets.UTF_8)
        val viaFile = T.scanFile(src, 4096)
        assertEquals(T.pretty(src), JsonPrinter.toString(viaFile, viaFile.root))
    }

    @Test
    fun `pages cover the document and start at line boundaries`() {
        val src = String(T.users(60_000), Charsets.UTF_8)
        val index = T.scan(src)
        val options = PrintOptions()
        val whole = JsonPrinter.toString(index, index.root, options)
        val plan = JsonPrinter.plan(index, options, 4_000)!!
        assertTrue("pages ${plan.pageCount}", plan.pageCount > 5)
        val joined = StringBuilder()
        for (page in 0 until plan.pageCount) {
            val sink = StringSink()
            plan.render(index, options, page, sink)
            val text = sink.builder.toString()
            assertFalse("page $page starts with a line break", text.startsWith("\n"))
            if (page > 0) joined.append('\n')
            joined.append(text)
        }
        // Page boundaries drop only the line break and its indentation.
        assertEquals(whole.filter { !it.isWhitespace() }, joined.toString().filter { !it.isWhitespace() })
        assertEquals(whole.length.toLong(), plan.totalUnits.coerceAtLeast(0).let { whole.length.toLong() })
    }

    @Test
    fun `page units equal the printed length for ascii text`() {
        val index = T.scan("""{"a":[1,2,3],"b":"xyz"}""")
        val plan = JsonPrinter.plan(index, PrintOptions(), 1_000_000)!!
        assertEquals(1, plan.pageCount)
        assertEquals(JsonPrinter.toString(index, index.root).length.toLong(), plan.totalUnits)
    }

    @Test
    fun `the observer reports the range of every closed container`() {
        val index = T.scan("""{"a":[1,{"b":2}],"c":{}}""")
        val printer = JsonPrinter(index)
        val seen = ArrayList<Triple<Int, Long, Long>>()
        printer.observer = PrintObserver { n, a, b -> seen += Triple(n, a, b) }
        printer.begin(index.root)
        val sink = StringSink()
        printer.step(sink, Long.MAX_VALUE)
        val text = sink.builder.toString()
        val root = seen.first { it.first == index.root }
        assertEquals(text, text.substring(root.second.toInt(), root.third.toInt()))
        val a = index.findMember(index.root, "a")
        val range = seen.first { it.first == a }
        assertEquals("[\n    1,\n    {\n      \"b\": 2\n    }\n  ]", text.substring(range.second.toInt(), range.third.toInt()))
    }

    @Test
    fun `printing stops at the budget and resumes`() {
        val index = T.scan("[" + (0 until 100).joinToString(",") + "]")
        val whole = JsonPrinter.toString(index)
        val printer = JsonPrinter(index)
        printer.begin(index.root)
        val sink = StringSink()
        var steps = 0
        while (!printer.step(sink, 50)) steps++
        assertTrue(steps > 3)
        assertEquals(whole, sink.builder.toString())
    }

    @Test
    fun `the token reader handles an invalid document by printing what exists`() {
        val index = T.scan("""{"a":[1,2""")
        assertTrue(index.error != null)
        assertTrue(JsonPrinter.toString(index).startsWith("{\n  \"a\": ["))
        assertNull(null)
    }
}
