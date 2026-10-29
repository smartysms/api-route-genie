package com.routegenie.frameworks.django

import com.routegenie.Limits
import com.routegenie.discovery.core.ConstFact
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.Lit
import com.routegenie.discovery.core.ModuleSource
import com.routegenie.discovery.core.StarBinding
import com.routegenie.discovery.core.nodeCount

/*
 * Immutable, PSI-free, context-free summary of what one Python file contributes to Django routing. Only strings, numbers,
 * unevaluated Expr trees and collections of those: it is derived from the text of the file alone, so it stays valid
 * while other files change. The linker evaluates the expressions (constants, imports) once it knows all files.
 */

/** A position inside the file; turned into a SourceRef together with the file path. */
data class Loc(val offset: Int, val line: Int)

enum class RouteKind { PATH, REGEX }

sealed interface ListItem

/** One path(), re_path() or url() call. */
data class RouteItem(
    val kind: RouteKind,
    val route: Expr,
    val target: Target,
    val name: Expr?,
    val loc: Loc,
) : ListItem

/** A reference to another pattern list or to `router.urls`, spliced in place (`urlpatterns += api_patterns`). */
data class SpliceItem(val ref: String, val loc: Loc) : ListItem

/** A list element that is not a recognizable route; reported, never guessed. */
data class OpaqueItem(val text: String, val loc: Loc) : ListItem

sealed interface Target

/**
 * A view. [ref] is the dotted reference as written (`views.Detail`), null when the view is not a plain reference.
 * [actionMap] is the `{"get": "list"}` argument of a viewset's `as_view`, as (key, value) expressions.
 */
data class ViewTarget(val ref: String?, val asView: Boolean, val actionMap: List<Pair<Expr, Expr>>? = null) : Target

data class IncludeTarget(val spec: IncludeSpec, val appName: Expr?, val namespace: Expr?) : Target

sealed interface IncludeSpec

/** `include("shop.urls")`. */
data class ModuleSpec(val name: Expr) : IncludeSpec

/** `include([path(...), ...])`. */
data class InlineSpec(val items: List<ListItem>) : IncludeSpec

/** `include(router.urls)`, `include(other_patterns)`, `include(some_module)`. */
data class RefSpec(val ref: String) : IncludeSpec

data class OpaqueSpec(val text: String) : IncludeSpec

/** One step of how decorators on a function view decide its HTTP methods; the linker replays them in order. */
sealed interface MethodStep

/** A decorator that fixes the methods (`require_GET`): used unless an earlier step set them. */
data class FixedMethods(val verbs: List<String>) : MethodStep

/** A decorator with no method argument that implies a default (`api_view`): replaces what is set. */
data class DefaultMethods(val verbs: List<String>) : MethodStep

/** A method argument that is not a list or tuple of strings: the methods are not known. */
data object DynamicMethods : MethodStep

/** `require_http_methods([..])` / `api_view([..])`: the listed elements, evaluated when linking. */
data class ListedMethods(val elements: List<Expr>) : MethodStep

/** A function view that a decorator restricts or that DRF turns into an API view. No steps means any method. */
data class FunctionFact(
    val name: String,
    val steps: List<MethodStep>,
    val isApiView: Boolean,
    val loc: Loc,
)

/** A viewset method marked with `@action`. */
data class ActionFact(
    val methodName: String,
    val detail: Boolean?,
    /** True when the decorator has a `methods` argument, whatever its shape. */
    val methodsPresent: Boolean,
    /** The elements of the `methods` list or tuple; null when it is absent or not a literal list. */
    val methodExprs: List<Expr>?,
    /** The verbs that apply when [methodExprs] is null. */
    val defaultMethods: List<String>,
    val urlPath: Expr,
    val urlName: Expr,
    val loc: Loc,
)

data class ClassFact(
    val name: String,
    val bases: List<String>,
    val methods: Set<String>,
    /** Elements of a class-level `http_method_names = [...]`; null when absent or not a literal list. */
    val httpMethodExprs: List<Expr>?,
    val actions: List<ActionFact>,
    val queryset: String?,
    val lookupField: Expr?,
    val lookupUrlKwarg: Expr?,
    val loc: Loc,
)

/** `router = DefaultRouter(trailing_slash=False)`. [kind] is the class name as written, last segment only. */
data class RouterDecl(
    val kind: String,
    val trailingSlash: Expr,
    val includeRootView: Boolean,
    val includeFormatSuffixes: Boolean,
    val loc: Loc,
)

/** `router.register("users", UserViewSet, basename="user")`; [router] and [viewset] are references as written. */
data class Registration(
    val router: String,
    val prefix: Expr,
    val viewset: String,
    val basename: Expr?,
    val loc: Loc,
)

data class DjangoFacts(
    override val imports: List<ImportBinding>,
    override val stars: List<StarBinding>,
    override val consts: List<ConstFact>,
    val rootUrlconf: Expr?,
    val appName: Expr?,
    val patternLists: Map<String, List<ListItem>>,
    val routers: Map<String, RouterDecl>,
    val registrations: List<Registration>,
    val functions: Map<String, FunctionFact>,
    val classes: Map<String, ClassFact>,
) : ModuleSource {
    /** True when the file adds nothing the linker could use, so extraction reports "no routes". */
    val isEmpty: Boolean
        get() = rootUrlconf == null && appName == null && patternLists.isEmpty() && routers.isEmpty() &&
            registrations.isEmpty() && functions.isEmpty() && classes.isEmpty()

    override val weight: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val nodes = imports.size + stars.size + consts.sumOf { 1 + (it.value?.nodeCount() ?: 0) } +
            patternLists.values.sumOf { items -> items.sumOf { itemNodes(it) } } + routers.size + registrations.size * 4 +
            functions.size * 3 + classes.values.sumOf { 4 + it.bases.size + it.methods.size + it.actions.size * 6 }
        Limits.FACT_ENTRY_BYTES + nodes * Limits.FACT_NODE_BYTES
    }

    private fun itemNodes(item: ListItem): Int = when (item) {
        is RouteItem -> 4 + item.route.nodeCount() + (item.target as? IncludeTarget)?.let { 3 + inlineNodes(it.spec) }.let { it ?: 1 }
        is SpliceItem, is OpaqueItem -> 2
    }

    private fun inlineNodes(spec: IncludeSpec): Int = if (spec is InlineSpec) spec.items.sumOf { itemNodes(it) } else 1

    override val definedNames: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        patternLists.keys + routers.keys + functions.keys + classes.keys
    }

    override val wantedRefs: List<List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val refs = LinkedHashSet<String>()
        fun items(list: List<ListItem>) {
            for (item in list) when (item) {
                is SpliceItem -> refs += item.ref
                is RouteItem -> when (val target = item.target) {
                    is ViewTarget -> target.ref?.let { refs += it }
                    is IncludeTarget -> when (val spec = target.spec) {
                        is RefSpec -> refs += spec.ref
                        is InlineSpec -> items(spec.items)
                        is ModuleSpec, is OpaqueSpec -> Unit
                    }
                }
                is OpaqueItem -> Unit
            }
        }
        patternLists.values.forEach(::items)
        registrations.forEach { refs += it.router; refs += it.viewset }
        classes.values.forEach { refs += it.bases }
        refs.map { it.split('.') }
    }

    override val wantedExprs: List<Expr> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val out = ArrayList<Expr>()
        fun items(list: List<ListItem>) {
            for (item in list) {
                if (item !is RouteItem) continue
                out += item.route
                item.name?.let { out += it }
                (item.target as? ViewTarget)?.actionMap?.forEach { (k, v) -> out += k; out += v }
                val include = item.target as? IncludeTarget ?: continue
                include.appName?.let { out += it }
                include.namespace?.let { out += it }
                when (val spec = include.spec) {
                    is ModuleSpec -> out += spec.name
                    is InlineSpec -> items(spec.items)
                    is RefSpec, is OpaqueSpec -> Unit
                }
            }
        }
        patternLists.values.forEach(::items)
        rootUrlconf?.let { out += it }
        appName?.let { out += it }
        routers.values.forEach { out += it.trailingSlash }
        registrations.forEach { out += it.prefix; it.basename?.let { b -> out += b } }
        for (c in classes.values) {
            c.httpMethodExprs?.let { out += it }
            c.lookupField?.let { out += it }
            c.lookupUrlKwarg?.let { out += it }
            c.actions.forEach { a -> a.methodExprs?.let { out += it }; out += a.urlPath; out += a.urlName }
        }
        for (f in functions.values) f.steps.forEach { if (it is ListedMethods) out += it.elements }
        out
    }

    override val wantedModules: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val modules = LinkedHashSet<String>()
        fun items(list: List<ListItem>) {
            for (item in list) {
                val include = (item as? RouteItem)?.target as? IncludeTarget ?: continue
                when (val spec = include.spec) {
                    is ModuleSpec -> (spec.name as? Lit)?.let { modules += it.text }
                    is InlineSpec -> items(spec.items)
                    is RefSpec, is OpaqueSpec -> Unit
                }
            }
        }
        patternLists.values.forEach(::items)
        (rootUrlconf as? Lit)?.let { modules += it.text }
        modules.toList()
    }
}

/** Path to module name mapping shared by the resolver (relative imports) and tests. */
object DjangoModules {
    private const val INIT = "__init__"

    /** `shop/views.py` is `shop.views`, `shop/__init__.py` is `shop`. */
    fun moduleOf(path: String): String {
        val noExt = path.removeSuffix(".py")
        val parts = noExt.split('/').filter { it.isNotEmpty() }
        return (if (parts.lastOrNull() == INIT) parts.dropLast(1) else parts).joinToString(".")
    }

    /** The package that relative imports of the file are relative to. */
    fun packageOf(path: String): String {
        val module = moduleOf(path)
        return if (path.removeSuffix(".py").substringAfterLast('/') == INIT) module else module.substringBeforeLast('.', "")
    }
}
