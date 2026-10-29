package com.routegenie.json

import com.routegenie.Limits

/**
 * A row of the JSON tree. Containers with many children do not list them all: past [Limits.JSON_TREE_BUCKET] children they
 * are grouped into [Range] rows (`[0 … 99]`, `[100 … 199]`), nested again for millions of children, so the Swing tree never
 * holds more than a bucket's worth of rows per level.
 */
sealed class TreeItem {
    /** A node of the document; [ordinal] is its position among its siblings, used for the `[3]` label. */
    data class Value(val node: Int, val ordinal: Int) : TreeItem()

    /** Children number [from] until [to] of [container]. */
    data class Range(val container: Int, val from: Int, val to: Int) : TreeItem()
}

/** Tree structure over an index: counts, children, and the path of items down to any node. Not thread safe (UI thread). */
class JsonTreeCore(val index: JsonIndex, private val bucket: Int = Limits.JSON_TREE_BUCKET) {
    private val cache = object : LinkedHashMap<Int, IntArray>(64, 0.75f, true) {}
    private var cachedInts = 0L

    fun root(): TreeItem = TreeItem.Value(index.root, 0)

    private fun valid(node: Int): Boolean = node >= 0 && node < index.nodeCount

    /**
     * Children a container really has right now. While the scanner is still running, its counter can be ahead of the nodes that
     * are readable, so the count comes from the readable children; once the scan is over it is the stored count.
     */
    private fun countOf(node: Int): Int {
        if (!valid(node)) return 0
        val stored = index.childCount(node)
        if (index.isComplete) return stored
        if (index.isFlat(node)) return minOf(stored, index.nodeCount - node - 1).coerceAtLeast(0)
        return childList(node).size
    }

    fun isLeaf(item: TreeItem): Boolean = when (item) {
        is TreeItem.Value -> !valid(item.node) || !index.isContainer(item.node) || countOf(item.node) == 0
        is TreeItem.Range -> false
    }

    fun childCount(item: TreeItem): Int = when (item) {
        is TreeItem.Value -> topCount(countOf(item.node))
        is TreeItem.Range -> {
            val span = item.to - item.from
            if (span <= bucket) span else ceilDiv(span, bucketSize(span))
        }
    }

    fun child(item: TreeItem, k: Int): TreeItem = when (item) {
        is TreeItem.Value -> {
            val n = countOf(item.node)
            if (n <= bucket) value(item.node, k) else rangeOf(item.node, 0, n, k)
        }
        is TreeItem.Range -> {
            val span = item.to - item.from
            if (span <= bucket) value(item.container, item.from + k) else rangeOf(item.container, item.from, item.to, k)
        }
    }

    fun indexOfChild(parent: TreeItem, child: TreeItem): Int {
        val (from, to, container) = when (parent) {
            is TreeItem.Value -> Triple(0, countOf(parent.node), parent.node)
            is TreeItem.Range -> Triple(parent.from, parent.to, parent.container)
        }
        val span = to - from
        return when (child) {
            is TreeItem.Value -> if (span <= bucket && child.ordinal in from until to && index.parent(child.node) == container) child.ordinal - from else -1
            is TreeItem.Range -> if (span > bucket && child.container == container && child.from >= from) (child.from - from) / bucketSize(span) else -1
        }
    }

    private fun value(container: Int, ordinal: Int): TreeItem.Value {
        val node = nodeAt(container, ordinal)
        return TreeItem.Value(node, ordinal)
    }

    private fun rangeOf(container: Int, from: Int, to: Int, k: Int): TreeItem.Range {
        val size = bucketSize(to - from)
        val start = from + k * size
        return TreeItem.Range(container, start, minOf(start + size, to))
    }

    private fun topCount(n: Int): Int = if (n <= bucket) n else ceilDiv(n, bucketSize(n))

    /** Largest bucket (a power of the bucket width) that still splits [span] into more than one range. */
    fun bucketSize(span: Int): Int {
        var size = bucket.toLong()
        while (size * bucket < span) size *= bucket
        return size.toInt()
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /** Child [ordinal] of [container]. Flat containers answer directly; others keep the child list until it is evicted. */
    fun nodeAt(container: Int, ordinal: Int): Int {
        if (!valid(container)) return -1
        if (index.isFlat(container)) return (container + 1 + ordinal).takeIf { it < index.nodeCount } ?: -1
        val list = childList(container)
        return if (ordinal < list.size) list[ordinal] else -1
    }

    private fun childList(container: Int): IntArray {
        var list = cache[container]
        if (list == null) {
            list = index.children(container)
            cache[container] = list
            cachedInts += list.size
            trim()
        }
        return list
    }

    private fun trim() {
        if (cachedInts <= Limits.JSON_TREE_CHILD_CACHE_INTS) return
        val it = cache.entries.iterator()
        while (it.hasNext() && cachedInts > Limits.JSON_TREE_CHILD_CACHE_INTS / 2 && cache.size > 1) {
            val e = it.next()
            cachedInts -= e.value.size
            it.remove()
        }
    }

    /** Forgets cached child lists; call when the index changed (a partial index grew). */
    fun invalidate() {
        cache.clear()
        cachedInts = 0
    }

    /** The items from the root down to [node], including range rows, or null when [node] is not below the root. */
    fun pathTo(node: Int): List<TreeItem>? {
        val root = index.root
        if (node < root || node >= index.nodeCount) return null
        val chain = ArrayList<Int>()
        var n = node
        while (n != root) {
            chain += n
            val p = index.parent(n)
            if (p < 0 || p < root && root != 0) return null
            n = p
        }
        val out = ArrayList<TreeItem>(chain.size + 4)
        out += TreeItem.Value(root, 0)
        var container = root
        for (k in chain.indices.reversed()) {
            val child = chain[k]
            val ordinal = index.ordinalOf(child)
            var from = 0
            var to = countOf(container)
            while (to - from > bucket) {
                val size = bucketSize(to - from)
                val start = from + ((ordinal - from) / size) * size
                out += TreeItem.Range(container, start, minOf(start + size, to))
                from = start
                to = minOf(start + size, to)
            }
            out += TreeItem.Value(child, ordinal)
            container = child
        }
        return out
    }
}
