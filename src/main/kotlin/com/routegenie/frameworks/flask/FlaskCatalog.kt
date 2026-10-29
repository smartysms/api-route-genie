package com.routegenie.frameworks.flask

import com.routegenie.frameworks.common.ArgSlot
import com.routegenie.frameworks.common.Composition
import com.routegenie.frameworks.common.Primitive
import com.routegenie.frameworks.common.PrimitiveKind
import com.routegenie.frameworks.common.PrimitiveTable
import com.routegenie.model.HttpMethod

/** Every fact about Flask the provider relies on, as a table of registration primitives plus the view-class names. */
internal object FlaskCatalog {
    const val PROVIDER_ID = "flask"

    /** Flask answers GET when `methods` is absent (HEAD and OPTIONS are added implicitly and not listed). */
    private val defaultMethod = HttpMethod.GET

    private const val NOT_POSITIONAL = -1
    private val rule = ArgSlot("rule", 0)
    private val prefix = ArgSlot("url_prefix", NOT_POSITIONAL)
    private val endpoint = ArgSlot("endpoint", NOT_POSITIONAL)
    private val methods = ArgSlot("methods", NOT_POSITIONAL)

    /** Shortcut decorators that fix the method; Flask has no head/options/trace shortcuts. */
    private val shortcuts = listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE).map {
        Primitive(it.name.lowercase(), PrimitiveKind.ROUTE_DECORATOR, path = rule, fixedMethod = it, endpoint = endpoint)
    }

    val table = PrimitiveTable(
        providerId = PROVIDER_ID,
        importRoots = setOf("flask"),
        capturesClasses = true,
        primitives = listOf(
            Primitive("Flask", PrimitiveKind.APP, origins = setOf("flask.Flask", "flask.app.Flask")),
            Primitive(
                "Blueprint", PrimitiveKind.CONTAINER,
                origins = setOf("flask.Blueprint", "flask.blueprints.Blueprint", "flask.sansio.blueprints.Blueprint"),
                label = ArgSlot("name", 0), prefix = prefix,
            ),
            Primitive("route", PrimitiveKind.ROUTE_DECORATOR, path = rule, methods = methods, defaultMethod = defaultMethod, endpoint = endpoint),
            Primitive(
                "add_url_rule", PrimitiveKind.ROUTE_CALL, path = rule, endpoint = ArgSlot("endpoint", 1),
                handler = ArgSlot("view_func", 2), methods = methods, defaultMethod = defaultMethod,
            ),
            Primitive(
                "register_blueprint", PrimitiveKind.ATTACH, child = ArgSlot("blueprint", 0), prefix = prefix,
                label = ArgSlot("name", NOT_POSITIONAL), composition = Composition.OVERRIDE_JOIN,
            ),
        ) + shortcuts,
    )

    /** Class based views: the base classes that make a class a view, and the `as_view` call that registers it. */
    val methodViewClasses = setOf("flask.views.MethodView", "flask.MethodView")
    val viewClasses = setOf("flask.views.View", "flask.View")
    const val AS_VIEW = "as_view"

    /** Lower-case handler names of a MethodView that become methods. */
    val viewVerbs: Map<String, HttpMethod> = HttpMethod.entries.filter { it.isConcrete }.associateBy { it.name.lowercase() }

    /** Joiner of nested blueprint names and the endpoint (`parent.child.view`). */
    const val ENDPOINT_SEPARATOR = "."

    /** URL path separator used when joining a prefix and a rule. */
    const val PATH_SEPARATOR = "/"
}
