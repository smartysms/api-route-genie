package com.routegenie.schema.psi

/** The alias generators Pydantic v2 ships (`pydantic.alias_generators`), reimplemented so no Python has to run. */
internal object AliasGenerators {
    /** `str.title()`: a letter is upper case when the character before it is not a letter, lower case otherwise. */
    private fun title(text: String): String {
        val out = StringBuilder(text.length)
        var previousLetter = false
        for (c in text) {
            out.append(if (c.isLetter()) (if (previousLetter) c.lowercaseChar() else c.uppercaseChar()) else c)
            previousLetter = c.isLetter()
        }
        return out.toString()
    }

    /** `re.sub('([0-9A-Za-z])_(?=[0-9A-Z])', r'\1', ...)`: drop an underscore between an alphanumeric and a digit or capital. */
    private fun dropUnderscores(text: String): String {
        val out = StringBuilder(text.length)
        for ((i, c) in text.withIndex()) {
            if (c == '_' && i > 0 && i + 1 < text.length && text[i - 1].isAsciiAlnum() && (text[i + 1].isDigit() || text[i + 1] in 'A'..'Z')) continue
            out.append(c)
        }
        return out.toString()
    }

    private fun Char.isAsciiAlnum() = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

    fun toPascal(snake: String): String = dropUnderscores(title(snake))

    fun toCamel(snake: String): String {
        // Already camel case (and no digit followed by a lower case letter): returned as is.
        if (ALREADY_CAMEL.matches(snake) && !DIGIT_LOWER.containsMatchIn(snake)) return snake
        val pascal = toPascal(snake)
        val lead = pascal.takeWhile { it == '_' }.length
        if (lead >= pascal.length) return pascal
        return pascal.substring(0, lead) + pascal[lead].lowercaseChar() + pascal.substring(lead + 1)
    }

    fun toSnake(camel: String): String {
        var text = camel.replace('-', '_')
        text = Regex("([A-Z]+)([A-Z][a-z])").replace(text) { "${it.groupValues[1]}_${it.groupValues[2]}" }
        text = Regex("([a-z])([A-Z])").replace(text) { "${it.groupValues[1]}_${it.groupValues[2]}" }
        text = Regex("([0-9])([A-Za-z])").replace(text) { "${it.groupValues[1]}_${it.groupValues[2]}" }
        text = Regex("([a-z])([0-9])").replace(text) { "${it.groupValues[1]}_${it.groupValues[2]}" }
        return text.lowercase()
    }

    private val ALREADY_CAMEL = Regex("^[a-z]+[A-Za-z0-9]*$")
    private val DIGIT_LOWER = Regex("[0-9][a-z]")
}
