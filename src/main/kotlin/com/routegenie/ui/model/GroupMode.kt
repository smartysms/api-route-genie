package com.routegenie.ui.model

/** How the endpoint tree is organized. [bundleKey] names the menu text; the pure layer holds no text itself. */
enum class GroupMode(val bundleKey: String) {
    /** Framework first, then the URL path as a hierarchy. */
    LOGICAL("group.logical"),
    BY_FILE("group.file"),

    /** One group per first path segment (/api, /admin). */
    BY_PREFIX("group.prefix"),
    NONE("group.none");

    companion object {
        fun parse(name: String?): GroupMode = entries.firstOrNull { it.name == name } ?: LOGICAL
    }
}
