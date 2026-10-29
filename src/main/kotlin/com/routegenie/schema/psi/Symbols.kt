package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyImportStatement
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStatement
import com.jetbrains.python.psi.PyStatementListContainer
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyTypeAliasStatement
import com.routegenie.Limits

/** What a name stands for. Library things are only known by their qualified name; project things by their PSI. */
internal sealed interface Sym {
    class Cls(val cls: PyClass) : Sym
    class Fn(val fn: PyFunction) : Sym

    /** A module-level `Name = expression` (alias, constant or instance). */
    class Value(val expr: PyExpression) : Sym

    /** A PEP 695 `type Name = expression`. */
    class TypeAlias(val expr: PyExpression) : Sym

    class Module(val file: PyFile) : Sym

    /** Defined outside the project, e.g. `pydantic.BaseModel`. */
    class Lib(val name: String) : Sym

    data object Unknown : Sym
}

/** One import binding: `from module import name` (name set) or `import module` (name null). */
internal data class Imp(val module: String, val level: Int, val name: String?)

/** The top-level names of one file: its own definitions and its imports. Built once per extraction call. */
internal class FileSyms(file: PyFile) {
    val classes = HashMap<String, PyClass>()
    val functions = HashMap<String, PyFunction>()
    val values = HashMap<String, PyExpression>()
    val typeAliases = HashMap<String, PyExpression>()
    val imports = HashMap<String, Imp>()
    val stars = ArrayList<Imp>()

    init {
        collect(file.statements, 0)
    }

    private fun collect(statements: List<PyStatement>, depth: Int) {
        for (statement in statements) {
            when (statement) {
                is PyClass -> statement.name?.let { classes.putIfAbsent(it, statement) }
                is PyFunction -> statement.name?.let { functions.putIfAbsent(it, statement) }
                is PyAssignmentStatement -> {
                    val target = statement.targets.singleOrNull() as? PyTargetExpression
                    val name = target?.name
                    val value = statement.assignedValue
                    if (name != null && value != null) values.putIfAbsent(name, value)
                }
                is PyTypeAliasStatement -> {
                    val name = statement.name
                    val expression = statement.typeExpression
                    if (name != null && expression != null) typeAliases.putIfAbsent(name, expression)
                }
                is PyImportStatement -> for (element in statement.importElements) {
                    val qualified = element.importedQName?.toString() ?: continue
                    val local = element.asName ?: qualified.substringBefore('.')
                    // `import a.b.c` binds `a`; the rest of the chain is resolved against it.
                    imports.putIfAbsent(local, Imp(if (element.asName != null) qualified else qualified.substringBefore('.'), 0, null))
                }
                is PyFromImportStatement -> {
                    val module = statement.importSourceQName?.toString().orEmpty()
                    val level = statement.relativeLevel
                    if (statement.isStarImport) {
                        stars += Imp(module, level, null)
                    } else {
                        for (element in statement.importElements) {
                            val name = element.importedQName?.toString() ?: continue
                            imports.putIfAbsent(element.asName ?: name.substringAfterLast('.'), Imp(module, level, name))
                        }
                    }
                }
                is PyIfStatement -> if (depth < Limits.SCHEMA_MAX_NESTING) {
                    containers(statement.ifPart, *statement.elifParts, statement.elsePart).forEach { collect(it.statementList.statements.toList(), depth + 1) }
                }
                is PyTryExceptStatement -> if (depth < Limits.SCHEMA_MAX_NESTING) {
                    containers(statement.tryPart, *statement.exceptParts).forEach { collect(it.statementList.statements.toList(), depth + 1) }
                }
                else -> Unit
            }
        }
    }

    private fun containers(vararg parts: PyStatementListContainer?): List<PyStatementListContainer> = parts.filterNotNull()
}

/** Name resolution over the project's own files, by import text. Library names stay qualified strings; nothing is resolved by the SDK. */
internal class Symbols(private val session: Session) {
    /** The symbol that a name or dotted attribute chain stands for in the file where it is written. */
    fun resolve(expression: PyExpression?, inFile: PyFile? = null): Sym {
        val e = PyLiterals.unparen(expression) as? PyReferenceExpression ?: return Sym.Unknown
        val file = inFile ?: e.containingFile as? PyFile ?: return Sym.Unknown
        val parts = chain(e) ?: return Sym.Unknown
        return resolveChain(file, parts, 0)
    }

    /** A top-level name of [file], following imports, aliases and star imports. */
    fun lookup(file: PyFile, name: String, depth: Int = 0): Sym {
        if (depth > Limits.SCHEMA_RESOLVE_DEPTH) return Sym.Unknown
        session.check()
        val table = session.table(file)
        table.classes[name]?.let { return Sym.Cls(it) }
        table.functions[name]?.let { return Sym.Fn(it) }
        table.typeAliases[name]?.let { return Sym.TypeAlias(it) }
        table.values[name]?.let { value -> return aliasOrValue(value, depth) }
        table.imports[name]?.let { return followImport(file, it, depth) }
        for (star in table.stars) {
            val target = session.moduleFile(file, star.module, star.level)
            // A star import from a library cannot be followed: which names it brings is not known.
            if (target != null && target !== file) {
                val found = lookup(target, name, depth + 1)
                if (found !is Sym.Unknown) return found
            }
        }
        return if (name in PythonBuiltins.NAMES) Sym.Lib("${PythonBuiltins.MODULE}.$name") else Sym.Unknown
    }

    /** `Alias = other.Name` resolves through to what it names; any other value stays a [Sym.Value]. */
    private fun aliasOrValue(value: PyExpression, depth: Int): Sym {
        val reference = PyLiterals.unparen(value) as? PyReferenceExpression ?: return Sym.Value(value)
        val file = reference.containingFile as? PyFile ?: return Sym.Value(value)
        val parts = chain(reference) ?: return Sym.Value(value)
        val target = resolveChain(file, parts, depth + 1)
        return if (target is Sym.Unknown) Sym.Value(value) else target
    }

    private fun followImport(file: PyFile, imp: Imp, depth: Int): Sym {
        if (imp.name == null) {
            val target = session.moduleFile(file, imp.module, imp.level)
            return if (target != null) Sym.Module(target) else Sym.Lib(imp.module)
        }
        val moduleFile = session.moduleFile(file, imp.module, imp.level)
        if (moduleFile != null) {
            if (moduleFile !== file) {
                val inner = lookup(moduleFile, imp.name, depth + 1)
                if (inner !is Sym.Unknown) return inner
            }
            // `from package import submodule`
            val sub = session.moduleFile(file, joinModule(imp.module, imp.name), imp.level)
            return if (sub != null) Sym.Module(sub) else Sym.Unknown
        }
        val sub = session.moduleFile(file, joinModule(imp.module, imp.name), imp.level)
        if (sub != null) return Sym.Module(sub)
        return if (imp.level == 0) Sym.Lib(joinModule(imp.module, imp.name)) else Sym.Unknown
    }

    private fun resolveChain(file: PyFile, parts: List<String>, depth: Int): Sym {
        if (depth > Limits.SCHEMA_RESOLVE_DEPTH) return Sym.Unknown
        var current = lookup(file, parts.first(), depth)
        for (part in parts.drop(1)) {
            current = when (val c = current) {
                is Sym.Lib -> Sym.Lib("${c.name}.$part")
                is Sym.Module -> {
                    val inner = lookup(c.file, part, depth + 1)
                    if (inner is Sym.Unknown) session.moduleFile(c.file, part, 1)?.let { Sym.Module(it) } ?: Sym.Unknown else inner
                }
                is Sym.Cls -> c.cls.findNestedClass(part, false)?.let { Sym.Cls(it) } ?: Sym.Unknown
                is Sym.Value -> {
                    // `Alias.Member` where Alias = pkg.module: resolve the alias first.
                    val reference = PyLiterals.unparen(c.expr) as? PyReferenceExpression
                    val chain = reference?.let { chain(it) }
                    val f = reference?.containingFile as? PyFile
                    if (chain == null || f == null) Sym.Unknown else resolveChain(f, chain + part, depth + 1)
                }
                else -> Sym.Unknown
            }
            if (current is Sym.Unknown) return current
        }
        return current
    }

    /** The qualified library name behind [expression], or null when it is a project symbol or unknown. */
    fun libName(expression: PyExpression?, inFile: PyFile? = null): String? = (resolve(expression, inFile) as? Sym.Lib)?.name

    /** `a.b.c` as parts; null when the chain contains anything but plain names. */
    fun chain(e: PyReferenceExpression): List<String>? {
        val parts = ArrayList<String>(3)
        var current: PyExpression? = e
        while (current is PyReferenceExpression) {
            parts += current.referencedName ?: return null
            current = current.qualifier
        }
        if (current != null) return null
        parts.reverse()
        return parts
    }

    private fun joinModule(module: String, name: String) = if (module.isEmpty()) name else "$module.$name"
}

/** Python's builtin type names; the only names that resolve without a definition or an import. */
internal object PythonBuiltins {
    const val MODULE = "builtins"
    val NAMES: Set<String> = setOf(
        "str", "int", "float", "bool", "bytes", "bytearray", "object", "list", "dict", "set", "frozenset", "tuple", "complex", "type", "None",
    )
}
