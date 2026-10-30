package com.routegenie.discovery.core

import com.routegenie.model.SettingSuggestion
import com.routegenie.model.SourceRef
import com.routegenie.model.SuggestionKind

/**
 * Follows `settings.NAME` to the class that defines the settings object, through imports and simple factories
 * (`settings = Settings()`, `settings = get_settings()` with `def get_settings(): return Settings()`), to find the written
 * default of the attribute. Pure; every step is bounded by [MAX_STEPS], and anything not simple gives no answer.
 */
internal class SettingsIndex(private val table: ModuleTable) {
    class ClassRef(val file: ModuleEntry, val fact: SettingsClassFact)

    private class InstanceRef(val file: ModuleEntry, val fact: SettingsInstanceFact)

    /** What `qualifier.attr` is: the class that holds the settings, and the attribute when the class (or a base) declares it. */
    class Found(val cls: ClassRef, val attr: AttrFact?, val attrFile: ModuleEntry?)

    fun find(owner: ModuleEntry, qualifier: List<String>, attr: String): Found? {
        val cls = classOfObject(owner, qualifier) ?: return null
        val (declared, file) = attributeOf(cls, attr, 0) ?: return Found(cls, null, null)
        return Found(cls, declared, file)
    }

    /** The default the code writes for [found], or null: only literal defaults qualify. */
    fun suggestionOf(found: Found): SettingSuggestion? {
        val attr = found.attr ?: return null
        val file = found.attrFile ?: return null
        if (attr.kind != AttrKind.VALUE) return null
        val origin = SourceRef(file.path, attr.offset, attr.line.coerceAtLeast(1))
        return when (val default = attr.default) {
            is Lit -> SettingSuggestion(default.text, SuggestionKind.CLASS_DEFAULT, origin)
            is SettingRead -> (default.default as? Lit)?.let { SettingSuggestion(it.text, SettingsCatalog.defaultKind(default.canonical), origin) }
            else -> null
        }
    }

    // --- objects and classes ---------------------------------------------------------------------------------------

    private fun classOfObject(owner: ModuleEntry, qualifier: List<String>): ClassRef? {
        if (qualifier.isEmpty()) return null
        val name = qualifier.last()
        val file = if (qualifier.size == 1) owner else moduleFor(owner, qualifier.dropLast(1)) ?: return null
        findInstance(file, name, 0)?.let { return classOfInstance(it, 0) }
        // `Settings.NAME` reads the class attribute itself.
        return findClass(file, name, 0)
    }

    private fun classOfInstance(instance: InstanceRef, depth: Int): ClassRef? {
        if (depth >= MAX_STEPS) return null
        val parts = instance.fact.callee.split('.')
        val last = parts.last()
        val file = if (parts.size == 1) instance.file else moduleFor(instance.file, parts.dropLast(1)) ?: return null
        findClass(file, last, 0)?.let { return it }
        // `get_settings()` is a function whose body returns the class.
        val factory = findInstance(file, last, depth + 1) ?: return null
        return if (factory.fact.isFunction) classOfInstance(factory, depth + 1) else null
    }

    private fun findInstance(file: ModuleEntry, name: String, depth: Int): InstanceRef? {
        if (depth >= MAX_STEPS) return null
        file.source.settingInstances.firstOrNull { it.name == name }?.let { return InstanceRef(file, it) }
        file.fromImportOf(name)?.let { binding ->
            val source = table.resolve(file, binding.level, binding.module) ?: return null
            return findInstance(source, binding.name ?: return null, depth + 1)
        }
        for (star in file.source.stars) {
            val source = table.resolve(file, star.level, star.module) ?: continue
            findInstance(source, name, depth + 1)?.let { return it }
        }
        return null
    }

    private fun findClass(file: ModuleEntry, name: String, depth: Int): ClassRef? {
        if (depth >= MAX_STEPS) return null
        file.source.settingClasses.firstOrNull { it.name == name }?.let { return ClassRef(file, it) }
        file.fromImportOf(name)?.let { binding ->
            val source = table.resolve(file, binding.level, binding.module) ?: return null
            return findClass(source, binding.name ?: return null, depth + 1)
        }
        for (star in file.source.stars) {
            val source = table.resolve(file, star.level, star.module) ?: continue
            findClass(source, name, depth + 1)?.let { return it }
        }
        return null
    }

    private fun attributeOf(cls: ClassRef, name: String, depth: Int): Pair<AttrFact, ModuleEntry>? {
        cls.fact.attrs.firstOrNull { it.name == name }?.let { return it to cls.file }
        if (depth >= MAX_STEPS) return null
        for (base in cls.fact.bases) {
            val parent = findClass(cls.file, base.substringAfterLast('.'), 0) ?: continue
            if (parent.fact === cls.fact) continue
            attributeOf(parent, name, depth + 1)?.let { return it }
        }
        return null
    }

    /** The project module a dotted qualifier names, via `from p import module` or `import a.b [as c]`. */
    private fun moduleFor(owner: ModuleEntry, parts: List<String>): ModuleEntry? {
        val head = parts.first()
        val rest = parts.drop(1)
        owner.fromImportOf(head)?.let { binding ->
            val base = table.absoluteName(owner, binding.level, binding.module, binding.name)
            return table.find((listOf(base) + rest).joinToString("."))
        }
        val dotted = parts.joinToString(".")
        for (binding in owner.source.imports) {
            val target = binding.dotted ?: continue
            if (binding.aliased) {
                if (binding.local == head) return table.find((listOf(target) + rest).joinToString("."))
            } else if (target == dotted) {
                return table.find(target)
            }
        }
        return null
    }

    private companion object {
        /** Import and factory chains longer than this are not followed (a settings object is one or two hops away). */
        const val MAX_STEPS = 4
    }
}
