package com.routegenie.json

import com.routegenie.Limits

enum class RowKind { CONTEXT, ADDED, REMOVED, CHANGED, COLLAPSED, NOTICE }

/**
 * One line of the rendered diff. [left] and [right] are the texts of the two sides (null where nothing exists on that side);
 * indent by [level] when drawing. A COLLAPSED row stands for [collapsedCount] unchanged siblings, a NOTICE row for cut output.
 */
class DiffRow(
    val kind: RowKind,
    val level: Int,
    val left: String?,
    val right: String?,
    val path: String,
    val leftNode: Int = -1,
    val rightNode: Int = -1,
    val collapsedCount: Int = 0,
) {
    val isDifference: Boolean get() = kind == RowKind.ADDED || kind == RowKind.REMOVED || kind == RowKind.CHANGED
}

class DiffRows(val rows: List<DiffRow>, val truncated: Boolean) {
    /** Row numbers where a run of differing rows begins; what "next difference" jumps between. */
    val groupStarts: IntArray = run {
        val out = ArrayList<Int>()
        var inRun = false
        rows.forEachIndexed { k, row ->
            if (row.isDifference && !inRun) out += k
            inRun = row.isDifference
        }
        out.toIntArray()
    }
}

/** Turns a [DiffResult] into aligned rows with unchanged regions collapsed. Works from the difference tree, so its cost follows the number of differences. */
object JsonDiffRows {
    fun build(result: DiffResult, context: Int = Limits.JSON_DIFF_CONTEXT_SIBLINGS, maxRows: Int = Limits.JSON_DIFF_MAX_ROWS): DiffRows {
        val run = RowRun(result, context, maxRows)
        run.start()
        return DiffRows(run.rows, run.truncated)
    }

    /** The diff as unified text: unchanged lines with two spaces, removed with `-`, added with `+`. */
    fun unifiedText(rows: DiffRows, leftTitle: String, rightTitle: String, indent: String = "  "): String {
        val sb = StringBuilder()
        sb.append("--- ").append(leftTitle).append('\n').append("+++ ").append(rightTitle).append('\n')
        for (row in rows.rows) {
            val pad = indent.repeat(row.level)
            when (row.kind) {
                RowKind.CONTEXT -> sb.append("  ").append(pad).append(row.left ?: row.right ?: "").append('\n')
                RowKind.COLLAPSED -> sb.append("  ").append(pad).append(row.left ?: "...").append('\n')
                RowKind.NOTICE -> sb.append("  ").append(pad).append(row.left ?: row.right ?: "").append('\n')
                RowKind.ADDED -> sb.append("+ ").append(pad).append(row.right ?: "").append('\n')
                RowKind.REMOVED -> sb.append("- ").append(pad).append(row.left ?: "").append('\n')
                RowKind.CHANGED -> {
                    sb.append("- ").append(pad).append(row.left ?: "").append('\n')
                    sb.append("+ ").append(pad).append(row.right ?: "").append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** One line per difference with its path: `~ $.a.b: 1 -> 2`, `+ $.c: "x"`, `- $.d: true`. */
    fun pathLines(result: DiffResult): String {
        val sb = StringBuilder()
        for (e in result.entries) {
            val l = if (e.left >= 0) preview(result.left, e.left) else null
            val r = if (e.right >= 0) preview(result.right, e.right) else null
            when (e.kind) {
                DiffKind.ADDED -> sb.append("+ ").append(e.path).append(": ").append(r)
                DiffKind.REMOVED -> sb.append("- ").append(e.path).append(": ").append(l)
                DiffKind.ORDER_CHANGED -> sb.append("~ ").append(e.path).append(": key order")
                else -> sb.append("~ ").append(e.path).append(": ").append(l).append(" -> ").append(r)
            }
            sb.append('\n')
        }
        if (result.notShown > 0) sb.append("... ").append(result.notShown).append(" more\n")
        return sb.toString()
    }

    /** Short text of any node: the value for scalars, a count for containers. */
    fun preview(index: JsonIndex, node: Int, maxChars: Int = Limits.JSON_DIFF_ROW_PREVIEW_CHARS): String {
        val kind = index.kind(node)
        if (kind.isContainer) {
            val n = index.childCount(node)
            return if (kind == JsonKind.OBJECT) "{…$n}" else "[…$n]"
        }
        val raw = index.rawValue(node, maxChars + 1)
        return if (raw.length > maxChars) raw.substring(0, maxChars) + "…" else raw
    }
}

private class RowRun(private val result: DiffResult, private val context: Int, private val maxRows: Int) {
    val rows = ArrayList<DiffRow>()
    var truncated = false
    private val path = ArrayList<PathSegment>()
    private val l = result.left
    private val r = result.right

    private class Stop : RuntimeException(null, null, false, false)

    fun start() {
        try {
            val root = result.root
            if (result.identical) return
            node(root, 0)
        } catch (e: Stop) {
            truncated = true
        }
        if (truncated) rows += DiffRow(RowKind.NOTICE, 0, "…", "…", "")
    }

    private fun add(row: DiffRow) {
        if (rows.size >= maxRows) throw Stop()
        rows += row
    }

    private fun pathText(): String = JsonPath.format(path)

    private fun label(seg: PathSegment?): String = when (seg) {
        is PathSegment.Key -> JsonStrings.quote(seg.name) + ": "
        else -> ""
    }

    private fun memberLabel(index: JsonIndex, n: Int): String = index.keyOf(n)?.let { JsonStrings.quote(it) + ": " } ?: ""

    private fun valueText(index: JsonIndex, n: Int): String = JsonDiffRows.preview(index, n)

    private fun node(d: DiffNode, level: Int) {
        val seg = d.segment
        if (seg != null) path += seg
        try {
            when (d.kind) {
                DiffKind.ADDED -> block(r, d.right, level, RowKind.ADDED, seg)
                DiffKind.REMOVED -> block(l, d.left, level, RowKind.REMOVED, seg)
                DiffKind.CHANGED, DiffKind.TYPE_CHANGED -> add(
                    DiffRow(
                        RowKind.CHANGED, level, label(seg) + valueText(l, d.left), label(seg) + valueText(r, d.right),
                        pathText(), d.left, d.right,
                    ),
                )
                else -> container(d, level)
            }
        } finally {
            if (seg != null) path.removeAt(path.size - 1)
        }
    }

    /** A container that exists on one side only: its pretty text, cut after a few lines. */
    private fun block(index: JsonIndex, n: Int, level: Int, kind: RowKind, seg: PathSegment?) {
        val printer = JsonPrinter(index, PrintOptions())
        printer.begin(n, withKey = false)
        val sink = StringSink()
        printer.step(sink, Limits.JSON_DIFF_BLOCK_LINES * BLOCK_UNITS_PER_LINE)
        val lines = sink.builder.toString().split('\n')
        val more = !printer.isFinished || lines.size > Limits.JSON_DIFF_BLOCK_LINES
        val shown = if (lines.size > Limits.JSON_DIFF_BLOCK_LINES) lines.subList(0, Limits.JSON_DIFF_BLOCK_LINES) else lines
        val text = path.let { pathText() }
        shown.forEachIndexed { k, line ->
            val t = if (k == 0) label(seg) + line else line
            val cut = if (t.length > Limits.JSON_DIFF_ROW_PREVIEW_CHARS) t.substring(0, Limits.JSON_DIFF_ROW_PREVIEW_CHARS) + "…" else t
            add(
                if (kind == RowKind.ADDED) DiffRow(kind, level, null, cut, text, -1, n) else DiffRow(kind, level, cut, null, text, n, -1),
            )
        }
        if (more) add(DiffRow(RowKind.NOTICE, level, "…", "…", text))
    }

    private fun summary(index: JsonIndex, n: Int, open: Boolean): String {
        val closer = if (index.kind(n) == JsonKind.OBJECT) "}" else "]"
        val opener = if (index.kind(n) == JsonKind.OBJECT) "{" else "["
        return if (open) opener else closer
    }

    private fun container(d: DiffNode, level: Int) {
        val ln = d.left
        val rn = d.right
        val lLabel = label(d.segment)
        add(DiffRow(RowKind.CONTEXT, level, lLabel + summary(l, ln, true), lLabel + summary(r, rn, true), pathText(), ln, rn))
        if (d.kind == DiffKind.ORDER_CHANGED) {
            add(DiffRow(RowKind.CHANGED, level + 1, keyOrder(l, ln), keyOrder(r, rn), pathText(), ln, rn))
        }
        val lch = l.children(ln)
        val total = lch.size
        val removedOrChanged = d.children.filter { it.leftPos >= 0 }.sortedBy { it.leftPos }
        val added = d.children.filter { it.leftPos < 0 }
        var cursor = 0
        var prevChanged = false

        fun unchanged(from: Int, to: Int) {
            for (p in from until to) {
                val c = lch[p]
                val text = memberLabel(l, c) + valueText(l, c)
                path += (if (l.hasKey(c)) PathSegment.Key(l.keyOf(c) ?: "") else PathSegment.Index(p))
                add(DiffRow(RowKind.CONTEXT, level + 1, text, text, pathText(), c, -1))
                path.removeAt(path.size - 1)
            }
        }

        fun window(next: Int, isTail: Boolean) {
            val afterEnd = if (prevChanged) minOf(cursor + context, next) else cursor
            unchanged(cursor, afterEnd)
            val beforeStart = if (isTail) next else maxOf(afterEnd, next - context)
            if (beforeStart > afterEnd) {
                val n = beforeStart - afterEnd
                add(DiffRow(RowKind.COLLAPSED, level + 1, null, null, pathText(), collapsedCount = n))
            }
            unchanged(beforeStart, next)
            cursor = next
        }

        for (child in removedOrChanged) {
            window(child.leftPos, isTail = false)
            node(child, level + 1)
            cursor = child.leftPos + 1
            prevChanged = true
        }
        if (added.isNotEmpty()) {
            window(total, isTail = false)
            for (child in added) node(child, level + 1)
        } else {
            window(total, isTail = true)
        }
        add(DiffRow(RowKind.CONTEXT, level, summary(l, ln, false), summary(r, rn, false), pathText(), ln, rn))
    }

    private fun keyOrder(index: JsonIndex, n: Int): String {
        val keys = index.children(n).take(KEY_ORDER_SHOWN).joinToString(", ") { index.keyOf(it) ?: "" }
        return "(keys: $keys" + (if (index.childCount(n) > KEY_ORDER_SHOWN) ", …)" else ")")
    }

    private companion object {
        const val BLOCK_UNITS_PER_LINE = 120L
        const val KEY_ORDER_SHOWN = 8
    }
}
