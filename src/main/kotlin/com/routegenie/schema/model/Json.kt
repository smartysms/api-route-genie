package com.routegenie.schema.model

import java.math.BigDecimal

/**
 * A JSON value, used for defaults, constants, enum members and generated samples. Immutable and PSI-free. Kotlin `null`
 * always means "absent"; a JSON null is [Json.Null], so a schema can say `"default": null` and `"no default"` apart.
 */
sealed interface Json {
    data object Null : Json

    data class Bool(val value: Boolean) : Json

    /** Compares by numeric value, so `1.0` and `1` are the same member of an enum or a `uniqueItems` array. */
    class Num(val value: BigDecimal) : Json {
        override fun equals(other: Any?): Boolean = other is Num && value.compareTo(other.value) == 0
        override fun hashCode(): Int = if (value.signum() == 0) 0 else value.stripTrailingZeros().hashCode()
        override fun toString(): String = "Num($value)"
    }

    data class Str(val value: String) : Json

    data class Arr(val items: List<Json>) : Json

    /** Members in declaration order. */
    data class Obj(val fields: Map<String, Json>) : Json

    companion object {
        fun num(value: Long): Json = Num(BigDecimal.valueOf(value))
        fun num(value: Int): Json = Num(BigDecimal.valueOf(value.toLong()))
        fun str(value: String): Json = Str(value)
        fun obj(vararg members: Pair<String, Json>): Json = Obj(linkedMapOf(*members))
        fun arr(vararg items: Json): Json = Arr(items.toList())
    }
}

/** The JSON type a value has; used to derive the `type` of an enum from its members. */
fun Json.jsonType(): JsonType = when (this) {
    Json.Null -> JsonType.NULL
    is Json.Bool -> JsonType.BOOLEAN
    is Json.Num -> if (value.signum() == 0 || value.stripTrailingZeros().scale() <= 0) JsonType.INTEGER else JsonType.NUMBER
    is Json.Str -> JsonType.STRING
    is Json.Arr -> JsonType.ARRAY
    is Json.Obj -> JsonType.OBJECT
}

/** Writes [Json] as text: pretty (two-space indent, one member per line, like Python's `json.dumps(indent=2)`) or compact. */
object JsonText {
    private const val INDENT = "  "

    fun pretty(value: Json): String = StringBuilder().also { write(value, it, true, 0) }.toString()

    fun compact(value: Json): String = StringBuilder().also { write(value, it, false, 0) }.toString()

    private fun write(value: Json, out: StringBuilder, pretty: Boolean, depth: Int) {
        when (value) {
            Json.Null -> out.append("null")
            is Json.Bool -> out.append(if (value.value) "true" else "false")
            is Json.Num -> out.append(number(value.value))
            is Json.Str -> quote(value.value, out)
            is Json.Arr -> container(out, pretty, depth, '[', ']', value.items) { item -> write(item, out, pretty, depth + 1) }
            is Json.Obj -> container(out, pretty, depth, '{', '}', value.fields.entries) { entry ->
                quote(entry.key, out)
                out.append(if (pretty) ": " else ":")
                write(entry.value, out, pretty, depth + 1)
            }
        }
    }

    private inline fun <T> container(
        out: StringBuilder,
        pretty: Boolean,
        depth: Int,
        open: Char,
        close: Char,
        items: Collection<T>,
        each: (T) -> Unit,
    ) {
        out.append(open)
        if (items.isEmpty()) {
            out.append(close)
            return
        }
        var first = true
        for (item in items) {
            if (!first) out.append(',')
            first = false
            if (pretty) newline(out, depth + 1)
            each(item)
        }
        if (pretty) newline(out, depth)
        out.append(close)
    }

    private fun newline(out: StringBuilder, depth: Int) {
        out.append('\n')
        repeat(depth) { out.append(INDENT) }
    }

    /** Plain notation, never an exponent, because JSON readers and people both expect `100` and not `1E+2`. */
    fun number(value: BigDecimal): String = value.toPlainString()

    fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    fun quoted(text: String): String = StringBuilder().also { quote(text, it) }.toString()
}
