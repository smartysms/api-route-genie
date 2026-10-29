package com.routegenie.frameworks.django

import com.routegenie.model.PathParam
import com.routegenie.model.PathSyntax

/** The display form of one route fragment: its template text, the parameters it declares, and whether it was approximated. */
internal class RoutePiece(val text: String, val params: List<PathParam>, val unsupported: Boolean)

/**
 * Turns the route strings of path() and re_path() into the `{name}` template notation. Pure text processing; unknown
 * parts that an earlier stage already marked as [PathSyntax.UNKNOWN_SEGMENT] pass through unchanged.
 */
internal object RouteText {
    private const val UNKNOWN = PathSyntax.UNKNOWN_SEGMENT
    private const val NAMED_GROUP_START = "?P<"

    /** `item/<int:pk>/` becomes `item/{pk}/` with pk typed int; `<name>` alone is a str. */
    fun fromPath(route: String): RoutePiece {
        val out = StringBuilder()
        val params = ArrayList<PathParam>()
        var i = 0
        while (i < route.length) {
            val c = route[i]
            val close = if (c == '<') route.indexOf('>', i) else -1
            if (close < 0) {
                out.append(c)
                i++
                continue
            }
            val inner = route.substring(i + 1, close)
            val converter = if (':' in inner) inner.substringBefore(':') else DjangoCatalog.DEFAULT_CONVERTER
            val name = inner.substringAfter(':')
            params += PathParam(name, converter)
            out.append('{').append(name).append('}')
            i = close + 1
        }
        return RoutePiece(out.toString(), params, unsupported = false)
    }

    /**
     * Anchors are dropped, escapes undone and named groups become parameters. Anything else (alternation, optional or
     * unnamed groups, classes, quantifiers) cannot be shown exactly: it is approximated and flagged [RoutePiece.unsupported].
     */
    fun fromRegex(regex: String): RoutePiece {
        var s = regex
        if (s.startsWith("^")) s = s.substring(1)
        if (s.endsWith("$") && !s.endsWith("\\$")) s = s.dropLast(1)
        val out = StringBuilder()
        val params = ArrayList<PathParam>()
        var unsupported = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith(UNKNOWN, i) -> {
                    out.append(UNKNOWN)
                    i += UNKNOWN.length
                }
                c == '\\' && i + 1 < s.length -> {
                    val next = s[i + 1]
                    if (next.isLetterOrDigit()) {
                        out.append(UNKNOWN)
                        unsupported = true
                        i = skipQuantifier(s, i + 2)
                    } else {
                        out.append(next)
                        i += 2
                    }
                }
                c == '(' -> {
                    val end = matchingParen(s, i)
                    val inner = s.substring(i + 1, end.coerceAtMost(s.length))
                    val after = end + 1
                    if (inner.startsWith(NAMED_GROUP_START)) {
                        val name = inner.substring(NAMED_GROUP_START.length).substringBefore('>')
                        params += PathParam(name)
                        out.append('{').append(name).append('}')
                    } else {
                        out.append(UNKNOWN)
                        unsupported = true
                    }
                    val next = skipQuantifier(s, after)
                    if (next != after) unsupported = true
                    i = next
                }
                c == '[' -> {
                    out.append(UNKNOWN)
                    unsupported = true
                    i = skipQuantifier(s, charClassEnd(s, i) + 1)
                }
                c == '.' -> {
                    out.append(UNKNOWN)
                    unsupported = true
                    i = skipQuantifier(s, i + 1)
                }
                c == '?' || c == '*' || c == '+' || c == '{' || c == '|' -> {
                    unsupported = true
                    if (c == '|') out.append(c)
                    i = maxOf(i + 1, if (c == '|') i + 1 else skipQuantifier(s, i))
                }
                else -> {
                    out.append(c)
                    i++
                    val next = skipQuantifier(s, i)
                    if (next != i) {
                        unsupported = true
                        i = next
                    }
                }
            }
        }
        return RoutePiece(out.toString(), params, unsupported)
    }

    /** Index just after a quantifier (`?`, `*`, `+`, `{n,m}`) starting at [from], or [from] itself when there is none. */
    private fun skipQuantifier(s: String, from: Int): Int {
        if (from >= s.length) return from
        var i = from
        when (s[i]) {
            '?', '*', '+' -> i++
            '{' -> {
                if (s.startsWith(UNKNOWN, i)) return from
                val close = s.indexOf('}', i)
                if (close < 0) return from
                i = close + 1
            }
            else -> return from
        }
        if (i < s.length && s[i] == '?') i++
        return i
    }

    private fun matchingParen(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '[' -> i = charClassEnd(s, i)
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
            i++
        }
        return s.length
    }

    private fun charClassEnd(s: String, open: Int): Int {
        var i = open + 1
        if (i < s.length && s[i] == '^') i++
        if (i < s.length && s[i] == ']') i++
        while (i < s.length && s[i] != ']') {
            if (s[i] == '\\') i++
            i++
        }
        return i.coerceAtMost(s.length - 1)
    }
}
