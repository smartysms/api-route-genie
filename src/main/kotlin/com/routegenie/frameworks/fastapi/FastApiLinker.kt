package com.routegenie.frameworks.fastapi

import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
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
 * Pure linker: applies constructor prefixes, `include_router` prefixes and mounts over the facts of all files.
 * The full path of a route is: every include prefix from the outermost down, each router's own prefix in between, then
 * the route path; tags are ordered the same way (verified against fastapi 0.162, see the fixtures).
 *
 * Correct or absent: an endpoint is produced only when its method and full path are exact. Anything else becomes a
 * diagnostic at the route's source and no endpoint.
 */
internal object FastApiLinker {
    private val table = FastApiCatalog.table

    private class Route(
        val file: PyRouteFacts,
        val pos: SourcePos,
        /** Null when the method list could not be read exactly. */
        val methods: List<HttpMethod>?,
        val path: StrValue,
        val handlerName: String,
        val handlerQualified: String,
        val name: String?,
        val tags: List<String>,
        val deprecated: Boolean?,
    )

    private class Edge(val child: Node, val prefix: StrValue, val tags: List<String>, val deprecated: Boolean, val source: SourceRef)

    private class Node(
        val symbol: SymbolRef,
        val isApp: Boolean,
        val prefix: StrValue,
        val tags: List<String>,
        val deprecated: Boolean,
        val source: SourceRef,
    ) {
        val routes = ArrayList<Route>()
        val edges = ArrayList<Edge>()
        var hasIncoming = false
    }

    /**
     * Why nothing below can be listed. A blocker with a [source] was reported once at that place (an include with a
     * computed prefix), so the routes below it add no diagnostic of their own.
     */
    private class Blocker(val code: DiagnosticCode, val source: SourceRef? = null)

    /** What accumulates from an application down to a route. */
    private class Acc(val path: StrValue, val tags: List<String>, val deprecated: Boolean, val blocker: Blocker?)

    fun link(facts: Collection<FileFacts>): LinkResult {
        val files = PyResolver.resolve(facts, FastApiCatalog.PROVIDER_ID).sortedBy { it.path }
        val index = PyModuleIndex(files)
        val diagnostics = LinkedHashSet<Diagnostic>()
        val endpoints = LinkedHashMap<String, Endpoint>()

        val nodes = LinkedHashMap<String, Node>()
        for (file in files) for (assign in file.assignments) {
            val origin = assign.call.callee?.let { index.originOf(file, it) } ?: continue
            val primitive = table.constructorFor(origin) ?: continue
            val isApp = primitive.kind == PrimitiveKind.APP
            val prefix = primitive.prefix?.of(assign.call)?.str ?: LinkSupport.EMPTY
            val tags = LinkSupport.tagsOf(primitive.tags?.of(assign.call))
            val deprecated = primitive.deprecated?.of(assign.call)?.bool == true
            val node = Node(SymbolRef(file, assign.scope, assign.name), isApp, prefix, tags, deprecated, LinkSupport.sourceOf(file, assign.pos))
            nodes.putIfAbsent(node.symbol.key, node)
        }

        fun nodeOf(file: PyRouteFacts, scope: String, ref: RefFact?): Node? {
            val symbol = ref?.let { index.resolveSymbol(file, scope, it) } ?: return null
            return nodes[symbol.key]
        }

        val orphans = ArrayList<Route>()
        for (file in files) {
            val declared = ArrayList<Pair<Int, () -> Unit>>()
            for (d in file.decorators) {
                if (table.methodPrimitive(d.method, PrimitiveKind.UNSUPPORTED_DECORATOR) != null) {
                    declared += d.pos.offset to {
                        if (nodeOf(file, d.scope, d.receiver) != null) {
                            diagnostics += LinkSupport.diagnostic(
                                DiagnosticCode.UNSUPPORTED_PATTERN, LinkSupport.sourceOf(file, d.pos), Severity.INFO, FastApiCatalog.UNSUPPORTED_ROUTE_NOTE,
                            )
                        }
                    }
                    continue
                }
                val primitive = table.methodPrimitive(d.method, PrimitiveKind.ROUTE_DECORATOR) ?: continue
                declared += d.pos.offset to {
                    val route = routeOf(file, primitive, d.pos, d.call, d.handler, null)
                    val node = nodeOf(file, d.scope, d.receiver)
                    if (node != null) node.routes += route else if (table.importsFramework(file)) orphans += route
                }
            }
            for (c in file.calls) {
                val primitive = table.methodPrimitive(c.method, PrimitiveKind.ROUTE_CALL, PrimitiveKind.ATTACH, PrimitiveKind.MOUNT) ?: continue
                declared += c.pos.offset to {
                    val source = LinkSupport.sourceOf(file, c.pos)
                    val parent = nodeOf(file, c.scope, c.receiver)
                    when (primitive.kind) {
                        PrimitiveKind.ROUTE_CALL -> {
                            val handler = primitive.handler?.of(c.call)?.ref
                            val name = handler?.last
                            val route = routeOf(
                                file, primitive, c.pos, c.call, name ?: LinkSupport.UNKNOWN,
                                handler?.let { index.qualifiedNameOf(file, c.scope, it) },
                            )
                            if (parent != null) parent.routes += route else if (table.importsFramework(file)) orphans += route
                        }
                        else -> {
                            val isMount = primitive.kind == PrimitiveKind.MOUNT
                            val child = nodeOf(file, c.scope, primitive.child?.of(c.call)?.ref)
                            when {
                                child == null && isMount -> for (ref in LinkSupport.wrappedRefs(primitive.child?.of(c.call))) {
                                    // `app.mount("/v1", Middleware(sub))`: the wrapper decides the served path, so `sub` is not a root.
                                    nodeOf(file, c.scope, ref)?.let { wrapped ->
                                        wrapped.hasIncoming = true
                                        diagnostics += LinkSupport.diagnostic(DiagnosticCode.MOUNTED_APP, source)
                                    }
                                }
                                child == null -> diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_INCLUDE, source)
                                parent == null -> diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_RECEIVER, source)
                                else -> {
                                    child.hasIncoming = true
                                    val prefixArg = (if (isMount) primitive.path else primitive.prefix)?.of(c.call)
                                    parent.edges += Edge(
                                        child,
                                        prefixArg?.str ?: LinkSupport.EMPTY,
                                        LinkSupport.tagsOf(primitive.tags?.of(c.call)),
                                        primitive.deprecated?.of(c.call)?.bool == true,
                                        source,
                                    )
                                }
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
            val blocker = if (node.isApp) null else Blocker(DiagnosticCode.UNATTACHED_ROUTER)
            expand(node, Acc(LinkSupport.EMPTY, emptyList(), false, blocker), hashSetOf(node.symbol.key), endpoints, diagnostics)
        }
        for (route in orphans) {
            diagnostics += LinkSupport.diagnostic(DiagnosticCode.UNRESOLVED_RECEIVER, LinkSupport.sourceOf(route.file, route.pos))
        }
        return LinkResult(endpoints.values.toList(), diagnostics.toList())
    }

    private fun expand(node: Node, acc: Acc, chain: MutableSet<String>, endpoints: MutableMap<String, Endpoint>, diagnostics: MutableSet<Diagnostic>) {
        val base = Acc(
            acc.path + node.prefix, acc.tags + node.tags, acc.deprecated || node.deprecated,
            acc.blocker ?: dynamicPrefix(node.prefix, node.source, diagnostics),
        )
        for (route in node.routes) emit(route, base, endpoints, diagnostics)
        for (edge in node.edges) {
            if (!chain.add(edge.child.symbol.key)) {
                diagnostics += LinkSupport.diagnostic(DiagnosticCode.INCLUDE_CYCLE, edge.source)
                continue
            }
            val next = Acc(
                base.path + edge.prefix, base.tags + edge.tags, base.deprecated || edge.deprecated,
                base.blocker ?: dynamicPrefix(edge.prefix, edge.source, diagnostics),
            )
            expand(edge.child, next, chain, endpoints, diagnostics)
            chain.remove(edge.child.symbol.key)
        }
    }

    /** A computed prefix is reported once, where it is written, instead of once for every route below it. */
    private fun dynamicPrefix(prefix: StrValue, source: SourceRef, diagnostics: MutableSet<Diagnostic>): Blocker? {
        if (prefix.isExact) return null
        diagnostics += Diagnostic(Severity.WARNING, DiagnosticCode.DYNAMIC_PREFIX, source = source)
        return Blocker(DiagnosticCode.DYNAMIC_PREFIX, source)
    }

    private fun emit(route: Route, acc: Acc, endpoints: MutableMap<String, Endpoint>, diagnostics: MutableSet<Diagnostic>) {
        val source = LinkSupport.sourceOf(route.file, route.pos)
        val methods = route.methods
        if (acc.blocker?.source != null) return
        val omit = acc.blocker?.code ?: when {
            methods == null || !route.path.isExact -> DiagnosticCode.DYNAMIC_VALUE
            else -> null
        }
        if (omit != null || methods == null) {
            diagnostics += Diagnostic(Severity.WARNING, omit ?: DiagnosticCode.DYNAMIC_VALUE, source = source)
            return
        }
        val text = LinkSupport.displayPath(acc.path + route.path)
        val params = PATH_PARAM.findAll(text).map { PathParam(it.groupValues[1], it.groupValues[2].ifEmpty { null }) }.toList()
        val tags = (acc.tags + route.tags).distinct()
        val deprecated = route.deprecated ?: acc.deprecated
        for (method in methods) {
            val endpoint = Endpoint(
                method = method,
                path = text,
                framework = Framework.FASTAPI,
                handler = Handler(route.handlerName, route.handlerQualified),
                source = source,
                routeName = route.name ?: route.handlerName,
                tags = tags,
                pathParams = params,
                deprecated = deprecated,
            )
            endpoints.putIfAbsent(endpoint.declarationKey, endpoint)
        }
    }

    private fun routeOf(
        file: PyRouteFacts,
        primitive: Primitive,
        pos: SourcePos,
        call: com.routegenie.frameworks.common.CallFact,
        handlerName: String,
        handlerQualified: String?,
    ) = Route(
        file = file,
        pos = pos,
        methods = LinkSupport.methodsFor(primitive, call),
        path = primitive.path?.of(call)?.str ?: StrValue.Unknown(),
        handlerName = handlerName,
        handlerQualified = handlerQualified ?: "${file.module}.$handlerName",
        name = primitive.label?.of(call)?.str?.takeIf { it.isExact }?.text,
        tags = LinkSupport.tagsOf(primitive.tags?.of(call)),
        deprecated = primitive.deprecated?.of(call)?.bool,
    )

    private val PATH_PARAM = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)(?::([A-Za-z_][A-Za-z0-9_]*))?\}""")
}
