package com.routegenie.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonScannerTest {
    private class Valid(val text: String, val nodes: Int)
    private class Invalid(val text: String, val code: JsonErrorCode, val offset: Long)

    private val valid = listOf(
        Valid("{}", 1), Valid("[]", 1), Valid("\"\"", 1), Valid("0", 1), Valid("-0", 1), Valid("1.5e10", 1),
        Valid("true", 1), Valid("false", 1), Valid("null", 1), Valid("\"a\"", 1),
        Valid("[1,2,3]", 4), Valid("{\"a\":1,\"b\":2}", 3), Valid("{\"a\":{\"b\":{\"c\":[1,[2,[3]]]}}}", 9),
        Valid("  [ 1 , 2 ]  ", 3), Valid("[\r\n\t1,\r\n\t2\r\n]", 3),
        Valid("\"\\u00e9\\ud83d\\ude00\"", 1), Valid("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"", 1), Valid("\"h\u00e9llo \u2713\"", 1),
        Valid("{\"a\":1,\"a\":2}", 3), Valid("{\"\":1}", 2),
        Valid("123456789012345678901234567890.123456789e+400", 1), Valid("[-1.0e-5,1E+2,0.0,0e0,10]", 6),
        Valid("[NaN,Infinity,-Infinity]", 4), Valid("[[],{},[{}]]", 5),
        Valid("{\"a\\\"b\":1}", 2), Valid("{\"\\u0041\":1}", 2), Valid("  42  ", 1),
        Valid("[1,-2,3.5,4e2]", 5), Valid("{\"a\":[]}", 2), Valid("\"{[}]\"", 1),
        Valid("{\"a\":null,\"b\":true,\"c\":false}", 4), Valid("[\"\\ud800\"]", 2),
        Valid("[\"\u00ff\"]", 2),
    )

    private val invalid = listOf(
        Invalid("", JsonErrorCode.EMPTY, 0), Invalid("   \n", JsonErrorCode.EMPTY, 0),
        Invalid("[", JsonErrorCode.UNEXPECTED_END, 1), Invalid("{", JsonErrorCode.UNEXPECTED_END, 1),
        Invalid("[1,", JsonErrorCode.UNEXPECTED_END, 3), Invalid("[1,]", JsonErrorCode.TRAILING_COMMA, 3),
        Invalid("{\"a\":1,}", JsonErrorCode.TRAILING_COMMA, 7), Invalid("{\"a\"}", JsonErrorCode.EXPECTED_COLON, 4),
        Invalid("{\"a\":}", JsonErrorCode.UNEXPECTED_CHAR, 5), Invalid("{a:1}", JsonErrorCode.EXPECTED_KEY, 1),
        Invalid("{'a':1}", JsonErrorCode.EXPECTED_KEY, 1), Invalid("[1 2]", JsonErrorCode.EXPECTED_COMMA_OR_END, 3),
        Invalid("[1}", JsonErrorCode.MISMATCHED_CLOSE, 2), Invalid("{\"a\":1]", JsonErrorCode.MISMATCHED_CLOSE, 6),
        Invalid("\"abc", JsonErrorCode.UNEXPECTED_END, 4), Invalid("\"a\\x\"", JsonErrorCode.BAD_ESCAPE, 3),
        Invalid("\"\\u12G4\"", JsonErrorCode.BAD_UNICODE_ESCAPE, 5), Invalid("\"a\tb\"", JsonErrorCode.CONTROL_CHAR, 2),
        Invalid("01", JsonErrorCode.BAD_NUMBER, 1), Invalid("1.", JsonErrorCode.BAD_NUMBER, 2),
        Invalid("1.e5", JsonErrorCode.BAD_NUMBER, 2), Invalid("-", JsonErrorCode.BAD_NUMBER, 1),
        Invalid("+1", JsonErrorCode.UNEXPECTED_CHAR, 0), Invalid(".5", JsonErrorCode.UNEXPECTED_CHAR, 0),
        Invalid("1e", JsonErrorCode.BAD_NUMBER, 2), Invalid("1e+", JsonErrorCode.BAD_NUMBER, 3),
        Invalid("tru", JsonErrorCode.BAD_LITERAL, 0), Invalid("nul", JsonErrorCode.BAD_LITERAL, 0),
        Invalid("truex", JsonErrorCode.BAD_LITERAL, 0), Invalid("[1]x", JsonErrorCode.TRAILING_CONTENT, 3),
        Invalid("[1] [2]", JsonErrorCode.TRAILING_CONTENT, 4), Invalid("{\"a\":1}}", JsonErrorCode.TRAILING_CONTENT, 7),
        Invalid("// c\n1", JsonErrorCode.UNEXPECTED_CHAR, 0), Invalid("[1,2,x]", JsonErrorCode.UNEXPECTED_CHAR, 5),
        Invalid("{\"a\":1 \"b\":2}", JsonErrorCode.EXPECTED_COMMA_OR_END, 7), Invalid("[,1]", JsonErrorCode.UNEXPECTED_CHAR, 1),
        Invalid("{,}", JsonErrorCode.EXPECTED_KEY, 1), Invalid("1.5.2", JsonErrorCode.BAD_NUMBER, 3),
        Invalid("12abc", JsonErrorCode.BAD_NUMBER, 2), Invalid("-a", JsonErrorCode.BAD_NUMBER, 1),
        Invalid("{\"a\":\"abc", JsonErrorCode.UNEXPECTED_END, 9), Invalid("{\"a\":1,\"b\"", JsonErrorCode.UNEXPECTED_END, 10),
        Invalid("[true false]", JsonErrorCode.EXPECTED_COMMA_OR_END, 6), Invalid("nulll", JsonErrorCode.BAD_LITERAL, 0),
        Invalid("[nul]", JsonErrorCode.BAD_LITERAL, 4), Invalid("[1,2,3", JsonErrorCode.UNEXPECTED_END, 6),
        Invalid("{\"a\":[1,{\"b\":", JsonErrorCode.UNEXPECTED_END, 13),
    )

    @Test
    fun `there are at least sixty scanner cases`() {
        assertTrue("${valid.size + invalid.size} cases", valid.size + invalid.size >= 60)
    }

    @Test
    fun `valid documents scan to the expected node count with no error`() {
        val failures = valid.mapNotNull { c ->
            val index = T.scan(c.text)
            val got = index.nodeCount - 1
            if (index.error != null || index.status != ScanStatus.COMPLETE || got != c.nodes) "'${c.text}': nodes $got (want ${c.nodes}) error ${index.error}" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `invalid documents report the exact code and offset`() {
        val failures = invalid.mapNotNull { c ->
            val index = T.scan(c.text)
            val e = index.error
            if (e == null || e.code != c.code || e.offset != c.offset || index.status != ScanStatus.FAILED) {
                "'${c.text.replace("\n", "\\n")}': got $e (want ${c.code} at ${c.offset})"
            } else {
                null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the result is the same through a file with a tiny read window`() {
        val failures = ArrayList<String>()
        for (c in valid.map { it.text } + invalid.map { it.text }) {
            val want = T.scan(c)
            for (window in intArrayOf(1, 2, 3, 5, 8, 13, 64)) {
                val got = T.scanFile(c, window)
                if (T.dump(got) != T.dump(want) || got.error?.toString() != want.error?.toString()) {
                    failures += "window $window '${c.replace("\n", "\\n")}': ${got.error} vs ${want.error}"
                }
                got.close()
            }
        }
        assertTrue(failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `values are read lazily from offsets`() {
        val index = T.scan("""{"name":"caf\u00e9","n":123456789012345678901234567890,"t":true,"nested":{"k":[1,"two"]}}""")
        val root = index.root
        val name = index.findMember(root, "name")
        assertEquals("caf\u00e9", index.stringValue(name))
        assertEquals("\"caf\\u00e9\"", index.rawValue(name))
        assertEquals("123456789012345678901234567890", index.stringValue(index.findMember(root, "n")))
        assertEquals(JsonKind.TRUE, index.kind(index.findMember(root, "t")))
        val k = index.findMember(index.findMember(root, "nested"), "k")
        assertEquals(2, index.childCount(k))
        assertEquals("two", index.stringValue(index.childAt(k, 1)))
        assertEquals("k", index.keyOf(k))
    }

    @Test
    fun `duplicate keys are all kept in order`() {
        val index = T.scan("""{"a":1,"b":2,"a":3}""")
        val keys = index.children(index.root).map { index.keyOf(it) }
        assertEquals(listOf("a", "b", "a"), keys)
        assertEquals("3", index.stringValue(index.children(index.root)[2]))
    }

    @Test
    fun `key escapes are decoded`() {
        val index = T.scan("""{"a\"b":1,"\u0041\n":2}""")
        assertEquals(listOf("a\"b", "A\n"), index.children(index.root).map { index.keyOf(it) })
    }

    @Test
    fun `a byte order mark is skipped and offsets start after it`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "{\"a\":1}".toByteArray()
        val index = T.scanBytes(bytes)
        assertNull(index.error)
        assertEquals(0, index.start(index.root))
        assertEquals(7, index.end(index.root))
    }

    @Test
    fun `utf16 documents with a byte order mark are converted`() {
        val text = "{\"k\":\"\u00e9\"}"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val index = T.scanBytes(bytes)
        assertNull(index.error)
        assertEquals("\u00e9", index.stringValue(index.findMember(index.root, "k")))
    }

    @Test
    fun `json lines become a list of documents`() {
        val index = T.scan("{\"a\":1}\n\n{\"a\":2}\r\n[3]\n")
        assertNull(index.error)
        assertTrue(index.lines)
        assertEquals(3, index.documentCount)
        assertEquals(0, index.root)
        assertEquals(3, index.childCount(0))
        assertEquals("$[1].a", JsonPath.format(index, index.findMember(index.childAt(0, 1), "a")))
    }

    @Test
    fun `a single document with trailing newlines is not json lines`() {
        val index = T.scan("[1,2]\n\n")
        assertFalse(index.lines)
        assertEquals(1, index.root)
        assertEquals(1, index.documentCount)
    }

    @Test
    fun `lines mode keeps one document as a list`() {
        val index = T.scan("[1,2]", ScanOptions(mode = DocumentMode.LINES))
        assertTrue(index.lines)
        assertEquals(0, index.root)
        assertEquals(1, index.childCount(0))
    }

    @Test
    fun `single mode rejects a second document`() {
        val e = T.scan("1\n2", ScanOptions(mode = DocumentMode.SINGLE)).error
        assertEquals(JsonErrorCode.TRAILING_CONTENT, e?.code)
        assertEquals(2L, e?.offset)
    }

    @Test
    fun `non finite numbers can be refused`() {
        assertEquals(JsonErrorCode.UNEXPECTED_CHAR, T.scan("NaN", ScanOptions(allowNonFinite = false)).error?.code)
        assertNotNull(T.scan("[-Infinity]", ScanOptions(allowNonFinite = false)).error)
    }

    @Test
    fun `depth and node limits stop the scan but keep the partial index`() {
        val deep = T.scan("[[[[1]]]]", ScanOptions(maxDepth = 3))
        assertEquals(JsonErrorCode.TOO_DEEP, deep.error?.code)
        assertEquals(3, deep.maxDepth)
        val many = T.scan("[1,2,3,4,5,6]", ScanOptions(maxNodes = 5))
        assertEquals(ScanStatus.LIMITED, many.status)
        assertEquals(JsonErrorCode.TOO_MANY_NODES, many.error?.code)
        assertEquals(5, many.nodeCount)
        // Open containers end where the scan stopped, so walking them works.
        assertTrue(many.subtreeSize(1) > 0)
        assertEquals(3, many.children(1).size)
    }

    @Test
    fun `a failed document keeps what was read before the problem`() {
        val index = T.scan("""{"a":[1,2,{"b":3},""")
        assertNotNull(index.error)
        val a = index.findMember(index.root, "a")
        assertEquals(3, index.childCount(a))
        assertTrue("open containers are closed at the error", index.subtreeSize(a) > 0 && index.subtreeSize(index.root) > 0)
    }

    @Test
    fun `very deep nesting scans without recursion`() {
        val n = 9_000
        val text = "[".repeat(n) + "1" + "]".repeat(n)
        val index = T.scan(text)
        assertNull(index.error)
        assertEquals(n, index.maxDepth)
        assertEquals(n + 2, index.nodeCount)
    }

    @Test
    fun `a flat array reaches any child in one step`() {
        val text = "[" + (0 until 1000).joinToString(",") + "]"
        val index = T.scan(text)
        assertTrue(index.isFlat(index.root))
        assertEquals("777", index.stringValue(index.childAt(index.root, 777)))
        assertEquals(777, index.ordinalOf(index.childAt(index.root, 777)))
    }

    @Test
    fun `an object array is walked by subtree sizes`() {
        val text = "[" + (0 until 100).joinToString(",") { """{"i":$it,"x":[1,2]}""" } + "]"
        val index = T.scan(text)
        assertFalse(index.isFlat(index.root))
        val c = index.childAt(index.root, 57)
        assertEquals("57", index.stringValue(index.findMember(c, "i")))
        assertEquals(57, index.ordinalOf(c))
        assertEquals(100, index.children(index.root).size)
    }

    @Test
    fun `a string preview reads only what it shows`() {
        val long = "x".repeat(100_000)
        val index = T.scan("""{"s":"$long\u00e9"}""")
        val p = index.stringPreview(index.findMember(index.root, "s"), 50)
        assertEquals(50, p.text.length)
        assertTrue(p.truncated)
        val short = T.scan("""{"s":"a\nb"}""")
        val q = short.stringPreview(short.findMember(short.root, "s"), 50)
        assertEquals("a\nb", q.text)
        assertFalse(q.truncated)
    }

    @Test
    fun `scanning can be cancelled and leaves a consistent index`() {
        val bytes = T.users(2_000_000)
        var seen = 0
        val index = JsonScanner.scan(JsonContent.Bytes(bytes), ScanOptions(), object : ScanListener {
            override fun isCancelled() = seen >= 2
            override fun onProgress(index: JsonIndex) {
                seen++
            }
        })
        assertEquals(ScanStatus.CANCELLED, index.status)
        assertNull(index.error)
        assertTrue(index.nodeCount in 2 until 200_000)
        assertTrue(index.subtreeSize(1) > 0)
    }

    @Test
    fun `progress is published early while the scan runs`() {
        val bytes = T.users(3_000_000)
        val counts = ArrayList<Int>()
        JsonScanner.scan(JsonContent.Bytes(bytes), ScanOptions(), object : ScanListener {
            override fun onProgress(index: JsonIndex) {
                counts += index.nodeCount
            }
        })
        assertTrue("first publish after few nodes: ${counts.take(3)}", counts.first() <= 2_000)
        assertTrue(counts.size >= 3)
    }

    @Test
    fun `error location gives line column and context`() {
        val text = "{\n  \"a\": 1,\n  \"b\": oops\n}"
        val index = T.scan(text)
        val e = index.error!!
        val loc = JsonErrors.locate(index.source, e.offset)
        assertEquals(3, loc.line)
        assertEquals(8, loc.column)
        assertEquals("  \"b\": oops", loc.lineText)
        assertEquals(loc.column - 1, loc.caretColumn)
    }

    @Test
    fun `bytes per node stay within the layout`() {
        val index = T.scanBytes(T.users(1_000_000))
        val perNode = index.memoryBytes().toDouble() / index.nodeCount
        assertTrue("bytes per node $perNode", perNode <= com.routegenie.Limits.JSON_MAX_BYTES_PER_NODE)
    }
}
