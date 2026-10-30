package com.routegenie.model

/**
 * The complete, immutable result of one discovery run. A new run produces a new snapshot with a higher [generation];
 * consumers swap the reference instead of mutating anything.
 *
 * Deliberately not a data class: equality is identity, so publishing a snapshot through a StateFlow costs O(1) instead
 * of comparing thousands of endpoints.
 */
class RouteSnapshot(
    val generation: Long,
    endpoints: List<Endpoint>,
    diagnostics: List<Diagnostic> = emptyList(),
    /** Settings that hide routes until a pin exists, most routes first. Empty when every prefix is known. */
    settings: List<UnresolvedSetting> = emptyList(),
) {
    val endpoints: List<Endpoint> = endpoints.toList()
    val diagnostics: List<Diagnostic> = diagnostics.toList()
    val settings: List<UnresolvedSetting> = settings.toList()

    /** Expressions of the pins that took part in at least one listed endpoint. */
    val usedPins: Set<String> = this.endpoints.flatMapTo(LinkedHashSet()) { it.pins }

    override fun toString(): String =
        "RouteSnapshot(generation=$generation, endpoints=${endpoints.size}, diagnostics=${diagnostics.size})"

    companion object {
        val EMPTY = RouteSnapshot(0, emptyList())
    }
}
