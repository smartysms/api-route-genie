package com.routegenie.schema.model

/** The fixed, realistic sample literals the generator writes; the one place they are defined, so samples never drift. */
object SampleValues {
    const val STRING = "string"

    /** Stand-in key of a map whose keys the code does not name (`dict[str, X]`). */
    const val MAP_KEY = "key"

    /** Format of a number written as text (Python's `Decimal` accepts `"12.50"` as well as `12.5`). Not a JSON Schema standard format. */
    const val DECIMAL = "decimal"

    private val byFormat: Map<String, String> = mapOf(
        "email" to "user@example.com",
        "idn-email" to "user@example.com",
        "uuid" to "123e4567-e89b-12d3-a456-426614174000",
        "date" to "2024-01-01",
        "date-time" to "2024-01-01T00:00:00Z",
        "time" to "12:00:00",
        "duration" to "PT1H",
        "uri" to "https://example.com",
        "url" to "https://example.com",
        "iri" to "https://example.com",
        "uri-reference" to "https://example.com",
        "hostname" to "example.com",
        "idn-hostname" to "example.com",
        "ipv4" to "192.0.2.1",
        "ipv6" to "2001:db8::1",
        "regex" to ".*",
        "json-pointer" to "/a",
        "password" to STRING,
        "byte" to "c3RyaW5n",
        DECIMAL to "0",
    )

    /** Formats that mean raw bytes; a text sample for them would be invented, so there is none. */
    private val noSample = setOf("binary")

    /** The sample for [format]; null when the format is unknown (the plain string sample is then used) or has none. */
    fun forFormat(format: String): String? = byFormat[format]

    fun hasNoSample(format: String): Boolean = format in noSample

    /** The text of a member name that a generated key value stands for; null when the value cannot be a key (an object, a list, null). */
    fun keyText(value: Json): String? = when (value) {
        is Json.Str -> value.value
        is Json.Num -> JsonText.number(value.value)
        is Json.Bool -> if (value.value) "true" else "false"
        else -> null
    }
}
