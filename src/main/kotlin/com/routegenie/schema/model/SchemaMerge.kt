package com.routegenie.schema.model

import com.routegenie.Limits

/** Flattens `allOf` into one schema so the generator can treat it like any other; conflicts keep the stricter bound. */
object SchemaMerge {
    /**
     * [schema] with its `allOf` parts folded in. A part that is a `$ref` is looked up with [resolve]; a part that cannot be
     * found is left out (the generator then simply knows less, it never invents).
     */
    fun allOf(schema: Schema, resolve: (String) -> Schema?): Schema {
        var merged = schema.copy(allOf = emptyList())
        val pending = ArrayDeque(schema.allOf)
        var budget = Limits.SCHEMA_RESOLVE_DEPTH * Limits.SCHEMA_RESOLVE_DEPTH
        while (pending.isNotEmpty() && budget-- > 0) {
            val part = pending.removeFirst()
            val ref = part.ref
            if (ref != null) {
                // Keywords written next to the `$ref` still apply, so the target is merged first and the siblings after it.
                val target = resolve(ref) ?: continue
                pending.addFirst(part.copy(ref = null))
                pending.addFirst(target)
                continue
            }
            pending.addAll(0, part.allOf)
            merged = combine(merged, part.copy(allOf = emptyList()))
        }
        return merged
    }

    private fun combine(a: Schema, b: Schema): Schema {
        val properties = LinkedHashMap(a.properties)
        for ((name, schema) in b.properties) {
            val existing = properties[name]
            properties[name] = if (existing == null) schema else Schema(allOf = listOf(existing, schema))
        }
        return a.copy(
            types = if (a.types.isEmpty()) b.types else if (b.types.isEmpty()) a.types else a.types.filter { it in b.types },
            format = a.format ?: b.format,
            description = a.description ?: b.description,
            properties = properties,
            required = (a.required + b.required).distinct(),
            additional = if (a.additional == Additional.Forbidden || b.additional == Additional.Forbidden) Additional.Forbidden else a.additional ?: b.additional,
            items = a.items ?: b.items,
            prefixItems = a.prefixItems.ifEmpty { b.prefixItems },
            enumValues = intersect(a.enumValues, b.enumValues),
            constValue = a.constValue ?: b.constValue,
            default = a.default ?: b.default,
            examples = a.examples.ifEmpty { b.examples },
            oneOf = a.oneOf.ifEmpty { b.oneOf },
            anyOf = a.anyOf.ifEmpty { b.anyOf },
            minimum = listOfNotNull(a.minimum, b.minimum).maxOrNull(),
            maximum = listOfNotNull(a.maximum, b.maximum).minOrNull(),
            exclusiveMinimum = listOfNotNull(a.exclusiveMinimum, b.exclusiveMinimum).maxOrNull(),
            exclusiveMaximum = listOfNotNull(a.exclusiveMaximum, b.exclusiveMaximum).minOrNull(),
            multipleOf = a.multipleOf ?: b.multipleOf,
            minLength = listOfNotNull(a.minLength, b.minLength).maxOrNull(),
            maxLength = listOfNotNull(a.maxLength, b.maxLength).minOrNull(),
            pattern = a.pattern ?: b.pattern,
            minItems = listOfNotNull(a.minItems, b.minItems).maxOrNull(),
            maxItems = listOfNotNull(a.maxItems, b.maxItems).minOrNull(),
            uniqueItems = a.uniqueItems || b.uniqueItems,
            minProperties = listOfNotNull(a.minProperties, b.minProperties).maxOrNull(),
            maxProperties = listOfNotNull(a.maxProperties, b.maxProperties).minOrNull(),
            nullable = a.nullable && b.nullable,
            readOnly = a.readOnly || b.readOnly,
            writeOnly = a.writeOnly || b.writeOnly,
            discriminator = a.discriminator ?: b.discriminator,
        )
    }

    private fun intersect(a: List<Json>?, b: List<Json>?): List<Json>? = when {
        a == null -> b
        b == null -> a
        else -> a.filter { it in b }
    }
}
