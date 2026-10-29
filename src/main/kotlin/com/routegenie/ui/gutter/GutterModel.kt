package com.routegenie.ui.gutter

import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.model.RouteSnapshot

/** One selectable endpoint on a line: what the user reads ("GET, POST /users") and the key that identifies it. */
data class Choice(val text: String, val key: String)

/** Everything the gutter knows about one source line; strings only. [label] is the tooltip. */
data class LineEntry(val choices: List<Choice>) {
    val label: String = choices.joinToString("; ") { it.text }
}

/**
 * Snapshot of the endpoints re-keyed by project-relative file path and 1-based line, for lookups at marker time.
 * Holds strings and ints only (no PSI, VirtualFile or Project), so it can be kept, compared and released freely.
 */
class GutterModel private constructor(val generation: Long, private val files: Map<String, Map<Int, LineEntry>>) {
    val isEmpty: Boolean get() = files.isEmpty()

    /** Lines of [path] that declare endpoints, or null. */
    fun linesOf(path: String): Map<Int, LineEntry>? = files[path]

    fun entryAt(path: String, line: Int): LineEntry? = files[path]?.get(line)

    /** Equal when the markers would be equal; the generation is not part of it, so an unchanged scan restarts nothing. */
    override fun equals(other: Any?): Boolean = other is GutterModel && other.files == files

    override fun hashCode(): Int = files.hashCode()

    companion object {
        val EMPTY = GutterModel(0, emptyMap())

        fun build(snapshot: RouteSnapshot): GutterModel {
            if (snapshot.endpoints.isEmpty()) return EMPTY
            val byLine = HashMap<String, HashMap<Int, MutableList<Endpoint>>>()
            for (endpoint in snapshot.endpoints) {
                byLine.getOrPut(endpoint.source.path) { HashMap() }.getOrPut(endpoint.source.line) { ArrayList() } += endpoint
            }
            val files = byLine.mapValues { (_, lines) -> lines.mapValues { (_, endpoints) -> LineEntry(choicesOf(endpoints)) } }
            return GutterModel(snapshot.generation, files)
        }

        /** One choice per distinct path, methods merged in verb order: `GET, POST /multi`. */
        fun choicesOf(endpoints: List<Endpoint>): List<Choice> =
            endpoints.groupBy { it.path }.map { (path, group) ->
                val methods = group.map { it.method }.distinct().sorted().joinToString(", ")
                Choice("$methods $path", group.first().key)
            }.sortedBy { it.text }.take(Limits.GUTTER_MAX_CHOICES)
    }
}
