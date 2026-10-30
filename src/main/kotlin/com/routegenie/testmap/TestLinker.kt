package com.routegenie.testmap

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.core.Add
import com.routegenie.discovery.core.ExprEvaluator
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.FStr
import com.routegenie.discovery.core.Format
import com.routegenie.discovery.core.Interp
import com.routegenie.discovery.core.Lit
import com.routegenie.discovery.core.ModuleEntry
import com.routegenie.discovery.core.ModuleNames
import com.routegenie.discovery.core.ModuleTable
import com.routegenie.discovery.core.Percent
import com.routegenie.discovery.core.Ref
import com.routegenie.frameworks.common.RefFact
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathSyntax
import com.routegenie.model.SourceRef
import com.routegenie.model.StrValue

/** One test file's facts together with where the file is now (a move needs a relink, not a re-read). */
data class LocatedTestFacts(override val path: String, val source: TestFileFacts, val sourceRoot: String = "") : FileFacts

/**
 * Maps the client calls found in test files onto the discovered endpoints. Pure: facts and endpoints in, an immutable
 * [TestIndex] out. Correct or absent: a call is attributed only when its verb and complete path (or route name) are exactly
 * determined and match exactly one endpoint; everything else is listed as not attributed with the reason.
 */
object TestLinker {
    fun link(files: List<LocatedTestFacts>, endpoints: List<Endpoint>, generation: Long = 1): TestIndex =
        Run(files.sortedBy { it.path }, endpoints).link(generation)

    /**
     * Dotted names of the modules that base classes of [files] are imported from but that are not among [files]: a test case
     * that derives from a project class in another file (`from app.testutils import BaseCase`) only has a `self.client` when that
     * file has been read. Library classes the table already knows are not asked for. The engine reads the project files of these
     * names (a library name finds none) and calls this again, a few rounds at most.
     */
    fun missingModules(files: List<LocatedTestFacts>): Set<String> = Run(files.sortedBy { it.path }, emptyList()).missing()

    private class FileCtx(val located: LocatedTestFacts, val entry: ModuleEntry) {
        val facts: TestFileFacts = located.source
        val path: String = located.path
        val isTestFile: Boolean = TestConventions.isTestFile(path)
        val classes: Map<String, TestClassFact> = facts.classes.associateBy { it.qualified }
        val functions: Map<String, TestFunctionFact> = facts.functions.associateBy { it.qualified }
        val bindings: Map<String, List<ClientBindingFact>> = facts.bindings.groupBy { it.scope }
        val assigns: Map<String, List<LocalAssignFact>> = facts.assigns.groupBy { it.scope }
        val asserts: Map<Int, List<StatusAssertFact>> = facts.asserts.groupBy { it.callOffset }
    }

    private class ClassRef(val file: FileCtx, val fact: TestClassFact)

    /** What a class inherits: its test style, the client families whose `self.client` it has, and the classes it derives from. */
    private class Traits(val style: TestStyle?, val sinks: Set<ClientSink>, val chain: List<ClassRef>)

    private sealed interface UrlEval {
        class Path(val template: UrlTemplate, val query: String) : UrlEval
        class Named(val resolver: NameResolver, val name: String, val positional: Int, val keys: List<String>?) : UrlEval
        class Fail(val code: DiagnosticCode, val detail: String?, val reach: Reach) : UrlEval
    }

    private class Run(val files: List<LocatedTestFacts>, val endpoints: List<Endpoint>) {
        private val entries = files.map { f ->
            ModuleEntry(f.path, ModuleNames.moduleOf(f.path, f.sourceRoot), ModuleNames.isPackage(f.path), f.source)
        }
        private val table = ModuleTable(entries)
        private val evaluator = ExprEvaluator(table)
        private val ctxs: List<FileCtx> = files.indices.map { FileCtx(files[it], entries[it]) }
        private val ctxByPath: Map<String, FileCtx> = ctxs.associateBy { it.path }
        private val matcher = EndpointMatcher(endpoints)
        private val traitsCache = HashMap<String, Traits>()
        private val fixtureCache = HashMap<String, Fixture>()
        private val ownerCache = HashMap<String, ProjectTest?>()
        private val mapped = LinkedHashMap<String, MutableList<TestCall>>()
        private val unattributed = ArrayList<UnattributedCall>()
        private val reasons = HashMap<DiagnosticCode, Int>()
        private var clientCalls = 0
        private var mappedCount = 0
        private var testFiles = 0
        private var testFunctions = 0
        private var truncated = false
        private var unresolvedReceivers = 0

        fun missing(): Set<String> {
            val out = LinkedHashSet<String>()
            for (ctx in ctxs) {
                for (cls in ctx.facts.classes) {
                    for (base in cls.bases) {
                        if (ctx.classes.containsKey(base.parts.joinToString("."))) continue
                        val origin = originOf(ctx, base)
                        if (origin != null && (ClientSinks.all.any { origin in it.baseClassOrigins } || origin in TestConventions.unittestBaseOrigins)) continue
                        if (findClass(ctx, base) != null) continue
                        for (module in baseModules(ctx, base)) if (table.find(module) == null) out += module
                    }
                }
            }
            return out
        }

        /** The modules a base class name may come from: the module of a `from m import Name`, that module's `Name` when it is a module itself, or the prefix of a dotted import. */
        private fun baseModules(file: FileCtx, ref: RefFact): List<String> {
            val binding = file.facts.imports.firstOrNull { it.local == ref.head } ?: return emptyList()
            if (binding.name != null) {
                val module = table.absoluteName(file.entry, binding.level, binding.module)
                val own = listOf(module, binding.name).filter { it.isNotEmpty() }.joinToString(".")
                return listOfNotNull(module.takeIf { it.isNotEmpty() }) + if (ref.parts.size >= 2) listOf(own) else emptyList()
            }
            val origin = originOf(file, ref) ?: return emptyList()
            return listOf(origin.substringBeforeLast('.', "")).filter { it.isNotEmpty() }
        }

        fun link(generation: Long): TestIndex {
            for (ctx in ctxs) {
                if (ctx.isTestFile) {
                    testFiles++
                    testFunctions += ctx.facts.testLikeFunctions
                }
                for (call in ctx.facts.calls) linkCall(ctx, call)
            }
            val stats = TestStats(testFiles, testFunctions, clientCalls, mappedCount, reasons.toMap(), truncated, unresolvedReceivers = unresolvedReceivers)
            mapped.values.forEach { list -> list.sortWith(compareBy({ it.test.id }, { it.source.offset })) }
            return TestIndex(generation, IndexPhase.READY, stats, mapped, unattributed)
        }

        // ---- classes and tests ----------------------------------------------------------------------------------------

        /** The class a base-class name refers to: defined in the same file, or in the project file an import points at. */
        private fun findClass(file: FileCtx, ref: RefFact): ClassRef? {
            file.classes[ref.parts.joinToString(".")]?.let { return ClassRef(file, it) }
            val binding = file.facts.imports.firstOrNull { it.local == ref.head && it.name != null }
            if (binding != null) {
                val module = table.resolve(file.entry, binding.level, binding.module) ?: return null
                val other = ctxByPath[module.path] ?: return null
                return other.classes[(listOf(binding.name!!) + ref.parts.drop(1)).joinToString(".")]?.let { ClassRef(other, it) }
            }
            val origin = originOf(file, ref) ?: return null
            val other = table.find(origin.substringBeforeLast('.', ""))?.let { ctxByPath[it.path] } ?: return null
            return other.classes[ref.last]?.let { ClassRef(other, it) }
        }

        private fun traitsOf(cls: ClassRef, depth: Int = 0): Traits {
            val key = cls.file.path + "#" + cls.fact.qualified
            traitsCache[key]?.let { return it }
            // Seed the cache first: a class that derives from itself (through a cycle) must not recurse forever.
            traitsCache[key] = Traits(null, emptySet(), listOf(cls))
            var django = false
            var unittest = false
            val sinks = LinkedHashSet<ClientSink>()
            val chain = ArrayList<ClassRef>().apply { add(cls) }
            if (depth < Limits.MAX_VALUE_RESOLVE_DEPTH) {
                for (base in cls.fact.bases) {
                    val origin = originOf(cls.file, base)
                    val families = ClientSinks.all.filter { origin != null && origin in it.baseClassOrigins }
                    if (families.isNotEmpty()) {
                        django = true
                        sinks += families
                    } else if (origin != null && origin in TestConventions.unittestBaseOrigins) {
                        unittest = true
                    } else {
                        val parent = findClass(cls.file, base) ?: continue
                        val inherited = traitsOf(parent, depth + 1)
                        sinks += inherited.sinks
                        chain += inherited.chain
                        when (inherited.style) {
                            TestStyle.DJANGO_TESTCASE -> django = true
                            TestStyle.UNITTEST -> unittest = true
                            else -> Unit
                        }
                    }
                }
            }
            val style = when {
                django -> TestStyle.DJANGO_TESTCASE
                unittest -> TestStyle.UNITTEST
                TestConventions.isPytestClass(cls.fact.name, cls.fact.hasInit) -> TestStyle.PYTEST
                else -> null
            }
            return Traits(style, sinks, chain).also { traitsCache[key] = it }
        }

        /** The import origin of a dotted name (`TestClient` from `from starlette.testclient import TestClient`), or null. */
        private fun originOf(file: FileCtx, ref: RefFact): String? {
            val head = ref.head
            val rest = ref.parts.drop(1)
            for (b in file.facts.imports) {
                if (b.local != head) continue
                if (b.name != null) {
                    if (b.level > 0) return null
                    return (listOf(b.module, b.name).filter { it.isNotEmpty() } + rest).joinToString(".")
                }
                return if (b.aliased) ((b.dotted ?: b.module).split('.') + rest).joinToString(".") else ref.parts.joinToString(".")
            }
            return null
        }

        // ---- clients ---------------------------------------------------------------------------------------------------

        /** The client family a constructor (or factory) call creates, or null. [keywords] null: unknown, so conditional families are refused. */
        private fun sinkOfCtor(file: FileCtx, ctor: RefFact, keywords: Set<String>?, callees: Map<String, RefFact>): ClientSink? {
            val origin = originOf(file, ctor)
            for (sink in ClientSinks.all) {
                val byOrigin = origin != null && origin in sink.constructorOrigins
                val byFactory = ctor.parts.size >= 2 && ctor.last in sink.factoryMethods
                if (!byOrigin && !byFactory) continue
                if (byOrigin && sink.requiresAny.isNotEmpty()) {
                    if (keywords == null) continue
                    val satisfied = sink.requiresAny.any { req ->
                        req.keyword in keywords && (req.callees.isEmpty() || callees[req.keyword]?.last in req.callees)
                    }
                    if (!satisfied) continue
                }
                return sink
            }
            return null
        }

        private fun sinkOfBinding(file: FileCtx, b: ClientBindingFact): ClientSink? = sinkOfCtor(file, b.ctor, b.keywords, b.keywordCallees)

        /** What a fixture named [name] provides for a test in [file]: its client families, none (it is something else), or unknown (no definition found). */
        private sealed interface Fixture {
            class Client(val sinks: Set<ClientSink>) : Fixture
            data object NotClient : Fixture
            data object Unknown : Fixture
        }

        private fun fixtureOf(file: FileCtx, name: String, depth: Int = 0): Fixture =
            if (depth == 0) fixtureCache.getOrPut(file.path + "#" + name) { resolveFixture(file, name, 0) } else resolveFixture(file, name, depth)

        private fun resolveFixture(file: FileCtx, name: String, depth: Int): Fixture {
            if (depth > Limits.TEST_FIXTURE_DEPTH) return Fixture.Unknown
            val definition = findFixture(file, name) ?: return Fixture.Unknown
            val (owner, fn) = definition
            val sinks = LinkedHashSet<ClientSink>()
            for (returned in fn.returned) {
                val bound = owner.bindings[fn.qualified].orEmpty().filter { it.name == returned }
                if (bound.isNotEmpty()) {
                    bound.mapNotNullTo(sinks) { sinkOfBinding(owner, it) }
                } else if (returned in fn.params) {
                    (fixtureOf(owner, returned, depth + 1) as? Fixture.Client)?.let { sinks += it.sinks }
                }
            }
            for (ctor in fn.returnedCtors) sinkOfCtor(owner, ctor, null, emptyMap())?.let { sinks += it }
            return if (sinks.isEmpty()) Fixture.NotClient else Fixture.Client(sinks)
        }

        /** The fixture function of [name]: the same file first, then the `conftest.py` files from the nearest directory upward. */
        private fun findFixture(file: FileCtx, name: String): Pair<FileCtx, TestFunctionFact>? {
            file.facts.functions.firstOrNull { it.isFixture && it.name == name }?.let { return file to it }
            var dir = file.path.substringBeforeLast('/', "")
            while (true) {
                val conftest = ctxByPath[if (dir.isEmpty()) CONFTEST else "$dir/$CONFTEST"]
                conftest?.facts?.functions?.firstOrNull { it.isFixture && it.name == name }?.let { return conftest to it }
                if (dir.isEmpty()) return null
                dir = dir.substringBeforeLast('/', "")
            }
        }

        /** The client families the receiver of [call] is, empty when it is not a client (a dict, a cache, a session). */
        private fun sinksOf(file: FileCtx, fn: TestFunctionFact?, call: ClientCallFact): Set<ClientSink> {
            call.inlineCtor?.let { ctor -> return setOfNotNull(sinkOfCtor(file, ctor, null, emptyMap())) }
            val receiver = call.receiver ?: return emptySet()
            val scope = call.scope
            when (receiver.parts.size) {
                1 -> {
                    val name = receiver.head
                    val local = file.bindings[scope].orEmpty().filter { it.name == name }
                    val bound = local.ifEmpty { file.bindings[MODULE_SCOPE].orEmpty().filter { it.name == name } }
                    if (bound.isNotEmpty()) return bound.mapNotNull { sinkOfBinding(file, it) }.toSet()
                    if (fn != null && name in fn.params) {
                        return when (val fixture = fixtureOf(file, name)) {
                            is Fixture.Client -> fixture.sinks
                            Fixture.NotClient -> emptySet()
                            Fixture.Unknown -> ClientSinks.all.filter { name in it.fixtureNames }.toSet()
                        }
                    }
                    return emptySet()
                }
                2 -> {
                    if (receiver.head != SELF && receiver.head != CLS) return emptySet()
                    val attribute = receiver.parts[1]
                    val key = "$SELF.$attribute"
                    val local = file.bindings[scope].orEmpty().filter { it.name == key }
                    if (local.isNotEmpty()) return local.mapNotNull { sinkOfBinding(file, it) }.toSet()
                    val cls = fn?.className?.let { file.classes[it] }?.let { ClassRef(file, it) } ?: return emptySet()
                    val traits = traitsOf(cls)
                    for (member in traits.chain) {
                        val bound = member.file.bindings[member.fact.qualified].orEmpty().filter { it.name == key }
                        if (bound.isNotEmpty()) return bound.mapNotNull { sinkOfBinding(member.file, it) }.toSet()
                    }
                    return traits.sinks.filter { attribute in it.classAttributes }.toSet()
                }
                else -> return emptySet()
            }
        }

        // ---- the test that owns a call ---------------------------------------------------------------------------------------

        /** `self.client` and the like: a receiver that would be a client if the test case's base classes were known. */
        private fun isClientAttribute(receiver: RefFact?): Boolean =
            receiver != null && receiver.parts.size == 2 && (receiver.head == SELF || receiver.head == CLS) &&
                ClientSinks.all.any { receiver.parts[1] in it.classAttributes }

        private fun ownerOf(file: FileCtx, fn: TestFunctionFact?): ProjectTest? {
            if (fn == null) return null
            return ownerCache.getOrPut(file.path + "#" + fn.qualified) { buildOwner(file, fn) }
        }

        private fun buildOwner(file: FileCtx, fn: TestFunctionFact?): ProjectTest? {
            if (fn == null || fn.isFixture || !TestConventions.isTestFunctionName(fn.name) || !file.isTestFile) return null
            val className = fn.className
            var style = TestStyle.PYTEST
            var mark = fn.mark
            if (className != null) {
                val cls = file.classes[className] ?: return null
                style = traitsOf(ClassRef(file, cls)).style ?: return null
                mark = mark.max(cls.mark)
            }
            val id = buildString {
                append(file.path)
                if (className != null) className.split('.').forEach { append("::").append(it) }
                append("::").append(fn.name)
            }
            return ProjectTest(id, file.path, className, fn.name, style, mark, SourceRef(file.path, fn.pos.offset, fn.pos.line.coerceAtLeast(1)))
        }

        // ---- one call --------------------------------------------------------------------------------------------------

        private fun linkCall(file: FileCtx, call: ClientCallFact) {
            val fn = file.functions[call.scope]
            val sinks = sinksOf(file, fn, call)
            if (sinks.isEmpty()) {
                if (isClientAttribute(call.receiver)) unresolvedReceivers++
                return
            }
            clientCalls++
            val source = SourceRef(file.path, call.pos.offset, call.pos.line.coerceAtLeast(1))
            val shape = ClientSinks.callShapes[call.method] ?: return
            val verb = shape.fixedVerb ?: call.verb?.let { evaluateVerb(file, it) }
            val test = ownerOf(file, fn)
            if (test == null) {
                // Calls in fixtures, helpers and setUp belong to no single test (helpers are not followed yet).
                val eval = call.url?.let { evalUrl(file, fn, call, it, sinks, 0) }
                record(UnattributedCall(source, null, verb, DiagnosticCode.TEST_HELPER_OPAQUE, fn?.name ?: call.scope.ifEmpty { null }, reachOf(file, sinks, verb, eval)))
                return
            }
            if (verb == null) {
                record(UnattributedCall(source, test.id, null, DiagnosticCode.TEST_URL_DYNAMIC, METHOD_DETAIL, Reach.Anywhere))
                return
            }
            val urlFact = call.url
            if (urlFact == null) {
                record(UnattributedCall(source, test.id, verb, DiagnosticCode.TEST_URL_DYNAMIC, null, Reach.Anywhere))
                return
            }
            val styleFrameworks = sinks.flatMap { it.targets }.filter { TestConventions.allows(it, test.style) }.toSet()
            if (styleFrameworks.isEmpty()) {
                record(UnattributedCall(source, test.id, verb, DiagnosticCode.TEST_STYLE_UNSUPPORTED, test.style.name, Reach.Unrelated))
                return
            }
            val usable = sinks.filter { s -> s.targets.any { it in styleFrameworks } }
            val frameworks = usable.flatMap { it.targets }.toSet().intersect(styleFrameworks)
            when (val eval = evalUrl(file, fn, call, urlFact, sinks, 0)) {
                is UrlEval.Fail -> record(UnattributedCall(source, test.id, verb, eval.code, eval.detail, eval.reach))
                is UrlEval.Path -> finish(file, call, test, source, verb, matcher.match(usable, verb, eval.template, frameworks), eval.template.display, null, eval.query)
                is UrlEval.Named -> {
                    val named = frameworks.intersect(eval.resolver.frameworks)
                    val candidates = matcher.named(eval.name, named).filter { fits(it, eval) }
                    val anyNamed = matcher.named(eval.name, named)
                    when {
                        anyNamed.isEmpty() -> record(UnattributedCall(source, test.id, verb, DiagnosticCode.TEST_NAME_UNRESOLVED, eval.name, Reach.Unrelated))
                        candidates.isEmpty() -> record(UnattributedCall(source, test.id, verb, DiagnosticCode.TEST_NAME_UNRESOLVED, eval.name, Reach.Endpoints(anyNamed.mapTo(LinkedHashSet()) { it.key })))
                        else -> finish(file, call, test, source, verb, matcher.pick(usable, verb, candidates), null, eval.name, null)
                    }
                }
            }
        }

        private fun fits(endpoint: Endpoint, eval: UrlEval.Named): Boolean {
            val names = endpoint.pathParams.map { it.name }
            val keys = eval.keys
            return when (eval.resolver.argsStyle) {
                ArgsStyle.DJANGO_REVERSE -> when {
                    keys != null && keys.isNotEmpty() -> keys.toSet() == names.toSet()
                    keys != null && eval.positional >= 0 -> eval.positional == names.size
                    else -> true
                }
                ArgsStyle.KEYWORDS -> keys == null || keys.toSet().containsAll(names)
            }
        }

        private fun finish(
            file: FileCtx, call: ClientCallFact, test: ProjectTest, source: SourceRef, verb: HttpMethod,
            result: MatchResult, calledPath: String?, routeName: String?, urlQuery: String?,
        ) {
            when (result) {
                is MatchResult.Mapped -> {
                    if (mappedCount >= Limits.TEST_CALLS_MAX_TOTAL) {
                        truncated = true
                        return
                    }
                    mappedCount++
                    val expected = file.asserts[call.pos.offset].orEmpty().filter { it.scope == call.scope }.map { it.code }.distinct().singleOrNull()
                    val tc = TestCall(
                        test, verb, calledPath ?: result.endpoint.path, source, CallOrigin.DIRECT, result.viaRedirect, routeName, expected,
                        requestOf(file, call, verb, result.endpoint, urlQuery),
                    )
                    mapped.getOrPut(result.endpoint.key) { ArrayList() } += tc
                }
                is MatchResult.NoEndpoint -> record(UnattributedCall(source, test.id, verb, DiagnosticCode.TEST_NO_ENDPOINT, result.detail, Reach.Unrelated))
                is MatchResult.Ambiguous -> record(UnattributedCall(source, test.id, verb, DiagnosticCode.TEST_AMBIGUOUS, result.keys.size.toString(), Reach.Endpoints(result.keys)))
            }
        }

        private fun record(call: UnattributedCall) {
            if (unattributed.size >= Limits.TEST_CALLS_MAX_TOTAL) {
                truncated = true
                return
            }
            unattributed += call
            reasons.merge(call.reason, 1, Int::plus)
        }

        /** Which endpoints a call in a helper might be about: what its URL resolves to when it resolves, anywhere when it does not. */
        private fun reachOf(file: FileCtx, sinks: Set<ClientSink>, verb: HttpMethod?, eval: UrlEval?): Reach = when (eval) {
            null -> Reach.Anywhere
            is UrlEval.Fail -> eval.reach
            is UrlEval.Path -> {
                val frameworks = sinks.flatMap { it.targets }.toSet()
                val keys = LinkedHashSet<String>()
                when (val r = matcher.match(sinks, verb ?: HttpMethod.GET, eval.template, frameworks)) {
                    is MatchResult.Mapped -> keys += r.endpoint.key
                    is MatchResult.Ambiguous -> keys += r.keys
                    is MatchResult.NoEndpoint -> Unit
                }
                if (keys.isEmpty()) Reach.Unrelated else Reach.Endpoints(keys)
            }
            is UrlEval.Named -> {
                val keys = matcher.named(eval.name, eval.resolver.frameworks).filter { fits(it, eval) }.mapTo(LinkedHashSet()) { it.key }
                if (keys.isEmpty()) Reach.Unrelated else Reach.Endpoints(keys)
            }
        }

        private fun evaluateVerb(file: FileCtx, expr: Expr): HttpMethod? =
            (evaluator.evaluate(file.entry, expr) as? StrValue.Known)?.let { HttpMethod.parse(it.text) }

        private fun requestOf(file: FileCtx, call: ClientCallFact, verb: HttpMethod, endpoint: Endpoint, urlQuery: String?): CallRequest =
            RequestLiterals.evaluate(evaluator, file.entry, call, verb, ClientSinks.all.firstOrNull { endpoint.framework in it.targets }, urlQuery)

        // ---- URLs ------------------------------------------------------------------------------------------------------

        private fun evalUrl(file: FileCtx, fn: TestFunctionFact?, call: ClientCallFact, fact: UrlFact, sinks: Set<ClientSink>, depth: Int): UrlEval {
            if (depth > Limits.MAX_VALUE_RESOLVE_DEPTH) return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, null, Reach.Anywhere)
            return when (fact) {
                is UrlFact.Text -> textUrl(file, fact.expr)
                is UrlFact.Name -> nameUrl(file, fn, call, fact.ref, sinks, depth)
                is UrlFact.Named -> namedUrl(file, fn, call, fact)
                UrlFact.Dynamic -> UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, null, Reach.Anywhere)
            }
        }

        private fun textUrl(file: FileCtx, expr: Expr): UrlEval {
            val value = evaluator.evaluate(file.entry, expr)
            if (value is StrValue.Unknown) return unresolved(expr)
            return pathOf(value.text, expr)
        }

        /** Turns an evaluated URL text (unknown parts as `{?}`) into a path template, or says why that is not possible. */
        private fun pathOf(text: String, expr: Expr?): UrlEval {
            var rest = text
            val scheme = SCHEME.find(rest)
            if (scheme != null) {
                val host = scheme.groupValues[1]
                if (host.contains(HOLE)) return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, null, Reach.Anywhere)
                if (host.substringBefore(':') !in ClientSinks.localHosts) return UrlEval.Fail(DiagnosticCode.TEST_EXTERNAL_URL, host, Reach.Unrelated)
                rest = rest.substring(scheme.range.last + 1).ifEmpty { "/" }
            }
            if (rest.startsWith(HOLE)) return if (expr != null) unresolved(expr) else UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, null, Reach.Anywhere)
            if (!rest.startsWith("/")) return UrlEval.Fail(DiagnosticCode.TEST_URL_RELATIVE, rest.take(Limits.MAX_QUOTED_SOURCE_CHARS), Reach.Anywhere)
            val cut = queryStart(rest)
            val path = if (cut >= 0) rest.substring(0, cut) else rest
            val query = if (cut >= 0 && rest[cut] == '?') rest.substring(cut + 1).substringBefore('#') else ""
            val template = UrlTemplate.parse(path) ?: return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, path.take(Limits.MAX_QUOTED_SOURCE_CHARS), Reach.Anywhere)
            return UrlEval.Path(template, query)
        }

        /** Where the query string or fragment starts; the `?` inside the marker of an unknown part (`{?}`) is not one. */
        private fun queryStart(text: String): Int {
            var i = 0
            while (i < text.length) {
                if (text.startsWith(HOLE, i)) {
                    i += HOLE.length
                    continue
                }
                if (text[i] == '?' || text[i] == '#') return i
                i++
            }
            return -1
        }

        /** A URL that could not be evaluated: a name that was not found when the expression starts with one, else a computed value. */
        private fun unresolved(expr: Expr): UrlEval {
            val name = leftmostRef(expr)
            return if (name != null) UrlEval.Fail(DiagnosticCode.TEST_CONSTANT_UNRESOLVED, name, Reach.Anywhere)
            else UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, null, Reach.Anywhere)
        }

        private fun leftmostRef(expr: Expr): String? = when (expr) {
            is Ref -> expr.parts.joinToString(".")
            is Add -> leftmostRef(expr.left)
            is FStr -> expr.parts.firstOrNull()?.let { leftmostRef(it) }
            is Interp -> leftmostRef(expr.value)
            is Percent -> leftmostRef(expr.format)
            is Format -> leftmostRef(expr.template)
            else -> null
        }

        private fun nameUrl(file: FileCtx, fn: TestFunctionFact?, call: ClientCallFact, ref: RefFact, sinks: Set<ClientSink>, depth: Int): UrlEval {
            val key = ref.parts.joinToString(".")
            val local = localAssignment(file, fn, call, key)
            when (local) {
                is Assignment.Found -> return evalUrl(file, fn, call, local.fact, sinks, depth + 1)
                Assignment.Ambiguous -> return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, key, Reach.Anywhere)
                Assignment.None -> Unit
            }
            if (fn != null && ref.parts.size == 1 && key in fn.params) return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, key, Reach.Anywhere)
            return textUrl(file, Ref(ref.parts))
        }

        private sealed interface Assignment {
            class Found(val fact: UrlFact) : Assignment
            data object Ambiguous : Assignment
            data object None : Assignment
        }

        /**
         * The value of variable [key] at [call]: in the function (the nearest assignment before the call; several assignments
         * count only when the last is not inside a branch), then, for `self.x`, in the class and its bases.
         */
        private fun localAssignment(file: FileCtx, fn: TestFunctionFact?, call: ClientCallFact, key: String): Assignment {
            val inFunction = file.assigns[call.scope].orEmpty().filter { it.name == key && it.pos.offset < call.pos.offset }
            if (inFunction.isNotEmpty()) {
                val last = inFunction.maxBy { it.pos.offset }
                return if (inFunction.size == 1 || !last.branch) Assignment.Found(last.url) else Assignment.Ambiguous
            }
            if (!key.startsWith("$SELF.") && !key.startsWith("$CLS.")) return Assignment.None
            val selfKey = "$SELF." + key.substringAfter('.')
            val cls = fn?.className?.let { file.classes[it] }?.let { ClassRef(file, it) } ?: return Assignment.None
            for (member in traitsOf(cls).chain) {
                val found = member.file.assigns[member.fact.qualified].orEmpty().filter { it.name == selfKey }
                if (found.isNotEmpty()) {
                    val distinct = found.map { it.url }.distinct()
                    return if (distinct.size == 1) Assignment.Found(distinct[0]) else Assignment.Ambiguous
                }
            }
            return Assignment.None
        }

        private fun namedUrl(file: FileCtx, fn: TestFunctionFact?, call: ClientCallFact, fact: UrlFact.Named): UrlEval {
            val resolver = resolverOf(file, fact.function)
                ?: return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, fact.function.last, Reach.Anywhere)
            if (fact.suffix != null) {
                val suffix = evaluator.evaluate(file.entry, fact.suffix)
                if (suffix !is StrValue.Known || !(suffix.text.isEmpty() || suffix.text.startsWith("?") || suffix.text.startsWith("#"))) {
                    return UrlEval.Fail(DiagnosticCode.TEST_URL_DYNAMIC, fact.function.last, Reach.Anywhere)
                }
            }
            // `reverse(self.endpoint, ...)` with `endpoint = "app:name"` on the test case or one of its bases: a literal class attribute.
            var nameExpr = fact.name
            if (nameExpr is Ref && nameExpr.parts.size == 2 && (nameExpr.parts[0] == SELF || nameExpr.parts[0] == CLS)) {
                val attribute = (localAssignment(file, fn, call, "$SELF.${nameExpr.parts[1]}") as? Assignment.Found)?.fact as? UrlFact.Text
                (attribute?.expr as? Lit)?.let { nameExpr = it }
            }
            val name = evaluator.evaluate(file.entry, nameExpr)
            if (name !is StrValue.Known) return UrlEval.Fail(DiagnosticCode.TEST_NAME_UNRESOLVED, leftmostRef(nameExpr), Reach.Anywhere)
            if (name.text.startsWith(".")) return UrlEval.Fail(DiagnosticCode.TEST_NAME_UNRESOLVED, name.text, Reach.Anywhere)
            return UrlEval.Named(resolver, name.text, fact.positional, fact.keys)
        }

        private fun resolverOf(file: FileCtx, function: RefFact): NameResolver? {
            val origin = originOf(file, function)
            ClientSinks.nameResolvers.firstOrNull { !it.asMethod && origin != null && origin in it.origins }?.let { return it }
            return ClientSinks.resolver(function.last)?.takeIf { it.asMethod && function.parts.size >= 2 }
        }
    }

    private const val MODULE_SCOPE = ""
    private const val CONFTEST = "conftest.py"
    private const val SELF = "self"
    private const val CLS = "cls"
    private const val HOLE = PathSyntax.UNKNOWN_SEGMENT
    private const val METHOD_DETAIL = "method"
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://([^/?#]*)""")
}
