package com.routegenie.schema.model

import com.routegenie.Limits
import com.routegenie.client.BodyTemplate

/** Turns a declared request body into the text a user edits: a sample JSON document or a form. */
object BodyRenderer {
    /**
     * The template for [body], or null when the schema determines no sample at all. A model whose members all failed to
     * produce a value is reported as null rather than as an empty object, because `{}` would claim an empty body.
     */
    fun render(body: RequestBodySchema, includeOptional: Boolean = true): BodyTemplate? {
        val sample = SampleGenerator.generate(body.schema, SampleOptions(includeOptional))
        val value = sample.value ?: return null
        if (value is Json.Obj && value.fields.isEmpty() && sample.gaps.isNotEmpty()) return null
        val text = when (body.encoding) {
            BodyEncoding.JSON -> JsonText.pretty(value)
            BodyEncoding.FORM_URLENCODED -> FormBody.encode(value) ?: return null
        }
        if (text.length > Limits.BODY_TEMPLATE_MAX_CHARS) return null
        return BodyTemplate(body.encoding.contentType, text)
    }
}
