package com.routegenie.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDiffTest {
    private class Case(val name: String, val left: String, val right: String, val expected: List<String>, val options: DiffOptions = DiffOptions())

    private val cases = listOf(
        Case("identical scalars", "1", "1", emptyList()),
        Case("changed number", """{"a":1}""", """{"a":2}""", listOf("~ $.a")),
        Case("changed string", """{"a":"x"}""", """{"a":"y"}""", listOf("~ $.a")),
        Case("flipped boolean", """{"a":true}""", """{"a":false}""", listOf("~ $.a")),
        Case("null to number", """{"a":null}""", """{"a":1}""", listOf("T $.a")),
        Case("number to string", """{"a":1}""", """{"a":"1"}""", listOf("T $.a")),
        Case("object to array", """{"a":{}}""", """{"a":[]}""", listOf("T $.a")),
        Case("scalar to object at the root", "1", "{}", listOf("T $")),
        Case("added key", """{"a":1}""", """{"a":1,"b":2}""", listOf("+ $.b")),
        Case("removed key", """{"a":1,"b":2}""", """{"a":1}""", listOf("- $.b")),
        Case("nested change", """{"a":{"b":{"c":1}}}""", """{"a":{"b":{"c":2}}}""", listOf("~ $.a.b.c")),
        Case("array element by index", """{"a":[1,2,3]}""", """{"a":[1,9,3]}""", listOf("~ $.a[1]")),
        Case("longer array", """{"a":[1,2]}""", """{"a":[1,2,3,4]}""", listOf("+ $.a[2]", "+ $.a[3]")),
        Case("shorter array", """{"a":[1,2,3]}""", """{"a":[1]}""", listOf("- $.a[1]", "- $.a[2]")),
        Case("empty versus filled array", "[]", "[1]", listOf("+ $[0]")),
        Case("key order ignored", """{"a":1,"b":2}""", """{"b":2,"a":1}""", emptyList()),
        Case("key order counted", """{"a":1,"b":2}""", """{"b":2,"a":1}""", listOf("O $"), DiffOptions(ignoreKeyOrder = false)),
        Case("same order is not a difference", """{"a":1,"b":2}""", """{"a":1,"b":2}""", emptyList(), DiffOptions(ignoreKeyOrder = false)),
        Case("ignored key", """{"a":1,"updatedAt":"x"}""", """{"a":1,"updatedAt":"y"}""", emptyList(), DiffOptions(ignoreKeys = setOf("updatedAt"))),
        Case("ignored key on one side only", """{"a":1,"id":1}""", """{"a":1}""", emptyList(), DiffOptions(ignoreKeys = setOf("id"))),
        Case("ignored path with wildcard", """{"items":[{"ts":1,"v":1},{"ts":2,"v":2}]}""", """{"items":[{"ts":9,"v":1},{"ts":8,"v":3}]}""", listOf("~ $.items[1].v"), DiffOptions(ignorePaths = listOf("$.items[*].ts"))),
        Case("ignored exact path", """{"a":1,"b":2}""", """{"a":5,"b":2}""", emptyList(), DiffOptions(ignorePaths = listOf("$.a"))),
        Case("ignored wildcard keys", """{"x":{"secret":1,"v":1},"y":{"secret":2,"v":1}}""", """{"x":{"secret":3,"v":1},"y":{"secret":4,"v":1}}""", emptyList(), DiffOptions(ignorePaths = listOf("$.*.secret"))),
        Case("one equals one point zero", """{"a":1}""", """{"a":1.0}""", emptyList()),
        Case("exponent form equals", """{"a":1e2}""", """{"a":100}""", emptyList()),
        Case("numeric equality off", """{"a":1}""", """{"a":1.0}""", listOf("~ $.a"), DiffOptions(numericEquality = false)),
        Case("big numbers differ", """{"a":123456789012345678901234567891}""", """{"a":123456789012345678901234567890}""", listOf("~ $.a")),
        Case("escaped and raw text are equal", "{\"a\":\"caf\\u00e9\"}", "{\"a\":\"café\"}", emptyList()),
        Case("different escapes of a line feed", """{"a":"x\ny"}""", """{"a":"x\u000ay"}""", emptyList()),
        Case("whitespace is not a difference", """{ "a" : [ 1 , 2 ] }""", """{"a":[1,2]}""", emptyList()),
        Case("empty objects", "{}", "{}", emptyList()),
        Case("keyed array reordered", """[{"id":1,"v":1},{"id":2,"v":2}]""", """[{"id":2,"v":2},{"id":1,"v":1}]""", emptyList(), DiffOptions(arrayKey = "id")),
        Case("keyed array changed element", """[{"id":1,"v":1},{"id":2,"v":2}]""", """[{"id":2,"v":2},{"id":1,"v":9}]""", listOf("~ $[id=1].v"), DiffOptions(arrayKey = "id")),
        Case("keyed array added and removed", """[{"id":1},{"id":2}]""", """[{"id":2},{"id":3}]""", listOf("- $[id=1]", "+ $[id=3]"), DiffOptions(arrayKey = "id")),
        Case("by index the same arrays differ", """[{"id":1,"v":1},{"id":2,"v":2}]""", """[{"id":2,"v":2},{"id":1,"v":1}]""", listOf("~ $[0].id", "~ $[0].v", "~ $[1].id", "~ $[1].v")),
        Case("keyed array falls back to position without the key", """[1,2,3]""", """[1,5,3]""", listOf("~ $[1]"), DiffOptions(arrayKey = "id")),
        Case("keyed array with string ids", """[{"id":"a","v":1}]""", """[{"id":"a","v":2}]""", listOf("~ $[id=a].v"), DiffOptions(arrayKey = "id")),
        Case("duplicate keys matched in order", """{"a":1,"a":2}""", """{"a":1,"a":3}""", listOf("~ $.a")),
        Case("duplicate key added", """{"a":1}""", """{"a":1,"a":2}""", listOf("+ $.a")),
        Case("type change inside an array", """[1,"x"]""", """[1,2]""", listOf("T $[1]")),
        Case("added subtree is one difference", """{"a":1}""", """{"a":1,"b":{"x":[1,2,3]}}""", listOf("+ $.b")),
        Case("json lines documents", "{\"a\":1}\n{\"a\":2}\n", "{\"a\":1}\n{\"a\":3}\n", listOf("~ $[1].a")),
        Case("root scalar changed", "\"a\"", "\"b\"", listOf("~ $")),
        Case("changes in several places", """{"a":1,"b":[1,2],"c":{"d":true}}""", """{"a":2,"b":[1,3],"c":{"d":false}}""", listOf("~ $.a", "~ $.b[1]", "~ $.c.d")),
    )

    @Test
    fun `there are at least forty diff cases`() {
        assertTrue("${cases.size} cases", cases.size >= 40)
    }

    @Test
    fun `every case produces exactly the expected differences`() {
        val failures = cases.mapNotNull { c ->
            val got = T.summary(T.diff(c.left, c.right, c.options))
            if (got != c.expected) "${c.name}: got $got want ${c.expected}" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `identical documents report identical`() {
        val r = T.diff("""{"a":[1,2]}""", """{ "a": [1, 2] }""")
        assertTrue(r.identical)
        assertEquals(0, r.totalDifferences)
    }

    @Test
    fun `counts are kept per kind`() {
        val r = T.diff("""{"a":1,"b":2,"c":"x"}""", """{"a":9,"c":1,"d":4}""")
        assertEquals(1, r.count(DiffKind.CHANGED))
        assertEquals(1, r.count(DiffKind.REMOVED))
        assertEquals(1, r.count(DiffKind.ADDED))
        assertEquals(1, r.count(DiffKind.TYPE_CHANGED))
        assertEquals(4, r.totalDifferences)
    }

    @Test
    fun `entries are capped and the rest is counted`() {
        val left = "[" + (0 until 100).joinToString(",") { "$it" } + "]"
        val right = "[" + (0 until 100).joinToString(",") { "${it + 1000}" } + "]"
        val r = T.diff(left, right, DiffOptions(maxEntries = 10))
        assertEquals(10, r.entries.size)
        assertEquals(100, r.totalDifferences)
        assertEquals(90, r.notShown)
    }

    @Test
    fun `entries keep the nodes so the viewer can jump to them`() {
        val r = T.diff("""{"a":[1,2]}""", """{"a":[1,5]}""")
        val e = r.entries.single()
        assertEquals("2", r.left.stringValue(e.left))
        assertEquals("5", r.right.stringValue(e.right))
    }

    @Test
    fun `the difference tree contains only places with differences`() {
        val r = T.diff("""{"a":{"x":1,"y":2},"b":{"z":1},"c":3}""", """{"a":{"x":1,"y":5},"b":{"z":1},"c":3}""")
        val root = r.root
        assertEquals(1, root.children.size)
        assertEquals(PathSegment.Key("a"), root.children[0].segment)
        assertEquals(PathSegment.Key("y"), root.children[0].children[0].segment)
        assertEquals(DiffKind.CHANGED, root.children[0].children[0].kind)
    }

    @Test
    fun `nesting beyond the depth limit is compared as text`() {
        val depth = com.routegenie.Limits.JSON_DIFF_MAX_DEPTH + 20
        val a = "[".repeat(depth) + "1" + "]".repeat(depth)
        val b = "[".repeat(depth) + "2" + "]".repeat(depth)
        assertEquals(1, T.diff(a, b).totalDifferences)
        assertEquals(0, T.diff(a, a).totalDifferences)
    }

    @Test
    fun `a comparison can be cancelled`() {
        val a = T.scanBytes(T.users(300_000))
        val b = T.scanBytes(T.users(300_000, seed = 7))
        assertNull(JsonDiff.compare(a, b, cancelled = { true }))
    }

    @Test
    fun `comparing through files gives the same differences`() {
        val a = String(T.users(30_000), Charsets.UTF_8)
        val b = String(T.users(30_000, seed = 2), Charsets.UTF_8)
        val mem = T.summary(T.diff(a, b))
        val viaFile = JsonDiff.compare(T.scanFile(a, 1024), T.scanFile(b, 1024))!!
        assertEquals(mem, T.summary(viaFile))
        assertTrue(mem.isNotEmpty())
    }

    // --- rows -------------------------------------------------------------------------------------------------------

    private fun rows(left: String, right: String, options: DiffOptions = DiffOptions()) = JsonDiffRows.build(T.diff(left, right, options))

    @Test
    fun `unchanged siblings are collapsed around a change`() {
        val left = """{"a":1,"b":2,"c":3,"d":4,"e":5,"f":6,"g":7,"h":8}"""
        val right = """{"a":1,"b":2,"c":3,"d":4,"e":50,"f":6,"g":7,"h":8}"""
        val rows = rows(left, right).rows
        assertEquals(listOf(RowKind.CONTEXT, RowKind.COLLAPSED, RowKind.CONTEXT, RowKind.CONTEXT, RowKind.CHANGED, RowKind.CONTEXT, RowKind.CONTEXT, RowKind.COLLAPSED, RowKind.CONTEXT), rows.map { it.kind })
        assertEquals(2, rows[1].collapsedCount)
        assertEquals(1, rows[7].collapsedCount)
        assertEquals("\"e\": 5", rows[4].left)
        assertEquals("\"e\": 50", rows[4].right)
        assertEquals("$.e", rows[4].path)
    }

    @Test
    fun `identical documents have no rows`() {
        assertTrue(rows("""{"a":1}""", """{"a":1}""").rows.isEmpty())
    }

    @Test
    fun `an added container is shown as a block`() {
        val rows = rows("""{"a":1}""", """{"a":1,"b":{"x":1,"y":[1,2]}}""").rows
        val added = rows.filter { it.kind == RowKind.ADDED }
        assertTrue(added.size >= 6)
        assertEquals("\"b\": {", added.first().right)
        assertNull(added.first().left)
        assertEquals("}", added.last().right)
    }

    @Test
    fun `a long added block is cut with a notice`() {
        val big = "[" + (0 until 500).joinToString(",") { "$it" } + "]"
        val rows = rows("""{"a":1}""", """{"a":1,"b":$big}""").rows
        assertEquals(com.routegenie.Limits.JSON_DIFF_BLOCK_LINES, rows.count { it.kind == RowKind.ADDED })
        assertEquals(1, rows.count { it.kind == RowKind.NOTICE })
    }

    @Test
    fun `groups of changed rows are what next and previous jump between`() {
        val left = """{"a":1,"b":2,"c":3,"d":4,"e":5,"f":6,"g":7,"h":8,"i":9,"j":10}"""
        val right = """{"a":0,"b":2,"c":3,"d":4,"e":5,"f":6,"g":7,"h":8,"i":9,"j":0}"""
        assertEquals(2, rows(left, right).groupStarts.size)
        val adjacent = rows("""{"a":1,"b":2}""", """{"a":0,"b":0}""")
        assertEquals(1, adjacent.groupStarts.size)
    }

    @Test
    fun `rows are cut at the row limit`() {
        val left = "[" + (0 until 100).joinToString(",") { "$it" } + "]"
        val right = "[" + (0 until 100).joinToString(",") { "${it + 1}" } + "]"
        val built = JsonDiffRows.build(T.diff(left, right), maxRows = 20)
        assertTrue(built.truncated)
        assertEquals(RowKind.NOTICE, built.rows.last().kind)
        assertTrue(built.rows.size <= 21)
    }

    @Test
    fun `unified text marks removed and added lines`() {
        val text = JsonDiffRows.unifiedText(rows("""{"a":1,"b":2}""", """{"a":1,"b":3}"""), "old", "new")
        assertTrue(text.startsWith("--- old\n+++ new\n"))
        assertTrue(text.contains("-   \"b\": 2"))
        assertTrue(text.contains("+   \"b\": 3"))
        assertTrue(text.contains("  \"a\": 1"))
    }

    @Test
    fun `path lines list every difference`() {
        val text = JsonDiffRows.pathLines(T.diff("""{"a":1,"b":2,"c":3}""", """{"a":9,"c":3,"d":true}"""))
        assertEquals("~ $.a: 1 -> 9\n- $.b: 2\n+ $.d: true\n", text)
    }

    @Test
    fun `key order differences show a row`() {
        val rows = rows("""{"a":1,"b":2}""", """{"b":2,"a":1}""", DiffOptions(ignoreKeyOrder = false)).rows
        assertNotNull(rows.firstOrNull { it.kind == RowKind.CHANGED && it.left!!.startsWith("(keys: a, b") })
    }
}
