package com.routegenie.frameworks.common

import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.core.ExprEvaluator
import com.routegenie.discovery.core.ModuleEntry
import com.routegenie.discovery.core.ModuleNames
import com.routegenie.discovery.core.ModuleTable
import com.routegenie.discovery.core.ModuleWalk

/**
 * The first stage of both Python linkers, and the only place where facts meet other files: it names every file as a
 * module, evaluates constants and imported names across files, and turns the context-free [PyFileFacts] into the
 * [PyRouteFacts] the linkers read. Pure (no IDE classes), so it is unit-testable and cheap to rerun after any change.
 */
object PyResolver {
    /** Facts of [providerId] in [files]: located context-free facts are resolved, already resolved facts pass through. */
    fun resolve(files: Collection<FileFacts>, providerId: String): List<PyRouteFacts> {
        val direct = files.filterIsInstance<PyRouteFacts>().filter { it.providerId == providerId }
        val entries = entries(files, providerId)
        if (entries.isEmpty()) return direct
        val evaluator = ExprEvaluator(ModuleTable(entries))
        return direct + entries.map { FileResolver(it, evaluator).resolve() }
    }

    /** The modules that resolving [files] would still need to read, see [ModuleWalk]. */
    fun missingModules(files: Collection<FileFacts>, providerId: String, frameworkRoots: Set<String>): Set<String> {
        val entries = entries(files, providerId)
        return if (entries.isEmpty()) emptySet() else ModuleWalk.missing(ModuleTable(entries), frameworkRoots)
    }

    private fun entries(files: Collection<FileFacts>, providerId: String): List<ModuleEntry> =
        files.filterIsInstance<LocatedFacts>().mapNotNull { located ->
            val source = (located.source as? PyFileFacts)?.takeIf { it.providerId == providerId } ?: return@mapNotNull null
            ModuleEntry(located.path, ModuleNames.moduleOf(located.path, located.sourceRoot), ModuleNames.isPackage(located.path), source)
        }

    private class FileResolver(private val entry: ModuleEntry, private val evaluator: ExprEvaluator) {
        fun arg(a: ArgFact): ArgFact = a.copy(
            str = a.expr?.let { evaluator.evaluate(entry, it) },
            list = a.exprs?.map { evaluator.evaluate(entry, it) },
            call = a.call?.let(::call),
            expr = null,
            exprs = null,
        )

        fun call(c: CallFact): CallFact = c.copy(args = c.args.map(::arg), keywords = c.keywords.mapValues { arg(it.value) })

        fun resolve(): PyRouteFacts {
            val source = entry.source as PyFileFacts
            return PyRouteFacts(
                path = entry.path,
                providerId = source.providerId,
                module = entry.module,
                isPackage = entry.isPackage,
                imports = source.imports.map {
                    it.copy(module = ModuleNames.absolute(entry.module, entry.isPackage, it.level, it.module), level = 0)
                },
                starModules = source.stars.map { ModuleNames.absolute(entry.module, entry.isPackage, it.level, it.module) },
                assignments = source.assignments.map { it.copy(call = call(it.call)) },
                decorators = source.decorators.map { it.copy(call = call(it.call)) },
                calls = source.calls.map { it.copy(call = call(it.call)) },
                classes = source.classes.map { c ->
                    c.copy(methodsAttr = c.methodsExprs?.map { evaluator.evaluate(entry, it) }, methodsExprs = null)
                },
            )
        }
    }
}
