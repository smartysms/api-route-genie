package com.routegenie.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPathSearchTest {
    private val doc = """{"users":[{"id":1,"name":"Ann","tags":["a","b"]},{"id":2,"name":"caf\u00e9 Bob","odd key":{"x.y":null}}],"total":2,"a-b":true}"""

    @Test
    fun `paths are generated in dot and bracket form`() {
        val index = T.scan(doc)
        val users = index.findMember(index.root, "users")
        val second = index.childAt(users, 1)
        assertEquals("$", JsonPath.format(index, index.root))
        assertEquals("$.users", JsonPath.format(index, users))
        assertEquals("$.users[1].name", JsonPath.format(index, index.findMember(second, "name")))
        assertEquals("$.users[1][\"odd key\"][\"x.y\"]", JsonPath.format(index, index.findMember(index.findMember(second, "odd key"), "x.y")))
        assertEquals("$[\"a-b\"]", JsonPath.format(index, index.findMember(index.root, "a-b")))
        assertEquals("$.users[0].tags[1]", JsonPath.format(index, index.childAt(index.findMember(index.childAt(users, 0), "tags"), 1)))
    }

    @Test
    fun `generated paths parse back and resolve to the same node`() {
        val index = T.scan(doc)
        val failures = (index.root until index.nodeCount).mapNotNull { n ->
            val text = JsonPath.format(index, n)
            val parsed = JsonPath.parse(text)
            val back = if (parsed.ok) JsonPath.resolve(index, parsed.segments!!) else -2
            if (back != n) "$text -> $back (want $n)" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `parse accepts the usual spellings`() {
        assertEquals(listOf(PathSegment.Key("a"), PathSegment.Index(0), PathSegment.Key("b")), JsonPath.parse("$.a[0].b").segments)
        assertEquals(listOf(PathSegment.Key("a"), PathSegment.Key("b c")), JsonPath.parse("a['b c']").segments)
        assertEquals(listOf(PathSegment.Key("a\"b")), JsonPath.parse("$[\"a\\\"b\"]").segments)
        assertEquals(listOf(PathSegment.Key("A")), JsonPath.parse("$[\"\\u0041\"]").segments)
        assertEquals(listOf(PathSegment.Key("a"), PathSegment.Wildcard, PathSegment.Key("b")), JsonPath.parse("$.a[*].b").segments)
        assertEquals(emptyList<PathSegment>(), JsonPath.parse("$").segments)
        assertEquals(listOf(PathSegment.Key("a"), PathSegment.Wildcard), JsonPath.parse("$.a.*").segments)
    }

    @Test
    fun `parse reports the position of a mistake`() {
        for (bad in listOf("$.a[", "$.a[x]", "$.a['b", "$..", "$.a[1", "$.a[1]x")) {
            val r = JsonPath.parse(bad)
            assertFalse("'$bad' should not parse", r.ok)
            assertNotNull(r.errorMessage)
            assertTrue(r.errorOffset >= 0)
        }
    }

    @Test
    fun `wildcards match one segment`() {
        val pattern = JsonPath.parse("$.items[*].ts").segments!!
        assertTrue(JsonPath.matches(pattern, JsonPath.parse("$.items[3].ts").segments!!))
        assertFalse(JsonPath.matches(pattern, JsonPath.parse("$.items[3].x").segments!!))
        assertFalse(JsonPath.matches(pattern, JsonPath.parse("$.items.ts").segments!!))
    }

    @Test
    fun `a path in json lines starts with the document number`() {
        val index = T.scan("{\"a\":1}\n{\"a\":2}\n")
        val second = JsonPath.resolve(index, JsonPath.parse("$[1].a").segments!!)
        assertEquals("2", index.stringValue(second))
        assertEquals("$[1].a", JsonPath.format(index, second))
    }

    private fun hits(index: JsonIndex, q: SearchQuery): List<String> =
        JsonSearch.search(index, q).let { r -> (0 until r.count).map { h -> JsonPath.format(index, SearchHit.node(r.hits[h])) + if (SearchHit.isKey(r.hits[h])) "#k" else "#v" } }

    @Test
    fun `search finds keys and values`() {
        val index = T.scan(doc)
        assertEquals(listOf("$.users[0].name#k", "$.users[1].name#k"), hits(index, SearchQuery("name", inValues = false)))
        assertEquals(listOf("$.users[0].name#v"), hits(index, SearchQuery("ann", inKeys = false)))
        assertEquals(listOf("$.users[0].name#v"), hits(index, SearchQuery("Ann", matchCase = true)))
        assertEquals(emptyList<String>(), hits(index, SearchQuery("ann", matchCase = true)))
        assertEquals(listOf("$.users[0].tags[0]#v"), hits(index, SearchQuery("^a$", regex = true, inKeys = false)))
    }

    @Test
    fun `search matches literals and numbers`() {
        val index = T.scan(doc)
        assertEquals(listOf("$.users[1][\"odd key\"][\"x.y\"]#v"), hits(index, SearchQuery("null")))
        assertEquals(listOf("$[\"a-b\"]#v"), hits(index, SearchQuery("true")))
        assertTrue(hits(index, SearchQuery("2")).contains("$.total#v"))
    }

    @Test
    fun `search sees through unicode escapes`() {
        val index = T.scan(doc)
        assertEquals(listOf("$.users[1].name#v"), hits(index, SearchQuery("caf\u00e9", inKeys = false)))
        assertEquals(listOf("$.users[1].name#v"), hits(index, SearchQuery("CAF\u00c9", inKeys = false)))
        val raw = T.scan("{\"k\":\"caf\u00e9\"}")
        assertEquals(listOf("$.k#v"), hits(raw, SearchQuery("caf\u00e9", inKeys = false)))
    }

    @Test
    fun `search by regular expression`() {
        val index = T.scan(doc)
        assertEquals(listOf("$.users[0].id#v", "$.users[1].id#v", "$.total#v"), hits(index, SearchQuery("^\\d+$", regex = true, inKeys = false)))
        assertEquals(listOf("$.users[1][\"odd key\"]#k"), hits(index, SearchQuery("^odd\\s", regex = true, inValues = false)))
    }

    @Test
    fun `a bad regular expression is reported not thrown`() {
        val r = JsonSearch.search(T.scan(doc), SearchQuery("(", regex = true))
        assertNotNull(r.regexError)
        assertEquals(0, r.count)
    }

    @Test
    fun `an empty query finds nothing`() {
        assertEquals(0, JsonSearch.search(T.scan(doc), SearchQuery("")).count)
    }

    @Test
    fun `results are capped and flagged`() {
        val index = T.scan("[" + (0 until 500).joinToString(",") { "\"x$it\"" } + "]")
        val r = JsonSearch.search(index, SearchQuery("x"), maxResults = 100)
        assertEquals(100, r.count)
        assertTrue(r.truncated)
    }

    @Test
    fun `a search can be cancelled`() {
        val index = T.scanBytes(T.users(500_000))
        var polls = 0
        val r = JsonSearch.search(index, SearchQuery("zzz-not-there"), cancelled = { ++polls > 3 })
        assertTrue(r.cancelled)
        assertTrue(r.nodesScanned < index.nodeCount)
    }

    @Test
    fun `a search stops at its time budget`() {
        val index = T.scanBytes(T.users(500_000))
        val r = JsonSearch.search(index, SearchQuery("zzz-not-there"), budgetMs = -1)
        assertTrue(r.timedOut)
    }

    @Test
    fun `search through a file source finds the same hits`() {
        val text = String(T.users(40_000), Charsets.UTF_8)
        val a = hits(T.scan(text), SearchQuery("User number 1"))
        val b = hits(T.scanFile(text, 2048), SearchQuery("User number 1"))
        assertTrue(a.isNotEmpty())
        assertEquals(a, b)
    }

    @Test
    fun `text search finds plain and regex matches`() {
        val text = "alpha beta ALPHA gamma alpha"
        val plain = JsonSearch.searchText(text, SearchQuery("alpha"))
        assertEquals(listOf(0, 11, 23), plain.starts.take(plain.count))
        val cs = JsonSearch.searchText(text, SearchQuery("alpha", matchCase = true))
        assertEquals(listOf(0, 23), cs.starts.take(cs.count))
        val rx = JsonSearch.searchText(text, SearchQuery("a\\w+a", regex = true, matchCase = true))
        assertEquals(listOf(0, 18, 23), rx.starts.take(rx.count))
        assertNotNull(JsonSearch.searchText(text, SearchQuery("[", regex = true)).regexError)
        assertNull(JsonSearch.searchText(text, SearchQuery("x")).regexError)
    }

    @Test
    fun `a catastrophic regular expression can be interrupted`() {
        val text = "a".repeat(3000)
        var polls = 0
        val r = JsonSearch.searchText(text, SearchQuery("a*a*a*a*b", regex = true), cancelled = { ++polls > 50 })
        assertTrue(r.cancelled)
    }
}
