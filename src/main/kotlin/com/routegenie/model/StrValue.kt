package com.routegenie.model

/**
 * The statically computed value of a string expression. Route prefixes and paths are often built at runtime
 * (`f"{settings.API}/users"`); instead of guessing or dropping the route, the unknown part is kept visible.
 */
sealed interface StrValue {
    /** The value with every unknown part replaced by [PathSyntax.UNKNOWN_SEGMENT]. */
    val text: String

    /** Why the value is not exact; null when it is. */
    val reason: DiagnosticCode?

    val isExact: Boolean get() = this is Known

    /**
     * The pins (user-confirmed settings, see `pins`) this value was computed with. A value that carries one is still exact:
     * the user stated the part the code could not.
     */
    val pins: List<String> get() = emptyList()

    /** Expressions of settings that stopped this value from being exact, as written in the code (`settings.API_V1_STR`). */
    val unresolved: List<String> get() = emptyList()

    data class Known(override val text: String, override val pins: List<String> = emptyList()) : StrValue {
        override val reason: DiagnosticCode? get() = null
    }

    /** Some parts are known, the rest is computed at runtime; [text] shows the unknown parts as `{?}`. */
    data class Partial(
        override val text: String,
        override val reason: DiagnosticCode,
        override val pins: List<String> = emptyList(),
        override val unresolved: List<String> = emptyList(),
    ) : StrValue

    /** Nothing about the value could be determined. */
    data class Unknown(
        override val reason: DiagnosticCode = DiagnosticCode.DYNAMIC_VALUE,
        override val unresolved: List<String> = emptyList(),
    ) : StrValue {
        override val text: String get() = PathSyntax.UNKNOWN_SEGMENT
    }

    /** Concatenation: exact only when both sides are exact; the first reason found is kept, pins and settings are united. */
    operator fun plus(other: StrValue): StrValue {
        val pinsOf = union(pins, other.pins)
        val settingsOf = union(unresolved, other.unresolved)
        return when {
            this is Known && other is Known -> Known(text + other.text, pinsOf)
            this is Unknown && other is Unknown -> Unknown(reason, settingsOf)
            else -> Partial(text + other.text, reason ?: other.reason ?: DiagnosticCode.DYNAMIC_VALUE, pinsOf, settingsOf)
        }
    }

    companion object {
        /** Order-preserving union that allocates nothing in the common case of two empty lists. */
        fun union(a: List<String>, b: List<String>): List<String> = when {
            b.isEmpty() -> a
            a.isEmpty() -> b
            else -> (a + b).distinct()
        }
    }
}
