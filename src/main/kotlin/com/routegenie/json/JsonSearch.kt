package com.routegenie.json

import com.routegenie.Limits
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class SearchQuery(
    val text: String,
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val inKeys: Boolean = true,
    val inValues: Boolean = true,
) {
    val isEmpty: Boolean get() = text.isEmpty()
}

/** A hit is a node number with a flag in the lowest bit: 1 when the key matched, 0 when the value matched. */
object SearchHit {
    fun of(node: Int, isKey: Boolean): Int = (node shl 1) or (if (isKey) 1 else 0)
    fun node(hit: Int): Int = hit ushr 1
    fun isKey(hit: Int): Boolean = (hit and 1) == 1
}

class SearchResult(
    /** Hits in document order. Only the first [count] entries are valid. */
    val hits: IntArray,
    val count: Int,
    /** More hits exist than were kept. */
    val truncated: Boolean,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val elapsedMs: Long,
    /** Set when a regular expression is not valid; the message is the parser's. */
    val regexError: String?,
    /** Nodes examined, to tell a partial result from a complete one. */
    val nodesScanned: Int,
)

/** Matches in a plain text: pairs of start offset and length. */
class TextSearchResult(
    val starts: IntArray,
    val lengths: IntArray,
    val count: Int,
    val truncated: Boolean,
    val cancelled: Boolean,
    val regexError: String?,
)

object JsonSearch {
    /**
     * Searches keys and/or scalar values of the nodes readable now. Runs on the calling thread; [cancelled] is polled about
     * every [Limits.JSON_SEARCH_CANCEL_CHECK_NODES] nodes, and the search stops by itself after [budgetMs] or [maxResults].
     */
    fun search(
        index: JsonIndex,
        query: SearchQuery,
        cancelled: () -> Boolean = { false },
        maxResults: Int = Limits.JSON_SEARCH_MAX_RESULTS,
        budgetMs: Long = Limits.JSON_SEARCH_BUDGET_MS,
    ): SearchResult {
        val t0 = System.nanoTime()
        fun elapsed() = (System.nanoTime() - t0) / 1_000_000
        val matcher = try {
            TokenMatcher(query, index.source.charset, cancelled)
        } catch (e: PatternSyntaxException) {
            return SearchResult(IntArray(0), 0, false, false, false, elapsed(), e.description ?: e.message, 0)
        }
        if (query.isEmpty) return SearchResult(IntArray(0), 0, false, false, false, 0, null, 0)

        var hits = IntArray(minOf(maxResults, 256))
        var count = 0
        var truncated = false
        var wasCancelled = false
        var timedOut = false
        val total = index.nodeCount
        var scanned = 0
        val slice = Slice()
        var i = 1
        var untilCheck = Limits.JSON_SEARCH_CANCEL_CHECK_NODES

        fun add(hit: Int): Boolean {
            if (count >= maxResults) {
                truncated = true
                return false
            }
            if (count == hits.size) hits = hits.copyOf(minOf(maxResults, hits.size * 2))
            hits[count++] = hit
            return true
        }

        try {
            while (i < total) {
                if (--untilCheck <= 0) {
                    untilCheck = Limits.JSON_SEARCH_CANCEL_CHECK_NODES
                    if (cancelled()) {
                        wasCancelled = true
                        break
                    }
                    if (elapsed() > budgetMs) {
                        timedOut = true
                        break
                    }
                }
                scanned++
                val keyed = index.hasKey(i)
                val container = index.isContainer(i)
                if (query.inKeys && keyed) {
                    val span = index.memberSpan(i, slice)
                    val keyEnd = (span ushr 32).toInt()
                    index.load(index.start(i) + 1, keyEnd, slice)
                    if (matcher.matches(slice.arr, slice.off, slice.len, true) && !add(SearchHit.of(i, true))) break
                }
                if (query.inValues && !container) {
                    val vs = if (keyed) (index.memberSpan(i, slice) and 0xFFFFFFFFL).toInt() else index.start(i)
                    val en = index.end(i)
                    val isString = index.kind(i) == JsonKind.STRING
                    val from = if (isString) vs + 1 else vs
                    val to = if (isString) en - 1 else en
                    index.load(from, to, slice)
                    if (matcher.matches(slice.arr, slice.off, slice.len, isString) && !add(SearchHit.of(i, false))) break
                }
                i++
            }
        } catch (e: SearchInterrupted) {
            wasCancelled = true
        }
        return SearchResult(hits, count, truncated, wasCancelled, timedOut, elapsed(), null, scanned)
    }

    /** Searches a text that is already in memory (the pretty or raw view of the current page). */
    fun searchText(
        text: CharSequence,
        query: SearchQuery,
        cancelled: () -> Boolean = { false },
        maxResults: Int = Limits.JSON_SEARCH_MAX_RESULTS,
    ): TextSearchResult {
        if (query.isEmpty) return TextSearchResult(IntArray(0), IntArray(0), 0, false, false, null)
        var starts = IntArray(minOf(maxResults, 64))
        var lengths = IntArray(starts.size)
        var count = 0
        var truncated = false
        fun add(start: Int, length: Int): Boolean {
            if (count >= maxResults) {
                truncated = true
                return false
            }
            if (count == starts.size) {
                starts = starts.copyOf(minOf(maxResults, starts.size * 2))
                lengths = lengths.copyOf(starts.size)
            }
            starts[count] = start
            lengths[count] = length
            count++
            return true
        }
        try {
            if (query.regex) {
                val pattern = try {
                    Pattern.compile(query.text, if (query.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                } catch (e: PatternSyntaxException) {
                    return TextSearchResult(IntArray(0), IntArray(0), 0, false, false, e.description ?: e.message)
                }
                val m = pattern.matcher(InterruptibleChars(text, cancelled))
                while (m.find()) {
                    if (m.end() == m.start()) continue
                    if (!add(m.start(), m.end() - m.start())) break
                }
            } else {
                var from = 0
                val needle = query.text
                while (true) {
                    val at = indexOf(text, needle, from, !query.matchCase)
                    if (at < 0) break
                    if (!add(at, needle.length)) break
                    from = at + needle.length
                    if (count % 1024 == 0 && cancelled()) return TextSearchResult(starts, lengths, count, truncated, true, null)
                }
            }
        } catch (e: SearchInterrupted) {
            return TextSearchResult(starts, lengths, count, truncated, true, null)
        }
        return TextSearchResult(starts, lengths, count, truncated, false, null)
    }

    private fun indexOf(text: CharSequence, needle: String, from: Int, ignoreCase: Boolean): Int {
        if (text is String) return text.indexOf(needle, from, ignoreCase)
        val last = text.length - needle.length
        var i = from
        while (i <= last) {
            if (regionEquals(text, i, needle, ignoreCase)) return i
            i++
        }
        return -1
    }

    private fun regionEquals(text: CharSequence, at: Int, needle: String, ignoreCase: Boolean): Boolean {
        for (k in needle.indices) {
            val a = text[at + k]
            val b = needle[k]
            if (a != b && !(ignoreCase && (a.lowercaseChar() == b.lowercaseChar()))) return false
        }
        return true
    }
}

internal class SearchInterrupted : RuntimeException(null, null, false, false)

/** Lets a regular expression that backtracks for too long be stopped: every few thousand reads it asks whether to quit. */
internal class InterruptibleChars(private val inner: CharSequence, private val cancelled: () -> Boolean) : CharSequence {
    private var reads = 0
    override val length: Int get() = inner.length
    override fun get(index: Int): Char {
        if (++reads and 0x3FFF == 0 && cancelled()) throw SearchInterrupted()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = InterruptibleChars(inner.subSequence(startIndex, endIndex), cancelled)
    override fun toString(): String = inner.toString()
}

/** Decides whether the bytes of one key or value match the query, decoding only when it has to. */
private class TokenMatcher(private val query: SearchQuery, private val charset: java.nio.charset.Charset, private val cancelled: () -> Boolean) {
    private val pattern: Pattern? =
        if (query.regex) Pattern.compile(query.text, if (query.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) else null
    private val ascii = query.text.all { it.code in 0x20 until 0x7F }
    private val needsEscapeCheck = !ascii || query.text.any { it == '"' || it == '\\' }
    private val utf8 = charset == Charsets.UTF_8
    private val foldedBytes: ByteArray = if (ascii) {
        (if (query.matchCase) query.text else query.text.lowercase()).toByteArray(Charsets.ISO_8859_1)
    } else {
        EMPTY
    }
    private val rawBytes: ByteArray = if (!ascii && utf8) query.text.toByteArray(Charsets.UTF_8) else EMPTY

    fun matches(arr: ByteArray, off: Int, len: Int, isString: Boolean): Boolean {
        if (pattern != null) return pattern.matcher(InterruptibleChars(decode(arr, off, len, isString), cancelled)).find()
        if (ascii) {
            if (findAscii(arr, off, len)) return true
            if (isString && needsEscapeCheck && hasBackslash(arr, off, len)) return containsText(decode(arr, off, len, true))
            return false
        }
        if (utf8 && query.matchCase && findRaw(arr, off, len)) return true
        if (!isString && !utf8) return containsText(String(arr, off, len, charset))
        return if (!query.matchCase || isString && hasBackslash(arr, off, len) || !utf8) containsText(decode(arr, off, len, isString)) else false
    }

    private fun decode(arr: ByteArray, off: Int, len: Int, isString: Boolean): String =
        if (isString) JsonStrings.decode(arr, off, len, charset) else String(arr, off, len, charset)

    private fun containsText(text: String): Boolean = text.contains(query.text, ignoreCase = !query.matchCase)

    private fun hasBackslash(arr: ByteArray, off: Int, len: Int): Boolean {
        for (k in off until off + len) if (arr[k] == BACKSLASH) return true
        return false
    }

    private fun findAscii(arr: ByteArray, off: Int, len: Int): Boolean {
        val pat = foldedBytes
        val m = pat.size
        if (m > len) return false
        val first = pat[0]
        val fold = !query.matchCase
        val last = off + len - m
        var i = off
        while (i <= last) {
            var b = arr[i]
            if (fold && b >= UPPER_A && b <= UPPER_Z) b = (b + CASE_GAP).toByte()
            if (b == first) {
                var k = 1
                while (k < m) {
                    var c = arr[i + k]
                    if (fold && c >= UPPER_A && c <= UPPER_Z) c = (c + CASE_GAP).toByte()
                    if (c != pat[k]) break
                    k++
                }
                if (k == m) return true
            }
            i++
        }
        return false
    }

    private fun findRaw(arr: ByteArray, off: Int, len: Int): Boolean {
        val pat = rawBytes
        val m = pat.size
        if (m == 0 || m > len) return false
        val last = off + len - m
        var i = off
        while (i <= last) {
            if (arr[i] == pat[0]) {
                var k = 1
                while (k < m && arr[i + k] == pat[k]) k++
                if (k == m) return true
            }
            i++
        }
        return false
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val BACKSLASH = '\\'.code.toByte()
        const val UPPER_A = 'A'.code.toByte()
        const val UPPER_Z = 'Z'.code.toByte()
        const val CASE_GAP = 32
    }
}
