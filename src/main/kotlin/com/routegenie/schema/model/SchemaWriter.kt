package com.routegenie.schema.model

/**
 * Serializes a [Schema] as JSON Schema draft 2020-12 text, the dialect the validation phase and the IDE's JSON schema
 * support read. Keys come in a fixed, readable order; absent and default-valued keywords are not written.
 */
object SchemaWriter {
    const val DIALECT = "https://json-schema.org/draft/2020-12/schema"

    fun text(schema: Schema, pretty: Boolean = true): String {
        val json = toJson(schema, root = true)
        return if (pretty) JsonText.pretty(json) else JsonText.compact(json)
    }

    fun toJson(schema: Schema, root: Boolean = false): Json.Obj {
        val out = LinkedHashMap<String, Json>()
        if (root) out["\$schema"] = Json.Str(DIALECT)
        schema.ref?.let { out["\$ref"] = Json.Str(it) }
        writeTypes(schema, out)
        schema.format?.let { out["format"] = Json.Str(it) }
        schema.enumValues?.let { out["enum"] = Json.Arr(it) }
        schema.constValue?.let { out["const"] = it }
        schema.default?.let { out["default"] = it }
        if (schema.examples.isNotEmpty()) out["examples"] = Json.Arr(schema.examples)
        schema.description?.let { out["description"] = Json.Str(it) }
        if (schema.properties.isNotEmpty()) {
            out["properties"] = Json.Obj(schema.properties.mapValuesTo(LinkedHashMap()) { toJson(it.value) })
        }
        if (schema.required.isNotEmpty()) out["required"] = Json.Arr(schema.required.map { Json.Str(it) })
        schema.additional?.let {
            out["additionalProperties"] = when (it) {
                Additional.Allowed -> Json.Bool(true)
                Additional.Forbidden -> Json.Bool(false)
                is Additional.OfSchema -> toJson(it.schema)
            }
        }
        schema.propertyNames?.let { out["propertyNames"] = toJson(it) }
        schema.items?.let { out["items"] = toJson(it) }
        if (schema.prefixItems.isNotEmpty()) out["prefixItems"] = Json.Arr(schema.prefixItems.map { toJson(it) })
        number(out, "minimum", schema.minimum)
        number(out, "maximum", schema.maximum)
        number(out, "exclusiveMinimum", schema.exclusiveMinimum)
        number(out, "exclusiveMaximum", schema.exclusiveMaximum)
        number(out, "multipleOf", schema.multipleOf)
        schema.minLength?.let { out["minLength"] = Json.num(it) }
        schema.maxLength?.let { out["maxLength"] = Json.num(it) }
        schema.pattern?.let { out["pattern"] = Json.Str(it) }
        schema.minItems?.let { out["minItems"] = Json.num(it) }
        schema.maxItems?.let { out["maxItems"] = Json.num(it) }
        if (schema.uniqueItems) out["uniqueItems"] = Json.Bool(true)
        schema.minProperties?.let { out["minProperties"] = Json.num(it) }
        schema.maxProperties?.let { out["maxProperties"] = Json.num(it) }
        branches(out, "oneOf", schema.oneOf)
        branches(out, "anyOf", schema.anyOf)
        branches(out, "allOf", schema.allOf)
        schema.discriminator?.let { out["discriminator"] = Json.obj("propertyName" to Json.Str(it)) }
        if (schema.readOnly) out["readOnly"] = Json.Bool(true)
        if (schema.writeOnly) out["writeOnly"] = Json.Bool(true)
        if (schema.deprecated) out["deprecated"] = Json.Bool(true)
        schema.extensions.forEach { (key, value) -> out[key] = value }
        if (schema.defs.isNotEmpty()) {
            out["\$defs"] = Json.Obj(schema.defs.mapValuesTo(LinkedHashMap()) { toJson(it.value) })
        }
        return Json.Obj(out)
    }

    private fun writeTypes(schema: Schema, out: MutableMap<String, Json>) {
        val types = if (schema.nullable && schema.types.isNotEmpty() && JsonType.NULL !in schema.types) schema.types + JsonType.NULL else schema.types
        when (types.size) {
            0 -> Unit
            1 -> out["type"] = Json.Str(types.first().keyword)
            else -> out["type"] = Json.Arr(types.map { Json.Str(it.keyword) })
        }
    }

    private fun number(out: MutableMap<String, Json>, key: String, value: java.math.BigDecimal?) {
        if (value != null) out[key] = Json.Num(value)
    }

    private fun branches(out: MutableMap<String, Json>, key: String, list: List<Schema>) {
        if (list.isNotEmpty()) out[key] = Json.Arr(list.map { toJson(it) })
    }
}

/** The schema as JSON Schema draft 2020-12 text. */
fun Schema.toJsonSchemaText(pretty: Boolean = true): String = SchemaWriter.text(this, pretty)
