package com.routegenie.pins

import com.routegenie.Limits

/** What went wrong with the file as a whole (one message for the user), as opposed to a single bad entry. */
enum class PinsFileProblem {
    TOO_LARGE,
    BROKEN,
    NEWER_VERSION,
    NOT_AN_OBJECT;

    val bundleKey: String get() = "pins.file.${name.lowercase()}"
}

/** A pin that was in the file but is not used: [expression] (may be blank) and why. */
data class SkippedPin(val expression: String, val problem: PinProblem)

/**
 * The outcome of reading a pins file. It never throws: a missing, empty, hand-edited or broken file yields the pins that
 * could be read (none when the whole file is unreadable) and the findings, so the caller shows one clear message and goes on.
 *
 * [canWrite] is false when writing would destroy data this version cannot read (a newer file format).
 */
class PinsRead(
    val pins: List<Pin>,
    val problem: PinsFileProblem? = null,
    /** Position of a syntax error as `line:column` (1-based); only with [PinsFileProblem.BROKEN]. */
    val where: String? = null,
    val skipped: List<SkippedPin> = emptyList(),
    val canWrite: Boolean = true,
    /** The line separator of the text that was read, kept when the file is written again. */
    val eol: String = "\n",
) {
    val isClean: Boolean get() = problem == null && skipped.isEmpty()

    companion object {
        val EMPTY = PinsRead(emptyList())
    }
}

/**
 * Reads and writes the text of `pins.json`. Pure. Format (sorted by expression, one pin per line so that two people adding
 * different pins do not conflict when merging):
 *
 * ```
 * {
 *   "version": 1,
 *   "pins": {
 *     "settings.API_V1_STR": {"value": "/api/v1", "note": "default in app/core/config.py"}
 *   }
 * }
 * ```
 */
object PinsCodec {
    private const val VERSION = "version"
    private const val PINS = "pins"
    private const val VALUE = "value"
    private const val NOTE = "note"
    private const val BY = "by"
    private const val AT = "at"
    private const val MAX_DEPTH = 8

    fun read(text: String): PinsRead {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        if (text.length > Limits.PINS_FILE_MAX_BYTES) return PinsRead(emptyList(), PinsFileProblem.TOO_LARGE, canWrite = false, eol = eol)
        val body = text.removePrefix("﻿")
        if (body.isBlank()) return PinsRead(emptyList(), eol = eol)
        val parsed = try {
            Parser(body).parseDocument()
        } catch (e: ParseError) {
            // Never overwritten: whatever the user typed there is theirs to fix, not ours to replace.
            return PinsRead(emptyList(), PinsFileProblem.BROKEN, where = e.where, canWrite = false, eol = eol)
        }
        val root = parsed as? Map<*, *> ?: return PinsRead(emptyList(), PinsFileProblem.NOT_AN_OBJECT, canWrite = false, eol = eol)
        val version = (root[VERSION] as? Number)?.toInt() ?: Limits.PINS_FILE_VERSION
        val newer = version > Limits.PINS_FILE_VERSION
        val entries = root[PINS] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val pins = ArrayList<Pin>()
        val skipped = ArrayList<SkippedPin>()
        for ((key, raw) in entries) {
            val expression = key as? String ?: continue
            if (pins.size >= Limits.MAX_PINS) {
                skipped += SkippedPin(expression, PinProblem.TOO_MANY)
                continue
            }
            val entry = raw as? Map<*, *>
            // A bare string is accepted for hand-written files: "settings.X": "/x".
            val value = (raw as? String) ?: entry?.get(VALUE) as? String
            if (value == null) {
                skipped += SkippedPin(expression, PinProblem.VALUE_CHARS)
                continue
            }
            val pin = Pin(
                expression, value, PinRules.cutNote(entry?.get(NOTE) as? String ?: ""),
                (entry?.get(BY) as? String ?: "").take(Limits.PIN_MAX_NOTE_CHARS), (entry?.get(AT) as? String ?: "").take(Limits.PIN_MAX_NOTE_CHARS),
            )
            // The slash rule depends on the framework that uses the pin, which a file does not know: only the framework-free rules apply here.
            val problem = PinRules.check(pin, requireLeadingSlash = false)
            if (problem != null) skipped += SkippedPin(expression, problem) else pins += pin
        }
        return PinsRead(
            pins.sortedBy { it.expression },
            if (newer) PinsFileProblem.NEWER_VERSION else null,
            skipped = skipped,
            canWrite = !newer,
            eol = eol,
        )
    }

    /** The file text for [pins] (sorted, duplicates by expression keep the last). */
    fun write(pins: Collection<Pin>, eol: String = "\n"): String {
        val unique = LinkedHashMap<String, Pin>()
        pins.forEach { unique[it.expression] = it }
        val lines = unique.values.sortedBy { it.expression }.map { pin ->
            val fields = ArrayList<String>()
            fields += "\"$VALUE\": ${quote(pin.value)}"
            if (pin.note.isNotEmpty()) fields += "\"$NOTE\": ${quote(pin.note)}"
            if (pin.by.isNotEmpty()) fields += "\"$BY\": ${quote(pin.by)}"
            if (pin.at.isNotEmpty()) fields += "\"$AT\": ${quote(pin.at)}"
            "    ${quote(pin.expression)}: {${fields.joinToString(", ")}}"
        }
        val out = StringBuilder()
        out.append("{").append(eol)
        out.append("  \"$VERSION\": ${Limits.PINS_FILE_VERSION},").append(eol)
        if (lines.isEmpty()) {
            out.append("  \"$PINS\": {}").append(eol)
        } else {
            out.append("  \"$PINS\": {").append(eol)
            out.append(lines.joinToString(",$eol")).append(eol)
            out.append("  }").append(eol)
        }
        out.append("}").append(eol)
        return out.toString()
    }

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private class ParseError(val where: String) : Exception()

    /** A small strict JSON reader: only what a pins file needs (objects, arrays, strings, numbers, literals), depth-bounded. */
    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val value = value(0)
            skipSpace()
            if (i < s.length) fail()
            return value
        }

        private fun fail(): Nothing {
            val upTo = i.coerceIn(0, s.length)
            val line = s.substring(0, upTo).count { it == '\n' } + 1
            val lastNewline = if (upTo == 0) -1 else s.lastIndexOf('\n', upTo - 1)
            throw ParseError("$line:${upTo - lastNewline}")
        }

        private fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail()
            skipSpace()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return result
        }

        private fun number(): Any? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (start == i) fail()
            return s.substring(start, i).toDoubleOrNull() ?: run { i = start; fail() }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipSpace()
                if (i >= s.length || s[i] != '"') fail()
                val key = string()
                skipSpace()
                if (i >= s.length || s[i] != ':') fail()
                i++
                map[key] = value(depth + 1)
                skipSpace()
                if (i >= s.length) fail()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> { i--; fail() }
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list += value(depth + 1)
                skipSpace()
                if (i >= s.length) fail()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> { i--; fail() }
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail())
                                i += 4
                            }
                            else -> { i--; fail() }
                        }
                    }
                    c.code < 0x20 -> { i--; fail() }
                    else -> out.append(c)
                }
            }
        }
    }
}
