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
        }
        if (text.length > Limits.BODY_TEMPLATE_MAX_CHARS) return null
        return BodyTemplate(body.encoding.contentType, text)
    }
}
