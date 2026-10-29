package com.routegenie.json

import com.routegenie.Limits
import java.io.Closeable

enum class JsonKind {
    OBJECT, ARRAY, STRING, NUMBER, TRUE, FALSE, NULL;

    val isContainer: Boolean get() = this == OBJECT || this == ARRAY
}

enum class ScanStatus { SCANNING, COMPLETE, FAILED, CANCELLED, LIMITED }

/** Reusable view of a byte range: the backing array, where the range starts in it and how long it is. */
class Slice {
    var arr: ByteArray = EMPTY
    var off: Int = 0
    var len: Int = 0
    internal var scratch: ByteArray = EMPTY

    /** Lets go of the document bytes; a big scratch buffer (one huge token) is not kept either. */
    internal fun clear() {
        arr = EMPTY
        if (scratch.size > KEEP_SCRATCH_BYTES) scratch = EMPTY
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val KEEP_SCRATCH_BYTES = 64 * 1024
    }
}

/**
 * Compact structural index of one JSON document: every value is one numbered node, in document order, stored in primitive
 * arrays (no object per node). A node costs 23 bytes: start, end, parent, subtree size and child count as ints, kind as a
 * byte and depth as a short. Text is never copied: values are read from the source on demand.
 *
 * Because nodes are in pre-order, the first child of a container is the next node and the next sibling is
 * `node + subtreeSize(node)`; an array of only scalars is "flat" and reaches child k in one step.
 *
 * An object member is one node whose [start] is the opening quote of its key; the value token follows the colon.
 *
 * Node 0 is a hidden wrapper that holds the top-level document(s): one document for ordinary JSON, several for JSON Lines.
 * Use [root] to get the node to show. The scanner fills the index while readers (other threads) may already look at it:
 * everything below [nodeCount] is readable at any time, and containers still being scanned report the children seen so far.
 */
class JsonIndex internal constructor(val source: JsonSource) : Closeable {
    private class Chunk(n: Int) {
        var start = IntArray(n)
        var end = IntArray(n)
        var parent = IntArray(n)
        var subtree = IntArray(n)
        var count = IntArray(n)
        var kind = ByteArray(n)
        var depth = ShortArray(n)

        fun bytes(): Long = start.size.toLong() * BYTES_PER_NODE

        fun shrink(n: Int) {
            if (n >= start.size) return
            start = start.copyOf(n)
            end = end.copyOf(n)
            parent = parent.copyOf(n)
            subtree = subtree.copyOf(n)
            count = count.copyOf(n)
            kind = kind.copyOf(n)
            depth = depth.copyOf(n)
        }
    }

    private val shift: Int = run {
        val estimate = (source.length / 4).coerceAtLeast(1)
        (64 - java.lang.Long.numberOfLeadingZeros(estimate - 1)).coerceIn(Limits.JSON_INDEX_MIN_CHUNK_SHIFT, Limits.JSON_INDEX_MAX_CHUNK_SHIFT)
    }
    private val mask = (1 shl shift) - 1

    @Volatile
    private var chunks: Array<Chunk?> = arrayOfNulls(4)
    private var size = 0

    @Volatile
    private var published = 0

    @Volatile
    var status: ScanStatus = ScanStatus.SCANNING
        internal set

    @Volatile
    var error: JsonError? = null
        internal set

    /** True for JSON Lines: the wrapper (node 0) is the list of documents. */
    @Volatile
    var lines: Boolean = false
        internal set

    @Volatile
    var documentCount: Int = 0
        internal set

    @Volatile
    var scannedBytes: Long = 0
        internal set

    @Volatile
    var maxDepth: Int = 0
        internal set

    /** Nodes readable now. While the scanner runs this grows; afterwards it is final. */
    val nodeCount: Int get() = published

    val isComplete: Boolean get() = status != ScanStatus.SCANNING

    /** The node to show: the single document, or the wrapper for JSON Lines and for input without any value. */
    val root: Int get() = if (lines || published < 2) 0 else 1

    // --- Writer side (the scanner) ------------------------------------------------------------------------------------

    internal fun allocate(kind: Int, start: Int, parent: Int, depth: Int): Int {
        val i = size
        val chunkNo = i ushr shift
        var table = chunks
        if (chunkNo >= table.size) {
            table = table.copyOf(table.size * 2)
            chunks = table
        }
        var chunk = table[chunkNo]
        if (chunk == null) {
            chunk = Chunk(1 shl shift)
            table[chunkNo] = chunk
        }
        val o = i and mask
        chunk.kind[o] = kind.toByte()
        chunk.start[o] = start
        chunk.end[o] = start
        chunk.parent[o] = parent
        chunk.subtree[o] = 0
        chunk.count[o] = 0
        chunk.depth[o] = depth.toShort()
        size = i + 1
        return i
    }

    internal fun setLeafEnd(i: Int, end: Int) {
        val c = chunk(i)
        val o = i and mask
        c.end[o] = end
        c.subtree[o] = 1
    }

    internal fun closeContainer(i: Int, end: Int) {
        val c = chunk(i)
        val o = i and mask
        c.end[o] = end
        c.subtree[o] = size - i
    }

    internal fun addChild(parent: Int) {
        chunk(parent).count[parent and mask]++
    }

    internal fun publish() {
        published = size
    }

    /** Called once the scan is over: the last chunk gives back the nodes it did not use. */
    internal fun trim() {
        if (size == 0) return
        val last = chunks[(size - 1) ushr shift] ?: return
        last.shrink(((size - 1) and mask) + 1)
    }

    internal val allocated: Int get() = size

    // --- Reader side ------------------------------------------------------------------------------------------------

    private fun chunk(i: Int): Chunk = chunks[i ushr shift]!!

    fun kind(i: Int): JsonKind = KINDS[chunk(i).kind[i and mask].toInt() and KIND_MASK]
    fun hasKey(i: Int): Boolean = (chunk(i).kind[i and mask].toInt() and HAS_KEY) != 0
    fun start(i: Int): Int = chunk(i).start[i and mask]
    fun end(i: Int): Int = chunk(i).end[i and mask]
    fun parent(i: Int): Int = chunk(i).parent[i and mask]
    fun childCount(i: Int): Int = chunk(i).count[i and mask]
    fun depth(i: Int): Int = chunk(i).depth[i and mask].toInt()

    /** Nodes in the subtree including the node itself; 0 while a container is still being scanned. */
    fun subtreeSize(i: Int): Int = chunk(i).subtree[i and mask]

    fun isContainer(i: Int): Boolean = (chunk(i).kind[i and mask].toInt() and KIND_MASK) <= KIND_ARRAY

    /** True when every child is a scalar, so child k is node `i + 1 + k`. */
    fun isFlat(i: Int): Boolean = subtreeSize(i) == childCount(i) + 1

    /** Heap held by the node arrays, in bytes. */
    fun memoryBytes(): Long {
        var total = 0L
        for (c in chunks) if (c != null) total += c.bytes()
        return total
    }

    fun children(i: Int): IntArray {
        val n = childCount(i)
        if (n == 0) return EMPTY_INTS
        val limit = published
        val out = IntArray(n)
        if (isFlat(i)) {
            val take = minOf(n, limit - i - 1).coerceAtLeast(0)
            for (k in 0 until take) out[k] = i + 1 + k
            return if (take == n) out else out.copyOf(take)
        }
        var c = i + 1
        var k = 0
        while (k < n && c < limit) {
            out[k++] = c
            val st = subtreeSize(c)
            if (st == 0) break
            c += st
        }
        return if (k == n) out else out.copyOf(k)
    }

    /** Child number [k] of container [i], or -1. Constant time for flat containers, otherwise one step per skipped child. */
    fun childAt(i: Int, k: Int): Int {
        if (k < 0 || k >= childCount(i)) return -1
        val limit = published
        if (isFlat(i)) return (i + 1 + k).takeIf { it < limit } ?: -1
        var c = i + 1
        var step = 0
        while (c < limit) {
            if (step == k) return c
            val st = subtreeSize(c)
            if (st == 0) return -1
            c += st
            step++
        }
        return -1
    }

    /** Position of [node] among the children of its parent. */
    fun ordinalOf(node: Int): Int {
        val p = parent(node)
        if (p < 0) return 0
        if (isFlat(p)) return node - p - 1
        var c = p + 1
        var k = 0
        while (c < node) {
            val st = subtreeSize(c)
            if (st == 0) break
            c += st
            k++
        }
        return k
    }

    internal fun load(from: Int, to: Int, out: Slice) {
        val a = source.array
        val n = (to - from).coerceAtLeast(0)
        if (a != null) {
            out.arr = a
            out.off = from
            out.len = minOf(n, a.size - from).coerceAtLeast(0)
        } else {
            if (out.scratch.size < n) out.scratch = ByteArray(maxOf(n, 256))
            val got = source.read(from.toLong(), out.scratch, 0, n)
            out.arr = out.scratch
            out.off = 0
            out.len = got
        }
    }

    /**
     * For a member node: the position of the closing quote of its key in the high 32 bits and the first byte of its value
     * in the low 32 bits. For other nodes both halves are [start].
     */
    internal fun memberSpan(i: Int, s: Slice): Long {
        val st = start(i)
        if (!hasKey(i)) return pack(st, st)
        val en = end(i)
        var window = minOf(en - st, 256)
        load(st, st + window, s)
        var p = 1
        while (true) {
            if (p >= s.len) {
                if (st + s.len >= en || window >= en - st) return pack(en, en)
                window = minOf(en - st, window * 2)
                load(st, st + window, s)
                continue
            }
            val b = s.arr[s.off + p].toInt()
            if (b == BACKSLASH) {
                p += 2
                continue
            }
            if (b == QUOTE) break
            p++
        }
        val keyEnd = st + p
        var q = p + 1
        while (true) {
            if (q >= s.len) {
                if (st + s.len >= en || window >= en - st) break
                window = minOf(en - st, window * 2)
                load(st, st + window, s)
                continue
            }
            val b = s.arr[s.off + q].toInt()
            if (b == SPACE || b == TAB || b == LF || b == CR || b == COLON) q++ else break
        }
        return pack(keyEnd, st + q)
    }

    /** The calling thread's scratch view, handed to [block] and cleared afterwards so no thread keeps a document alive. */
    private inline fun <R> withSlice(block: (Slice) -> R): R {
        val s = TLS.get()
        try {
            return block(s)
        } finally {
            s.clear()
        }
    }

    /** First byte of the value token (after the key and colon for members). */
    fun valueStart(i: Int): Int = if (hasKey(i)) withSlice { (memberSpan(i, it) and 0xFFFFFFFFL).toInt() } else start(i)

    /** Decoded key of a member node, null for array items and documents. */
    fun keyOf(i: Int): String? {
        if (!hasKey(i)) return null
        return withSlice { s ->
            val keyEnd = (memberSpan(i, s) ushr 32).toInt()
            load(start(i) + 1, keyEnd, s)
            JsonStrings.decode(s.arr, s.off, s.len, source.charset)
        }
    }

    /** Whether the key of member [i] equals [key]. Compares bytes when the key has no escapes. */
    fun keyEquals(i: Int, key: String, keyUtf8: ByteArray?): Boolean {
        if (!hasKey(i)) return false
        return withSlice { s ->
            val keyEnd = (memberSpan(i, s) ushr 32).toInt()
            val from = start(i) + 1
            var same = false
            if (keyUtf8 != null && keyEnd - from == keyUtf8.size) {
                load(from, keyEnd, s)
                same = s.len == keyUtf8.size
                var k = 0
                while (same && k < keyUtf8.size) {
                    if (s.arr[s.off + k] != keyUtf8[k]) same = false
                    k++
                }
            }
            if (same) {
                true
            } else {
                load(from, keyEnd, s)
                JsonStrings.decode(s.arr, s.off, s.len, source.charset) == key
            }
        }
    }

    /** The source text of a scalar value token, including the quotes of a string. */
    fun rawValue(i: Int, maxChars: Int = Int.MAX_VALUE): String = withSlice { s ->
        val vs = if (hasKey(i)) (memberSpan(i, s) and 0xFFFFFFFFL).toInt() else start(i)
        val en = end(i)
        val limitBytes = if (maxChars >= Int.MAX_VALUE / 4) Int.MAX_VALUE else maxChars * 4
        load(vs, if (en - vs > limitBytes) vs + limitBytes else en, s)
        String(s.arr, s.off, s.len, source.charset)
    }

    /** Decoded content of a string node; the literal text for numbers, true, false and null. */
    fun stringValue(i: Int): String = withSlice { s ->
        val vs = if (hasKey(i)) (memberSpan(i, s) and 0xFFFFFFFFL).toInt() else start(i)
        val en = end(i)
        load(vs, en, s)
        if (JsonKind.STRING == kind(i)) {
            if (s.len < 2) "" else JsonStrings.decode(s.arr, s.off + 1, s.len - 2, source.charset)
        } else {
            String(s.arr, s.off, s.len, source.charset)
        }
    }

    /**
     * Decoded start of a string node, at most about [maxChars] characters; [truncated] tells whether more follows. Reads only
     * the bytes it needs, so a 10 MB string costs the same as a short one.
     */
    fun stringPreview(i: Int, maxChars: Int): Preview = withSlice { s ->
        val vs = if (hasKey(i)) (memberSpan(i, s) and 0xFFFFFFFFL).toInt() else start(i)
        val en = end(i)
        val isString = kind(i) == JsonKind.STRING
        val contentStart = if (isString) vs + 1 else vs
        val contentEnd = if (isString) en - 1 else en
        val total = (contentEnd - contentStart).coerceAtLeast(0)
        val want = minOf(total.toLong(), maxChars.toLong() * 4).toInt()
        load(contentStart, contentStart + want, s)
        var text = if (isString) {
            // A cut may end inside an escape or a multi-byte character; decode what is whole and drop the damaged tail.
            val cut = want < total
            val raw = String(s.arr, s.off, s.len, source.charset)
            val safe = if (cut) trimPartialEscape(raw) else raw
            JsonStrings.unescape(safe)
        } else {
            String(s.arr, s.off, s.len, source.charset)
        }
        var truncated = want < total
        if (text.length > maxChars) {
            text = text.substring(0, maxChars)
            truncated = true
        }
        if (truncated && text.isNotEmpty() && text.last() == REPLACEMENT_CHAR) text = text.dropLast(1)
        Preview(text, truncated, total)
    }

    class Preview(val text: String, val truncated: Boolean, val totalBytes: Int)

    private fun trimPartialEscape(raw: String): String {
        val lastBackslash = raw.lastIndexOf('\\')
        if (lastBackslash < 0) return raw
        val tail = raw.length - lastBackslash
        // "\" alone, "\u" with fewer than four hex digits: cut it off (an even run of backslashes would be complete).
        var run = 0
        var k = lastBackslash
        while (k >= 0 && raw[k] == '\\') { run++; k-- }
        if (run % 2 == 0) return raw
        val needs = if (tail >= 2 && raw[lastBackslash + 1] == 'u') 6 else 2
        return if (tail < needs) raw.substring(0, lastBackslash) else raw
    }

    /** Number of bytes of the value token. */
    fun valueByteLength(i: Int): Int = end(i) - valueStart(i)

    /** Child indexes of an object ordered by decoded key (stable, so duplicates keep their order). */
    fun childrenSortedByKey(i: Int): IntArray {
        val ch = children(i)
        val keys = Array(ch.size) { keyOf(ch[it]) ?: "" }
        val order = ch.indices.sortedWith { a, b -> keys[a].compareTo(keys[b]) }
        return IntArray(ch.size) { ch[order[it]] }
    }

    /** The bytes to hand to [findMember] when the same key is looked up in many objects. */
    fun keyBytes(key: String): ByteArray? = if (source.charset == Charsets.UTF_8) key.toByteArray(Charsets.UTF_8) else null

    /** First member of object [i] whose key equals [key], or -1. */
    fun findMember(i: Int, key: String, utf8: ByteArray? = keyBytes(key)): Int {
        val n = childCount(i)
        val limit = published
        var c = i + 1
        var k = 0
        while (k < n && c < limit) {
            if (keyEquals(c, key, utf8)) return c
            val st = subtreeSize(c)
            if (st == 0) break
            c += st
            k++
        }
        return -1
    }

    override fun close() {
        source.close()
    }

    companion object {
        /** Layout: five ints, one byte and one short. */
        const val BYTES_PER_NODE = 23

        internal const val KIND_ARRAY = 1
        internal const val KIND_MASK = 0x0F
        internal const val HAS_KEY = 0x10
        internal val KINDS = JsonKind.entries.toTypedArray()
        private val EMPTY_INTS = IntArray(0)
        private val TLS: ThreadLocal<Slice> = ThreadLocal.withInitial { Slice() }

        private const val BACKSLASH = '\\'.code
        private const val QUOTE = '"'.code
        private const val SPACE = ' '.code
        private const val TAB = '\t'.code
        private const val LF = '\n'.code
        private const val CR = '\r'.code
        private const val COLON = ':'.code
        private val REPLACEMENT_CHAR = Char(0xFFFD)

        private fun pack(high: Int, low: Int): Long = (high.toLong() shl 32) or (low.toLong() and 0xFFFFFFFFL)
    }
}
