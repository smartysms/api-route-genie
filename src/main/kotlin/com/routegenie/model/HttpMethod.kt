package com.routegenie.model

/** HTTP verbs a route can answer. [ANY] means the framework does not restrict the method (a plain Django view). */
enum class HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS, TRACE, ANY;

    /** True when a request can be sent with this verb as is; [ANY] has to be resolved to a concrete verb first. */
    val isConcrete: Boolean get() = this != ANY

    companion object {
        /** Case-insensitive; null for anything that is not a concrete verb (including the text "ANY"). */
        fun parse(text: String): HttpMethod? =
            entries.firstOrNull { it.isConcrete && it.name.equals(text.trim(), ignoreCase = true) }
    }
}
