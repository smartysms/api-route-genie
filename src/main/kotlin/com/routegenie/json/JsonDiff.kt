package com.routegenie.json

import com.routegenie.Limits
import java.math.BigDecimal

enum class DiffKind { ADDED, REMOVED, CHANGED, TYPE_CHANGED, ORDER_CHANGED }

class DiffOptions(
    /** Objects are compared by key, so the order of their members does not matter. Off: a different order is a difference. */
    val ignoreKeyOrder: Boolean = true,
    /** Keys that are skipped wherever they occur. */
    val ignoreKeys: Set<String> = emptySet(),
    /** Paths that are skipped, in path notation; `[*]` and `.*` stand for any index or key (for example `$.items[*].updatedAt`). */
    val ignorePaths: List<String> = emptyList(),
    /** Compare the elements of arrays by the value of this member (for example `id`) instead of by position. */
    val arrayKey: String? = null,
    /** 1 and 1.0 and 1e0 are the same number. */
    val numericEquality: Boolean = true,
    /** Differences kept; the rest is only counted. */
    val maxEntries: Int = Limits.JSON_DIFF_MAX_ENTRIES,
)

class DiffEntry(val segments: List<PathSegment>, val kind: DiffKind, val left: Int, val right: Int) {
    val path: String by lazy(LazyThreadSafetyMode.PUBLICATION) { JsonPath.format(segments) }
}

/** A place in the document that has a difference at or below it. Unchanged siblings are not represented. */
class DiffNode(val segment: PathSegment?, val left: Int, val right: Int) {
    /** Set when this node itself differs (a scalar, a type change, an added or removed value, a reordering). */
    var kind: DiffKind? = null
    var children: List<DiffNode> = emptyList()

    /** Position among the children of the left and right container; -1 where the value does not exist. */
    var leftPos: Int = -1
    var rightPos: Int = -1
}

class DiffResult(
    val left: JsonIndex,
    val right: JsonIndex,
    val options: DiffOptions,
    val root: DiffNode,
    val entries: List<DiffEntry>,
    private val counts: IntArray,
    val totalDifferences: Int,
) {
    val identical: Boolean get() = totalDifferences == 0

    /** Differences found but not kept because of the entry cap. */
    val notShown: Int get() = totalDifferences - entries.size

    fun count(kind: DiffKind): Int = counts[kind.ordinal]
}

object JsonDiff {
    /** Compares two scanned documents. Returns null when [cancelled] became true. Both indexes must be complete. */
    fun compare(left: JsonIndex, right: JsonIndex, options: DiffOptions = DiffOptions(), cancelled: () -> Boolean = { false }): DiffResult? =
        try {
            DiffRun(left, right, options, cancelled).run()
        } catch (e: DiffCancelled) {
            null
        }
}

private class DiffCancelled : RuntimeException(null, null, false, false)

private enum class Category { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

private class DiffRun(
    private val l: JsonIndex,
    private val r: JsonIndex,
    private val opt: DiffOptions,
    private val cancelled: () -> Boolean,
) {
    private val entries = ArrayList<DiffEntry>()
    private val counts = IntArray(DiffKind.entries.size)
    private var total = 0
    private val path = ArrayList<PathSegment>()
    private val ignorePatterns: List<List<PathSegment>> = opt.ignorePaths.mapNotNull { JsonPath.parse(it).segments }
    private val sa = Slice()
    private val sb = Slice()
    private var untilCheck = Limits.JSON_SEARCH_CANCEL_CHECK_NODES

    fun run(): DiffResult {
        val rootNode = DiffNode(null, l.root, r.root)
        val kids = ArrayList<DiffNode>(1)
        compare(l.root, r.root, null, kids, 0, -1, -1)
        val node = kids.firstOrNull()
        if (node != null) return DiffResult(l, r, opt, node, entries, counts, total)
        return DiffResult(l, r, opt, rootNode, entries, counts, total)
    }

    private fun category(index: JsonIndex, n: Int): Category = when (index.kind(n)) {
        JsonKind.OBJECT -> Category.OBJECT
        JsonKind.ARRAY -> Category.ARRAY
        JsonKind.STRING -> Category.STRING
        JsonKind.NUMBER -> Category.NUMBER
        JsonKind.TRUE, JsonKind.FALSE -> Category.BOOLEAN
        JsonKind.NULL -> Category.NULL
    }

    private fun tick() {
        if (--untilCheck <= 0) {
            untilCheck = Limits.JSON_SEARCH_CANCEL_CHECK_NODES
            if (cancelled()) throw DiffCancelled()
        }
    }

    /** Records one difference; false when the entry cap is reached (the difference is only counted). */
    private fun record(kind: DiffKind, ln: Int, rn: Int): Boolean {
        total++
        counts[kind.ordinal]++
        if (entries.size >= opt.maxEntries) return false
        entries += DiffEntry(ArrayList(path), kind, ln, rn)
        return true
    }

    private fun ignored(seg: PathSegment): Boolean {
        if (seg is PathSegment.Key && seg.name in opt.ignoreKeys) return true
        if (ignorePatterns.isEmpty()) return false
        path += seg
        val hit = ignorePatterns.any { JsonPath.matches(it, path) }
        path.removeAt(path.size - 1)
        return hit
    }

    /**
     * Compares node [ln] on the left with [rn] on the right and adds a [DiffNode] to [out] when something differs and could be
     * recorded. Returns whether the two differ at all.
     */
    private fun compare(ln: Int, rn: Int, seg: PathSegment?, out: MutableList<DiffNode>, depth: Int, lpos: Int, rpos: Int): Boolean {
        tick()
        if (seg != null) path += seg
        try {
            val lc = category(l, ln)
            val rc = category(r, rn)
            if (lc != rc) return leafDifference(DiffKind.TYPE_CHANGED, ln, rn, seg, out, lpos, rpos)
            return when (lc) {
                Category.OBJECT, Category.ARRAY ->
                    if (depth >= Limits.JSON_DIFF_MAX_DEPTH) {
                        if (sameText(ln, rn)) false else leafDifference(DiffKind.CHANGED, ln, rn, seg, out, lpos, rpos)
                    } else if (lc == Category.OBJECT) compareObjects(ln, rn, seg, out, depth, lpos, rpos)
                    else compareArrays(ln, rn, seg, out, depth, lpos, rpos)
                Category.STRING -> if (sameString(ln, rn)) false else leafDifference(DiffKind.CHANGED, ln, rn, seg, out, lpos, rpos)
                Category.NUMBER -> if (sameNumber(ln, rn)) false else leafDifference(DiffKind.CHANGED, ln, rn, seg, out, lpos, rpos)
                Category.BOOLEAN -> if (l.kind(ln) == r.kind(rn)) false else leafDifference(DiffKind.CHANGED, ln, rn, seg, out, lpos, rpos)
                Category.NULL -> false
            }
        } finally {
            if (seg != null) path.removeAt(path.size - 1)
        }
    }

    private fun leafDifference(kind: DiffKind, ln: Int, rn: Int, seg: PathSegment?, out: MutableList<DiffNode>, lpos: Int, rpos: Int): Boolean {
        if (record(kind, ln, rn)) out += DiffNode(seg, ln, rn).also {
            it.kind = kind
            it.leftPos = lpos
            it.rightPos = rpos
        }
        return true
    }

    private fun oneSided(kind: DiffKind, ln: Int, rn: Int, seg: PathSegment, out: MutableList<DiffNode>, pos: Int) {
        path += seg
        if (record(kind, ln, rn)) out += DiffNode(seg, ln, rn).also {
            it.kind = kind
            if (kind == DiffKind.REMOVED) it.leftPos = pos else it.rightPos = pos
        }
        path.removeAt(path.size - 1)
    }

    private fun container(ln: Int, rn: Int, seg: PathSegment?, lpos: Int, rpos: Int): DiffNode =
        DiffNode(seg, ln, rn).also {
            it.leftPos = lpos
            it.rightPos = rpos
        }

    private fun compareObjects(ln: Int, rn: Int, seg: PathSegment?, out: MutableList<DiffNode>, depth: Int, lpos: Int, rpos: Int): Boolean {
        val lch = l.children(ln)
        val rch = r.children(rn)
        val rmap = HashMap<String, ArrayList<Int>>(rch.size * 2)
        val rkeys = arrayOfNulls<String>(rch.size)
        for (k in rch.indices) {
            val key = r.keyOf(rch[k]) ?: ""
            rkeys[k] = key
            if (key in opt.ignoreKeys) continue
            rmap.getOrPut(key) { ArrayList(1) } += k
        }
        val used = BooleanArray(rch.size)
        val kids = ArrayList<DiffNode>()
        var differs = false
        var lastMatchedRight = -1
        var reordered = false
        for (k in lch.indices) {
            val key = l.keyOf(lch[k]) ?: ""
            if (key in opt.ignoreKeys) continue
            val segment = PathSegment.Key(key)
            if (ignorePatterns.isNotEmpty() && ignored(segment)) {
                rmap[key]?.let { list -> if (list.isNotEmpty()) used[list.removeAt(0)] = true }
                continue
            }
            val candidates = rmap[key]
            if (candidates == null || candidates.isEmpty()) {
                differs = true
                oneSided(DiffKind.REMOVED, lch[k], -1, segment, kids, k)
                continue
            }
            val rk = candidates.removeAt(0)
            used[rk] = true
            if (rk < lastMatchedRight) reordered = true
            lastMatchedRight = maxOf(lastMatchedRight, rk)
            if (compare(lch[k], rch[rk], segment, kids, depth + 1, k, rk)) differs = true
        }
        for (k in rch.indices) {
            if (used[k]) continue
            val key = rkeys[k] ?: ""
            if (key in opt.ignoreKeys) continue
            val segment = PathSegment.Key(key)
            if (ignorePatterns.isNotEmpty() && ignored(segment)) continue
            differs = true
            oneSided(DiffKind.ADDED, -1, rch[k], segment, kids, k)
        }
        var node: DiffNode? = null
        if (!opt.ignoreKeyOrder && reordered) {
            differs = true
            if (record(DiffKind.ORDER_CHANGED, ln, rn)) node = container(ln, rn, seg, lpos, rpos).also { it.kind = DiffKind.ORDER_CHANGED }
        }
        if (kids.isNotEmpty()) {
            node = node ?: container(ln, rn, seg, lpos, rpos)
            node.children = kids
        }
        if (node != null) out += node
        return differs
    }

    private fun compareArrays(ln: Int, rn: Int, seg: PathSegment?, out: MutableList<DiffNode>, depth: Int, lpos: Int, rpos: Int): Boolean {
        val lch = l.children(ln)
        val rch = r.children(rn)
        val kids = ArrayList<DiffNode>()
        var differs = false
        val field = opt.arrayKey
        if (field == null) {
            val common = minOf(lch.size, rch.size)
            for (k in 0 until common) if (compare(lch[k], rch[k], PathSegment.Index(k), kids, depth + 1, k, k)) differs = true
            for (k in common until lch.size) {
                differs = true
                oneSided(DiffKind.REMOVED, lch[k], -1, PathSegment.Index(k), kids, k)
            }
            for (k in common until rch.size) {
                differs = true
                oneSided(DiffKind.ADDED, -1, rch[k], PathSegment.Index(k), kids, k)
            }
        } else {
            differs = compareKeyed(lch, rch, field, kids, depth)
        }
        if (kids.isNotEmpty()) out += container(ln, rn, seg, lpos, rpos).also { it.children = kids }
        return differs
    }

    /** Identity text of an array element: the value of [field] when the element is an object that has it, else null. */
    private fun identity(index: JsonIndex, element: Int, field: String): String? {
        if (index.kind(element) != JsonKind.OBJECT) return null
        val member = index.findMember(element, field)
        if (member < 0 || index.isContainer(member)) return null
        return index.stringValue(member)
    }

    private fun compareKeyed(lch: IntArray, rch: IntArray, field: String, kids: MutableList<DiffNode>, depth: Int): Boolean {
        var differs = false
        val rmap = HashMap<String, ArrayList<Int>>()
        val rUnkeyed = ArrayList<Int>()
        val rUsed = BooleanArray(rch.size)
        for (k in rch.indices) {
            val id = identity(r, rch[k], field)
            if (id == null) rUnkeyed += k else rmap.getOrPut(id) { ArrayList(1) } += k
        }
        val lUnkeyed = ArrayList<Int>()
        for (k in lch.indices) {
            tick()
            val id = identity(l, lch[k], field)
            if (id == null) {
                lUnkeyed += k
                continue
            }
            val segment = PathSegment.Match(field, id)
            val candidates = rmap[id]
            if (candidates == null || candidates.isEmpty()) {
                differs = true
                oneSided(DiffKind.REMOVED, lch[k], -1, segment, kids, k)
                continue
            }
            val rk = candidates.removeAt(0)
            rUsed[rk] = true
            if (compare(lch[k], rch[rk], segment, kids, depth + 1, k, rk)) differs = true
        }
        val pairs = minOf(lUnkeyed.size, rUnkeyed.size)
        for (p in 0 until pairs) {
            val lk = lUnkeyed[p]
            val rk = rUnkeyed[p]
            rUsed[rk] = true
            if (compare(lch[lk], rch[rk], PathSegment.Index(lk), kids, depth + 1, lk, rk)) differs = true
        }
        for (p in pairs until lUnkeyed.size) {
            differs = true
            oneSided(DiffKind.REMOVED, lch[lUnkeyed[p]], -1, PathSegment.Index(lUnkeyed[p]), kids, lUnkeyed[p])
        }
        for (k in rch.indices) {
            if (rUsed[k]) continue
            differs = true
            val id = identity(r, rch[k], field)
            val segment = if (id != null) PathSegment.Match(field, id) else PathSegment.Index(k)
            oneSided(DiffKind.ADDED, -1, rch[k], segment, kids, k)
        }
        return differs
    }

    // --- scalar equality ---------------------------------------------------------------------------------------------

    private fun tokenRange(index: JsonIndex, n: Int, s: Slice, content: Boolean) {
        val vs = index.valueStart(n)
        val en = index.end(n)
        if (content && index.kind(n) == JsonKind.STRING) index.load(vs + 1, en - 1, s) else index.load(vs, en, s)
    }

    private fun bytesEqual(): Boolean =
        sa.len == sb.len && java.util.Arrays.equals(sa.arr, sa.off, sa.off + sa.len, sb.arr, sb.off, sb.off + sb.len)

    private fun hasBackslash(s: Slice): Boolean {
        for (k in s.off until s.off + s.len) if (s.arr[k] == BACKSLASH) return true
        return false
    }

    private fun sameString(ln: Int, rn: Int): Boolean {
        tokenRange(l, ln, sa, true)
        tokenRange(r, rn, sb, true)
        if (bytesEqual()) return true
        if (!hasBackslash(sa) && !hasBackslash(sb)) return false
        return JsonStrings.decode(sa.arr, sa.off, sa.len, l.source.charset) == JsonStrings.decode(sb.arr, sb.off, sb.len, r.source.charset)
    }

    private fun sameNumber(ln: Int, rn: Int): Boolean {
        val a = l.rawValue(ln)
        val b = r.rawValue(rn)
        if (a == b) return true
        if (!opt.numericEquality || a.length > MAX_NUMBER_CHARS || b.length > MAX_NUMBER_CHARS) return false
        return try {
            val x = BigDecimal(a)
            val y = BigDecimal(b)
            // A huge exponent makes the comparison allocate a huge integer; such numbers are compared as text only.
            if (Math.abs(x.scale()) > MAX_EXPONENT || Math.abs(y.scale()) > MAX_EXPONENT) false else x.compareTo(y) == 0
        } catch (e: NumberFormatException) {
            false
        }
    }

    private fun sameText(ln: Int, rn: Int): Boolean {
        val a = l.valueStart(ln)
        val b = r.valueStart(rn)
        if (l.end(ln) - a != r.end(rn) - b) return false
        l.load(a, l.end(ln), sa)
        r.load(b, r.end(rn), sb)
        return bytesEqual()
    }

    private companion object {
        const val BACKSLASH = '\\'.code.toByte()
        const val MAX_NUMBER_CHARS = 64
        const val MAX_EXPONENT = 1000
    }
}
