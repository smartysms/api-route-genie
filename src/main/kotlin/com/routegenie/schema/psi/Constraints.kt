package com.routegenie.schema.psi

import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyPrefixExpression
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.SampleValues
import com.routegenie.schema.model.Schema
import com.routegenie.schema.psi.PydanticCatalog.Keys
import java.math.BigDecimal

/** Turns a name that stands for a module level constant (`MAX_AGE`) into the expression it was assigned; anything else is returned as it is. */
internal typealias ConstantResolver = (PyExpression) -> PyExpression

/** Turns the keyword arguments of `Field(...)`, `Body(...)`, `conint(...)` and `Annotated` constraint objects into schema keywords. */
internal object Constraints {
    val NO_CONSTANTS: ConstantResolver = { it }

    /**
     * [schema] with the constraints and annotations written in [call]'s keywords. Null when a constraint is present but cannot be
     * read (an expression such as `Decimal("9.99")`): what the field accepts is then not known, and a limit that is dropped
     * silently would let a sample break it.
     */
    fun applyCall(schema: Schema, call: PyCallExpression, constants: ConstantResolver = NO_CONSTANTS): Schema? =
        apply(schema, constants) { key -> PyLiterals.keyword(call, key) }

    /** Same for a source of keywords that is not a single call. */
    fun apply(schema: Schema, constants: ConstantResolver = NO_CONSTANTS, keyword: (String) -> PyExpression?): Schema? {
        var unreadable = false

        fun expression(key: String): PyExpression? = keyword(key)?.takeUnless { PyLiterals.isNone(it) }

        fun number(key: String): BigDecimal? {
            val e = expression(key) ?: return null
            return numberOf(e, constants).also { if (it == null) unreadable = true }
        }

        fun int(key: String): Int? {
            val e = expression(key) ?: return null
            return PyLiterals.int(constants(e)).also { if (it == null) unreadable = true }
        }

        fun string(key: String): String? {
            val e = expression(key) ?: return null
            return PyLiterals.string(constants(e)).also { if (it == null) unreadable = true }
        }

        val bounds = Bounds(
            gt = number(Keys.GT),
            ge = number(Keys.GE),
            lt = number(Keys.LT),
            le = number(Keys.LE),
            multipleOf = number(Keys.MULTIPLE_OF),
            maxDigits = int(Keys.MAX_DIGITS),
            decimalPlaces = int(Keys.DECIMAL_PLACES),
            minLength = if (expression(Keys.MIN_LENGTH) != null) int(Keys.MIN_LENGTH) else int(Keys.MIN_ITEMS),
            maxLength = if (expression(Keys.MAX_LENGTH) != null) int(Keys.MAX_LENGTH) else int(Keys.MAX_ITEMS),
            pattern = if (expression(Keys.PATTERN) != null) string(Keys.PATTERN) else string(Keys.REGEX),
            unique = PyLiterals.bool(keyword(Keys.UNIQUE_ITEMS)) == true,
        )
        if (unreadable) return null
        var result = if (bounds.isEmpty) schema else leaves(schema) { leaf -> bound(leaf, bounds) }
        keyword(Keys.DESCRIPTION)?.let { e -> PyLiterals.string(e)?.let { result = result.copy(description = it) } }
        examples(keyword(Keys.EXAMPLES) ?: keyword(Keys.EXAMPLE), keyword(Keys.EXAMPLE) != null && keyword(Keys.EXAMPLES) == null)?.let { result = result.copy(examples = it) }
        keyword(Keys.DEPRECATED)?.let { e -> if (PyLiterals.bool(e) != false) result = result.copy(deprecated = true) }
        return result
    }

    /** A number written as a literal, a constant that is one, or a sign in front of either. */
    private fun numberOf(expression: PyExpression, constants: ConstantResolver): BigDecimal? {
        val resolved = PyLiterals.unparen(constants(expression)) ?: return null
        if (resolved is PyPrefixExpression && resolved.operator == PyTokenTypes.MINUS) return resolved.operand?.let { numberOf(it, constants) }?.negate()
        if (resolved is PyPrefixExpression && resolved.operator == PyTokenTypes.PLUS) return resolved.operand?.let { numberOf(it, constants) }
        return PyLiterals.number(resolved)
    }

    private fun examples(expression: PyExpression?, single: Boolean): List<Json>? {
        if (expression == null) return null
        if (single) return PyLiterals.json(expression)?.let { listOf(it) }
        val items = PyLiterals.elements(expression) ?: return null
        val values = items.map { PyLiterals.json(it) ?: return null }
        return values.takeIf { it.isNotEmpty() }
    }

    private class Bounds(
        val gt: BigDecimal?,
        val ge: BigDecimal?,
        val lt: BigDecimal?,
        val le: BigDecimal?,
        val multipleOf: BigDecimal?,
        val maxDigits: Int?,
        val decimalPlaces: Int?,
        val minLength: Int?,
        val maxLength: Int?,
        val pattern: String?,
        val unique: Boolean,
    ) {
        val isEmpty: Boolean
            get() = gt == null && ge == null && lt == null && le == null && multipleOf == null && maxDigits == null && decimalPlaces == null &&
                minLength == null && maxLength == null && pattern == null && !unique
    }

    /** Applies [change] to every schema that carries a type of its own; `anyOf` is entered, `$ref` and the null branch are left alone. */
    fun leaves(schema: Schema, change: (Schema) -> Schema): Schema {
        if (schema.ref != null) return schema
        if (schema.anyOf.isNotEmpty() || schema.oneOf.isNotEmpty()) {
            return schema.copy(
                anyOf = schema.anyOf.map { if (isNull(it)) it else leaves(it, change) },
                oneOf = schema.oneOf.map { if (isNull(it)) it else leaves(it, change) },
            )
        }
        return change(schema)
    }

    fun isNull(schema: Schema): Boolean = schema.types == listOf(JsonType.NULL)

    private fun bound(leaf: Schema, b: Bounds): Schema {
        // A decimal written as text is bounded like the number it spells.
        if (leaf.format == SampleValues.DECIMAL) return numeric(leaf, b)
        var s = leaf
        when (leaf.types.firstOrNull { it != JsonType.NULL }) {
            JsonType.INTEGER, JsonType.NUMBER -> s = numeric(s, b)
            JsonType.STRING -> {
                b.minLength?.let { s = s.copy(minLength = it) }
                b.maxLength?.let { s = s.copy(maxLength = it) }
                b.pattern?.let { s = s.copy(pattern = it) }
            }
            JsonType.ARRAY -> {
                b.minLength?.let { s = s.copy(minItems = it) }
                b.maxLength?.let { s = s.copy(maxItems = it) }
                if (b.unique) s = s.copy(uniqueItems = true)
            }
            JsonType.OBJECT -> {
                b.minLength?.let { s = s.copy(minProperties = it) }
                b.maxLength?.let { s = s.copy(maxProperties = it) }
            }
            else -> Unit
        }
        return s
    }

    private fun numeric(leaf: Schema, b: Bounds): Schema {
        var s = leaf
        b.gt?.let { s = s.copy(exclusiveMinimum = it) }
        b.ge?.let { s = s.copy(minimum = it) }
        b.lt?.let { s = s.copy(exclusiveMaximum = it) }
        b.le?.let { s = s.copy(maximum = it) }
        b.multipleOf?.let { s = s.copy(multipleOf = it) }
        return digits(s, b.maxDigits, b.decimalPlaces)
    }

    /**
     * The limits `max_digits` and `decimal_places` of a decimal field as bounds: values are multiples of 10^-places and have
     * fewer than `max_digits - decimal_places` whole digits. `max_digits` alone says nothing about the size of a fractional value,
     * so it adds no bound.
     */
    fun digits(schema: Schema, maxDigits: Int?, decimalPlaces: Int?): Schema {
        if (decimalPlaces == null || decimalPlaces < 0) return schema
        var s = schema
        if (s.multipleOf == null) s = s.copy(multipleOf = BigDecimal.ONE.movePointLeft(decimalPlaces))
        if (maxDigits == null || maxDigits < decimalPlaces) return s
        val limit = BigDecimal.ONE.movePointRight(maxDigits - decimalPlaces)
        val below = s.exclusiveMaximum
        val above = s.exclusiveMinimum
        s = s.copy(exclusiveMaximum = if (below == null || limit < below) limit else below)
        return s.copy(exclusiveMinimum = if (above == null || limit.negate() > above) limit.negate() else above)
    }
}
