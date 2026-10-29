package com.routegenie.schema.psi

import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyBoolLiteralExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyEllipsisLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyNoneLiteralExpression
import com.jetbrains.python.psi.PyNumericLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyPrefixExpression
import com.jetbrains.python.psi.PySetLiteralExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.routegenie.Limits
import com.routegenie.schema.model.Json
import java.math.BigDecimal

/** Reads Python literal expressions. Anything that is not a plain literal gives null: a value is never guessed. */
internal object PyLiterals {
    fun unparen(expression: PyExpression?): PyExpression? {
        var current = expression
        while (current is PyParenthesizedExpression) current = current.containedExpression
        return current
    }

    fun string(expression: PyExpression?): String? {
        val e = unparen(expression) as? PyStringLiteralExpression ?: return null
        // An f-string depends on runtime values; a byte string is not text.
        if (e.isInterpolated || e.stringElements.any { it.prefix.contains('b', ignoreCase = true) }) return null
        return e.stringValue
    }

    fun bool(expression: PyExpression?): Boolean? = (unparen(expression) as? PyBoolLiteralExpression)?.value

    fun isNone(expression: PyExpression?): Boolean = unparen(expression) is PyNoneLiteralExpression

    fun isEllipsis(expression: PyExpression?): Boolean = unparen(expression) is PyEllipsisLiteralExpression

    fun number(expression: PyExpression?): BigDecimal? {
        val e = unparen(expression)
        return when {
            e is PyNumericLiteralExpression -> if (e.isIntegerLiteral) e.bigIntegerValue?.let { BigDecimal(it) } else e.bigDecimalValue
            e is PyPrefixExpression && e.operator == PyTokenTypes.MINUS -> number(e.operand)?.negate()
            e is PyPrefixExpression && e.operator == PyTokenTypes.PLUS -> number(e.operand)
            else -> null
        }
    }

    fun int(expression: PyExpression?): Int? = number(expression)?.takeIf { it.scale() <= 0 || it.stripTrailingZeros().scale() <= 0 }?.toBigInteger()?.takeIf { it.bitLength() < Int.SIZE_BITS }?.toInt()

    /** Elements of a list, tuple or set literal; null for anything else. */
    fun elements(expression: PyExpression?): List<PyExpression>? = when (val e = unparen(expression)) {
        is PyListLiteralExpression -> e.elements.toList()
        is PyTupleExpression -> e.elements.toList()
        is PySetLiteralExpression -> e.elements.toList()
        else -> null
    }

    fun strings(expression: PyExpression?): List<String>? {
        val items = elements(expression) ?: return null
        val out = ArrayList<String>(items.size)
        for (item in items) out += string(item) ?: return null
        return out
    }

    /** A literal as JSON: numbers, strings, booleans, None, and lists, tuples and dicts of those. */
    fun json(expression: PyExpression?, depth: Int = 0): Json? {
        if (depth > Limits.SCHEMA_MAX_NESTING) return null
        val e = unparen(expression) ?: return null
        return when {
            e is PyNoneLiteralExpression -> Json.Null
            e is PyBoolLiteralExpression -> Json.Bool(e.value)
            e is PyStringLiteralExpression -> string(e)?.let { Json.Str(it) }
            e is PyNumericLiteralExpression || e is PyPrefixExpression -> number(e)?.let { Json.Num(it) }
            e is PyDictLiteralExpression -> {
                val members = LinkedHashMap<String, Json>()
                for (pair in e.elements) {
                    val key = string(pair.key) ?: return null
                    members[key] = json(pair.value, depth + 1) ?: return null
                }
                Json.Obj(members)
            }
            else -> {
                val items = elements(e) ?: return null
                Json.Arr(items.map { json(it, depth + 1) ?: return null })
            }
        }
    }

    /** `X | Y | Z` flattened into its operands; a single expression gives a one element list. */
    fun unionOperands(expression: PyExpression?): List<PyExpression> {
        val e = unparen(expression) ?: return emptyList()
        if (e is PyBinaryExpression && e.operator == PyTokenTypes.OR) {
            return unionOperands(e.leftExpression) + unionOperands(e.rightExpression)
        }
        return listOf(e)
    }

    fun keyword(call: PyCallExpression, name: String): PyExpression? = call.getKeywordArgument(name)

    /** The positional arguments of [call], in order. */
    fun positional(call: PyCallExpression): List<PyExpression> = call.arguments.filter { it !is PyKeywordArgument }
}
