package com.routegenie.frameworks.django

import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.core.ModuleEntry
import com.routegenie.discovery.core.ModuleNames
import com.routegenie.discovery.core.ModuleTable
import com.routegenie.discovery.core.ModuleWalk

/**
 * One Django file as the linker sees it: the context-free [facts] placed at [path], with the imports resolved to absolute
 * names. Built fresh for every link, so moving a file changes only this wrapper, never the cached facts.
 */
internal class DjangoFile(val path: String, val facts: DjangoFacts, val entry: ModuleEntry) {
    val module: String get() = entry.module
    val rootUrlconf get() = facts.rootUrlconf
    val appName get() = facts.appName
    val patternLists get() = facts.patternLists
    val routers get() = facts.routers
    val registrations get() = facts.registrations
    val functions get() = facts.functions
    val classes get() = facts.classes

    /** Local name to the absolute dotted thing it stands for (`views` to `shop.views`, `Detail` to `shop.views.Detail`). */
    val imports: Map<String, String> by lazy(LazyThreadSafetyMode.NONE) {
        val map = LinkedHashMap<String, String>()
        for (binding in facts.imports) {
            val dotted = binding.dotted
            if (dotted != null) {
                map[binding.local] = if (binding.aliased) dotted else binding.local
            } else {
                val source = ModuleNames.absolute(module, entry.isPackage, binding.level, binding.module)
                val name = binding.name.orEmpty()
                map[binding.local] = if (source.isEmpty()) name else "$source.$name"
            }
        }
        map
    }
}

/** Places Django facts on their paths: modules are named from the project-relative path alone. */
internal object DjangoResolver {
    fun files(facts: Collection<FileFacts>): List<DjangoFile> =
        facts.filterIsInstance<LocatedFacts>().mapNotNull { located ->
            val source = located.source as? DjangoFacts ?: return@mapNotNull null
            DjangoFile(located.path, source, ModuleEntry(located.path, DjangoModules.moduleOf(located.path), ModuleNames.isPackage(located.path), source))
        }.sortedBy { it.path }

    fun missingModules(facts: Collection<FileFacts>, frameworkRoots: Set<String>): Set<String> {
        val files = files(facts)
        return if (files.isEmpty()) emptySet() else ModuleWalk.missing(ModuleTable(files.map { it.entry }), frameworkRoots)
    }
}
