package com.routegenie.model

/** Where a suggested pin value came from in the code; the UI says so next to the value. */
enum class SuggestionKind {
    /** A literal default of a class attribute (a pydantic `BaseSettings` field or a dataclass field). */
    CLASS_DEFAULT,

    /** The literal second argument of `os.environ.get(...)` / `os.getenv(...)`. */
    ENV_DEFAULT,

    /** The literal second argument of `app.config.get(...)`. */
    CONFIG_DEFAULT,
}

/** A value the code itself states as a default. Only literals qualify; nothing is derived from names. */
data class SettingSuggestion(val value: String, val kind: SuggestionKind, val origin: SourceRef?)

/**
 * A string piece of a route path that comes from a setting the plugin cannot know statically, so the routes below it are
 * omitted until the user pins a value. [expression] is the canonical text as written (`settings.API_V1_STR`,
 * `os.environ["PREFIX"]`); [use] is the first place it is used; [routeCount] counts the routes that depend on it.
 */
data class UnresolvedSetting(
    val expression: String,
    val use: SourceRef,
    val routeCount: Int,
    val suggestion: SettingSuggestion? = null,
    /** The framework whose route is hidden; decides what a valid value looks like (FastAPI and Flask prefixes start with '/'). */
    val framework: Framework? = null,
) {
    /** True when the value must be a URL prefix that starts with '/' (everything except Django route text). */
    val needsLeadingSlash: Boolean get() = framework != null && framework != Framework.DJANGO && framework != Framework.DRF
}
