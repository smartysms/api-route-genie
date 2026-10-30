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
    /** Position among endpoints that are otherwise identical (same method, path, file and handler); see [EndpointKeys]. */
    val ordinal: Int = 0,
    /**
     * Expressions of pinned settings (`settings.API_V1_STR`) whose user-confirmed value is part of [path]. Not part of the
     * identity: [key] only changes through the resolved path.
     */
    val pins: List<String> = emptyList(),
) {
    val isPartial: Boolean get() = problems.isNotEmpty()

    /** Identity that survives refreshes and edits elsewhere in the file (no offsets or line numbers in it). */
    val baseKey: String = listOf(framework, method, path, source.path, handler.qualifiedName).joinToString("|")

    /** Unique within a snapshot (see [EndpointKeys.disambiguate]); equal to [baseKey] for the first of identical endpoints. */
    val key: String = if (ordinal == 0) baseKey else "$baseKey|#$ordinal"

    /** One declaration in the source expanded to one path: two expansions of the same line are the same endpoint. */
    val declarationKey: String get() = "$key@${source.offset}"
}

object EndpointKeys {
    /**
     * Makes every [Endpoint.key] unique. Declarations that only differ by where they sit in a file (the same route in two
     * test functions that each build their own app) share a base key; they get the ordinals 0, 1, 2 in source order, so
     * the first keeps the plain key and the numbering does not depend on line numbers or on edits elsewhere in the file.
     */
    fun disambiguate(endpoints: List<Endpoint>): List<Endpoint> {
        val groups = endpoints.groupBy { it.baseKey }.values.filter { it.size > 1 }
        if (groups.isEmpty()) return endpoints
        val ordinals = java.util.IdentityHashMap<Endpoint, Int>()
        for (group in groups) group.sortedBy { it.source.offset }.forEachIndexed { index, endpoint -> ordinals[endpoint] = index }
        return endpoints.map { endpoint -> ordinals[endpoint]?.let { endpoint.copy(ordinal = it) } ?: endpoint }
    }
}
