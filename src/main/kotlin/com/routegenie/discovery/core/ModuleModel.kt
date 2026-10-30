package com.routegenie.discovery.core

import com.routegenie.discovery.SourceFacts

/**
 * A name bound by an import, as written. [module] is the part after `from` without the leading dots (empty for
 * `from . import x`), [level] the number of dots; both are resolved against the file's own module name only when linking,
 * so the facts do not change when the file moves. [name] is null for `import a.b [as c]`, where [dotted] holds `a.b`.
 */
data class ImportBinding(
    val local: String,
    val module: String,
    val name: String?,
    val level: Int = 0,
    val dotted: String? = null,
    val aliased: Boolean = false,
)

/** `from module import *`. */
data class StarBinding(val module: String, val level: Int = 0)

/** A top-level `name = value`. [value] is null when it cannot be read (augmented assignment, missing value). */
data class ConstFact(val name: String, val value: Expr?)

/**
 * What a file offers to the linker's name resolution, independent of the framework. The facts of both Python route
 * families implement it, so constants, imports and module names are resolved in one place.
 */
interface ModuleSource : SourceFacts {
    val imports: List<ImportBinding>
    val stars: List<StarBinding>
    val consts: List<ConstFact>

    /** Names defined here by something other than a constant (classes, functions, routers, pattern lists). */
    val definedNames: Set<String>

    /** Dotted names (as written in this file) that linking will look up in other files. */
    val wantedRefs: List<List<String>>

    /** Expressions whose names linking will evaluate. */
    val wantedExprs: List<Expr>

    /** Absolute module names mentioned as strings, such as `include("shop.urls")`. */
    val wantedModules: List<String>

    /** Classes that look like a settings holder (`class Settings(BaseSettings)`), with their attributes; see [SettingsClassFact]. */
    val settingClasses: List<SettingsClassFact> get() = emptyList()

    /** Module-level `settings = Settings()` / `settings = get_settings()` and settings factory functions. */
    val settingInstances: List<SettingsInstanceFact> get() = emptyList()
}

/** One file as the pure linker sees it: where it is, how it is named as a module, and its context-free facts. */
class ModuleEntry(val path: String, val module: String, val isPackage: Boolean, val source: ModuleSource) {
    private val constsByName: Map<String, List<ConstFact>> = source.consts.groupBy { it.name }

    /** Constants of [name] in file order (a second entry makes the name ambiguous). */
    fun constsNamed(name: String): List<ConstFact> = constsByName[name].orEmpty()

    /** The first `from x import name [as local]` binding of [local]. */
    fun fromImportOf(local: String): ImportBinding? = source.imports.firstOrNull { it.name != null && it.local == local }
}

/** Module naming from paths and relative-import arithmetic; pure so it is the same in tests and in the IDE. */
object ModuleNames {
    private const val INIT = "__init__"
    private const val PY_SUFFIX = ".py"

    /** `app/routers/items.py` under source root `app` is `routers.items`; `pkg/__init__.py` is `pkg`. */
    fun moduleOf(path: String, sourceRoot: String): String {
        val relative = if (sourceRoot.isNotEmpty() && path.startsWith("$sourceRoot/")) path.substring(sourceRoot.length + 1) else path
        val parts = relative.removeSuffix(PY_SUFFIX).split('/').filter { it.isNotEmpty() }
        return (if (parts.lastOrNull() == INIT) parts.dropLast(1) else parts).joinToString(".")
    }

    fun isPackage(path: String): Boolean = path.substringAfterLast('/') == "$INIT$PY_SUFFIX"

    /** The absolute dotted name of `from <dots><written> import ...` written in module [module]. */
    fun absolute(module: String, isPackage: Boolean, level: Int, written: String): String {
        if (level <= 0) return written
        val segments = module.split('.').filter { it.isNotEmpty() }
        val packageSegments = if (isPackage) segments else segments.dropLast(1)
        val base = packageSegments.dropLast(level - 1).joinToString(".")
        return listOf(base, written).filter { it.isNotEmpty() }.joinToString(".")
    }
}

/**
 * Modules by dotted name. A name is matched exactly first, then by suffix, because facts know a module relative to its own
 * source root while an import may be written relative to another one (`app.routers.items` against `src/app/routers/items.py`).
 */
class ModuleTable(entries: Collection<ModuleEntry>) {
    val entries: List<ModuleEntry> = entries.sortedBy { it.path }
    private val byModule = HashMap<String, ModuleEntry>()
    private val bySuffix = HashMap<String, MutableList<ModuleEntry>>()

    init {
        for (entry in this.entries) {
            byModule.putIfAbsent(entry.module, entry)
            val segments = entry.module.split('.')
            for (i in 1 until segments.size) bySuffix.getOrPut(segments.drop(i).joinToString(".")) { ArrayList() }.add(entry)
        }
    }

    fun find(name: String): ModuleEntry? {
        if (name.isEmpty()) return null
        byModule[name]?.let { return it }
        return bySuffix[name]?.minByOrNull { it.module.length }
    }

    /** The module that `from <dots><written> import` in [from] names; [extra] is appended (`from p import module`). */
    fun resolve(from: ModuleEntry, level: Int, written: String, extra: String? = null): ModuleEntry? =
        find(absoluteName(from, level, written, extra))

    fun absoluteName(from: ModuleEntry, level: Int, written: String, extra: String? = null): String {
        val joined = listOf(written, extra.orEmpty()).filter { it.isNotEmpty() }.joinToString(".")
        return ModuleNames.absolute(from.module, from.isPackage, level, joined)
    }
}
