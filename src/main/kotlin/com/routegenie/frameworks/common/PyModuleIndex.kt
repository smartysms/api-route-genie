package com.routegenie.frameworks.common

import java.util.IdentityHashMap

/** A named thing found in a file: an instance assignment or a class. */
data class SymbolRef(val file: PyRouteFacts, val scope: String, val name: String) {
    /** Stable identity for maps and cycle checks; paths are unique per file. */
    val key: String get() = "${file.path}|$scope|$name"
}

/**
 * Pure name resolution over the facts of all files: modules by dotted name, symbols through imports, import origins.
 * Module names are matched by suffix, because the facts know a module only relative to its own source root while an
 * import may be written relative to another one (`app.routers.items` against `src/app/routers/items.py`).
 */
class PyModuleIndex(files: Collection<PyRouteFacts>) {
    private val byModule = HashMap<String, PyRouteFacts>()
    private val bySuffix = HashMap<String, MutableList<PyRouteFacts>>()
    private val assignments = IdentityHashMap<PyRouteFacts, Map<String, AssignFact>>()
    private val classes = IdentityHashMap<PyRouteFacts, Map<String, ClassFact>>()
    private val importsByLocal = IdentityHashMap<PyRouteFacts, Map<String, ImportFact>>()

    init {
        for (file in files.sortedBy { it.path }) {
            byModule.putIfAbsent(file.module, file)
            val segments = file.module.split('.')
            for (i in segments.indices) bySuffix.getOrPut(segments.drop(i).joinToString(".")) { ArrayList() }.add(file)
            assignments[file] = file.assignments.associateByFirst { key(it.scope, it.name) }
            classes[file] = file.classes.associateByFirst { key(it.scope, it.name) }
            importsByLocal[file] = file.imports.associateBy { it.local }
        }
    }

    fun findModule(name: String): PyRouteFacts? {
        byModule[name]?.let { return it }
        return bySuffix[name]?.minByOrNull { it.module.length }
    }

    fun assignment(symbol: SymbolRef): AssignFact? = assignments[symbol.file]?.get(key(symbol.scope, symbol.name))

    fun classOf(symbol: SymbolRef): ClassFact? = classes[symbol.file]?.get(key(symbol.scope, symbol.name))

    /** The symbol that [ref] names when written in [scope] of [from]; null when it is not a project symbol. */
    fun resolveSymbol(from: PyRouteFacts, scope: String, ref: RefFact, depth: Int = 0): SymbolRef? {
        if (depth > MAX_DEPTH) return null
        if (ref.parts.size == 1) return resolveName(from, scope, ref.head, depth)
        val import = importsByLocal[from]?.get(ref.head) ?: return null
        val rest = ref.parts.drop(1)
        val module = if (import.name == null) import.module else "${import.module}.${import.name}"
        val modulePath = (listOf(module) + rest.dropLast(1)).filter { it.isNotEmpty() }.joinToString(".")
        val target = findModule(modulePath) ?: return null
        return resolveName(target, "", rest.last(), depth + 1)
    }

    private fun resolveName(file: PyRouteFacts, scope: String, name: String, depth: Int): SymbolRef? {
        if (depth > MAX_DEPTH) return null
        for (candidate in scopeChain(scope)) {
            val k = key(candidate, name)
            if (assignments[file]?.containsKey(k) == true || classes[file]?.containsKey(k) == true) {
                return SymbolRef(file, candidate, name)
            }
        }
        val import = importsByLocal[file]?.get(name)
        if (import?.name != null) {
            val target = findModule(import.module)
            if (target != null && target !== file) return resolveName(target, "", import.name, depth + 1)
        }
        for (star in file.starModules) {
            val target = findModule(star) ?: continue
            if (target === file) continue
            resolveName(target, "", name, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * The qualified name the written [ref] stands for, from the import table: `FastAPI` after
     * `from fastapi import FastAPI` is `fastapi.FastAPI`. Null for names that are not imported.
     */
    fun originOf(from: PyRouteFacts, ref: RefFact): String? {
        val import = importsByLocal[from]?.get(ref.head) ?: return null
        val tail = ref.parts.drop(1)
        val parts = if (import.name == null) listOf(import.module) + tail else listOf(import.module, import.name) + tail
        return parts.filter { it.isNotEmpty() }.joinToString(".")
    }

    /** The dotted name a handler or view written as [ref] has, for display. Local names live in [from]'s module. */
    fun qualifiedNameOf(from: PyRouteFacts, scope: String, ref: RefFact): String {
        resolveSymbol(from, scope, ref)?.let { return "${it.file.module}.${it.name}" }
        originOf(from, ref)?.let { origin ->
            val module = origin.substringBeforeLast('.', "")
            val target = if (module.isEmpty()) null else findModule(module)
            return if (target != null) "${target.module}.${origin.substringAfterLast('.')}" else origin
        }
        return if (ref.parts.size == 1) "${from.module}.${ref.head}" else ref.toString()
    }

    companion object {
        /** `a.b` -> `a.b`, `a`, `` : innermost scope first. */
        fun scopeChain(scope: String): List<String> {
            val chain = ArrayList<String>()
            var current = scope
            while (current.isNotEmpty()) {
                chain += current
                current = current.substringBeforeLast('.', "")
            }
            chain += ""
            return chain
        }

        private fun key(scope: String, name: String) = "$scope\u0000$name"

        /** Guards against import cycles and re-export chains without end. */
        private const val MAX_DEPTH = 8

        private inline fun <T> List<T>.associateByFirst(keyOf: (T) -> String): Map<String, T> {
            val map = LinkedHashMap<String, T>()
            for (item in this) map.putIfAbsent(keyOf(item), item)
            return map
        }
    }
}
