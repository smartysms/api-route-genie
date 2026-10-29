package com.routegenie.ui.model

import com.routegenie.model.HttpMethod

/** The tags the query box understands, in one place so the parser, the help text and the tests agree. */
object QueryKeys {
    const val METHOD = "method"
    const val FRAMEWORK = "framework"
    const val TAG = "tag"
    const val PATH = "path"
    const val FILE = "file"
    const val HANDLER = "handler"

    val ALL = listOf(METHOD, FRAMEWORK, TAG, PATH, FILE, HANDLER)
}

/**
 * What the user typed in the query box, parsed once per keystroke. Terms are separated by whitespace; `key:value` terms with a
 * known key narrow one property (several values of one key, or a comma list, are alternatives; different keys all have to
 * hold), everything else is free text that must appear in the row's searchable text. An unknown key or an empty value
 * (the user is still typing `method:`) is treated as free text or ignored, never as an error.
 */
class EndpointQuery private constructor(
    /** Null means "any method"; an empty set (an unknown verb was typed) matches nothing. */
    val methods: Set<HttpMethod>?,
    val frameworks: List<String>,
    val tags: List<String>,
    val paths: List<String>,
    val files: List<String>,
    val handlers: List<String>,
    /** Lower case, all of them have to occur. */
    val words: List<String>,
) {
    val isEmpty: Boolean
        get() = methods == null && frameworks.isEmpty() && tags.isEmpty() && paths.isEmpty() &&
            files.isEmpty() && handlers.isEmpty() && words.isEmpty()

    companion object {
        private val WHITESPACE = Regex("\\s+")

        val EMPTY = parse("")

        fun parse(text: String): EndpointQuery {
            var methods: MutableSet<HttpMethod>? = null
            val frameworks = ArrayList<String>()
            val tags = ArrayList<String>()
            val paths = ArrayList<String>()
            val files = ArrayList<String>()
            val handlers = ArrayList<String>()
            val words = ArrayList<String>()
            for (term in text.trim().split(WHITESPACE)) {
                if (term.isEmpty()) continue
                val colon = term.indexOf(':')
                val key = if (colon > 0) term.substring(0, colon).lowercase() else null
                if (key == null || key !in QueryKeys.ALL) {
                    words += term.lowercase()
                    continue
                }
                val values = term.substring(colon + 1).split(',').filter { it.isNotEmpty() }.map { it.lowercase() }
                if (values.isEmpty()) continue
                when (key) {
                    QueryKeys.METHOD -> {
                        val set = methods ?: HashSet<HttpMethod>().also { methods = it }
                        values.forEach { set += methodsMatching(it) }
                    }
                    QueryKeys.FRAMEWORK -> frameworks += values
                    QueryKeys.TAG -> tags += values
                    QueryKeys.PATH -> paths += values
                    QueryKeys.FILE -> files += values
                    QueryKeys.HANDLER -> handlers += values
                }
            }
            return EndpointQuery(methods, frameworks, tags, paths, files, handlers, words)
        }

        /** A complete verb, or every verb that starts with what was typed so far (`method:P` finds POST, PUT, PATCH). */
        private fun methodsMatching(value: String): List<HttpMethod> =
            HttpMethod.entries.filter { it.name.startsWith(value, ignoreCase = true) }
    }
}
