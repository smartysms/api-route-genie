package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PySubscriptionExpression
import com.routegenie.Limits
import java.util.IdentityHashMap

/** The bases of one project class: the project classes it derives from and the library classes behind them. */
internal class ClassInfo(
    /** Direct bases found in the project, in declaration order. */
    val projectBases: List<PyClass>,
    /** Qualified names of every library class reachable through the bases (transitively through project classes). */
    val libraryBases: Set<String>,
    /** True when a base could not be resolved at all (a call, an unknown name); the hierarchy is then incomplete. */
    val hasUnresolvedBase: Boolean,
)

/** Inheritance by import origin: project classes are followed through PSI, library classes are known by their qualified name. */
internal class Hierarchy(private val session: Session) {
    private val infos = IdentityHashMap<PyClass, ClassInfo>()

    fun info(cls: PyClass): ClassInfo {
        infos[cls]?.let { return it }
        // A cycle (a class naming itself as a base through an alias) ends here instead of recursing.
        infos[cls] = EMPTY
        val info = compute(cls, 0)
        infos[cls] = info
        return info
    }

    private fun compute(cls: PyClass, depth: Int): ClassInfo {
        session.check()
        val projectBases = ArrayList<PyClass>()
        val libs = LinkedHashSet<String>()
        var unresolved = false
        val file = cls.containingFile as? PyFile
        if (file != null && depth <= Limits.SCHEMA_RESOLVE_DEPTH) {
            for (expression in cls.superClassExpressions) {
                when (val sym = session.symbols.resolve(baseOf(expression), file)) {
                    is Sym.Cls -> {
                        if (sym.cls === cls) continue
                        projectBases += sym.cls
                        val inner = info(sym.cls)
                        libs += inner.libraryBases
                        unresolved = unresolved || inner.hasUnresolvedBase
                    }
                    is Sym.Lib -> libs += sym.name
                    else -> unresolved = true
                }
            }
        }
        return ClassInfo(projectBases, libs, unresolved)
    }

    /** `Base[T]` names `Base`; anything else is the expression itself. */
    private fun baseOf(expression: PyExpression): PyExpression = (expression as? PySubscriptionExpression)?.operand ?: expression

    /** Project classes of the hierarchy of [cls], most basic first and [cls] last: the order fields are collected in. */
    fun baseFirst(cls: PyClass): List<PyClass> {
        val preorder = ArrayList<PyClass>()
        fun walk(c: PyClass, depth: Int) {
            if (depth > Limits.SCHEMA_RESOLVE_DEPTH || preorder.size > Limits.SCHEMA_MAX_CLASSES_PER_CALL) return
            preorder += c
            for (base in info(c).projectBases) walk(base, depth + 1)
        }
        walk(cls, 0)
        // Python's linearization keeps the last occurrence of a class that is reached twice; base first is its reverse.
        val seen = HashSet<PyClass>()
        val kept = ArrayList<PyClass>()
        for (c in preorder.asReversed()) if (seen.add(c)) kept += c
        return kept
    }

    fun inherits(cls: PyClass, names: Set<String>): Boolean = info(cls).libraryBases.any { it in names }

    /** The method [name] as the class sees it: its own, else the nearest project base's. */
    fun findMethod(cls: PyClass, name: String): PyFunction? {
        for (c in baseFirst(cls).asReversed()) c.findMethodByName(name, false, null)?.let { return it }
        return null
    }

    private companion object {
        val EMPTY = ClassInfo(emptyList(), emptySet(), false)
    }
}
