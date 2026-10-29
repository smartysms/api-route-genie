package com.routegenie.json

/** One step of a JSON path. */
sealed class PathSegment {
    data class Key(val name: String) : PathSegment()
    data class Index(val index: Int) : PathSegment()

    /** Any key or any index; only meaningful in patterns (ignore lists). */
    data object Wildcard : PathSegment()

    /** An array element identified by the value of one of its fields (a keyed array in a diff), shown as `[id=5]`. */
    data class Match(val field: String, val value: String) : PathSegment()
}

class PathParseResult(val segments: List<PathSegment>?, val errorOffset: Int, val errorMessage: String?) {
    val ok: Boolean get() = segments != null
}

/** `$.a[0].b` notation: generate it for a node, parse it, and find the node a path points to. */
object JsonPath {
    private val IDENTIFIER = Regex("[A-Za-z_\$][A-Za-z0-9_\$]*")

    fun format(segments: List<PathSegment>): String {
        val sb = StringBuilder("$")
        for (s in segments) append(sb, s)
        return sb.toString()
    }

    fun append(sb: StringBuilder, segment: PathSegment) {
        when (segment) {
            is PathSegment.Key ->
                if (IDENTIFIER.matches(segment.name)) sb.append('.').append(segment.name)
                else sb.append('[').append(JsonStrings.quote(segment.name)).append(']')
            is PathSegment.Index -> sb.append('[').append(segment.index).append(']')
            PathSegment.Wildcard -> sb.append("[*]")
            is PathSegment.Match -> sb.append('[').append(segment.field).append('=').append(segment.value).append(']')
        }
    }

    /** Segments from the root down to [node]. Node 0 and the document node have the empty path. */
    fun of(index: JsonIndex, node: Int): List<PathSegment> {
        val root = index.root
        val out = ArrayList<PathSegment>(index.depth(node))
        var n = node
        while (n > root && n > 0) {
            val p = index.parent(n)
            if (p < 0) break
            val parentIsObject = index.kind(p) == JsonKind.OBJECT
            if (parentIsObject) out += PathSegment.Key(index.keyOf(n) ?: "") else out += PathSegment.Index(index.ordinalOf(n))
            n = p
            if (p == root) break
        }
        out.reverse()
        return out
    }

    fun format(index: JsonIndex, node: Int): String = format(of(index, node))

    /** Parses `$.a[0]["odd key"].*`. A leading `$` is optional. */
    fun parse(text: String): PathParseResult {
        val s = text.trim()
        var i = 0
        val out = ArrayList<PathSegment>()
        if (s.startsWith("$")) i++
        fun fail(at: Int, message: String) = PathParseResult(null, at, message)
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    i++
                    if (i < s.length && s[i] == '*') {
                        out += PathSegment.Wildcard
                        i++
                        continue
                    }
                    val from = i
                    while (i < s.length && s[i] != '.' && s[i] != '[') i++
                    if (i == from) return fail(from, "name expected")
                    out += PathSegment.Key(s.substring(from, i))
                }
                '[' -> {
                    i++
                    if (i >= s.length) return fail(i, "unterminated bracket")
                    val c = s[i]
                    if (c == '"' || c == '\'') {
                        val sb = StringBuilder()
                        i++
                        var closed = false
                        while (i < s.length) {
                            val ch = s[i]
                            if (ch == '\\' && i + 1 < s.length) {
                                val e = s[i + 1]
                                when (e) {
                                    'n' -> sb.append('\n')
                                    't' -> sb.append('\t')
                                    'r' -> sb.append('\r')
                                    'u' -> {
                                        val code = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                                        if (code == null) return fail(i, "bad unicode escape")
                                        sb.append(code.toChar())
                                        i += 4
                                    }
                                    else -> sb.append(e)
                                }
                                i += 2
                                continue
                            }
                            if (ch == c) {
                                closed = true
                                i++
                                break
                            }
                            sb.append(ch)
                            i++
                        }
                        if (!closed) return fail(i, "unterminated string")
                        if (i >= s.length || s[i] != ']') return fail(i, "] expected")
                        i++
                        out += PathSegment.Key(sb.toString())
                    } else if (c == '*') {
                        i++
                        if (i >= s.length || s[i] != ']') return fail(i, "] expected")
                        i++
                        out += PathSegment.Wildcard
                    } else {
                        val from = i
                        while (i < s.length && s[i] != ']') i++
                        if (i >= s.length) return fail(i, "] expected")
                        val inner = s.substring(from, i)
                        i++
                        val n = inner.trim().toIntOrNull()
                        if (n != null && n >= 0) {
                            out += PathSegment.Index(n)
                        } else if (inner.contains('=')) {
                            out += PathSegment.Match(inner.substringBefore('=').trim(), inner.substringAfter('=').trim())
                        } else {
                            return fail(from, "index expected")
                        }
                    }
                }
                else -> {
                    if (i == 0 || (s.startsWith("$") && i == 1)) {
                        val from = i
                        while (i < s.length && s[i] != '.' && s[i] != '[') i++
                        out += PathSegment.Key(s.substring(from, i))
                    } else {
                        return fail(i, "unexpected character")
                    }
                }
            }
        }
        return PathParseResult(out, -1, null)
    }

    /** The node [segments] point to (first match for duplicate keys), or -1. Wildcards and field matches are not resolved. */
    fun resolve(index: JsonIndex, segments: List<PathSegment>, from: Int = index.root): Int {
        var n = from
        for (s in segments) {
            if (n < 0 || !index.isContainer(n)) return -1
            n = when (s) {
                is PathSegment.Key -> if (index.kind(n) == JsonKind.OBJECT) index.findMember(n, s.name) else -1
                is PathSegment.Index -> if (index.kind(n) == JsonKind.ARRAY) index.childAt(n, s.index) else -1
                else -> -1
            }
        }
        return n
    }

    /** True when [path] is matched by [pattern]; a [PathSegment.Wildcard] in the pattern stands for any one segment. */
    fun matches(pattern: List<PathSegment>, path: List<PathSegment>): Boolean {
        if (pattern.size != path.size) return false
        for (k in pattern.indices) {
            val p = pattern[k]
            if (p == PathSegment.Wildcard) continue
            val q = path[k]
            if (p != q && !(p is PathSegment.Key && q is PathSegment.Key && p.name == q.name)) return false
        }
        return true
    }
}
