package com.routegenie.discovery.core

import com.routegenie.Limits

/**
 * Finds the modules that linking will need but the file set does not contain yet. Facts are context-free, so a route file
 * that uses `API_PREFIX` from `config.py` does not say what `config.py` holds; the engine asks this walk which modules are
 * missing, extracts them and asks again, until nothing is missing. Only names that linking actually looks up are followed,
 * so importing `models` for a type annotation does not pull `models.py` into the work.
 *
 * Pure: it never touches the IDE; resolving a module name to a file is the engine's job.
 */
object ModuleWalk {
    /**
     * Absolute module names (and, for `from pkg import name`, the candidates `pkg.name` and `pkg`) that are referenced but
     * absent from [table]. [skipRoots] are import roots that are never project files (the frameworks themselves).
     */
    fun missing(table: ModuleTable, skipRoots: Set<String>): Set<String> = Walker(table, skipRoots).run()

    private class Walker(private val table: ModuleTable, private val skipRoots: Set<String>) {
        private val missing = LinkedHashSet<String>()
        private val visited = HashSet<String>()

        fun run(): Set<String> {
            for (entry in table.entries) {
                val source = entry.source
                source.wantedRefs.forEach { walkRef(entry, it, 0) }
                source.wantedExprs.forEach { walkExpr(entry, it, 0) }
                source.wantedModules.forEach { need(it) }
            }
            return missing
        }

        private fun need(name: String): ModuleEntry? {
            if (name.isEmpty()) return null
            table.find(name)?.let { return it }
            val root = name.substringBefore('.')
            if (root !in skipRoots) missing += name
            return null
        }

        private fun needFrom(from: ModuleEntry, level: Int, written: String, extra: String? = null): ModuleEntry? {
            val absolute = table.absoluteName(from, level, written, extra)
            // A relative import that climbs out of the project resolves to an empty or partial name: nothing to find.
            return if (level > 0 && absolute.isEmpty()) null else need(absolute)
        }

        private fun walkExpr(owner: ModuleEntry, expr: Expr, depth: Int) {
            val refs = ArrayList<List<String>>()
            expr.collectRefs(refs)
            refs.forEach { walkRef(owner, it, depth) }
        }

        private fun walkRef(owner: ModuleEntry, parts: List<String>, depth: Int) {
            if (depth > Limits.MAX_VALUE_RESOLVE_DEPTH || parts.isEmpty()) return
            val head = parts.first()
            if (parts.size == 1) {
                walkName(owner, head, depth)
                return
            }
            val binding = owner.fromImportOf(head)
            if (binding != null) {
                // `from p import m` then `m.NAME`: m may be a module; `from p import Name` then `Name.attr` needs Name's file.
                needFrom(owner, binding.level, binding.module, binding.name)?.let { walkName(it, parts.last(), depth + 1) }
                if (parts.size == 2) needFrom(owner, binding.level, binding.module)?.let { walkName(it, binding.name!!, depth + 1) }
                return
            }
            for (plain in owner.source.imports) {
                val dotted = plain.dotted ?: continue
                val qualifier = parts.dropLast(1).joinToString(".")
                val target = when {
                    plain.aliased && plain.local == head && parts.size == 2 -> dotted
                    !plain.aliased && dotted == qualifier -> dotted
                    else -> continue
                }
                need(target)?.let { walkName(it, parts.last(), depth + 1) }
                return
            }
        }

        private fun defines(file: ModuleEntry, name: String): Boolean =
            file.constsNamed(name).isNotEmpty() || name in file.source.definedNames || file.fromImportOf(name) != null

        private fun walkName(file: ModuleEntry, name: String, depth: Int) {
            if (depth > Limits.MAX_VALUE_RESOLVE_DEPTH || !visited.add("${file.path}#$name")) return
            val assigned = file.constsNamed(name)
            if (assigned.isNotEmpty()) {
                assigned.forEach { c -> c.value?.let { walkExpr(file, it, depth + 1) } }
                return
            }
            if (name in file.source.definedNames) return
            val binding = file.fromImportOf(name)
            if (binding != null) {
                val parent = needFrom(file, binding.level, binding.module)
                parent?.let { walkName(it, binding.name!!, depth + 1) }
                // `from pkg import module` binds a module, not a name of pkg; only then is the longer name worth a lookup.
                if (parent == null || !defines(parent, binding.name!!)) needFrom(file, binding.level, binding.module, binding.name)
                return
            }
            for (star in file.source.stars) {
                needFrom(file, star.level, star.module)?.let { walkName(it, name, depth + 1) }
            }
        }
    }
}
