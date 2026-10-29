package com.routegenie.json

import com.routegenie.Limits

/** What went wrong while scanning; each code has one message in the bundle (`bundleKey`). */
enum class JsonErrorCode(val bundleKey: String) {
    EMPTY("json.error.empty"),
    UNEXPECTED_CHAR("json.error.unexpected_char"),
    UNEXPECTED_END("json.error.unexpected_end"),
    BAD_ESCAPE("json.error.bad_escape"),
    BAD_UNICODE_ESCAPE("json.error.bad_unicode_escape"),
    CONTROL_CHAR("json.error.control_char"),
    BAD_NUMBER("json.error.bad_number"),
    BAD_LITERAL("json.error.bad_literal"),
    EXPECTED_COLON("json.error.expected_colon"),
    EXPECTED_KEY("json.error.expected_key"),
    EXPECTED_COMMA_OR_END("json.error.expected_comma_or_end"),
    MISMATCHED_CLOSE("json.error.mismatched_close"),
    TRAILING_COMMA("json.error.trailing_comma"),
    TRAILING_CONTENT("json.error.trailing_content"),
    TOO_DEEP("json.error.too_deep"),
    TOO_MANY_NODES("json.error.too_many_nodes"),
    TOKEN_TOO_LARGE("json.error.token_too_large"),
    READ_FAILED("json.error.read_failed"),
}

/**
 * [offset] is the byte position in the document (after a byte order mark). [detail] is a short technical fragment for the
 * message (the offending character, a limit), never document text beyond one character.
 */
class JsonError(val code: JsonErrorCode, val offset: Long, val detail: String? = null) {
    override fun toString(): String = "JsonError($code at $offset${detail?.let { ", $it" } ?: ""})"
}

/** Line and column (both 1-based) of a byte offset, and the text around it for display. */
class ErrorLocation(val line: Int, val column: Int, val lineText: String, val caretColumn: Int)

object JsonErrors {
    /** Reads the source from the start to count lines; done once, for display, never in a hot path. */
    fun locate(source: JsonSource, offset: Long, contextChars: Int = 60): ErrorLocation {
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        var line = 1
        var lineStart = 0L
        val end = minOf(offset, source.length)
        while (pos < end) {
            val n = source.readBulk(pos, buf, 0, minOf(buf.size.toLong(), end - pos).toInt())
            if (n <= 0) break
            for (k in 0 until n) if (buf[k] == '\n'.code.toByte()) {
                line++
                lineStart = pos + k + 1
            }
            pos += n
        }
        // The line may continue past the offset; read a bounded window around the offset for the context.
        val from = maxOf(lineStart, offset - contextChars)
        val windowEnd = minOf(source.length, offset + contextChars)
        val len = (windowEnd - from).toInt().coerceAtLeast(0)
        val window = ByteArray(len)
        val got = source.readBulk(from, window, 0, len)
        var text = String(window, 0, got.coerceAtLeast(0), source.charset)
        val nl = text.indexOfFirst { it == '\n' || it == '\r' }
        val caretBytes = (offset - from).toInt().coerceIn(0, got.coerceAtLeast(0))
        val caret = String(window, 0, caretBytes, source.charset).length
        if (nl in 0 until caret) text = text.substring(0, caret) else if (nl >= 0) text = text.substring(0, nl)
        val column = (offset - lineStart).toInt() + 1
        return ErrorLocation(line, column, text, caret.coerceAtMost(text.length))
    }
}

enum class DocumentMode {
    /** One document, or several on separate lines (JSON Lines) when the input has them. */
    AUTO,

    /** Exactly one document; anything after it is an error. */
    SINGLE,

    /** Every non-empty top-level value is a document, even when there is only one. */
    LINES,
}

class ScanOptions(
    val mode: DocumentMode = DocumentMode.AUTO,
    val maxDepth: Int = Limits.JSON_MAX_DEPTH,
    val maxNodes: Int = Limits.JSON_MAX_NODES,
    /** Python's json module writes NaN, Infinity and -Infinity; they are shown as numbers instead of being rejected. */
    val allowNonFinite: Boolean = true,
    /** Read window for file sources; tests make it tiny to cross window boundaries inside every kind of token. */
    val windowBytes: Int = Limits.JSON_SCAN_WINDOW_BYTES,
)

/** Receives progress on the scanning thread. The partial index may be read by other threads in the meantime. */
interface ScanListener {
    fun isCancelled(): Boolean = false
    fun onStart(index: JsonIndex) {}
    fun onProgress(index: JsonIndex) {}
}

object JsonScanner {
    /**
     * Scans the whole document (blocking; call it off the UI thread). The returned index owns [source] and closes it with
     * [JsonIndex.close]. Invalid input does not throw: the index holds everything before the problem and [JsonIndex.error].
     */
    fun scan(source: JsonSource, options: ScanOptions = ScanOptions(), listener: ScanListener? = null): JsonIndex {
        val index = JsonIndex(source)
        ScanRun(source, index, options, listener).run()
        return index
    }

    fun scan(content: JsonContent, options: ScanOptions = ScanOptions(), listener: ScanListener? = null): JsonIndex =
        scan(content.open(), options, listener)

    /** Creates the index and runs the scan in the same call but hands the index out first, for readers on other threads. */
    fun scanInto(source: JsonSource, options: ScanOptions, listener: ScanListener?, onCreated: (JsonIndex) -> Unit): JsonIndex {
        val index = JsonIndex(source)
        onCreated(index)
        ScanRun(source, index, options, listener).run()
        return index
    }
}

private class ScanFailure(val error: JsonError) : RuntimeException(null, null, false, false)
private class ScanCancelled : RuntimeException(null, null, false, false)

private class ScanRun(
    private val src: JsonSource,
    private val idx: JsonIndex,
    private val opt: ScanOptions,
    private val lis: ScanListener?,
) {
    private var buf: ByteArray
    private var pos = 0
    private var lim = 0
    private var base = 0L
    private var eof = false
    private val growable: Boolean

    /** Start of the token being scanned (kept when the window is compacted), -1 outside tokens. */
    private var tok = -1
    private var sawNewline = false

    private var stackNode = IntArray(64)
    private var stackObj = BooleanArray(64)
    private var sp = 0

    private var state = S_VALUE
    private var pendingKey = -1
    private var docs = 0
    private var nextProgress = Limits.JSON_PROGRESS_FIRST_NODES

    init {
        val array = src.array
        if (array != null) {
            buf = array
            lim = array.size
            eof = true
            growable = false
        } else {
            buf = ByteArray(opt.windowBytes)
            growable = true
        }
    }

    fun run() {
        var wrapper = -1
        try {
            wrapper = idx.allocate(KIND_ARRAY, 0, -1, 0)
            idx.lines = opt.mode == DocumentMode.LINES
            idx.publish()
            lis?.onStart(idx)
            scanAll()
            idx.closeContainer(wrapper, src.length.toInt())
            idx.documentCount = docs
            idx.lines = opt.mode == DocumentMode.LINES || docs > 1 && opt.mode == DocumentMode.AUTO
            idx.scannedBytes = src.length
            idx.status = ScanStatus.COMPLETE
        } catch (f: ScanFailure) {
            finish(wrapper, f.error, if (f.error.code == JsonErrorCode.TOO_MANY_NODES) ScanStatus.LIMITED else ScanStatus.FAILED)
        } catch (c: ScanCancelled) {
            finish(wrapper, null, ScanStatus.CANCELLED)
        } catch (e: java.io.IOException) {
            finish(wrapper, JsonError(JsonErrorCode.READ_FAILED, base + pos, e.javaClass.simpleName), ScanStatus.FAILED)
        } finally {
            idx.trim()
            idx.publish()
        }
        lis?.onProgress(idx)
    }

    /** Leaves the partial index consistent: open containers end where the scan stopped. */
    private fun finish(wrapper: Int, error: JsonError?, status: ScanStatus) {
        val stop = if (error != null) error.offset.coerceAtMost(src.length).toInt() else (base + pos).coerceAtMost(src.length).toInt()
        for (k in sp - 1 downTo 0) idx.closeContainer(stackNode[k], stop)
        sp = 0
        if (wrapper >= 0) idx.closeContainer(wrapper, stop)
        idx.documentCount = docs
        idx.lines = opt.mode == DocumentMode.LINES || docs > 1 && opt.mode == DocumentMode.AUTO
        idx.scannedBytes = (base + pos).coerceAtMost(src.length)
        idx.error = error
        idx.status = status
    }

    private fun abs(p: Int): Int {
        val a = base + p
        if (a > Int.MAX_VALUE) throw ScanFailure(JsonError(JsonErrorCode.TOKEN_TOO_LARGE, a, "2 GB"))
        return a.toInt()
    }

    private fun fail(code: JsonErrorCode, p: Int, detail: String? = null): Nothing =
        throw ScanFailure(JsonError(code, base + p, detail))

    private fun describe(c: Int): String = when {
        c < 0 -> "byte 0x" + Integer.toHexString(c and 0xFF)
        c < 0x20 -> "0x" + Integer.toHexString(c)
        c < 0x7F -> c.toChar().toString()
        else -> "byte 0x" + Integer.toHexString(c and 0xFF)
    }

    /**
     * Reads more input behind the current window. The window is first compacted so that the token being scanned (or the
     * current position) is at index 0; [pos] and [tok] are adjusted. Returns false at the end of the input.
     */
    private fun refill(): Boolean {
        if (eof) return false
        val keep = if (tok >= 0) tok else pos
        if (keep > 0) {
            System.arraycopy(buf, keep, buf, 0, lim - keep)
            lim -= keep
            base += keep
            pos -= keep
            if (tok >= 0) tok -= keep
        }
        if (lim == buf.size) {
            if (buf.size >= Limits.JSON_MAX_TOKEN_BYTES) fail(JsonErrorCode.TOKEN_TOO_LARGE, lim, (Limits.JSON_MAX_TOKEN_BYTES shr 20).toString() + " MB")
            buf = buf.copyOf(minOf(buf.size.toLong() * 2, Int.MAX_VALUE.toLong() - 8).toInt())
        }
        val n = src.readBulk(base + lim, buf, lim, buf.size - lim)
        if (n <= 0) {
            eof = true
            return false
        }
        lim += n
        return true
    }

    private fun ensure(n: Int): Boolean {
        while (lim - pos < n) if (!refill()) return lim - pos >= n
        return true
    }

    private fun skipWs(): Boolean {
        while (true) {
            var p = pos
            val b = buf
            val l = lim
            while (p < l) {
                val c = b[p].toInt()
                if (c == SPACE || c == TAB || c == CR) {
                    p++
                } else if (c == LF) {
                    sawNewline = true
                    p++
                } else {
                    pos = p
                    return true
                }
            }
            pos = p
            if (!refill()) return false
        }
    }

    private fun checkpoint() {
        val n = idx.allocated
        if (n < nextProgress) return
        nextProgress = n + Limits.JSON_PROGRESS_EVERY_NODES
        idx.scannedBytes = base + pos
        idx.publish()
        val l = lis ?: return
        if (l.isCancelled()) throw ScanCancelled()
        l.onProgress(idx)
    }

    private fun push(node: Int, isObject: Boolean) {
        if (sp == stackNode.size) {
            stackNode = stackNode.copyOf(sp * 2)
            stackObj = stackObj.copyOf(sp * 2)
        }
        stackNode[sp] = node
        stackObj[sp] = isObject
        sp++
        if (sp > idx.maxDepth) idx.maxDepth = sp
    }

    private fun afterValue() {
        state = if (sp == 0) S_TOP_AFTER else S_AFTER
        if (sp == 0) {
            docs++
            sawNewline = false
        }
    }

    private fun scanAll() {
        while (true) {
            if (!skipWs()) {
                endOfInput()
                return
            }
            val c = buf[pos].toInt()
            when (state) {
                S_VALUE -> {
                    if (c == RBRACKET || c == RBRACE) {
                        fail(if (afterComma) JsonErrorCode.TRAILING_COMMA else JsonErrorCode.UNEXPECTED_CHAR, pos, describe(c))
                    }
                    beginValue(c)
                }
                S_ARR_FIRST -> if (c == RBRACKET) closeContainer() else beginValue(c)
                S_OBJ_FIRST -> if (c == RBRACE) closeContainer() else beginKey(c)
                S_OBJ_KEY -> {
                    if (c == RBRACE) fail(JsonErrorCode.TRAILING_COMMA, pos, describe(c))
                    beginKey(c)
                }
                S_COLON -> {
                    if (c != COLON) fail(JsonErrorCode.EXPECTED_COLON, pos, describe(c))
                    pos++
                    state = S_VALUE
                    afterComma = false
                }
                S_AFTER -> {
                    val inObject = stackObj[sp - 1]
                    when {
                        c == COMMA -> {
                            pos++
                            state = if (inObject) S_OBJ_KEY else S_VALUE
                            afterComma = true
                        }
                        c == RBRACE && inObject || c == RBRACKET && !inObject -> closeContainer()
                        c == RBRACE || c == RBRACKET -> fail(JsonErrorCode.MISMATCHED_CLOSE, pos, describe(c))
                        else -> fail(JsonErrorCode.EXPECTED_COMMA_OR_END, pos, describe(c))
                    }
                }
                else -> { // S_TOP_AFTER: only reached with a non-space character after a complete document
                    if (opt.mode == DocumentMode.SINGLE || !sawNewline) fail(JsonErrorCode.TRAILING_CONTENT, pos, describe(c))
                    sawNewline = false
                    if (opt.mode == DocumentMode.AUTO) idx.lines = true
                    state = S_VALUE
                    afterComma = false
                    // The next loop turn reads the value; this turn only switched state.
                }
            }
        }
    }

    private var afterComma = false

    private fun endOfInput() {
        val end = (base + lim).toInt()
        when {
            sp > 0 -> throw ScanFailure(JsonError(JsonErrorCode.UNEXPECTED_END, base + lim, null))
            state == S_TOP_AFTER -> Unit
            docs == 0 -> throw ScanFailure(JsonError(JsonErrorCode.EMPTY, 0, null))
            else -> throw ScanFailure(JsonError(JsonErrorCode.UNEXPECTED_END, end.toLong(), null))
        }
    }

    private fun closeContainer() {
        val node = stackNode[sp - 1]
        idx.closeContainer(node, abs(pos + 1))
        sp--
        pos++
        afterValue()
    }

    private fun beginKey(c: Int) {
        if (c != QUOTE) fail(JsonErrorCode.EXPECTED_KEY, pos, describe(c))
        pendingKey = abs(pos)
        scanString()
        state = S_COLON
    }

    private fun allocate(kind: Int, startAbs: Int): Int {
        if (idx.allocated >= opt.maxNodes) fail(JsonErrorCode.TOO_MANY_NODES, pos, opt.maxNodes.toString())
        val parent = if (sp == 0) 0 else stackNode[sp - 1]
        val node = idx.allocate(if (pendingKey >= 0) kind or JsonIndex.HAS_KEY else kind, startAbs, parent, sp + 1)
        idx.addChild(parent)
        pendingKey = -1
        return node
    }

    private fun beginValue(c: Int) {
        val startAbs = if (pendingKey >= 0) pendingKey else abs(pos)
        when (c) {
            LBRACE, LBRACKET -> {
                if (sp >= opt.maxDepth) fail(JsonErrorCode.TOO_DEEP, pos, opt.maxDepth.toString())
                val isObject = c == LBRACE
                val node = allocate(if (isObject) KIND_OBJECT else KIND_ARRAY, startAbs)
                push(node, isObject)
                pos++
                state = if (isObject) S_OBJ_FIRST else S_ARR_FIRST
                afterComma = false
                checkpoint()
                return
            }
            QUOTE -> {
                scanString()
                val node = allocate(KIND_STRING, startAbs)
                idx.setLeafEnd(node, abs(pos))
            }
            't'.code -> literal(TRUE_BYTES, KIND_TRUE, startAbs)
            'f'.code -> literal(FALSE_BYTES, KIND_FALSE, startAbs)
            'n'.code -> literal(NULL_BYTES, KIND_NULL, startAbs)
            'N'.code -> nonFinite(NAN_BYTES, startAbs)
            'I'.code -> nonFinite(INFINITY_BYTES, startAbs)
            else -> {
                if (c == MINUS || c in ZERO..NINE) scanNumber(startAbs) else fail(JsonErrorCode.UNEXPECTED_CHAR, pos, describe(c))
            }
        }
        afterValue()
        checkpoint()
    }

    private fun isIdentChar(c: Int): Boolean =
        c in ZERO..NINE || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c == '_'.code || c == '.'.code || c == '+'.code || c == MINUS

    private fun literal(word: ByteArray, kind: Int, startAbs: Int) {
        if (!ensure(word.size)) fail(JsonErrorCode.BAD_LITERAL, pos, String(word))
        for (k in word.indices) if (buf[pos + k] != word[k]) fail(JsonErrorCode.BAD_LITERAL, pos + k, String(word))
        if (ensure(word.size + 1) && isIdentChar(buf[pos + word.size].toInt())) fail(JsonErrorCode.BAD_LITERAL, pos, String(word))
        pos += word.size
        val node = allocate(kind, startAbs)
        idx.setLeafEnd(node, abs(pos))
    }

    private fun nonFinite(word: ByteArray, startAbs: Int) {
        if (!opt.allowNonFinite) fail(JsonErrorCode.UNEXPECTED_CHAR, pos, describe(buf[pos].toInt()))
        literalNumber(word, startAbs)
    }

    /** [skip] bytes (a minus sign) precede the word. */
    private fun literalNumber(word: ByteArray, startAbs: Int, skip: Int = 0) {
        val total = skip + word.size
        if (!ensure(total)) fail(JsonErrorCode.BAD_LITERAL, pos, String(word))
        for (k in word.indices) if (buf[pos + skip + k] != word[k]) fail(JsonErrorCode.BAD_LITERAL, pos + skip + k, String(word))
        if (ensure(total + 1) && isIdentChar(buf[pos + total].toInt())) fail(JsonErrorCode.BAD_LITERAL, pos, String(word))
        pos += total
        val node = allocate(KIND_NUMBER, startAbs)
        idx.setLeafEnd(node, abs(pos))
    }

    /** pos is at the opening quote; on return it is behind the closing quote. */
    private fun scanString() {
        tok = pos
        var p = pos + 1
        while (true) {
            val b = buf
            val l = lim
            scan@ while (p < l) {
                val c = b[p].toInt()
                if (c == QUOTE) {
                    pos = p + 1
                    tok = -1
                    return
                }
                if (c == BACKSLASH) {
                    if (p + 1 >= l) break@scan
                    when (b[p + 1].toInt()) {
                        QUOTE, BACKSLASH, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code -> p += 2
                        'u'.code -> {
                            if (p + 5 >= l) break@scan
                            for (k in 2..5) if (!isHex(b[p + k].toInt())) fail(JsonErrorCode.BAD_UNICODE_ESCAPE, p + k, describe(b[p + k].toInt()))
                            p += 6
                        }
                        else -> fail(JsonErrorCode.BAD_ESCAPE, p + 1, describe(b[p + 1].toInt()))
                    }
                    continue@scan
                }
                if (c and 0xE0 == 0) fail(JsonErrorCode.CONTROL_CHAR, p, describe(c))
                p++
            }
            pos = p
            if (!refill()) fail(JsonErrorCode.UNEXPECTED_END, lim, null)
            p = pos
        }
    }

    private fun isHex(c: Int): Boolean = c in ZERO..NINE || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code

    /** Grammar of RFC 8259, plus the Python extras (-Infinity) when allowed. pos is at the first character. */
    private fun scanNumber(startAbs: Int) {
        while (true) {
            tok = pos
            val b = buf
            val l = lim
            var p = pos
            if (b[p].toInt() == MINUS) {
                p++
                if (p < l && b[p].toInt() == 'I'.code && opt.allowNonFinite) {
                    tok = -1
                    literalNumber(INFINITY_BYTES, startAbs, 1)
                    return
                }
            }
            var needMore = false
            // integer part
            if (p >= l) {
                needMore = true
            } else {
                val c = b[p].toInt()
                if (c == ZERO) {
                    p++
                    if (p < l && b[p].toInt() in ZERO..NINE) fail(JsonErrorCode.BAD_NUMBER, p, "leading zero")
                } else if (c in ONE..NINE) {
                    p++
                    while (p < l && b[p].toInt() in ZERO..NINE) p++
                } else {
                    fail(JsonErrorCode.BAD_NUMBER, p, describe(c))
                }
            }
            if (!needMore && p < l && b[p].toInt() == '.'.code) {
                p++
                val digitsFrom = p
                while (p < l && b[p].toInt() in ZERO..NINE) p++
                if (p == digitsFrom && p < l) fail(JsonErrorCode.BAD_NUMBER, p, "digits expected after the decimal point")
            }
            if (!needMore && p < l && (b[p].toInt() == 'e'.code || b[p].toInt() == 'E'.code)) {
                p++
                if (p < l && (b[p].toInt() == '+'.code || b[p].toInt() == MINUS)) p++
                val digitsFrom = p
                while (p < l && b[p].toInt() in ZERO..NINE) p++
                if (p == digitsFrom && p < l) fail(JsonErrorCode.BAD_NUMBER, p, "digits expected in the exponent")
            }
            if (p >= l && !eof) needMore = true
            if (needMore) {
                // Refilling compacts the window even when it finds no more input, so the local copies are stale either way.
                val wasEof = eof
                refill()
                if (!wasEof) continue
            }
            if (p >= l) {
                // end of input inside the token: the token is valid only when it ended on a digit
                val last = b[p - 1].toInt()
                if (last !in ZERO..NINE) fail(JsonErrorCode.BAD_NUMBER, p, "unfinished number")
            } else if (isIdentChar(b[p].toInt())) {
                fail(JsonErrorCode.BAD_NUMBER, p, describe(b[p].toInt()))
            }
            pos = p
            tok = -1
            val node = allocate(KIND_NUMBER, startAbs)
            idx.setLeafEnd(node, abs(p))
            return
        }
    }

    private companion object {
        const val S_VALUE = 0
        const val S_ARR_FIRST = 1
        const val S_OBJ_FIRST = 2
        const val S_OBJ_KEY = 3
        const val S_COLON = 4
        const val S_AFTER = 5
        const val S_TOP_AFTER = 6

        const val KIND_OBJECT = 0
        const val KIND_ARRAY = 1
        const val KIND_STRING = 2
        const val KIND_NUMBER = 3
        const val KIND_TRUE = 4
        const val KIND_FALSE = 5
        const val KIND_NULL = 6

        const val SPACE = 0x20
        const val TAB = 0x09
        const val LF = 0x0A
        const val CR = 0x0D
        const val QUOTE = '"'.code
        const val BACKSLASH = '\\'.code
        const val LBRACE = '{'.code
        const val RBRACE = '}'.code
        const val LBRACKET = '['.code
        const val RBRACKET = ']'.code
        const val COLON = ':'.code
        const val COMMA = ','.code
        const val MINUS = '-'.code
        const val ZERO = '0'.code
        const val ONE = '1'.code
        const val NINE = '9'.code

        val TRUE_BYTES = "true".toByteArray()
        val FALSE_BYTES = "false".toByteArray()
        val NULL_BYTES = "null".toByteArray()
        val NAN_BYTES = "NaN".toByteArray()
        val INFINITY_BYTES = "Infinity".toByteArray()
    }
}
