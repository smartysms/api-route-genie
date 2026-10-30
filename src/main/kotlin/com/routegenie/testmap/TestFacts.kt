package com.routegenie.testmap

import com.routegenie.Limits
import com.routegenie.discovery.core.ConstFact
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.ModuleSource
import com.routegenie.discovery.core.StarBinding
import com.routegenie.discovery.core.nodeCount
import com.routegenie.frameworks.common.RefFact
import com.routegenie.frameworks.common.SourcePos

/**
 * The PSI-free, context-free facts of one test file. Like the route facts they are a function of the text of that one file:
 * names, positions, unevaluated [Expr] trees and the import table as written. Whether `client` really is an HTTP client,
 * whether a class is a test case, and which endpoint a URL belongs to is decided later by the pure [TestLinker].
 */

/** How a call wrote its URL argument. */
sealed interface UrlFact {
    /** A string expression: literal, f-string, `+`, `%`, `.format`, or an unknown (Opaque) tree. */
    data class Text(val expr: Expr) : UrlFact

    /** A plain name or attribute chain: a local variable, `self.url`, or a module constant. Resolved by the linker. */
    data class Name(val ref: RefFact) : UrlFact

    /**
     * `reverse("app:name", args=..., kwargs=...)`, `url_for("endpoint", id=1)`, `app.url_path_for("name", id=1)`.
     * [function] is the callee as written; [positional] counts the arguments after the name (`args=[...]` for reverse, -1 when
     * it is not a literal list); [keys] are the literal argument names (`kwargs={...}` keys for reverse), null when unknown;
     * [suffix] is a string appended to the result (`reverse(...) + "?a=1"`), null when there is none.
     */
    data class Named(
        val function: RefFact,
        val name: Expr,
        val positional: Int,
        val keys: List<String>?,
        val suffix: Expr?,
    ) : UrlFact

    /** Anything else: a call of a helper, a subscript, an expression the extractor does not read. */
    data object Dynamic : UrlFact
}

/** A literal argument value (for the request a test makes); only what the code spells out, never a guess. */
sealed interface LitValue {
    /** A string-ish expression, evaluated by the linker. */
    data class Str(val expr: Expr) : LitValue
    data class Num(val text: String) : LitValue
    data class Bool(val value: Boolean) : LitValue
    data object None : LitValue
    data class Dict(val entries: List<Pair<LitValue, LitValue>>) : LitValue
    data class Seq(val items: List<LitValue>) : LitValue

    /** Not a literal (a variable, a call, a comprehension, or too big). */
    data object Unknown : LitValue
}

fun LitValue.nodeCount(): Int = when (this) {
    is LitValue.Str -> 1 + expr.nodeCount()
    is LitValue.Num, is LitValue.Bool, LitValue.None, LitValue.Unknown -> 1
    is LitValue.Dict -> 1 + entries.sumOf { it.first.nodeCount() + it.second.nodeCount() }
    is LitValue.Seq -> 1 + items.sumOf { it.nodeCount() }
}

/** A keyword (or the second positional, name "") of a client call that carries a part of the request. */
data class NamedLit(val name: String, val value: LitValue)

/**
 * `name = Ctor(...)`, `with Ctor(...) as name`, `name = app.test_client()`. [scope] is the qualified name of the function it
 * is in, the qualified name of the class for class attributes and setup methods (then [name] is `self.x`), "" at module level.
 */
data class ClientBindingFact(
    val name: String,
    val scope: String,
    val ctor: RefFact,
    val keywords: Set<String>,
    val keywordCallees: Map<String, RefFact>,
    val baseUrl: Expr?,
    val pos: SourcePos,
)

/**
 * `receiver.method(url, ...)` where method is one of the call names of the sink table. [receiver] is a name chain, or null
 * when the receiver is a call ([inlineCtor] is that call's callee, `TestClient(app).get(...)`). [scope] is the outermost
 * function the call is in ("" at module level). [assignedTo] is the variable the response goes to.
 */
data class ClientCallFact(
    val receiver: RefFact?,
    val inlineCtor: RefFact?,
    val method: String,
    val url: UrlFact?,
    val verb: Expr?,
    val scope: String,
    val assignedTo: String?,
    val parts: List<NamedLit>,
    val pos: SourcePos,
)

/** `assert r.status_code == 201` or `assertEqual(r.status_code, 404)` bound to the call that starts at [callOffset]. */
data class StatusAssertFact(val callOffset: Int, val code: Int, val scope: String, val pos: SourcePos)

/** `name = <url>` in [scope] (function, or class for `self.x`/class attributes); [branch] when inside if/for/while/try. */
data class LocalAssignFact(val name: String, val scope: String, val url: UrlFact, val branch: Boolean, val pos: SourcePos)

data class TestClassFact(
    val name: String,
    /** Qualified with the enclosing classes: `Outer.Inner`. */
    val qualified: String,
    val bases: List<RefFact>,
    val hasInit: Boolean,
    val mark: TestMark,
    val pos: SourcePos,
)

data class TestFunctionFact(
    val name: String,
    /** Qualified name of the class it is a method of, or null. */
    val className: String?,
    val qualified: String,
    val params: List<String>,
    val isAsync: Boolean,
    val isFixture: Boolean,
    val mark: TestMark,
    /** Names returned or yielded; `TestClient(app)` returned directly is in [returnedCtors]. */
    val returned: List<String>,
    val returnedCtors: List<RefFact>,
    val pos: SourcePos,
)

/** What the extractor read from one test file; see the file comment. */
data class TestFileFacts(
    override val imports: List<ImportBinding>,
    override val stars: List<StarBinding>,
    override val consts: List<ConstFact>,
    val classes: List<TestClassFact>,
    val functions: List<TestFunctionFact>,
    val bindings: List<ClientBindingFact>,
    val calls: List<ClientCallFact>,
    val asserts: List<StatusAssertFact>,
    val assigns: List<LocalAssignFact>,
    /** Functions whose name looks like a test (cheap count for the empty-state texts; not every one is kept above). */
    val testLikeFunctions: Int,
) : ModuleSource {
    val isEmpty: Boolean
        get() = classes.isEmpty() && functions.isEmpty() && bindings.isEmpty() && calls.isEmpty()

    override val weight: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var nodes = imports.size + stars.size + consts.sumOf { 1 + (it.value?.nodeCount() ?: 0) } + classes.size * CLASS_NODES +
            functions.sumOf { FUNCTION_NODES + it.params.size + it.returned.size }
        nodes += bindings.size * BINDING_NODES + asserts.size + assigns.sumOf { 1 + it.url.nodeCount() }
        nodes += calls.sumOf { CALL_NODES + (it.url?.nodeCount() ?: 0) + it.parts.sumOf { p -> 1 + p.value.nodeCount() } }
        Limits.FACT_ENTRY_BYTES + nodes * Limits.FACT_NODE_BYTES
    }

    override val definedNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val names = HashSet<String>()
        classes.filter { '.' !in it.qualified }.mapTo(names) { it.name }
        functions.filter { it.className == null }.mapTo(names) { it.name }
        names
    }

    override val wantedRefs: List<List<String>> get() = emptyList()
    override val wantedExprs: List<Expr> get() = emptyList()
    override val wantedModules: List<String> get() = emptyList()

    private companion object {
        const val CLASS_NODES = 3
        const val FUNCTION_NODES = 4
        const val BINDING_NODES = 4
        const val CALL_NODES = 5
    }
}

private fun UrlFact.nodeCount(): Int = when (this) {
    is UrlFact.Text -> expr.nodeCount()
    is UrlFact.Name -> ref.parts.size
    is UrlFact.Named -> 1 + name.nodeCount() + (suffix?.nodeCount() ?: 0) + (keys?.size ?: 0)
    UrlFact.Dynamic -> 1
}
