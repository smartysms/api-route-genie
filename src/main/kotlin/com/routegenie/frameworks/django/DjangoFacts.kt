package com.routegenie.frameworks.django

import com.routegenie.discovery.FileFacts
import com.routegenie.model.StrValue

/*
 * Immutable, PSI-free summary of what one Python file contributes to Django routing. Only strings, numbers, StrValue and
 * collections of those: the linker works on these alone, and nothing here points back into the editor.
 */

/** A position inside the file; turned into a SourceRef together with the file path. */
data class Loc(val offset: Int, val line: Int)

enum class RouteKind { PATH, REGEX }

sealed interface ListItem

/** One path(), re_path() or url() call. */
data class RouteItem(
    val kind: RouteKind,
    val route: StrValue,
    val target: Target,
    val name: StrValue?,
    val loc: Loc,
) : ListItem

/** A reference to another pattern list or to `router.urls`, spliced in place (`urlpatterns += api_patterns`). */
data class SpliceItem(val ref: String, val loc: Loc) : ListItem

/** A list element that is not a recognizable route; reported, never guessed. */
data class OpaqueItem(val text: String, val loc: Loc) : ListItem

sealed interface Target

/**
 * A view. [ref] is the dotted reference as written (`views.Detail`), null when the view is not a plain reference.
 * [actionMap] is the `{"get": "list"}` argument of a viewset's `as_view`.
 */
data class ViewTarget(val ref: String?, val asView: Boolean, val actionMap: Map<String, String>? = null) : Target

data class IncludeTarget(val spec: IncludeSpec, val appName: StrValue?, val namespace: StrValue?) : Target

sealed interface IncludeSpec

/** `include("shop.urls")`. */
data class ModuleSpec(val name: StrValue) : IncludeSpec

/** `include([path(...), ...])`. */
data class InlineSpec(val items: List<ListItem>) : IncludeSpec

/** `include(router.urls)`, `include(other_patterns)`, `include(some_module)`. */
data class RefSpec(val ref: String) : IncludeSpec

data class OpaqueSpec(val text: String) : IncludeSpec

/** A function view that a decorator restricts or that DRF turns into an API view. [methods] null means any. */
data class FunctionFact(
    val name: String,
    val methods: List<String>?,
    val isApiView: Boolean,
    val dynamicMethods: Boolean,
    val loc: Loc,
)

/** A viewset method marked with `@action`. */
data class ActionFact(
    val methodName: String,
    val detail: Boolean?,
    val methods: List<String>,
    val urlPath: StrValue,
    val urlName: StrValue,
    val dynamicMethods: Boolean,
    val loc: Loc,
)

data class ClassFact(
    val name: String,
    val bases: List<String>,
    val methods: Set<String>,
    val httpMethodNames: List<String>?,
    val actions: List<ActionFact>,
    val queryset: String?,
    val lookupField: String?,
    val lookupUrlKwarg: String?,
    val loc: Loc,
)

/** `router = DefaultRouter(trailing_slash=False)`. [kind] is the class name as written, last segment only. */
data class RouterDecl(
    val kind: String,
    val trailingSlash: StrValue,
    val includeRootView: Boolean,
    val includeFormatSuffixes: Boolean,
    val loc: Loc,
)

/** `router.register("users", UserViewSet, basename="user")`; [router] and [viewset] are references as written. */
data class Registration(
    val router: String,
    val prefix: StrValue,
    val viewset: String,
    val basename: StrValue?,
    val loc: Loc,
)

data class DjangoFacts(
    override val path: String,
    val module: String,
    /** Local name to the dotted thing it stands for (`views` to `shop.views`, `Detail` to `shop.views.Detail`). */
    val imports: Map<String, String>,
    val rootUrlconf: StrValue?,
    val appName: StrValue?,
    val patternLists: Map<String, List<ListItem>>,
    val routers: Map<String, RouterDecl>,
    val registrations: List<Registration>,
    val functions: Map<String, FunctionFact>,
    val classes: Map<String, ClassFact>,
) : FileFacts {
    /** True when the file adds nothing the linker could use, so extraction reports "no routes". */
    val isEmpty: Boolean
        get() = rootUrlconf == null && appName == null && patternLists.isEmpty() && routers.isEmpty() &&
            registrations.isEmpty() && functions.isEmpty() && classes.isEmpty()
}

/** Path to module name mapping shared by the extractor (relative imports) and tests. */
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
