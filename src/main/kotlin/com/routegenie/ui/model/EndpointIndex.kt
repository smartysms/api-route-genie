package com.routegenie.ui.model

import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod

/**
 * The endpoints of one snapshot, sorted once (path, method, file, line) and with one lower-case search text per row, so a
 * keystroke is a linear scan over strings without allocation. Immutable; the UI builds it off the UI thread per snapshot.
 * Free text matches path, handler, file, route name, tags, framework and method; tagged terms test the endpoint's own fields.
 */
class EndpointIndex private constructor(
    val endpoints: List<Endpoint>,
    private val haystacks: Array<String>,
    // Lower-case views of path, file and handler. String.lowercase() returns the same instance when nothing changes, so for
    // the usual lower-case paths these arrays cost one reference per row and make tagged terms a plain contains().
    private val lowerPaths: Array<String>,
    private val lowerFiles: Array<String>,
    private val lowerHandlers: Array<String>,
) {
    val size: Int get() = endpoints.size

    /** The endpoints that satisfy [query]; the same list instance when nothing is filtered out. */
    fun filter(query: EndpointQuery, includeDeprecated: Boolean = true): List<Endpoint> {
        if (query.isEmpty && includeDeprecated) return endpoints
        val methods = query.methods
        val words = query.words.toTypedArray()
        val out = ArrayList<Endpoint>()
        for (i in endpoints.indices) {
            val e = endpoints[i]
            if (!includeDeprecated && e.deprecated) continue
            // ANY answers every verb, so it passes every method filter.
            if (methods != null && e.method != HttpMethod.ANY && e.method !in methods) continue
            if (query.frameworks.isNotEmpty() && !query.frameworks.any { frameworkMatches(e.framework, it) }) continue
            if (query.paths.isNotEmpty() && !query.paths.any { lowerPaths[i].contains(it) }) continue
            if (query.files.isNotEmpty() && !query.files.any { lowerFiles[i].contains(it) }) continue
            if (query.handlers.isNotEmpty() && !query.handlers.any {
                    lowerHandlers[i].contains(it)
                }
            ) continue
            if (query.tags.isNotEmpty() && !query.tags.any { t -> e.tags.any { it.contains(t, ignoreCase = true) } }) continue
            if (words.isNotEmpty()) {
                val haystack = haystacks[i]
                if (!words.all { haystack.contains(it) }) continue
            }
            out += e
        }
        return out
    }

    fun contains(key: String): Boolean = endpoints.any { it.key == key }

    companion object {
        /** Stable and total: the order does not depend on discovery timing. */
        val ORDER: Comparator<Endpoint> = compareBy<Endpoint> { it.path }
            .thenBy { it.method.ordinal }
            .thenBy { it.source.path }
            .thenBy { it.source.line }
            .thenBy { it.handler.qualifiedName }

        val EMPTY = build(emptyList())

        fun build(source: List<Endpoint>): EndpointIndex {
            val sorted = source.sortedWith(ORDER)
            val haystacks = Array(sorted.size) { haystackOf(sorted[it]) }
            return EndpointIndex(
                sorted, haystacks,
                Array(sorted.size) { sorted[it].path.lowercase() },
                Array(sorted.size) { sorted[it].source.path.lowercase() },
                Array(sorted.size) { sorted[it].handler.qualifiedName.lowercase() },
            )
        }

        private fun haystackOf(e: Endpoint): String {
            val sb = StringBuilder(e.path.length + e.handler.qualifiedName.length + e.source.path.length + 32)
            sb.append(e.path).append(' ').append(e.handler.qualifiedName).append(' ').append(e.source.path).append(' ')
            e.routeName?.let { sb.append(it).append(' ') }
            for (tag in e.tags) sb.append(tag).append(' ')
            sb.append(e.framework.displayName).append(' ').append(e.method.name)
            return sb.toString().lowercase()
        }

        /** framework:django also finds the REST framework; the enum name is matched by prefix, the display name anywhere. */
        fun frameworkMatches(framework: Framework, value: String): Boolean =
            framework.name.startsWith(value, ignoreCase = true) || framework.displayName.contains(value, ignoreCase = true)
    }
}
