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

    data class Known(override val text: String) : StrValue {
        override val reason: DiagnosticCode? get() = null
    }

    /** Some parts are known, the rest is computed at runtime; [text] shows the unknown parts as `{?}`. */
    data class Partial(override val text: String, override val reason: DiagnosticCode) : StrValue

    /** Nothing about the value could be determined. */
    data class Unknown(override val reason: DiagnosticCode = DiagnosticCode.DYNAMIC_VALUE) : StrValue {
        override val text: String get() = PathSyntax.UNKNOWN_SEGMENT
    }

    /** Concatenation: exact only when both sides are exact; the first reason found is kept. */
    operator fun plus(other: StrValue): StrValue = when {
        this is Known && other is Known -> Known(text + other.text)
        this is Unknown && other is Unknown -> this
        else -> Partial(text + other.text, reason ?: other.reason ?: DiagnosticCode.DYNAMIC_VALUE)
    }
}
