package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyExpression
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import com.routegenie.schema.psi.PydanticCatalog.Keys
import java.math.BigDecimal

/** Turns the keyword arguments of `Field(...)`, `Body(...)`, `conint(...)` and `Annotated` constraint objects into schema keywords. */
internal object Constraints {
    /** [schema] with the constraints and annotations written in [call]'s keywords. */
    fun applyCall(schema: Schema, call: PyCallExpression): Schema = apply(schema) { key -> PyLiterals.keyword(call, key) }

    /** Same for a source of keywords that is not a single call. */
    fun apply(schema: Schema, keyword: (String) -> PyExpression?): Schema {
        val bounds = Bounds(
            gt = PyLiterals.number(keyword(Keys.GT)),
            ge = PyLiterals.number(keyword(Keys.GE)),
            lt = PyLiterals.number(keyword(Keys.LT)),
            le = PyLiterals.number(keyword(Keys.LE)),
            multipleOf = PyLiterals.number(keyword(Keys.MULTIPLE_OF)),
            minLength = PyLiterals.int(keyword(Keys.MIN_LENGTH) ?: keyword(Keys.MIN_ITEMS)),
            maxLength = PyLiterals.int(keyword(Keys.MAX_LENGTH) ?: keyword(Keys.MAX_ITEMS)),
            pattern = PyLiterals.string(keyword(Keys.PATTERN) ?: keyword(Keys.REGEX)),
            unique = PyLiterals.bool(keyword(Keys.UNIQUE_ITEMS)) == true,
        )
        var result = if (bounds.isEmpty) schema else leaves(schema) { leaf -> bound(leaf, bounds) }
        keyword(Keys.DESCRIPTION)?.let { e -> PyLiterals.string(e)?.let { result = result.copy(description = it) } }
        examples(keyword(Keys.EXAMPLES) ?: keyword(Keys.EXAMPLE), keyword(Keys.EXAMPLE) != null && keyword(Keys.EXAMPLES) == null)?.let { result = result.copy(examples = it) }
        keyword(Keys.DEPRECATED)?.let { e -> if (PyLiterals.bool(e) != false) result = result.copy(deprecated = true) }
        return result
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
        val minLength: Int?,
        val maxLength: Int?,
        val pattern: String?,
        val unique: Boolean,
    ) {
        val isEmpty: Boolean
            get() = gt == null && ge == null && lt == null && le == null && multipleOf == null && minLength == null && maxLength == null && pattern == null && !unique
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
        var s = leaf
        when (leaf.types.firstOrNull { it != JsonType.NULL }) {
            JsonType.INTEGER, JsonType.NUMBER -> {
                b.gt?.let { s = s.copy(exclusiveMinimum = it) }
                b.ge?.let { s = s.copy(minimum = it) }
                b.lt?.let { s = s.copy(exclusiveMaximum = it) }
                b.le?.let { s = s.copy(maximum = it) }
                b.multipleOf?.let { s = s.copy(multipleOf = it) }
            }
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
}
