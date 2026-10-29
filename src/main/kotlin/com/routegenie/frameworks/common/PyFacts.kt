package com.routegenie.frameworks.common

import com.routegenie.discovery.FileFacts
import com.routegenie.model.StrValue

/**
 * The PSI-free facts both Python route providers (FastAPI, Flask) extract from one file. Everything is a string, a number
 * or a small value object, so the facts can be cached per file, compared by value and linked on any thread.
 * Whether a call such as `APIRouter(...)` really is the framework's class is decided later by the linker from
 * [imports]; the extractor only pre-filters by the last name segment, which keeps extraction to one cheap pass.
 */

/** A name bound by an import. [module] is absolute; [name] is null for `import a.b` and `import a.b as c`. */
data class ImportFact(val local: String, val module: String, val name: String?)

/** A dotted name as written, e.g. `items.router` is `["items", "router"]`. */
data class RefFact(val parts: List<String>) {
    val head: String get() = parts.first()
    val last: String get() = parts.last()
    val qualifier: RefFact? get() = if (parts.size > 1) RefFact(parts.dropLast(1)) else null
    override fun toString(): String = parts.joinToString(".")
}

/** An argument of a call, reduced to what the linkers read. Only the parts that apply to the expression are set. */
data class ArgFact(
    val ref: RefFact? = null,
    val str: StrValue? = null,
    val list: List<StrValue>? = null,
    val bool: Boolean? = null,
    /** Set for `Cls.as_view("n")` style arguments; nested calls inside it are not followed. */
    val call: CallFact? = null,
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
    /** Value of a class-level `methods = [...]`, or null when the class does not define one. */
    val methodsAttr: List<StrValue>?,
    val pos: SourcePos,
)

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
) : FileFacts {
    val isEmpty: Boolean
        get() = assignments.isEmpty() && decorators.isEmpty() && calls.isEmpty() && classes.isEmpty()
}
