package com.routegenie.client

import com.routegenie.model.HttpMethod

/** One request header; a disabled header is kept in the editor but never sent or exported. */
data class Header(val name: String, val value: String, val enabled: Boolean = true) {
    // A header value can be a credential and specs end up in logs and failure messages, so the text form never shows it.
    override fun toString(): String = "Header(name=$name, value=${Redactor.header(name, value)}, enabled=$enabled)"
}

/** One part of a multipart/form-data body: either a text [value] or the content of the file at [filePath]. */
data class MultipartPart(
    val name: String,
    val value: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
)

sealed interface RequestBody {
    data object None : RequestBody

    /** A text body such as JSON or XML, sent with [contentType]. */
    data class Text(val contentType: String, val text: String) : RequestBody

    /** application/x-www-form-urlencoded fields, in order. */
    data class Form(val fields: List<Pair<String, String>>) : RequestBody

    data class Multipart(val parts: List<MultipartPart>) : RequestBody
}

/**
 * A request ready to send or to export as cURL. The URL is complete (variables already substituted, path parameters and
 * query already filled in). The method is always concrete: an endpoint listed as ANY has to get a verb from the user first,
 * so no code path can send or export an ANY request.
 */
data class RequestSpec(
    val method: HttpMethod,
    val url: String,
    val headers: List<Header> = emptyList(),
    val body: RequestBody = RequestBody.None,
) {
    init {
        require(method.isConcrete) { "A request needs a concrete HTTP method, not $method" }
    }
}
