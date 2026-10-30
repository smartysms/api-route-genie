package com.routegenie.settings

/** How the request body of a POST, PUT or PATCH is filled on its own the first time an endpoint is opened. */
enum class BodyFillMode(val bundleKey: String) {
    /** Fixed, readable sample values ("string", 0). */
    SAMPLE("settings.fill.sample"),

    /** Random values that fit the declared types and limits; the "Random body" button gives another set any time. */
    RANDOM("settings.fill.random"),

    /** Nothing is filled; the Generate body and Random body buttons still work. */
    OFF("settings.fill.off"),
    ;

    companion object {
        fun parse(name: String?): BodyFillMode = entries.firstOrNull { it.name == name } ?: SAMPLE
    }
}
