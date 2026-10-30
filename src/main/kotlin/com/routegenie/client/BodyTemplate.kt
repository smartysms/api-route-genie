package com.routegenie.client

import com.routegenie.model.Endpoint
import com.routegenie.schema.model.MultipartTemplate

/** A request body ready to edit, derived from what the code of an endpoint declares (never from guesses). */
data class BodyTemplate(val contentType: String, val text: String)

/**
 * Where the body editor's "Generate body" gets its text. Returns null when the code does not determine a request body for
 * the endpoint (no declared schema, or a schema that cannot be read statically): then nothing is generated and nothing is
 * invented. May do PSI work, so callers invoke it off the UI thread.
 */
fun interface BodyTemplateSource {
    suspend fun templateFor(endpoint: Endpoint): BodyTemplate?

    /**
     * Like [templateFor], but with random values that fit the declared types and limits; another document on every call.
     * A source that cannot do that answers null, as for a body that is not declared.
     */
    suspend fun randomTemplateFor(endpoint: Endpoint): BodyTemplate? = null

    /**
     * The parts of a `multipart/form-data` body the code declares (text values and file descriptions, no file bytes); null
     * when the endpoint's body is not multipart or cannot be represented. [random] draws the text values instead of using the
     * fixed sample. A source that cannot do that answers null.
     */
    suspend fun multipartTemplateFor(endpoint: Endpoint, random: Boolean): MultipartTemplate? = null
}
