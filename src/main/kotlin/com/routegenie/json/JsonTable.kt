package com.routegenie.json

import com.routegenie.Limits

/**
 * An array shown as a table: one row per element, one column per key found in the first rows (in order of appearance).
 * Elements that are not objects fill the [VALUE_COLUMN]. The table reads cells from the index on demand.
 */
class JsonTable private constructor(
    val index: JsonIndex,
    val arrayNode: Int,
    /** Column keys; [VALUE_COLUMN] stands for the element itself when it is not an object. */
    val columns: List<String>,
    private val rows: IntArray,
    /** More keys existed than the column limit allows. */
    val columnsCut: Boolean,
    /** Rows examined to discover the columns; later rows may carry keys that have no column. */
    val sampledRows: Int,
) {
    private val columnBytes: Array<ByteArray?> = Array(columns.size) { index.keyBytes(columns[it]) }
    val rowCount: Int get() = rows.size

    fun rowNode(row: Int): Int = rows[row]

    /** The node in cell ([row], [col]), or -1 when the row has no such key. */
    fun cellNode(row: Int, col: Int): Int {
        val rn = rows[row]
        val key = columns[col]
        if (key == VALUE_COLUMN) return if (index.kind(rn) == JsonKind.OBJECT) -1 else rn
        if (index.kind(rn) != JsonKind.OBJECT) return -1
        return index.findMember(rn, key, columnBytes[col])
    }

    /** Row numbers ordered by the cells of [col]; null when cancelled or when there are too many rows to sort. */
    fun sortedRows(col: Int, ascending: Boolean, cancelled: () -> Boolean = { false }): IntArray? {
        if (rows.size > Limits.JSON_TABLE_SORT_MAX_ROWS) return null
        val keys = arrayOfNulls<SortKey>(rows.size)
        for (r in rows.indices) {
            if (r % Limits.JSON_SEARCH_CANCEL_CHECK_NODES == 0 && cancelled()) return null
            keys[r] = sortKey(cellNode(r, col))
        }
        val order = (0 until rows.size).sortedWith { a, b ->
            val c = keys[a]!!.compareTo(keys[b]!!)
            if (ascending) c else -c
        }
        return order.toIntArray()
    }

    private class SortKey(val rank: Int, val number: Double, val text: String) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            if (rank != other.rank) return rank.compareTo(other.rank)
            return when (rank) {
                RANK_NUMBER -> number.compareTo(other.number)
                RANK_STRING -> text.compareTo(other.text, ignoreCase = true).let { if (it != 0) it else text.compareTo(other.text) }
                else -> 0
            }
        }
    }

    private fun sortKey(node: Int): SortKey {
        if (node < 0) return MISSING
        return when (index.kind(node)) {
            JsonKind.NUMBER -> SortKey(RANK_NUMBER, index.rawValue(node).toDoubleOrNull() ?: Double.NaN, "")
            JsonKind.STRING -> SortKey(RANK_STRING, 0.0, index.stringPreview(node, SORT_TEXT_CHARS).text)
            JsonKind.TRUE -> SortKey(RANK_BOOL, 1.0, "")
            JsonKind.FALSE -> SortKey(RANK_BOOL, 0.0, "")
            JsonKind.NULL -> SortKey(RANK_NULL, 0.0, "")
            else -> SortKey(RANK_CONTAINER, 0.0, "")
        }
    }

    companion object {
        const val VALUE_COLUMN = "(value)"
        private const val RANK_NUMBER = 0
        private const val RANK_STRING = 1
        private const val RANK_BOOL = 2
        private const val RANK_NULL = 3
        private const val RANK_CONTAINER = 4
        private const val RANK_MISSING = 5
        private const val SORT_TEXT_CHARS = 64
        private val MISSING = SortKey(RANK_MISSING, 0.0, "")

        /**
         * The array that makes the best table: the document itself when it is an array, otherwise the largest array of
         * objects within the first levels (responses are often wrapped, as in `{"data": {"items": [...]}}`). -1 when there is none.
         */
        fun detect(index: JsonIndex): Int {
            if (index.nodeCount < 2) return -1
            val root = index.root
            if (index.kind(root) == JsonKind.ARRAY) return if (index.childCount(root) > 0) root else -1
            if (index.kind(root) != JsonKind.OBJECT) return -1
            var best = -1
            var bestSize = 0
            var frontier = intArrayOf(root)
            var visited = 0
            for (level in 0 until Limits.JSON_TABLE_DETECT_DEPTH) {
                val next = ArrayList<Int>()
                for (container in frontier) {
                    for (c in index.children(container)) {
                        if (++visited > Limits.JSON_TABLE_DETECT_NODES) return best
                        when (index.kind(c)) {
                            JsonKind.OBJECT -> next += c
                            JsonKind.ARRAY -> {
                                val n = index.childCount(c)
                                val first = if (n > 0) index.childAt(c, 0) else -1
                                if (n > bestSize && first >= 0 && index.kind(first) == JsonKind.OBJECT) {
                                    best = c
                                    bestSize = n
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                frontier = next.toIntArray()
            }
            return best
        }

        fun create(index: JsonIndex, arrayNode: Int, cancelled: () -> Boolean = { false }): JsonTable? {
            val rows = index.children(arrayNode)
            val seen = LinkedHashSet<String>()
            var hasValues = false
            var cut = false
            val sample = minOf(rows.size, Limits.JSON_TABLE_SAMPLE_ROWS)
            for (r in 0 until sample) {
                if (r % 256 == 0 && cancelled()) return null
                val rn = rows[r]
                if (index.kind(rn) != JsonKind.OBJECT) {
                    hasValues = true
                    continue
                }
                for (c in index.children(rn)) {
                    val key = index.keyOf(c) ?: continue
                    if (key in seen) continue
                    if (seen.size >= Limits.JSON_TABLE_MAX_COLUMNS) {
                        cut = true
                        break
                    }
                    seen += key
                }
            }
            val columns = ArrayList<String>(seen)
            if (hasValues || columns.isEmpty()) columns += VALUE_COLUMN
            return JsonTable(index, arrayNode, columns, rows, cut, sample)
        }
    }
}
