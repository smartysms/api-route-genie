package com.routegenie.schema.model

import com.routegenie.Limits
import com.routegenie.client.BodyTemplate

/** Turns a declared request body into the text a user edits: a sample JSON document or a form. */
object BodyRenderer {
    /**
     * The template for [body], or null when the schema determines no sample at all. A model whose members all failed to
     * produce a value is reported as null rather than as an empty object, because `{}` would claim an empty body.
     */
    fun render(body: RequestBodySchema, includeOptional: Boolean = true): BodyTemplate? =
        finish(body, SampleGenerator.generate(body.schema, SampleOptions(includeOptional)))

    /** Like [render], but with random values that fit the declared types and limits; a different document on every call. */
    fun renderRandom(body: RequestBodySchema, random: kotlin.random.Random = kotlin.random.Random.Default, options: RandomOptions = RandomOptions()): BodyTemplate? =
        finish(body, RandomSampleGenerator.generate(body.schema, random, options))

    private fun finish(body: RequestBodySchema, sample: SampleResult): BodyTemplate? {
        val value = sample.value ?: return null
        if (value is Json.Obj && value.fields.isEmpty() && sample.gaps.isNotEmpty()) return null
        val text = when (body.encoding) {
            BodyEncoding.JSON -> JsonText.pretty(value)
            BodyEncoding.FORM_URLENCODED -> FormBody.encode(value) ?: return null
            // Files are not text: a multipart body has a template of parts, not a text one (see renderMultipart).
            BodyEncoding.MULTIPART -> return null
        }
        if (text.length > Limits.BODY_TEMPLATE_MAX_CHARS) return null
        return BodyTemplate(body.encoding.contentType, text)
    }

    /**
     * The parts a `multipart/form-data` request of [body] should send, or null when the body is not multipart or a part
     * cannot be represented. With [random] null the sample is the fixed one (every property written, one file per list);
     * otherwise values are drawn from [random] like [renderRandom] does.
     *
     * Names: a part is named by its top-level property (the alias when the code declares one). A file list becomes several
     * parts with the same name, as every framework reads them (`list[UploadFile]`, `getlist`, DRF `many`).
     *
     * Text values are what a browser form sends: a number or string as written, a boolean as `true` or `false`, a list of
     * scalars as the same name repeated once per item, a null as no part at all. A nested object, a list of objects, and
     * any file below the top level have no plain form encoding that all four frameworks read (FastAPI cannot parse a JSON
     * text into a model or dict `Form` field; DRF reads nested serializers from dotted names and only when the serializer is
     * the declared one), so such a body is refused with null instead of guessed. A required text property that has no sample
     * (a pattern outside the supported subset, say) also refuses the body; an optional one is left out.
     */
    fun renderMultipart(body: RequestBodySchema, random: kotlin.random.Random? = null, options: RandomOptions = RandomOptions()): MultipartTemplate? {
        if (body.encoding != BodyEncoding.MULTIPART) return null
        val root = body.schema
        val resolved = root.ref?.let { root.defs[it.substringAfterLast('/')] } ?: root
        if (JsonType.OBJECT !in resolved.types || resolved.properties.isEmpty()) return null
        val required = resolved.required.toHashSet()
        val files = LinkedHashMap<String, FileShape>()
        val texts = LinkedHashMap<String, Schema>()
        for ((name, property) in resolved.properties) {
            if (property.readOnly) continue
            val shape = FileSpec.shape(property)
            if (shape != null) files[name] = shape else texts[name] = if (name in required) formRequired(property) else property
        }
        val values = LinkedHashMap<String, Json>()
        if (texts.isNotEmpty()) {
            val textSchema = Schema(types = listOf(JsonType.OBJECT), properties = texts, required = resolved.required.filter { it in texts }, defs = root.defs)
            val sample = if (random == null) SampleGenerator.generate(textSchema) else RandomSampleGenerator.generate(textSchema, random, options)
            val generated = sample.value as? Json.Obj ?: return null
            values.putAll(generated.fields)
        }
        val parts = ArrayList<TemplatePart>()
        for (name in resolved.properties.keys) {
            val shape = files[name]
            if (shape != null) {
                repeat(fileCount(shape, name in required, random, options)) { parts += TemplatePart(name, null, shape.spec) }
            } else if (name in texts) {
                val value = values[name]
                if (value == null) {
                    if (name in required) return null
                    continue
                }
                if (!textParts(name, value, parts)) return null
            }
            if (parts.count { it.file != null } > Limits.MULTIPART_MAX_PARTS) return null
        }
        return MultipartTemplate(parts)
    }

    /**
     * A required property as a form can carry it: a form has no null and no empty list (both mean "not sent", which a required
     * field refuses), so the null branch is dropped and a list gets at least one item.
     */
    private fun formRequired(property: Schema): Schema {
        val branches = property.anyOf.filterNot { it.types == listOf(JsonType.NULL) && it.enumValues == null }
        var core = if (property.anyOf.size > branches.size && branches.size == 1) branches.single() else property
        core = core.copy(nullable = false)
        if (JsonType.ARRAY in core.types && (core.maxItems == null || core.maxItems >= 1)) core = core.copy(minItems = maxOf(core.minItems ?: 0, 1))
        return core
    }

    private fun fileCount(shape: FileShape, required: Boolean, random: kotlin.random.Random?, options: RandomOptions): Int {
        if (random != null && !required && random.nextDouble() >= options.optionalChance) return 0
        if (!shape.many) return 1
        val most = shape.maxItems ?: Int.MAX_VALUE
        val least = maxOf(shape.minItems ?: 0, 1)
        if (most < least) return if (most <= 0) 0 else most
        val wanted = if (random == null) least else random.nextInt(least, minOf(most.toLong(), least.toLong() + Limits.MULTIPART_RANDOM_EXTRA_FILES).toInt() + 1)
        return minOf(wanted, Limits.MULTIPART_MAX_PARTS + 1)
    }

    /** Adds the text part(s) of [value]; false when it has no plain form encoding. */
    private fun textParts(name: String, value: Json, out: MutableList<TemplatePart>): Boolean {
        fun scalar(item: Json): String? = when (item) {
            is Json.Str -> item.value
            is Json.Num -> JsonText.number(item.value)
            is Json.Bool -> if (item.value) "true" else "false"
            else -> null
        }
        when (value) {
            Json.Null -> Unit
            is Json.Arr -> for (item in value.items) {
                if (item == Json.Null) continue
                out += TemplatePart(name, scalar(item) ?: return false, null)
            }
            is Json.Obj -> return false
            else -> out += TemplatePart(name, scalar(value) ?: return false, null)
        }
        return true
    }
}
