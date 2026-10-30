package com.routegenie.frameworks.flask

import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.core.SettingTally
import com.routegenie.frameworks.common.ArgFact
import com.routegenie.frameworks.common.CallFact
import com.routegenie.frameworks.common.LinkSupport
import com.routegenie.frameworks.common.Primitive
import com.routegenie.frameworks.common.PrimitiveKind
import com.routegenie.frameworks.common.PyModuleIndex
import com.routegenie.frameworks.common.PyResolver
import com.routegenie.frameworks.common.PyRouteFacts
import com.routegenie.frameworks.common.RefFact
import com.routegenie.frameworks.common.SourcePos
import com.routegenie.frameworks.common.SymbolRef
import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.Severity
import com.routegenie.model.SourceRef
import com.routegenie.model.StrValue

/**
 * Pure linker for Flask. Implements the prefix rules of Flask's own blueprint code (checked against flask 3.1):
 * a register-time `url_prefix` replaces the blueprint's own; a nested blueprint's prefix is appended to its parent's;
 * a rule is appended to the prefix with exactly one slash between them, and an empty rule is the prefix alone.
 *
 * Correct or absent: an endpoint is produced only when method and full path are exact; everything else is a
 * diagnostic at the route's source.
 */
internal object FlaskLinker {
    private val table = FlaskCatalog.table

    private class Route(
        val file: PyRouteFacts,
        val pos: SourcePos,
        /** Null when the method list could not be read exactly. */
        val methods: List<HttpMethod>?,
        val rule: StrValue,
        val endpointName: String,
        val handlerName: String,
        val handlerQualified: String,
    )

    private class Edge(val child: Node, val prefix: StrValue?, val name: StrValue?, val source: SourceRef, val guessed: Boolean)

    private class Node(val symbol: SymbolRef, val isApp: Boolean, val name: StrValue, val urlPrefix: StrValue?) {
        val routes = ArrayList<Route>()
        val edges = ArrayList<Edge>()
        var hasIncoming = false
    }

    /** Why nothing below can be listed; a [source] means it was reported once at that include, not per route. */
    private class Blocker(val code: DiagnosticCode, val source: SourceRef? = null)

    private class State(
        val prefix: StrValue?,
        val names: String,
        val blocker: Blocker?,
        /** The settings the prefix was built from and where each is written, to count the routes hidden below them. */
        val uses: List<Pair<String, SourceRef>> = emptyList(),
    )

    private enum class ViewKind { METHOD_VIEW, VIEW, UNKNOWN }

    private class ViewTraits(val kind: ViewKind, val verbs: List<HttpMethod>, val methodsAttr: List<StrValue>?)

    fun link(facts: Collection<FileFacts>): LinkResult {
        val resolution = PyResolver.resolveAll(facts, FlaskCatalog.PROVIDER_ID)
        val files = resolution.facts.sortedBy { it.path }
        val tally = SettingTally(resolution.suggestions)
        val index = PyModuleIndex(files)
        val diagnostics = LinkedHashSet<Diagnostic>()
        val endpoints = LinkedHashMap<String, Endpoint>()

        val nodes = LinkedHashMap<String, Node>()
        for (file in files) for (assign in file.assignments) {
            val origin = assign.call.callee?.let { index.originOf(file, it) } ?: continue
            val primitive = table.constructorFor(origin) ?: continue
            val isApp = primitive.kind == PrimitiveKind.APP
            val name = primitive.label?.of(assign.call)?.str ?: if (isApp) LinkSupport.EMPTY else StrValue.Unknown()
            val prefix = primitive.prefix?.of(assign.call)?.str
            val node = Node(SymbolRef(file, assign.scope, assign.name), isApp, name, prefix)
            nodes.putIfAbsent(node.symbol.key, node)
        }
        val soleApp = nodes.values.singleOrNull { it.isApp }

        fun nodeOf(file: PyRouteFacts, scope: String, ref: RefFact?): Node? {
            val symbol = ref?.let { index.resolveSymbol(file, scope, it) } ?: return null
            return nodes[symbol.key]
        }

        val orphans = ArrayList<Route>()
        for (file in files) {
            val declared = ArrayList<Pair<Int, () -> Unit>>()
            for (d in file.decorators) {
                val primitive = table.methodPrimitive(d.method, PrimitiveKind.ROUTE_DECORATOR) ?: continue
                declared += d.pos.offset to {
                    val endpoint = primitive.endpoint?.of(d.call)?.str?.takeIf { it.isExact }?.text ?: d.handler.substringAfterLast('.')
                    val route = Route(
                        file, d.pos, LinkSupport.methodsFor(primitive, d.call), primitive.path?.of(d.call)?.str ?: StrValue.Unknown(),
                        endpoint, d.handler, "${file.module}.${d.handler}",
                    )
                    val node = nodeOf(file, d.scope, d.receiver)
                    if (node != null) node.routes += route else if (table.importsFramework(file)) orphans += route
                }
            }
            for (c in file.calls) {
                val primitive = table.methodPrimitive(c.method, PrimitiveKind.ROUTE_CALL, PrimitiveKind.ATTACH, PrimitiveKind.MOUNT) ?: continue
                declared += c.pos.offset to {
                    val source = LinkSupport.sourceOf(file, c.pos)
                    if (primitive.kind == PrimitiveKind.MOUNT) {
                        // A Flask app handed to `mount` (usually inside WSGIMiddleware) is served under the mount path.
                        for (ref in LinkSupport.wrappedRefs(primitive.child?.of(c.call))) {
                            nodeOf(file, c.scope, ref)?.takeIf { it.isApp }?.let { wrapped ->
                                wrapped.hasIncoming = true
                                diagnostics += LinkSupport.diagnostic(DiagnosticCode.MOUNTED_APP, source)
                            }
                        }
                    } else if (primitive.kind == PrimitiveKind.ROUTE_CALL) {
                        val routes = routesOfCall(file, index, primitive, c.scope, c.call, c.pos, diagnostics)
                        val node = nodeOf(file, c.scope, c.receiver)
                        if (node != null) node.routes += routes else if (table.importsFramework(file)) orphans += routes
                    } else {
                        val child = nodeOf(file, c.scope, primitive.child?.of(c.call)?.ref)
                        val declaredParent = nodeOf(file, c.scope, c.receiver)
                        val parent = declaredParent ?: soleApp
                        when {
                            child == null -> diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_INCLUDE, source)
                            parent == null -> diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_RECEIVER, source)
                            else -> {
                                child.hasIncoming = true
                                parent.edges += Edge(
                                    child, primitive.prefix?.of(c.call)?.str, primitive.label?.of(c.call)?.str, source,
                                    guessed = declaredParent == null,
                                )
                            }
                        }
                    }
                }
            }
            declared.sortBy { it.first }
            declared.forEach { it.second() }
        }

        for (node in nodes.values) {
            if (node.hasIncoming) continue
            val state = if (node.isApp) State(null, "", null) else State(node.urlPrefix, nameOf(node.name, null, ""), Blocker(DiagnosticCode.UNATTACHED_ROUTER))
            expand(node, state, hashSetOf(node.symbol.key), endpoints, diagnostics, tally)
        }
        for (route in orphans) {
            diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_RECEIVER, LinkSupport.sourceOf(route.file, route.pos))
        }
        return LinkResult(endpoints.values.toList(), diagnostics.toList(), tally.result(Framework.FLASK))
    }

    private fun routesOfCall(
        file: PyRouteFacts,
        index: PyModuleIndex,
        primitive: Primitive,
        scope: String,
        call: CallFact,
        pos: SourcePos,
        diagnostics: MutableSet<Diagnostic>,
    ): List<Route> {
        val source = LinkSupport.sourceOf(file, pos)
        val rule = primitive.path?.of(call)?.str ?: StrValue.Unknown()
        val explicitEndpoint = primitive.endpoint?.of(call)?.str?.takeIf { it.isExact }?.text
        val view: ArgFact? = primitive.handler?.of(call)
        val viewCall = view?.call
        val callee = viewCall?.callee
        if (viewCall != null && callee != null && callee.last == FlaskCatalog.AS_VIEW) {
            val classRef = callee.qualifier
            val symbol = classRef?.let { index.resolveSymbol(file, scope, it) }
            val traits = symbol?.let { viewTraits(index, it, 0) }
            val className = classRef?.last ?: LinkSupport.UNKNOWN
            val endpoint = explicitEndpoint ?: viewCall.args.firstOrNull()?.str?.takeIf { it.isExact }?.text ?: className
            val methods: List<HttpMethod>? = when {
                primitive.methods?.of(call) != null -> LinkSupport.methodsFor(primitive, call)
                traits == null || traits.kind == ViewKind.UNKNOWN || (traits.kind == ViewKind.METHOD_VIEW && traits.verbs.isEmpty()) -> null
                traits.kind == ViewKind.METHOD_VIEW -> traits.verbs
                else -> traits.methodsAttr?.let { LinkSupport.methodsOf(ArgFact(list = it)).let { (read, ok) -> if (ok) read else null } }
                    ?: if (traits.methodsAttr == null) listOfNotNull(primitive.defaultMethod) else null
            }
            if (methods == null || symbol == null) {
                diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_VIEW, source, Severity.WARNING, className)
                return emptyList()
            }
            return listOf(Route(file, pos, methods, rule, endpoint, className, "${symbol.file.module}.${symbol.name}"))
        }
        val ref = view?.ref
        if (ref == null) {
            diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_VIEW, source)
            return emptyList()
        }
        val name = ref.last
        return listOf(
            Route(file, pos, LinkSupport.methodsFor(primitive, call), rule, explicitEndpoint ?: name, name, index.qualifiedNameOf(file, scope, ref)),
        )
    }

    /** The methods a class based view answers, following base classes through the project. */
    private fun viewTraits(index: PyModuleIndex, symbol: SymbolRef, depth: Int): ViewTraits? {
        if (depth > MAX_VIEW_DEPTH) return null
        val cls = index.classOf(symbol) ?: return null
        var kind = ViewKind.UNKNOWN
        val verbs = LinkedHashSet<HttpMethod>()
        cls.methods.mapNotNullTo(verbs) { FlaskCatalog.viewVerbs[it] }
        var attr = cls.methodsAttr
        for (base in cls.bases) {
            val origin = index.originOf(symbol.file, base)
            val inherited = when {
                origin in FlaskCatalog.methodViewClasses -> ViewTraits(ViewKind.METHOD_VIEW, emptyList(), null)
                origin in FlaskCatalog.viewClasses -> ViewTraits(ViewKind.VIEW, emptyList(), null)
                else -> index.resolveSymbol(symbol.file, symbol.scope, base)?.let { viewTraits(index, it, depth + 1) }
            } ?: continue
            if (inherited.kind == ViewKind.UNKNOWN) continue
            if (kind == ViewKind.UNKNOWN || inherited.kind == ViewKind.METHOD_VIEW) kind = inherited.kind
            verbs += inherited.verbs
            if (attr == null) attr = inherited.methodsAttr
        }
        return ViewTraits(kind, verbs.toList(), attr)
    }

    private fun expand(
        node: Node,
        state: State,
        chain: MutableSet<String>,
        endpoints: MutableMap<String, Endpoint>,
        diagnostics: MutableSet<Diagnostic>,
        tally: SettingTally,
    ) {
        for (route in node.routes) emit(route, state, endpoints, diagnostics, tally)
        for (edge in node.edges) {
            if (!chain.add(edge.child.symbol.key)) {
                diagnostics += LinkSupport.diagnostic(DiagnosticCode.INCLUDE_CYCLE, edge.source)
                continue
            }
            val own = edge.prefix ?: edge.child.urlPrefix
            val effective = when {
                state.prefix != null && own != null -> joinPrefix(state.prefix, own)
                own != null -> own
                else -> state.prefix
            }
            val blocker = state.blocker ?: when {
                own != null && !own.isExact -> {
                    diagnostics += Diagnostic(Severity.WARNING, DiagnosticCode.DYNAMIC_PREFIX, source = edge.source)
                    Blocker(DiagnosticCode.DYNAMIC_PREFIX, edge.source)
                }
                edge.guessed -> Blocker(DiagnosticCode.UNRESOLVED_RECEIVER)
                else -> null
            }
            val uses = state.uses + LinkSupport.usesOf(own, edge.source)
            expand(edge.child, State(effective, nameOf(edge.child.name, edge.name, state.names), blocker, uses), chain, endpoints, diagnostics, tally)
            chain.remove(edge.child.symbol.key)
        }
    }

    private fun nameOf(own: StrValue, override: StrValue?, parentNames: String): String {
        val name = (override ?: own).text
        return if (parentNames.isEmpty()) name else parentNames + FlaskCatalog.ENDPOINT_SEPARATOR + name
    }

    private fun emit(route: Route, state: State, endpoints: MutableMap<String, Endpoint>, diagnostics: MutableSet<Diagnostic>, tally: SettingTally) {
        val source = LinkSupport.sourceOf(route.file, route.pos)
        val joined = joinRule(state.prefix, route.rule)
        val methods = route.methods
        if (state.blocker?.source != null) {
            tally.hitAll(state.uses + LinkSupport.usesOf(route.rule, source))
            return
        }
        val omit = state.blocker?.code ?: if (methods == null || !joined.isExact) DiagnosticCode.DYNAMIC_VALUE else null
        if (omit != null || methods == null) {
            if (state.blocker == null && !joined.isExact) tally.hitAll(state.uses + LinkSupport.usesOf(route.rule, source))
            diagnostics += Diagnostic(Severity.WARNING, omit ?: DiagnosticCode.DYNAMIC_VALUE, source = source)
            return
        }
        val (path, params) = FlaskPaths.normalize(LinkSupport.displayPath(joined))
        val endpointName = if (state.names.isEmpty()) route.endpointName else state.names + FlaskCatalog.ENDPOINT_SEPARATOR + route.endpointName
        for (method in methods) {
            val endpoint = Endpoint(
                method = method,
                path = path,
                framework = Framework.FLASK,
                handler = Handler(route.handlerName, route.handlerQualified),
                source = source,
                routeName = endpointName,
                pathParams = params,
                pins = joined.pins,
            )
            endpoints.putIfAbsent(endpoint.declarationKey, endpoint)
        }
    }

    /** Flask's `"/".join((prefix.rstrip("/"), rule.lstrip("/")))`, or the prefix alone for an empty rule. */
    internal fun joinRule(prefix: StrValue?, rule: StrValue): StrValue = when {
        prefix == null -> rule
        rule.isExact && rule.text.isEmpty() -> prefix
        else -> joinPrefix(prefix, rule)
    }

    /** Nested blueprint prefixes: `parent.rstrip("/") + "/" + child.lstrip("/")`. */
    internal fun joinPrefix(parent: StrValue, child: StrValue): StrValue {
        val text = parent.text.trimEnd('/') + FlaskCatalog.PATH_SEPARATOR + child.text.trimStart('/')
        val pins = StrValue.union(parent.pins, child.pins)
        return if (parent.isExact && child.isExact) StrValue.Known(text, pins)
        else StrValue.Partial(
            text, parent.reason ?: child.reason ?: DiagnosticCode.DYNAMIC_VALUE, pins,
            StrValue.union(parent.unresolved, child.unresolved),
        )
    }

    /** Class hierarchies deeper than this are cut off; real view hierarchies are two or three levels. */
    private const val MAX_VIEW_DEPTH = 8
}

/** Werkzeug converters (`<int:id>`, `<path:p>`, `<string(length=2):lang>`) in the shared `{name}` notation. */
internal object FlaskPaths {
    private val CONVERTER = Regex("""<(?:([A-Za-z_][A-Za-z0-9_]*)(?:\((.*?)\))?:)?([A-Za-z_][A-Za-z0-9_]*)>""")

    fun normalize(rule: String): Pair<String, List<PathParam>> {
        val params = ArrayList<PathParam>()
        val text = CONVERTER.replace(rule) { match ->
            val name = match.groupValues[3]
            params += PathParam(name, match.groupValues[1].ifEmpty { null })
            "{$name}"
        }
        return text to params
    }
}
