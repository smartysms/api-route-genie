package com.routegenie.frameworks.common

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.core.ConstFact
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.ModuleSource
import com.routegenie.discovery.core.StarBinding
import com.routegenie.discovery.core.nodeCount
import com.routegenie.model.StrValue

/**
 * The PSI-free facts both Python route providers (FastAPI, Flask) deal with, in two stages.
 *
 * [PyFileFacts] is what extraction produces for one file: only what the file's own text says (names, unevaluated
 * [Expr] trees, its import table as written). It does not depend on any other file or on where the file is.
 * [PyRouteFacts] is what the linkers read: the same facts after the pure resolver evaluated the expressions, absolutized
 * the imports and gave the file its module name. Whether a call such as `APIRouter(...)` really is the framework's
 * class is decided by the linker from the imports; the extractor only pre-filters by the last name segment.
 */

/** A name bound by an import. In [PyRouteFacts] [module] is absolute and [ImportBinding.level] is 0. */
typealias ImportFact = ImportBinding

/** A dotted name as written, e.g. `items.router` is `["items", "router"]`. */
data class RefFact(val parts: List<String>) {
    val head: String get() = parts.first()
    val last: String get() = parts.last()
    val qualifier: RefFact? get() = if (parts.size > 1) RefFact(parts.dropLast(1)) else null
    override fun toString(): String = parts.joinToString(".")
}

/**
 * An argument of a call, reduced to what the linkers read. Only the parts that apply to the expression are set.
 * A [PyFileFacts] argument carries [expr] / [exprs] (unevaluated); the resolver replaces them by [str] / [list].
 */
data class ArgFact(
    val ref: RefFact? = null,
    val str: StrValue? = null,
    val list: List<StrValue>? = null,
    val bool: Boolean? = null,
    /** Set for `Cls.as_view("n")` style arguments; nested calls inside it are not followed. */
    val call: CallFact? = null,
    val expr: Expr? = null,
    val exprs: List<Expr>? = null,
)

data class CallFact(
    val callee: RefFact?,
    val args: List<ArgFact>,
    val keywords: Map<String, ArgFact>,
    /** True when `*args` or `**kwargs` is passed: positions and names are then not trustworthy. */
    val dynamicArgs: Boolean = false,
) {
    /** Keyword argument [name], else the positional argument at [index]. */
    fun arg(name: String, index: Int): ArgFact? = keywords[name] ?: args.getOrNull(index)
}

data class SourcePos(val offset: Int, val line: Int)

/** `name = Callee(...)` in [scope] ("" for module level, else the enclosing functions and classes joined by dots). */
data class AssignFact(val name: String, val scope: String, val call: CallFact, val pos: SourcePos)

/** `@receiver.method(...)` on [handler] (class-qualified for methods). */
data class DecoratorFact(
    val scope: String,
    val receiver: RefFact,
    val method: String,
    val call: CallFact,
    val handler: String,
    val pos: SourcePos,
)

/** `receiver.method(...)` as a statement: include_router, register_blueprint, add_url_rule and so on. */
data class CallStmtFact(
    val scope: String,
    val receiver: RefFact,
    val method: String,
    val call: CallFact,
    val pos: SourcePos,
)

data class ClassFact(
    val name: String,
    val scope: String,
    val bases: List<RefFact>,
    val methods: List<String>,
    /** Value of a class-level `methods = [...]`, or null when the class does not define one. Set after resolving. */
    val methodsAttr: List<StrValue>?,
    val pos: SourcePos,
    /** The unevaluated elements of `methods = [...]` in a [PyFileFacts]; null when absent. */
    val methodsExprs: List<Expr>? = null,
)

/** What the extractor read from one file; see the file comment. */
data class PyFileFacts(
    /** Which provider produced the facts; a linker ignores facts of other providers. */
    val providerId: String,
    override val imports: List<ImportBinding>,
    override val stars: List<StarBinding>,
    override val consts: List<ConstFact>,
    val assignments: List<AssignFact>,
    val decorators: List<DecoratorFact>,
    val calls: List<CallStmtFact>,
    val classes: List<ClassFact>,
) : ModuleSource {
    val isEmpty: Boolean
        get() = assignments.isEmpty() && decorators.isEmpty() && calls.isEmpty() && classes.isEmpty()

    override val weight: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val nodes = imports.size + stars.size + consts.sumOf { 1 + (it.value?.nodeCount() ?: 0) } +
            assignments.sumOf { 1 + it.call.nodeCount() } + decorators.sumOf { 2 + it.call.nodeCount() } +
            calls.sumOf { 2 + it.call.nodeCount() } + classes.sumOf { 2 + it.bases.size + it.methods.size + (it.methodsExprs?.sumOf { e -> e.nodeCount() } ?: 0) }
        Limits.FACT_ENTRY_BYTES + nodes * Limits.FACT_NODE_BYTES
    }

    override val definedNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val names = HashSet<String>()
        assignments.filter { it.scope.isEmpty() }.mapTo(names) { it.name }
        classes.filter { it.scope.isEmpty() }.mapTo(names) { it.name }
        names
    }

    override val wantedRefs: List<List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val refs = LinkedHashSet<List<String>>()
        fun call(c: CallFact) {
            c.callee?.let { refs += it.parts }
            (c.args + c.keywords.values).forEach { arg ->
                arg.ref?.let { refs += it.parts }
                arg.call?.let(::call)
            }
        }
        assignments.forEach { call(it.call) }
        decorators.forEach { refs += it.receiver.parts; call(it.call) }
        calls.forEach { refs += it.receiver.parts; call(it.call) }
        classes.forEach { c -> c.bases.forEach { refs += it.parts } }
        refs.toList()
    }

    override val wantedExprs: List<Expr> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = ArrayList<Expr>()
        fun call(c: CallFact) {
            (c.args + c.keywords.values).forEach { arg ->
                arg.expr?.let { out += it }
                arg.exprs?.let { out += it }
                arg.call?.let(::call)
            }
        }
        assignments.forEach { call(it.call) }
        decorators.forEach { call(it.call) }
        calls.forEach { call(it.call) }
        classes.forEach { c -> c.methodsExprs?.let { out += it } }
        out
    }

    override val wantedModules: List<String> get() = emptyList()
}

private fun CallFact.nodeCount(): Int =
    1 + (args + keywords.values).sumOf { it.nodeCount() }

private fun ArgFact.nodeCount(): Int =
    1 + (ref?.parts?.size ?: 0) + (expr?.nodeCount() ?: 0) + (exprs?.sumOf { it.nodeCount() } ?: 0) + (call?.nodeCount() ?: 0)

/** What the linkers read; see the file comment. */
data class PyRouteFacts(
    override val path: String,
    /** Which provider produced the facts; a linker ignores facts of other providers. */
    val providerId: String,
    /** Dotted module name relative to the source root, e.g. `app.routers.items`. */
    val module: String,
    val isPackage: Boolean,
    val imports: List<ImportFact>,
    val starModules: List<String>,
    val assignments: List<AssignFact>,
    val decorators: List<DecoratorFact>,
    val calls: List<CallStmtFact>,
    val classes: List<ClassFact>,
) : FileFacts
