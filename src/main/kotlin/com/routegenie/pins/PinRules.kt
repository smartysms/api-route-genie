package com.routegenie.pins

import com.routegenie.Limits

/**
 * One value the user confirmed for an expression the code takes from a setting. [expression] is the canonical text
 * (`settings.API_V1_STR`); [note], [by] and [at] are optional and only for people reading the file.
 */
data class Pin(val expression: String, val value: String, val note: String = "", val by: String = "", val at: String = "")

/** Why a pin is refused. The text shown to the user lives in the bundle under [bundleKey]. */
enum class PinProblem {
    EXPRESSION_EMPTY,
    EXPRESSION_TOO_LONG,
    EXPRESSION_CHARS,
    EXPRESSION_SECRET,
    VALUE_TOO_LONG,
    VALUE_BLANK_CHARS,
    VALUE_DOTS,
    VALUE_CHARS,
    VALUE_SLASH_START,
    VALUE_SLASH_END,
    TOO_MANY;

    val bundleKey: String get() = "pins.problem.${name.lowercase()}"
}

/**
 * The rules a pin must satisfy before it is stored or applied. Pure. A pin is a statement by the user that goes into a file
 * shared with the team, so it must never hold a secret: refusing by the name of the setting is the cheap, reliable half of
 * that (the value of a path prefix cannot be one).
 */
object PinRules {
    /** Words that make a setting name look like a secret; matched against the words of the name (`API_KEY` has the word `key`). */
    private val SECRET_WORDS = setOf(
        "password", "passwd", "pwd", "secret", "secrets", "token", "tokens", "key", "keys", "credential", "credentials", "apikey",
    )

    private val WORD_SPLIT = Regex("[^A-Za-z0-9]+")
    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /** Characters of a path value that are refused: whitespace and controls are checked separately. */
    private const val REFUSED_VALUE_CHARS = "?#\\\""

    fun looksSecret(expression: String): Boolean {
        val words = expression.split(WORD_SPLIT).flatMap { it.split(CAMEL) }.map { it.lowercase() }.filter { it.isNotEmpty() }
        if (words.any { it in SECRET_WORDS }) return true
        // "apikey", "secretkey" and "dbpassword" written as one word.
        val joined = expression.lowercase().replace(WORD_SPLIT, "")
        return listOf("password", "passwd", "secret", "token", "credential", "apikey").any { joined.contains(it) }
    }

    fun checkExpression(expression: String): PinProblem? = when {
        expression.isBlank() -> PinProblem.EXPRESSION_EMPTY
        expression.length > Limits.PIN_MAX_EXPRESSION_CHARS -> PinProblem.EXPRESSION_TOO_LONG
        expression.any { it.isISOControl() } || expression != expression.trim() -> PinProblem.EXPRESSION_CHARS
        looksSecret(expression) -> PinProblem.EXPRESSION_SECRET
        else -> null
    }

    /**
     * [requireLeadingSlash] is true for FastAPI and Flask prefixes: they start with '/' and (FastAPI asserts it) do not end
     * with one, except the single "/" for the root. Django route text has no leading slash (`api/`), so it passes false. An
     * empty value is always allowed: it is a real, common prefix (a root deployment).
     */
    fun checkValue(value: String, requireLeadingSlash: Boolean): PinProblem? = when {
        value.length > Limits.PIN_MAX_VALUE_CHARS -> PinProblem.VALUE_TOO_LONG
        value.any { it.isWhitespace() } -> PinProblem.VALUE_BLANK_CHARS
        value.any { it.isISOControl() || it in REFUSED_VALUE_CHARS } -> PinProblem.VALUE_CHARS
        value.contains("..") -> PinProblem.VALUE_DOTS
        value.isEmpty() -> null
        requireLeadingSlash && !value.startsWith('/') -> PinProblem.VALUE_SLASH_START
        requireLeadingSlash && value.length > 1 && value.endsWith('/') -> PinProblem.VALUE_SLASH_END
        else -> null
    }

    fun check(pin: Pin, requireLeadingSlash: Boolean): PinProblem? =
        checkExpression(pin.expression) ?: checkValue(pin.value, requireLeadingSlash)

    /** Notes and names are free text for people; they are only cut to the limit, not validated. */
    fun cutNote(text: String): String = text.take(Limits.PIN_MAX_NOTE_CHARS)
}
