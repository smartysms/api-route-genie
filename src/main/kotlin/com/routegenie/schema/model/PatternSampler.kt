package com.routegenie.schema.model

import com.routegenie.Limits
import kotlin.random.Random

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
        // A text longer than the sampler builds can never be reached; trying every length up to it only costs time.
        if ((minLength ?: 0) > Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS) return null
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

    /**
     * Like [sample], but every free choice (alternative, repeat count, member of a class) is drawn from [random], so repeated
     * calls give different strings. A draw that breaks the length limits or does not match is dropped; after
     * [Limits.RANDOM_PATTERN_ATTEMPTS] misses the plain [sample] answers, so the result is as provable as before.
     */
    fun sampleRandom(pattern: String, random: Random, minLength: Int? = null, maxLength: Int? = null): String? {
        if (pattern.length > Limits.SAMPLE_PATTERN_MAX_CHARS) return null
        if ((minLength ?: 0) > Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS) return null
        val regex = runCatching { Regex(pattern.replace("(?P<", "(?<")) }.getOrNull() ?: return null
        repeat(Limits.RANDOM_PATTERN_ATTEMPTS) {
            val text = runCatching { Parser(pattern, 0, random).parse() }.getOrNull() ?: return@repeat
            if (text.length > Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS) return@repeat
            val length = text.codePointCount(0, text.length)
            if (maxLength != null && length > maxLength) return@repeat
            if (length >= (minLength ?: 0) && regex.containsMatchIn(text)) return text
        }
        return sample(pattern, minLength, maxLength)
    }

    private class Unsupported : RuntimeException(null, null, false, false)

    private class Parser(private val src: String, private val extra: Int, private val random: Random? = null) {
        private var pos = 0
        private var budget = Limits.SAMPLE_PATTERN_MAX_OUTPUT_CHARS
        private var steps = 0L

        fun parse(): String {
            val out = alternation()
            if (pos != src.length) throw Unsupported()
            return out
        }

        private fun alternation(): String {
            val branches = ArrayList<String>()
            do {
                branches += sequence()
                val more = pos < src.length && src[pos] == '|'
                if (more) pos++
            } while (more)
            // Every branch is parsed so the position ends after the last one; only one of them is used.
            return (if (random != null) branches[random.nextInt(branches.size)] else branches.first())
        }

        private fun sequence(): String {
            val out = StringBuilder()
            while (pos < src.length && src[pos] != '|' && src[pos] != ')') {
                val start = pos
                val before = steps
                val atom = atom()
                val afterAtom = pos
                val (min, max) = quantifier()
                // A repeated group is read once more for each copy, so what it cost counts once per copy: nested repeats multiply,
                // which is also what the JVM regex engine does when it checks the answer (1000 x 1000 x 1000 x 1000 empty groups never ends).
                steps += (steps - before) * (maxOf(min, 1) - 1)
                if (steps > Limits.SAMPLE_PATTERN_MAX_STEPS) throw Unsupported()
                if (random == null) {
                    repeat(minOf(max.toLong(), min.toLong() + extra).toInt()) { out.append(atom) }
                } else {
                    val upper = minOf(max.toLong(), min.toLong() + Limits.RANDOM_PATTERN_EXTRA_REPEAT).toInt()
                    val count = if (upper > min) random.nextInt(min, upper + 1) else min
                    val afterQuantifier = pos
                    for (i in 0 until count) {
                        if (i == 0) {
                            out.append(atom)
                        } else {
                            // Each repeat is drawn again, so "[a-z]{3}" gives three different letters.
                            pos = start
                            out.append(atom())
                        }
                        if (out.length > budget) throw Unsupported()
                    }
                    pos = afterQuantifier
                }
                if (afterAtom > src.length || out.length > budget) throw Unsupported()
            }
            return out.toString()
        }

        private fun atom(): String {
            // Every repeat of a group reads it again, so nested repeats multiply; the count of atoms read bounds the work.
            if (++steps > Limits.SAMPLE_PATTERN_MAX_STEPS) throw Unsupported()
            val c = src[pos++]
            return when (c) {
                '^', '$' -> ""
                '.' -> letter()
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
            if (random != null) return randomMember(members, negated)
            if (!negated) return (members.firstOrNull { it.first.isLetterOrDigit() } ?: members.first()).first.toString()
            return NEGATED_CANDIDATES.firstOrNull { cand -> members.none { cand in it } }?.toString() ?: throw Unsupported()
        }

        /** A random character of the class (or, for a negated class, of the printable ASCII characters outside it). */
        private fun randomMember(members: List<CharRange>, negated: Boolean): String {
            val rnd = random!!
            // A space is drawn only when nothing else fits: servers trim the ends of text fields, which would change the value.
            if (negated) {
                val allowed = PRINTABLE.filter { c -> members.none { c in it } }
                val fallback = if (allowed.isEmpty()) listOf(' ').filter { c -> members.none { c in it } } else allowed
                if (fallback.isEmpty()) throw Unsupported()
                return fallback[rnd.nextInt(fallback.size)].toString()
            }
            // Printable characters of the class read better and are always valid; a class without any (control characters only) keeps its first.
            val printable = members.flatMap { range -> range.filter { it in PRINTABLE_RANGE } }
            if (printable.isEmpty()) return members.first().first.toString()
            return printable[rnd.nextInt(printable.size)].toString()
        }

        private fun letter(): String = if (random != null) LETTERS[random.nextInt(LETTERS.length)].toString() else "a"

        /** One member of a class; a shorthand such as `\d` stands for its representative character. */
        private fun classChar(): Char {
            val c = src[pos++]
            return if (c == '\\') escape(inClass = true).firstOrNull() ?: throw Unsupported() else c
        }

        private fun escape(inClass: Boolean): String {
            if (pos >= src.length) throw Unsupported()
            val c = src[pos++]
            return when (c) {
                'd' -> if (random != null) random.nextInt(10).toString() else "0"
                'w' -> letter()
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
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
    private val PRINTABLE_RANGE = '!'..'~'
    private val PRINTABLE: List<Char> = PRINTABLE_RANGE.toList()
}
