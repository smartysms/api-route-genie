package com.routegenie.json

import com.routegenie.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTreeTableTest {
    private fun numbers(n: Int) = "[" + (0 until n).joinToString(",") + "]"

    @Test
    fun `few children are listed directly`() {
        val core = JsonTreeCore(T.scan(numbers(5)))
        val root = core.root()
        assertEquals(5, core.childCount(root))
        assertTrue(core.child(root, 3) is TreeItem.Value)
        assertTrue(core.isLeaf(core.child(root, 3)))
        assertTrue(!core.isLeaf(root))
    }

    @Test
    fun `many children are grouped into ranges`() {
        val index = T.scan(numbers(250))
        val core = JsonTreeCore(index)
        val root = core.root()
        assertEquals(3, core.childCount(root))
        assertEquals(TreeItem.Range(1, 0, 100), core.child(root, 0))
        assertEquals(TreeItem.Range(1, 100, 200), core.child(root, 1))
        assertEquals(TreeItem.Range(1, 200, 250), core.child(root, 2))
        val last = core.child(root, 2)
        assertEquals(50, core.childCount(last))
        assertEquals("249", index.stringValue((core.child(last, 49) as TreeItem.Value).node))
    }

    @Test
    fun `a million children need only a few levels and no more than a bucket per level`() {
        val index = T.scan(numbers(1_000_000))
        val core = JsonTreeCore(index)
        val root = core.root()
        assertEquals(100, core.childCount(root))
        val target = index.childAt(index.root, 543_210)
        val path = core.pathTo(target)!!
        assertEquals(4, path.size)
        assertTrue(path[1] is TreeItem.Range && path[2] is TreeItem.Range)
        assertEquals(TreeItem.Value(target, 543_210), path.last())
        for (k in 1 until path.size) {
            assertTrue("a bucket never has more than ${Limits.JSON_TREE_BUCKET} rows", core.childCount(path[k - 1]) <= Limits.JSON_TREE_BUCKET)
            val i = core.indexOfChild(path[k - 1], path[k])
            assertTrue(i >= 0)
            assertEquals(path[k], core.child(path[k - 1], i))
        }
    }

    @Test
    fun `the path to every node is a chain of real children`() {
        val index = T.scan(String(T.users(30_000), Charsets.UTF_8))
        val core = JsonTreeCore(index, bucket = 7)
        var checked = 0
        for (node in index.root until index.nodeCount step 5) {
            val path = core.pathTo(node)!!
            assertEquals(index.root, (path.first() as TreeItem.Value).node)
            assertEquals(node, (path.last() as TreeItem.Value).node)
            for (k in 1 until path.size) {
                val i = core.indexOfChild(path[k - 1], path[k])
                assertTrue("$node step $k", i >= 0)
                assertEquals(path[k], core.child(path[k - 1], i))
            }
            checked++
        }
        assertTrue(checked > 100)
    }

    @Test
    fun `a node above the root has no path`() {
        val index = T.scan("[1,2]")
        assertNull(JsonTreeCore(index).pathTo(0))
    }

    @Test
    fun `reading the tree of a document that is still being scanned never fails`() {
        val bytes = T.users(3_000_000)
        var checks = 0
        JsonScanner.scan(
            JsonContent.Bytes(bytes), ScanOptions(),
            object : ScanListener {
                override fun onProgress(index: JsonIndex) {
                    val core = JsonTreeCore(index)
                    val root = core.root()
                    val n = core.childCount(root)
                    // Walk down the last branch like a tree being expanded would.
                    var item: TreeItem = root
                    var depth = 0
                    while (depth < 6 && !core.isLeaf(item)) {
                        val count = core.childCount(item)
                        if (count == 0) break
                        val child = core.child(item, count - 1)
                        if (child is TreeItem.Value) assertTrue(child.node in 0 until index.nodeCount)
                        item = child
                        depth++
                    }
                    assertTrue(n >= 0)
                    checks++
                }
            },
        )
        assertTrue(checks > 3)
    }

    // --- table ----------------------------------------------------------------------------------------------------------

    private fun table(text: String): JsonTable {
        val index = T.scan(text)
        return JsonTable.create(index, JsonTable.detect(index))!!
    }

    @Test
    fun `the table is the document array or the largest array of objects below the root`() {
        assertEquals(1, JsonTable.detect(T.scan("""[{"a":1}]""")))
        val wrapped = T.scan("""{"meta":{"n":1},"data":{"items":[{"a":1},{"a":2},{"a":3}],"other":[{"x":1}]},"tags":["a","b","c","d"]}""")
        val node = JsonTable.detect(wrapped)
        assertEquals("items", wrapped.keyOf(node))
        assertEquals(-1, JsonTable.detect(T.scan("42")))
        assertEquals(-1, JsonTable.detect(T.scan("""{"a":[1,2,3]}""")))
        assertEquals(-1, JsonTable.detect(T.scan("[]")))
    }

    @Test
    fun `columns are the union of keys in order of appearance`() {
        val t = table("""[{"id":1,"name":"a"},{"id":2,"email":"x"},{"name":"c","extra":true}]""")
        assertEquals(listOf("id", "name", "email", "extra"), t.columns)
        assertEquals(3, t.rowCount)
        assertEquals("x", t.index.stringValue(t.cellNode(1, 2)))
        assertEquals(-1, t.cellNode(0, 2))
    }

    @Test
    fun `rows that are not objects use the value column`() {
        val t = table("""[{"a":1},5,"x"]""")
        assertEquals(listOf("a", JsonTable.VALUE_COLUMN), t.columns)
        assertEquals("5", t.index.stringValue(t.cellNode(1, 1)))
        assertEquals(-1, t.cellNode(0, 1))
    }

    @Test
    fun `columns beyond the limit are cut and flagged`() {
        val keys = (0 until Limits.JSON_TABLE_MAX_COLUMNS + 20).joinToString(",") { "\"k$it\":$it" }
        val t = table("[{$keys}]")
        assertEquals(Limits.JSON_TABLE_MAX_COLUMNS, t.columns.size)
        assertTrue(t.columnsCut)
    }

    @Test
    fun `sorting orders numbers numerically and missing cells count as the largest`() {
        val t = table("""[{"n":10},{"n":9},{"x":1},{"n":100},{"n":-1.5}]""")
        val order = t.sortedRows(0, true)!!
        assertEquals(listOf(4, 1, 0, 3, 2), order.toList())
        assertEquals(listOf(2, 3, 0, 1, 4), t.sortedRows(0, false)!!.toList())
    }

    @Test
    fun `sorting strings ignores case and is stable`() {
        val t = table("""[{"s":"b"},{"s":"A"},{"s":"a"},{"s":"B"}]""")
        val order = t.sortedRows(0, true)!!.map { t.index.stringValue(t.cellNode(it, 0)) }
        assertEquals(listOf("A", "a", "B", "b"), order)
    }

    @Test
    fun `sorting is refused above the row limit and can be cancelled`() {
        val big = "[" + (0..Limits.JSON_TABLE_SORT_MAX_ROWS).joinToString(",") { "{\"a\":$it}" } + "]"
        val t = table(big)
        assertNull(t.sortedRows(0, true))
        val small = table("""[{"a":1},{"a":2}]""")
        assertNull(small.sortedRows(0, true) { true })
        assertNotNull(small.sortedRows(0, true))
    }
}
