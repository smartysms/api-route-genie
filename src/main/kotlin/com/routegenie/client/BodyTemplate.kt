package com.routegenie.client

import com.routegenie.model.Endpoint

/** A request body ready to edit, derived from what the code of an endpoint declares (never from guesses). */
data class BodyTemplate(val contentType: String, val text: String)

/**
 * Where the body editor's "Generate body" gets its text. Returns null when the code does not determine a request body for
 * the endpoint (no declared schema, or a schema that cannot be read statically): then nothing is generated and nothing is
 * invented. May do PSI work, so callers invoke it off the UI thread.
 */
fun interface BodyTemplateSource {
    suspend fun templateFor(endpoint: Endpoint): BodyTemplate?
}
