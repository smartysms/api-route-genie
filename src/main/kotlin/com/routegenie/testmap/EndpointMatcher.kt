package com.routegenie.testmap

import com.routegenie.client.PathConverters
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.PathSyntax

/** One segment of a URL a test wrote. */
sealed interface CallSeg {
    data class Lit(val text: String) : CallSeg

    /** The whole segment is computed (`f"/items/{item_id}"`). */
    data object Hole : CallSeg

    /** Part of the segment is computed; [parts] are the pieces between the holes (a hole is a null). */
    data class Pattern(val parts: List<String?>) : CallSeg
}

/** The path of a call, split into segments. [trailingSlash] is kept apart because redirect rules care about it. */
class UrlTemplate(val segments: List<CallSeg>, val trailingSlash: Boolean) {
    val display: String by lazy {
        if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/", postfix = if (trailingSlash) "/" else "") { seg ->
            when (seg) {
                is CallSeg.Lit -> seg.text
                CallSeg.Hole -> PathSyntax.UNKNOWN_SEGMENT
                is CallSeg.Pattern -> seg.parts.joinToString("") { it ?: PathSyntax.UNKNOWN_SEGMENT }
            }
        }
    }

    companion object {
        /** [path] starts with '/', has no query string or fragment; unknown parts are written `{?}`. Null when a middle segment is empty. */
        fun parse(path: String): UrlTemplate? {
            if (!path.startsWith("/")) return null
            val body = path.substring(1)
            if (body.isEmpty()) return UrlTemplate(emptyList(), false)
            val trailing = body.endsWith("/")
            val parts = body.removeSuffix("/").split('/')
            if (parts.any { it.isEmpty() }) return null
            val segments = parts.map { part ->
                when {
                    part == PathSyntax.UNKNOWN_SEGMENT -> CallSeg.Hole
                    part.contains(PathSyntax.UNKNOWN_SEGMENT) -> CallSeg.Pattern(part.split(PathSyntax.UNKNOWN_SEGMENT).flatMapIndexed { i, s -> if (i == 0) listOf(s) else listOf(null, s) })
                    else -> CallSeg.Lit(part)
                }
            }
            return UrlTemplate(segments, trailing)
        }
    }
}

sealed interface MatchResult {
    data class Mapped(val endpoint: Endpoint, val viaRedirect: Boolean) : MatchResult

    /** Nothing matches; [detail] says what was looked for (`GET /x/`). */
    data class NoEndpoint(val detail: String) : MatchResult

    data class Ambiguous(val keys: Set<String>) : MatchResult
}

/**
 * Finds the endpoints a call can reach. Pure and built once per link from the route snapshot. The endpoints are kept in a trie
 * of their path segments, so a call walks a few nodes instead of comparing with every template (a project whose routes all
 * start with `/api/0/` costs the same as one with five), and equal (frameworks, path) questions are answered once.
 *
 * Path rules are segment-wise unification: equal literals; a parameter against a literal that passes its converter (`me` is not
 * an `int`); a parameter against a computed segment; a `path` parameter takes one segment or more.
 */
class EndpointMatcher(endpoints: List<Endpoint>) {
    private sealed interface EpSeg {
        data class Lit(val text: String) : EpSeg
        data class Param(val param: PathParam) : EpSeg {
            val isRest: Boolean get() = param.type == REST_TYPE
        }

        /** A segment of text and parameters (`{name}.{ext}`), matched by its text. */
        data class Mixed(val pattern: String) : EpSeg {
            val regex: Regex by lazy(LazyThreadSafetyMode.NONE) { Regex(pattern) }
        }
    }

    private class Compiled(val endpoint: Endpoint, val segs: List<EpSeg>, val trailing: Boolean)

    /** A non-literal step of the trie; converters differ per framework, so the framework is part of the edge. */
    private data class Edge(val seg: EpSeg, val framework: Framework)

    private class Node {
        val literal = HashMap<String, Node>()
        val edges = ArrayList<Pair<Edge, Node>>()
        val ending = ArrayList<Compiled>()
    }

    private class Matches(val exact: List<Compiled>, val redirect: List<Compiled>)

    private val root = Node()
    private val byName = HashMap<String, MutableList<Endpoint>>()
    private val memo = HashMap<String, Matches>()

    init {
        for (endpoint in endpoints) {
            val c = compile(endpoint)
            var node = root
            for (seg in c.segs) {
                node = when (seg) {
                    is EpSeg.Lit -> node.literal.getOrPut(seg.text) { Node() }
                    else -> {
                        val edge = Edge(seg, endpoint.framework)
                        node.edges.firstOrNull { it.first == edge }?.second ?: Node().also { node.edges += edge to it }
                    }
                }
            }
            node.ending += c
            endpoint.routeName?.let { byName.getOrPut(it) { ArrayList() } += endpoint }
        }
    }

    /** Endpoints of the given frameworks with this route name (Django namespaced `app:name`, Flask `bp.view`). */
    fun named(name: String, frameworks: Set<Framework>): List<Endpoint> = byName[name].orEmpty().filter { it.framework in frameworks }

    fun match(sinks: Collection<ClientSink>, verb: HttpMethod, template: UrlTemplate, frameworks: Set<Framework>): MatchResult {
        val matches = pathMatches(frameworks, template)
        val pool = matches.exact.ifEmpty { matches.redirect }
        val redirect = matches.exact.isEmpty()
        val byVerb = pool.filter { verbOk(sinks, verb, it.endpoint) }
        val detail = "$verb ${template.display}"
        return when {
            byVerb.isEmpty() -> MatchResult.NoEndpoint(detail)
            byVerb.size == 1 -> MatchResult.Mapped(byVerb[0].endpoint, redirect)
            else -> {
                val staticWins = byVerb.all { c -> sinkFor(sinks, c.endpoint)?.matchPolicy == MatchPolicy.STATIC_BEATS_DYNAMIC }
                val winner = if (staticWins) mostStatic(byVerb) else null
                if (winner != null) MatchResult.Mapped(winner.endpoint, redirect) else MatchResult.Ambiguous(byVerb.mapTo(LinkedHashSet()) { it.endpoint.key })
            }
        }
    }

    /** The one endpoint among [candidates] of an already name-resolved call whose verb fits, else the ambiguity. */
    fun pick(sinks: Collection<ClientSink>, verb: HttpMethod, candidates: List<Endpoint>): MatchResult {
        val fitting = candidates.filter { verbOk(sinks, verb, it) }
        return when {
            fitting.isEmpty() -> MatchResult.NoEndpoint(verb.name)
            fitting.size == 1 -> MatchResult.Mapped(fitting[0], false)
            else -> MatchResult.Ambiguous(fitting.mapTo(LinkedHashSet()) { it.key })
        }
    }

    /** The client family that reaches [endpoint]: the quirks of its framework (werkzeug answers HEAD from a GET rule) apply to it. */
    private fun sinkFor(sinks: Collection<ClientSink>, endpoint: Endpoint): ClientSink? = sinks.firstOrNull { endpoint.framework in it.targets }

    fun verbOk(sinks: Collection<ClientSink>, verb: HttpMethod, endpoint: Endpoint): Boolean =
        endpoint.method == verb || endpoint.method == HttpMethod.ANY ||
            endpoint.method in sinkFor(sinks, endpoint)?.implicitVerbs?.get(verb).orEmpty()

    /** Every candidate that is not beaten by another in the first segment where they differ in kind; null unless exactly one is. */
    private fun mostStatic(candidates: List<Compiled>): Compiled? {
        val winners = candidates.filter { a -> candidates.all { b -> a === b || beats(a, b) } }
        return winners.singleOrNull()
    }

    private fun beats(a: Compiled, b: Compiled): Boolean {
        for (i in 0 until minOf(a.segs.size, b.segs.size)) {
            val sa = a.segs[i] is EpSeg.Lit
            val sb = b.segs[i] is EpSeg.Lit
            if (sa != sb) return sa
        }
        return false
    }

    private fun pathMatches(frameworks: Set<Framework>, template: UrlTemplate): Matches {
        val key = frameworks.joinToString(",") { it.name } + "|" + template.display
        return memo.getOrPut(key) {
            val found = LinkedHashSet<Compiled>()
            walk(root, template.segments, 0, frameworks, found)
            val exact = ArrayList<Compiled>()
            val redirect = ArrayList<Compiled>()
            for (c in found) if (c.trailing == template.trailingSlash || c.segs.isEmpty()) exact += c else redirect += c
            Matches(exact, redirect)
        }
    }

    private fun walk(node: Node, call: List<CallSeg>, i: Int, frameworks: Set<Framework>, out: MutableSet<Compiled>) {
        if (i == call.size) {
            for (c in node.ending) if (c.endpoint.framework in frameworks) out += c
            return
        }
        val seg = call[i]
        if (seg is CallSeg.Lit) node.literal[seg.text]?.let { walk(it, call, i + 1, frameworks, out) }
        for ((edge, child) in node.edges) {
            if (edge.framework !in frameworks || !segMatches(seg, edge.seg, edge.framework)) continue
            val param = edge.seg as? EpSeg.Param
            if (param != null && param.isRest) {
                for (k in i + 1..call.size) walk(child, call, k, frameworks, out)
            } else {
                walk(child, call, i + 1, frameworks, out)
            }
        }
    }

    /** A literal call segment against a non-literal step; a path parameter's first segment is checked here, the rest is free. */
    private fun segMatches(call: CallSeg, ep: EpSeg, framework: Framework): Boolean = when (call) {
        is CallSeg.Lit -> when (ep) {
            is EpSeg.Lit -> ep.text == call.text
            is EpSeg.Param -> PathConverters.check(ep.param, call.text, framework) == null
            is EpSeg.Mixed -> ep.regex.matches(call.text)
        }
        // A computed segment can only be a parameter: it is not known to equal a literal.
        CallSeg.Hole, is CallSeg.Pattern -> ep !is EpSeg.Lit
    }

    private fun compile(endpoint: Endpoint): Compiled {
        val path = endpoint.path
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val segs = parts.map { part ->
            val whole = PLACEHOLDER.matchEntire(part)
            when {
                whole != null -> EpSeg.Param(endpoint.pathParams.firstOrNull { it.name == whole.groupValues[1] } ?: PathParam(whole.groupValues[1]))
                part.contains('{') -> EpSeg.Mixed(mixedPattern(part))
                else -> EpSeg.Lit(part)
            }
        }
        return Compiled(endpoint, segs, path.length > 1 && path.endsWith("/"))
    }

    private fun mixedPattern(part: String): String {
        val sb = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER_ANYWHERE.findAll(part)) {
            sb.append(Regex.escape(part.substring(last, m.range.first))).append("(.+)")
            last = m.range.last + 1
        }
        sb.append(Regex.escape(part.substring(last)))
        return sb.toString()
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")
        val PLACEHOLDER_ANYWHERE = Regex("""\{[A-Za-z_][A-Za-z0-9_]*}""")
        const val REST_TYPE = "path"
    }
}
