package com.routegenie.model

/** A parameter of the URL path, e.g. `user_id` with type `int` for `/users/<int:user_id>`. */
data class PathParam(val name: String, val type: String? = null)

/** The function or class that answers the request. [qualifiedName] is dotted, e.g. `app.routers.users.get_user`. */
data class Handler(val name: String, val qualifiedName: String)

/**
 * One callable endpoint: one HTTP method on one full path. A route that accepts several methods yields one
 * [Endpoint] per method. Holds no PSI, VirtualFile or Project, so it can be kept, compared and shown from any thread.
 *
 * [path] is the complete template including every prefix, starting with '/'. Parts that cannot be computed
 * statically are written as [PathSyntax.UNKNOWN_SEGMENT] and recorded in [problems]; such an endpoint is [isPartial].
 */
data class Endpoint(
    val method: HttpMethod,
    val path: String,
    val framework: Framework,
    val handler: Handler,
    val source: SourceRef,
    val routeName: String? = null,
    val tags: List<String> = emptyList(),
    val pathParams: List<PathParam> = emptyList(),
    val deprecated: Boolean = false,
    val problems: List<Diagnostic> = emptyList(),
) {
    val isPartial: Boolean get() = problems.isNotEmpty()

    /** Identity that survives refreshes and edits elsewhere in the file (no offsets or line numbers in it). */
    val key: String = listOf(framework, method, path, source.path, handler.qualifiedName).joinToString("|")
}
