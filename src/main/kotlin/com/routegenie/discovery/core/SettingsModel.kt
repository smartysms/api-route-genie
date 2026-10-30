package com.routegenie.discovery.core

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.model.Framework
import com.routegenie.model.SettingSuggestion
import com.routegenie.model.SourceRef
import com.routegenie.model.UnresolvedSetting

/*
 * Pure model of "settings": values the code takes from a configuration object or the environment, which no static
 * analysis can know. They are recognized (so the user can be asked once), never guessed: a pin the user confirmed is the
 * only thing that makes such a value known. Nothing here imports the IDE.
 */

/** How a class attribute is initialised. */
enum class AttrKind {
    /** `X: str = "/api"` or `Field(default="/api")`: [AttrFact.default] holds the expression. */
    VALUE,

    /** `Field(default_factory=...)`: computed at runtime, never suggested. */
    FACTORY,

    /** `X: str` without a default. */
    NONE,
}

data class AttrFact(val name: String, val kind: AttrKind, val default: Expr?, val offset: Int, val line: Int)

/** A class that looks like a settings holder, with the attributes written in its body. [bases] are dotted names as written. */
data class SettingsClassFact(val name: String, val bases: List<String>, val attrs: List<AttrFact>)

/**
 * `settings = Settings()` ([isFunction] false) or `def get_settings(): return Settings()` ([isFunction] true). [callee] is
 * the dotted name that is called, as written.
 */
data class SettingsInstanceFact(val name: String, val callee: String, val isFunction: Boolean)

/** The names that identify settings, in one place. They only classify an expression; no value is ever taken from a name. */
object SettingsCatalog {
    /** A qualifier with one of these names (`settings.X`, `config.X`) is a settings object when nothing else resolves it. */
    val OBJECT_NAMES: Set<String> = setOf("settings", "config", "conf", "cfg", "app_settings", "SETTINGS", "CONFIG")

    /** A class or base class whose name contains one of these words (any case) holds settings. */
    private val CLASS_WORDS = listOf("settings", "config")

    /** The pydantic field constructor and its keywords. */
    const val FIELD = "Field"
    const val FIELD_DEFAULT = "default"
    const val FIELD_FACTORY = "default_factory"

    /** Callees that read the environment: the call forms of `os.environ.get(name, default)`. */
    val ENV_CALLS: Set<List<String>> = setOf(
        listOf("os", "environ", "get"), listOf("os", "getenv"), listOf("environ", "get"), listOf("getenv"),
    )

    /** Subscript receivers that read the environment: `os.environ["X"]`. */
    val ENV_MAPPINGS: Set<List<String>> = setOf(listOf("os", "environ"), listOf("environ"))

    /** Subscript receivers whose last segment names a settings mapping: `app.config["X"]`, `current_app.config["X"]`. */
    val CONFIG_MAPPING_LAST: Set<String> = setOf("config", "settings")

    const val ENV_CANONICAL = "os.environ"

    fun envCanonical(name: String): String = "$ENV_CANONICAL[\"$name\"]"

    /** Which kind of default a literal in [canonical]'s read is: the environment's or a config mapping's. */
    fun defaultKind(canonical: String): com.routegenie.model.SuggestionKind =
        if (canonical.startsWith("$ENV_CANONICAL[")) com.routegenie.model.SuggestionKind.ENV_DEFAULT else com.routegenie.model.SuggestionKind.CONFIG_DEFAULT

    fun configCanonical(receiver: List<String>, key: String): String = "${receiver.joinToString(".")}[\"$key\"]"

    fun looksLikeSettingsClass(name: String, bases: List<String>): Boolean =
        (listOf(name) + bases.map { it.substringAfterLast('.') }).any { word -> CLASS_WORDS.any { word.contains(it, ignoreCase = true) } }

    fun looksLikeSettingsFactory(name: String): Boolean = CLASS_WORDS.any { name.contains(it, ignoreCase = true) }
}

/**
 * What the evaluator needs to know about settings: the pins the user confirmed (expression to value), where to report what
 * it saw, and the counting mode in which an unresolved setting is assumed empty (to count the routes below an include).
 */
class SettingsContext(
    val pins: Map<String, String> = emptyMap(),
    val collector: SettingsCollector? = null,
    val assumeEmpty: Boolean = false,
) {
    companion object {
        val NONE = SettingsContext()
    }
}

/**
 * The pins as plain data, handed to a provider together with the facts of the files (it is a [FileFacts] so the provider
 * interface does not change). Equal pins are equal objects, which is what lets an unchanged pass skip linking.
 */
class PinEnv(val values: Map<String, String>) : FileFacts {
    override val path: String get() = PATH

    override fun equals(other: Any?): Boolean = other is PinEnv && other.values == values

    override fun hashCode(): Int = values.hashCode()

    companion object {
        /** Not a project path, so it can never collide with a file. */
        const val PATH = "<pins>"
        val EMPTY = PinEnv(emptyMap())

        /** The pins in [facts], or none. */
        fun of(facts: Collection<FileFacts>): PinEnv = facts.filterIsInstance<PinEnv>().firstOrNull() ?: EMPTY
    }
}

/** Suggestions seen while evaluating: the default the code states for an expression, dropped when places disagree. */
class SettingsCollector {
    private val found = LinkedHashMap<String, SettingSuggestion>()
    private val conflicted = HashSet<String>()

    fun note(expression: String, suggestion: SettingSuggestion?) {
        if (suggestion == null || expression in conflicted) return
        val existing = found.putIfAbsent(expression, suggestion) ?: return
        // Two different literals for one expression: nothing is the default any more.
        if (existing.value != suggestion.value) {
            found.remove(expression)
            conflicted += expression
        }
    }

    val suggestions: Map<String, SettingSuggestion> get() = found
}

/** Counts, per expression, the routes that are hidden because of it and the first place it is used. */
class SettingTally(private val suggestions: Map<String, SettingSuggestion>) {
    private class Entry(val use: SourceRef, var count: Int)

    private val entries = LinkedHashMap<String, Entry>()

    fun hit(expression: String, use: SourceRef, routes: Int = 1) {
        val entry = entries[expression]
        if (entry == null) entries[expression] = Entry(use, routes) else entry.count += routes
    }

    /** One route that depends on all of [uses]; an expression listed twice counts once. */
    fun hitAll(uses: Collection<Pair<String, SourceRef>>, routes: Int = 1) {
        for ((expression, use) in uses.distinctBy { it.first }) hit(expression, use, routes)
    }

    /** Most routes first, then by text, at most [Limits.MAX_UNRESOLVED_SETTINGS]. */
    fun result(framework: Framework? = null): List<UnresolvedSetting> = entries.entries
        .map { (expression, entry) ->
            val suggestion = suggestions[expression]?.let { if (it.origin == null) it.copy(origin = entry.use) else it }
            UnresolvedSetting(expression, entry.use, entry.count, suggestion, framework)
        }
        .sortedWith(compareByDescending<UnresolvedSetting> { it.routeCount }.thenBy { it.expression })
        .take(Limits.MAX_UNRESOLVED_SETTINGS)
}
