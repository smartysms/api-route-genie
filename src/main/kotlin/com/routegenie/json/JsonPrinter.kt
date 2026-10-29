package com.routegenie.json

import com.routegenie.Limits
import java.io.Writer

/** Where printed text goes. Output is appended in pieces, so no giant string has to exist. */
interface CharSink {
    fun append(c: Char)
    fun append(chars: CharArray, off: Int, len: Int)
    fun append(text: String)
}

class StringSink(val builder: StringBuilder = StringBuilder()) : CharSink {
    override fun append(c: Char) {
        builder.append(c)
    }

    override fun append(chars: CharArray, off: Int, len: Int) {
        builder.append(chars, off, len)
    }

    override fun append(text: String) {
        builder.append(text)
    }
}

class WriterSink(private val writer: Writer) : CharSink {
    override fun append(c: Char) = writer.write(c.code)
    override fun append(chars: CharArray, off: Int, len: Int) = writer.write(chars, off, len)
    override fun append(text: String) = writer.write(text)
}

class PrintOptions(
    val indent: Int = Limits.JSON_INDENT_DEFAULT,
    val sortKeys: Boolean = false,
    val minify: Boolean = false,
)

/** A saved position of the printer, for starting a page in the middle of a large document. */
class PrintState internal constructor(
    internal val written: Long,
    internal val started: Boolean,
    internal val finished: Boolean,
    internal val node: IntArray,
    internal val remaining: IntArray,
    internal val next: IntArray,
    internal val sorted: Array<IntArray?>,
    internal val sortedPos: IntArray,
    internal val first: BooleanArray,
    internal val lines: BooleanArray,
    internal val openAt: LongArray,
    internal val linesRoot: Boolean,
    /** Every node before this one has been printed; nodes are in document order, so it locates the page of a node. */
    internal val resumeNode: Int,
)

/**
 * Called when a container has been printed completely. With a sink the offsets are characters written during this run (so they
 * index the produced text; [openOffset] is -1 when the container was opened by an earlier page); without one they are units.
 */
fun interface PrintObserver {
    fun container(node: Int, openOffset: Long, closeOffset: Long)
}

/**
 * Streams a pretty (or minified) rendering of a node from the index, in steps. Strings and numbers are copied from the
 * source exactly as written, so nothing is reformatted or reordered unless sorting is asked for. The printer is iterative
 * (no recursion), so nesting depth cannot overflow the stack, and it can be paused and resumed from a [PrintState].
 *
 * Output is counted in units: one per structural character and one per byte of a token. A dry run (no sink) counts the
 * same units without decoding anything, which is how page boundaries are found cheaply.
 */
class JsonPrinter(private val index: JsonIndex, private val options: PrintOptions = PrintOptions()) {
    private var sink: CharSink? = null
    var written: Long = 0
        private set
    private var started = false
    private var finished = false
    private var linesRoot = false
    private var skipNewline = false
    private var pendingRoot = -1
    private var pendingRootKey = false
    private var chars = 0L
    private var fOpenChars = LongArray(32)

    /** When set, [revealOffset] receives the character offset at which this node starts to print (-1 if it was not reached). */
    var revealNode: Int = -1
    var revealOffset: Long = -1

    private var sp = 0
    private var fNode = IntArray(32)
    private var fRemaining = IntArray(32)
    private var fNext = IntArray(32)
    private var fSorted = arrayOfNulls<IntArray>(32)
    private var fSortedPos = IntArray(32)
    private var fFirst = BooleanArray(32)
    private var fLines = BooleanArray(32)
    private var fOpenAt = LongArray(32)

    private val slice = Slice()
    private var tokenChars = CharArray(256)
    var observer: PrintObserver? = null

    val isFinished: Boolean get() = finished

    /** Prepares printing of [node]; nothing is written until [step]. A member node prints its key only with [withKey]. */
    fun begin(node: Int = index.root, withKey: Boolean = false) {
        sp = 0
        written = 0
        finished = false
        started = true
        skipNewline = false
        chars = 0
        revealOffset = -1
        pendingRoot = node
        pendingRootKey = withKey
        linesRoot = index.lines && node == 0
    }

    private fun startRoot(node: Int) {
        if (linesRoot) {
            pushFrame(0, isLines = true)
            if (fRemaining[0] == 0) finished = true
        } else {
            beginNode(node, 0, pendingRootKey)
            if (sp == 0) finished = true
        }
    }

    fun snapshot(): PrintState = PrintState(
        written, started, finished, fNode.copyOf(sp), fRemaining.copyOf(sp), fNext.copyOf(sp), fSorted.copyOf(sp),
        fSortedPos.copyOf(sp), fFirst.copyOf(sp), fLines.copyOf(sp), fOpenAt.copyOf(sp), linesRoot,
        if (sp > 0) fNext[sp - 1] else index.nodeCount,
    )

    fun restore(state: PrintState) {
        sp = state.node.size
        ensureFrames(sp + 1)
        System.arraycopy(state.node, 0, fNode, 0, sp)
        System.arraycopy(state.remaining, 0, fRemaining, 0, sp)
        System.arraycopy(state.next, 0, fNext, 0, sp)
        System.arraycopy(state.sorted, 0, fSorted, 0, sp)
        System.arraycopy(state.sortedPos, 0, fSortedPos, 0, sp)
        System.arraycopy(state.first, 0, fFirst, 0, sp)
        System.arraycopy(state.lines, 0, fLines, 0, sp)
        System.arraycopy(state.openAt, 0, fOpenAt, 0, sp)
        java.util.Arrays.fill(fOpenChars, 0, sp, -1L)
        chars = 0
        revealOffset = -1
        written = state.written
        started = state.started
        finished = state.finished
        linesRoot = state.linesRoot
        // A page starts at the end of a line: the line break that would come first belongs to the previous page.
        skipNewline = true
    }

    /**
     * Prints until about [budget] more units are written or the node is complete. Returns true when complete. With a null
     * [out] it only counts.
     */
    fun step(out: CharSink?, budget: Long): Boolean {
        sink = out
        if (!started) begin()
        val limit = written + budget
        try {
            if (pendingRoot >= 0) {
                val root = pendingRoot
                pendingRoot = -1
                startRoot(root)
            }
            while (!finished) {
                if (written >= limit) return false
                val f = sp - 1
                if (fRemaining[f] == 0) {
                    closeFrame(f)
                    continue
                }
                val child = takeChild(f)
                if (!(fLines[f] && fFirst[f])) newline(levelOf(f) + 1, force = fLines[f])
                fFirst[f] = false
                beginNode(child, levelOf(f) + 1)
            }
            return true
        } finally {
            sink = null
        }
    }

    private fun levelOf(frame: Int): Int = if (linesRoot) frame - 1 else frame

    private fun ensureFrames(n: Int) {
        if (n <= fNode.size) return
        val size = maxOf(n, fNode.size * 2)
        fNode = fNode.copyOf(size)
        fRemaining = fRemaining.copyOf(size)
        fNext = fNext.copyOf(size)
        fSorted = fSorted.copyOf(size)
        fSortedPos = fSortedPos.copyOf(size)
        fFirst = fFirst.copyOf(size)
        fLines = fLines.copyOf(size)
        fOpenAt = fOpenAt.copyOf(size)
        fOpenChars = fOpenChars.copyOf(size)
    }

    private fun pushFrame(node: Int, isLines: Boolean) {
        ensureFrames(sp + 1)
        val f = sp++
        fNode[f] = node
        fRemaining[f] = index.childCount(node)
        fNext[f] = node + 1
        fSortedPos[f] = 0
        fFirst[f] = true
        fLines[f] = isLines
        fOpenAt[f] = written
        fOpenChars[f] = chars
        fSorted[f] = if (options.sortKeys && !isLines && index.kind(node) == JsonKind.OBJECT && fRemaining[f] <= Limits.JSON_SORT_MAX_CHILDREN) {
            index.childrenSortedByKey(node)
        } else {
            null
        }
        if (fSorted[f] != null && fSorted[f]!!.size < fRemaining[f]) fRemaining[f] = fSorted[f]!!.size
    }

    private fun takeChild(f: Int): Int {
        fRemaining[f]--
        val sorted = fSorted[f]
        if (sorted != null) return sorted[fSortedPos[f]++]
        val child = fNext[f]
        val st = index.subtreeSize(child)
        if (st == 0 || child >= index.nodeCount) fRemaining[f] = 0 else fNext[f] = child + st
        return child
    }

    private fun closeFrame(f: Int) {
        val node = fNode[f]
        val wasFirst = fFirst[f]
        val isLines = fLines[f]
        val open = fOpenChars[f]
        sp--
        if (isLines) {
            finished = true
            return
        }
        if (!wasFirst) newline(levelOf(f))
        val kind = index.kind(node)
        put(if (kind == JsonKind.OBJECT) '}' else ']')
        observer?.container(node, open, if (sink != null) chars else written)
        if (sp == 0) finished = true else finishChild()
    }

    /** A comma after a child that is followed by another one. */
    private fun finishChild() {
        val f = sp - 1
        if (fRemaining[f] > 0 && !fLines[f]) put(',')
    }

    private fun beginNode(node: Int, level: Int, printKey: Boolean = true) {
        if (node == revealNode && revealOffset < 0) revealOffset = chars
        if (printKey && index.hasKey(node)) {
            val span = index.memberSpan(node, slice)
            val keyEnd = (span ushr 32).toInt()
            token(index.start(node), keyEnd + 1)
            put(':')
            if (!options.minify) put(' ')
        }
        val kind = index.kind(node)
        if (!kind.isContainer) {
            token(index.valueStart(node), index.end(node))
            if (sp > 0) finishChild() else finished = true
            return
        }
        if (index.childCount(node) == 0) {
            put(if (kind == JsonKind.OBJECT) '{' else '[')
            put(if (kind == JsonKind.OBJECT) '}' else ']')
            if (sp > 0) finishChild() else finished = true
            return
        }
        put(if (kind == JsonKind.OBJECT) '{' else '[')
        pushFrame(node, isLines = false)
        // The opening brace is part of this container's range.
        fOpenAt[sp - 1] = written - 1
        fOpenChars[sp - 1] = chars - 1
    }

    private fun put(c: Char) {
        val s = sink
        if (s != null) {
            s.append(c)
            chars++
        }
        written++
    }

    /** A line break and indentation; [force] keeps the break when minifying (between JSON Lines documents). */
    private fun newline(level: Int, force: Boolean = false) {
        if (options.minify && !force) {
            skipNewline = false
            return
        }
        val spaces = if (options.minify) 0 else level * options.indent
        if (skipNewline) {
            skipNewline = false
        } else {
            val s = sink
            if (s != null) {
                s.append('\n')
                chars += 1L + spaces
                var left = spaces
                while (left > 0) {
                    val n = minOf(left, SPACES.size)
                    s.append(SPACES, 0, n)
                    left -= n
                }
            }
        }
        written += 1 + spaces
    }

    /** Copies source bytes [from, to) as text. */
    private fun token(from: Int, to: Int) {
        val len = to - from
        if (len <= 0) return
        val s = sink
        if (s != null) {
            index.load(from, to, slice)
            val arr = slice.arr
            val off = slice.off
            val n = slice.len
            if (tokenChars.size < n) tokenChars = CharArray(maxOf(n, tokenChars.size * 2))
            var ascii = true
            val out = tokenChars
            var k = 0
            while (k < n) {
                val b = arr[off + k].toInt()
                if (b < 0) {
                    ascii = false
                    break
                }
                out[k] = b.toChar()
                k++
            }
            if (ascii) {
                s.append(out, 0, n)
                chars += n
            } else {
                val text = String(arr, off, n, index.source.charset)
                s.append(text)
                chars += text.length
            }
        }
        written += len
    }

    companion object {
        private val SPACES = CharArray(256) { ' ' }

        /** The whole node as text; for values small enough to hold in memory (copy value, previews). */
        fun toString(index: JsonIndex, node: Int = index.root, options: PrintOptions = PrintOptions(), maxUnits: Long = Long.MAX_VALUE): String {
            val sink = StringSink()
            val printer = JsonPrinter(index, options)
            printer.begin(node)
            printer.step(sink, maxUnits)
            return sink.builder.toString()
        }

        /** Streams the node to [out]; [cancelled] is checked about once per [chunkUnits]. Returns false when cancelled. */
        fun write(
            index: JsonIndex,
            node: Int,
            options: PrintOptions,
            out: CharSink,
            chunkUnits: Long = 256L * 1024,
            cancelled: () -> Boolean = { false },
        ): Boolean {
            val printer = JsonPrinter(index, options)
            printer.begin(node)
            while (!printer.step(out, chunkUnits)) if (cancelled()) return false
            return true
        }

        /** Finds the page boundaries of the whole document by a dry run. Returns null when cancelled. */
        fun plan(index: JsonIndex, options: PrintOptions, pageUnits: Long, cancelled: () -> Boolean = { false }): PagePlan? {
            val printer = JsonPrinter(index, options)
            printer.begin(index.root)
            val states = ArrayList<PrintState>()
            while (!printer.step(null, pageUnits)) {
                if (cancelled()) return null
                states += printer.snapshot()
            }
            return PagePlan(pageUnits, states, printer.written)
        }
    }
}

/** What one rendered page produced: units written and, if a node was asked for, where it starts in the page text (-1 if not on it). */
class RenderInfo(val units: Long, val revealOffset: Long)

/** Page boundaries of a pretty rendering: page 0 starts at the beginning, page k at [states] k - 1. */
class PagePlan(val pageUnits: Long, val states: List<PrintState>, val totalUnits: Long) {
    val pageCount: Int get() = states.size + 1

    /** The page on which [node] is printed, assuming document order (not valid when keys are sorted). */
    fun pageOfNode(node: Int): Int {
        var lo = 0
        var hi = states.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (states[mid].resumeNode <= node) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Prints page [page] into [out], reporting finished containers (offsets are characters of the page text) to [observer]. */
    fun render(index: JsonIndex, options: PrintOptions, page: Int, out: CharSink, observer: PrintObserver? = null, reveal: Int = -1): RenderInfo {
        val printer = JsonPrinter(index, options)
        var base = 0L
        if (page == 0) {
            printer.begin(index.root)
        } else {
            printer.restore(states[page - 1])
            base = states[page - 1].written
        }
        printer.observer = observer
        printer.revealNode = reveal
        printer.step(out, pageUnits)
        return RenderInfo(printer.written - base, printer.revealOffset)
    }
}
