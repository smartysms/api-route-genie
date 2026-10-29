package com.routegenie.schema.model

import com.routegenie.Limits

/**
 * Builds one string that matches a regular expression, for a simple subset: literals, escapes (`\d \w \s ...`), classes,
 * `.`, groups, alternation (first branch), and quantifiers (the minimum count, at least one for `+`). Anything else
 * (look-around, back-references, inline flags) returns null, and so does a result that the JVM's own regex does not accept
 * as a match: a sample is only offered when it provably satisfies the pattern.
 */
object PatternSampler {
    /**
     * A string matching [pattern] whose length in characters lies within [minLength]..[maxLength]. Open-ended repeats are
     * lengthened one step at a time until the minimum is reached.
     */
    fun sample(pattern: String, minLength: Int? = null, maxLength: Int? = null): String? {
        if (pattern.length > Limits.SAMPLE_PATTERN_MAX_CHARS) return null
        // The JVM dialect differs from Python's in named groups only for what this subset allows.
        val regex = runCatching { Regex(pattern.replace("(?P<", "(?<")) }.getOrNull() ?: return null
        val steps = minOf(maxOf(minLength ?: 0, 0), Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS)
        for (extra in 0..steps) {
            val text = runCatching { Parser(pattern, extra).parse() }.getOrNull() ?: return null
            if (text.length > Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS) return null
            val length = text.codePointCount(0, text.length)
            if (maxLength != null && length > maxLength) return null
            if (length >= (minLength ?: 0) && regex.containsMatchIn(text)) return text
        }
        return null
    }

    private class Unsupported : RuntimeException(null, null, false, false)

    private class Parser(private val src: String, private val extra: Int) {
        private var pos = 0
        private var budget = Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS

        fun parse(): String {
            val out = alternation()
            if (pos != src.length) throw Unsupported()
            return out
        }

        private fun alternation(): String {
            var first: String? = null
            do {
                val branch = sequence()
                if (first == null) first = branch
                val more = pos < src.length && src[pos] == '|'
                if (more) pos++
            } while (more)
            return first.orEmpty()
        }

        private fun sequence(): String {
            val out = StringBuilder()
            while (pos < src.length && src[pos] != '|' && src[pos] != ')') {
                val atom = atom()
                val (min, max) = quantifier()
                repeat(minOf(max.toLong(), min.toLong() + extra).toInt()) { out.append(atom) }
                if (out.length > budget) throw Unsupported()
            }
            return out.toString()
        }

        private fun atom(): String {
            val c = src[pos++]
            return when (c) {
                '^', '$' -> ""
                '.' -> "a"
                '(' -> group()
                '[' -> charClass()
                '\\' -> escape(inClass = false)
                '*', '+', '?', '{' -> throw Unsupported()
                else -> c.toString()
            }
        }

        private fun group(): String {
            if (pos < src.length && src[pos] == '?') {
                pos++
                val kind = src.getOrNull(pos) ?: throw Unsupported()
                when {
                    kind == ':' -> pos++
                    kind == 'P' && src.getOrNull(pos + 1) == '<' -> skipName(pos + 2)
                    kind == '<' && src.getOrNull(pos + 1) != '=' && src.getOrNull(pos + 1) != '!' -> skipName(pos + 1)
                    else -> throw Unsupported()
                }
            }
            val inner = alternation()
            if (pos >= src.length || src[pos] != ')') throw Unsupported()
            pos++
            return inner
        }

        private fun skipName(from: Int) {
            val end = src.indexOf('>', from)
            if (end < 0) throw Unsupported()
            pos = end + 1
        }

        private fun charClass(): String {
            var negated = false
            if (pos < src.length && src[pos] == '^') {
                negated = true
                pos++
            }
            val members = ArrayList<CharRange>()
            var first = true
            while (pos < src.length && (src[pos] != ']' || first)) {
                first = false
                val lo = classChar()
                if (pos + 1 < src.length && src[pos] == '-' && src[pos + 1] != ']') {
                    pos++
                    val hi = classChar()
                    if (hi < lo) throw Unsupported()
                    members += lo..hi
                } else {
                    members += lo..lo
                }
            }
            if (pos >= src.length || members.isEmpty()) throw Unsupported()
            pos++
            // A letter or digit reads better than the first member of a class such as [-a-z]; any member satisfies the class.
            if (!negated) return (members.firstOrNull { it.first.isLetterOrDigit() } ?: members.first()).first.toString()
            return NEGATED_CANDIDATES.firstOrNull { cand -> members.none { cand in it } }?.toString() ?: throw Unsupported()
        }

        /** One member of a class; a shorthand such as `\d` stands for its representative character. */
        private fun classChar(): Char {
            val c = src[pos++]
            return if (c == '\\') escape(inClass = true).firstOrNull() ?: throw Unsupported() else c
        }

        private fun escape(inClass: Boolean): String {
            if (pos >= src.length) throw Unsupported()
            val c = src[pos++]
            return when (c) {
                'd' -> "0"
                'w' -> "a"
                's' -> " "
                'D', 'S' -> "a"
                'W' -> "-"
                'n' -> "\n"
                't' -> "\t"
                'r' -> "\r"
                'b', 'B', 'A', 'Z', 'z' -> if (inClass && c == 'b') "\b" else ""
                in '1'..'9' -> throw Unsupported()
                'x', 'u', 'p', 'P', 'N', 'k', 'G', 'Q', 'E' -> throw Unsupported()
                else -> c.toString()
            }
        }

        /** Returns the minimum and maximum repeat count; the sampler only uses the minimum. */
        private fun quantifier(): Pair<Int, Int> {
            if (pos >= src.length) return 1 to 1
            val result = when (src[pos]) {
                '?' -> { pos++; 0 to 1 }
                '*' -> { pos++; 0 to Int.MAX_VALUE }
                '+' -> { pos++; 1 to Int.MAX_VALUE }
                '{' -> braces() ?: return 1 to 1
                else -> return 1 to 1
            }
            // Lazy and possessive suffixes do not change which strings match.
            if (pos < src.length && (src[pos] == '?' || src[pos] == '+')) pos++
            return result
        }

        private fun braces(): Pair<Int, Int>? {
            val end = src.indexOf('}', pos)
            if (end < 0) return null
            val body = src.substring(pos + 1, end)
            val parts = body.split(',')
            val min = parts[0].trim().toIntOrNull() ?: return null
            val max = when {
                parts.size == 1 -> min
                parts[1].isBlank() -> Int.MAX_VALUE
                else -> parts[1].trim().toIntOrNull() ?: return null
            }
            if (parts.size > 2 || min > Limits.SAMPLE_PATTERN_MAX_REPEAT) return null
            pos = end + 1
            return min to max
        }
    }

    private val NEGATED_CANDIDATES = listOf('a', 'A', '0', 'x', '_', '-')
}
