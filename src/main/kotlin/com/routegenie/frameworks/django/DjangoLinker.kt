package com.routegenie.frameworks.django

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.PathSyntax
import com.routegenie.model.Severity
import com.routegenie.model.SourceRef
import com.routegenie.model.StrValue

/**
 * Pure function from the facts of all files to Django endpoints: follows ROOT_URLCONF through include() chains, joins
 * prefixes in the order Django matches them, and expands DRF routers. Holds no state between calls and touches no IDE class.
 */
class DjangoLinker(private val options: Options = Options()) {
    /**
     * [emitFormatSuffixVariants]: DefaultRouter also serves `users.json`; by default these variants are collapsed
     * into the plain route (research 02: "collapse suffix variants"), because they double the list without new information.
     */
    data class Options(val emitFormatSuffixVariants: Boolean = false)

    fun link(facts: Collection<FileFacts>): LinkResult =
        Run(facts.filterIsInstance<DjangoFacts>().sortedBy { it.path }, options).execute()

    private class Ctx(
        val prefix: String,
        val params: List<PathParam>,
        val problems: List<Diagnostic>,
        val namespaces: List<String>,
        val chain: Set<String>,
        val depth: Int,
    ) {
        fun with(prefix: String = this.prefix, params: List<PathParam> = this.params, problems: List<Diagnostic> = this.problems) =
            Ctx(prefix, params, problems, namespaces, chain, depth)
    }

    // --- Symbols found by following a reference through imports ------------------------------------------------------

    private sealed interface Sym
    private class ModuleSym(val facts: DjangoFacts) : Sym
    private class ListSym(val facts: DjangoFacts, val name: String) : Sym
    private class RouterSym(val facts: DjangoFacts, val name: String) : Sym
    private class RouterUrlsSym(val facts: DjangoFacts, val name: String) : Sym
    private class ClassSym(val facts: DjangoFacts, val cls: ClassFact) : Sym
    private class FunctionSym(val facts: DjangoFacts, val name: String, val fact: FunctionFact?) : Sym
    private class ExternalSym(val dotted: String) : Sym

    private class ClassInfo {
        val defined = HashSet<String>()
        val tableMethods = HashSet<HttpMethod>()
        val standardActions = HashSet<String>()
        val actions = LinkedHashMap<String, Pair<DjangoFacts, ActionFact>>()
        var httpMethodNames: List<String>? = null
        var queryset: String? = null
        var lookupField: String? = null
        var lookupUrlKwarg: String? = null
        var isDrf = false

        /** A library or unresolvable base class whose methods are not known to the catalog. */
        var hasUnknownBase = false
    }

    private class Run(private val all: List<DjangoFacts>, private val options: Options) {
        private val endpoints = ArrayList<Endpoint>()
        private val diagnostics = ArrayList<Diagnostic>()
        private val byModule = HashMap<String, DjangoFacts>()
        private val bySuffix = HashMap<String, MutableList<DjangoFacts>>()
        private val registrationsByRouter = HashMap<String, MutableList<Pair<DjangoFacts, Registration>>>()

        init {
            all.forEach { facts ->
                byModule.putIfAbsent(facts.module, facts)
                val parts = facts.module.split('.')
                for (i in 1 until parts.size) bySuffix.getOrPut(parts.drop(i).joinToString(".")) { ArrayList() }.add(facts)
            }
            all.forEach { facts ->
                facts.registrations.forEach { registration ->
                    val router = lookup(facts, registration.router) as? RouterSym ?: return@forEach
                    registrationsByRouter.getOrPut(routerKey(router.facts, router.name)) { ArrayList() }.add(facts to registration)
                }
            }
        }

        fun execute(): LinkResult {
            val roots = rootModules()
            roots.forEach { root ->
                val items = root.patternLists[DjangoCatalog.URLPATTERNS]
                if (items != null) expand(root, items, Ctx("", emptyList(), emptyList(), emptyList(), setOf(listKey(root, DjangoCatalog.URLPATTERNS)), 0))
            }
            return LinkResult(endpoints.toList(), diagnostics.toList())
        }

        // --- Roots ---------------------------------------------------------------------------------------------------

        private fun rootModules(): List<DjangoFacts> {
            val configured = all.filter { it.rootUrlconf != null }
            if (configured.isNotEmpty()) {
                val roots = LinkedHashMap<String, DjangoFacts>()
                configured.forEach { settings ->
                    val value = settings.rootUrlconf!!
                    val module = (value as? StrValue.Known)?.let { findModule(it.text) }
                    if (module == null) diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, null, value.text)
                    else roots.putIfAbsent(module.path, module)
                }
                return roots.values.toList()
            }
            val referenced = referencedPaths()
            return all.filter { DjangoCatalog.URLPATTERNS in it.patternLists && it.path !in referenced }.onEach {
                diagnostics += Diagnostic(Severity.INFO, DiagnosticCode.GUESSED_ROOT, listOf(it.module), SourceRef(it.path, 0, 1))
            }
        }

        private fun referencedPaths(): Set<String> {
            val paths = HashSet<String>()
            fun scan(owner: DjangoFacts, items: List<ListItem>) {
                items.forEach { item ->
                    when (item) {
                        is SpliceItem -> pathOf(lookup(owner, item.ref))?.takeIf { it != owner.path }?.let { paths += it }
                        is RouteItem -> (item.target as? IncludeTarget)?.let { include ->
                            when (val spec = include.spec) {
                                is ModuleSpec -> (spec.name as? StrValue.Known)?.let { findModule(it.text) }?.let { paths += it.path }
                                is RefSpec -> pathOf(lookup(owner, spec.ref))?.takeIf { it != owner.path }?.let { paths += it }
                                is InlineSpec -> scan(owner, spec.items)
                                is OpaqueSpec -> Unit
                            }
                        }
                        is OpaqueItem -> Unit
                    }
                }
            }
            all.forEach { facts -> facts.patternLists.values.forEach { scan(facts, it) } }
            return paths
        }

        private fun pathOf(sym: Sym): String? = when (sym) {
            is ModuleSym -> sym.facts.path
            is ListSym -> sym.facts.path
            else -> null
        }

        // --- Expanding pattern lists ---------------------------------------------------------------------------------

        private fun expand(owner: DjangoFacts, items: List<ListItem>, ctx: Ctx) {
            items.forEach { item ->
                when (item) {
                    is RouteItem -> route(owner, item, ctx)
                    is SpliceItem -> splice(owner, item, ctx)
                    is OpaqueItem -> diagnostics += Diagnostic(Severity.INFO, DiagnosticCode.DYNAMIC_VALUE, listOf(item.text), source(owner, item.loc))
                }
            }
        }

        private fun splice(owner: DjangoFacts, item: SpliceItem, ctx: Ctx) {
            when (val sym = lookup(owner, item.ref)) {
                is ListSym -> enter(sym.facts, listKey(sym.facts, sym.name), sym.facts.patternLists.getValue(sym.name), ctx, null, owner, item.loc)
                is RouterUrlsSym -> router(sym, ctx, null, owner, item.loc)
                else -> diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(owner, item.loc), item.ref)
            }
        }

        private fun enter(
            facts: DjangoFacts, key: String, items: List<ListItem>, ctx: Ctx, namespace: String?, at: DjangoFacts, loc: Loc,
        ) {
            if (key in ctx.chain || ctx.depth >= Limits.MAX_URLCONF_DEPTH) {
                diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(at, loc), key)
                return
            }
            val next = Ctx(ctx.prefix, ctx.params, ctx.problems, ctx.namespaces + listOfNotNull(namespace), ctx.chain + key, ctx.depth + 1)
            expand(facts, items, next)
        }

        private fun route(owner: DjangoFacts, item: RouteItem, ctx: Ctx) {
            val source = source(owner, item.loc)
            val isInclude = item.target is IncludeTarget
            // Correct or absent: a route whose text is not fully known is left out and reported, never listed approximately.
            if (!item.route.isExact) {
                val code = if (isInclude) DiagnosticCode.DYNAMIC_PREFIX else item.route.reason ?: DiagnosticCode.DYNAMIC_VALUE
                diagnostics += warning(code, source, item.route.text)
                return
            }
            val piece = when (item.kind) {
                RouteKind.PATH -> RouteText.fromPath(item.route.text)
                RouteKind.REGEX -> RouteText.fromRegex(item.route.text)
            }
            if (piece.unsupported) {
                diagnostics += warning(DiagnosticCode.UNSUPPORTED_REGEX, source, item.route.text)
                return
            }
            val next = ctx.with(prefix = ctx.prefix + piece.text, params = ctx.params + piece.params)
            when (val target = item.target) {
                is ViewTarget -> view(owner, item, target, next)
                is IncludeTarget -> include(owner, item, target, next)
            }
        }

        private fun include(owner: DjangoFacts, item: RouteItem, target: IncludeTarget, ctx: Ctx) {
            fun namespaceOf(module: DjangoFacts?): String? =
                (target.namespace ?: target.appName ?: module?.appName)?.text?.takeIf { it.isNotEmpty() }

            fun enterModule(module: DjangoFacts) {
                val items = module.patternLists[DjangoCatalog.URLPATTERNS]
                if (items == null) diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(owner, item.loc), module.module)
                else enter(module, listKey(module, DjangoCatalog.URLPATTERNS), items, ctx, namespaceOf(module), owner, item.loc)
            }

            when (val spec = target.spec) {
                is ModuleSpec -> {
                    val module = (spec.name as? StrValue.Known)?.let { findModule(it.text) }
                    if (module == null) diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(owner, item.loc), spec.name.text)
                    else enterModule(module)
                }
                is InlineSpec -> enter(owner, "${owner.path}#inline@${item.loc.offset}", spec.items, ctx, namespaceOf(null), owner, item.loc)
                is RefSpec -> when (val sym = lookup(owner, spec.ref)) {
                    is ListSym -> enter(sym.facts, listKey(sym.facts, sym.name), sym.facts.patternLists.getValue(sym.name), ctx, namespaceOf(null), owner, item.loc)
                    is ModuleSym -> enterModule(sym.facts)
                    is RouterUrlsSym -> router(sym, ctx, namespaceOf(null), owner, item.loc)
                    is ExternalSym ->
                        if (sym.dotted.endsWith(DjangoCatalog.ADMIN_SITE_URLS_SUFFIX)) {
                            mount(owner, item, ctx, sym.dotted)
                        } else {
                            diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(owner, item.loc), spec.ref)
                        }
                    else -> diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(owner, item.loc), spec.ref)
                }
                is OpaqueSpec -> diagnostics += warning(DiagnosticCode.UNRESOLVED_INCLUDE, source(owner, item.loc), spec.text)
            }
        }

        /** An opaque sub-application such as the admin site: one entry at its mount point, never expanded. */
        private fun mount(owner: DjangoFacts, item: RouteItem, ctx: Ctx, dotted: String) =
            add(HttpMethod.ANY, ctx.prefix, Framework.DJANGO, handlerOf(dotted), owner.path, item.loc, routeName(owner, item, ctx), ctx.params)

        // --- Views ---------------------------------------------------------------------------------------------------

        private fun view(owner: DjangoFacts, item: RouteItem, target: ViewTarget, ctx: Ctx) {
            val name = routeName(owner, item, ctx)
            fun emit(method: HttpMethod, framework: Framework, handler: Handler) =
                add(method, ctx.prefix, framework, handler, owner.path, item.loc, name, ctx.params)

            fun omit(code: DiagnosticCode) {
                diagnostics += warning(code, source(owner, item.loc), target.ref.orEmpty())
            }

            val ref = target.ref
            if (ref == null) {
                omit(DiagnosticCode.UNRESOLVED_VIEW)
                return
            }
            when (val sym = lookup(owner, ref)) {
                is ClassSym -> classView(sym.facts, sym.cls, target, ::emit, ::omit)
                is FunctionSym -> {
                    val fact = sym.fact
                    val methods = fact?.methods?.mapNotNull { HttpMethod.parse(it) }?.distinct().orEmpty().ifEmpty { listOf(HttpMethod.ANY) }
                    if (fact?.dynamicMethods == true) omit(DiagnosticCode.DYNAMIC_VALUE)
                    else methods.forEach { emit(it, if (fact?.isApiView == true) Framework.DRF else Framework.DJANGO, handlerOf("${sym.facts.module}.${sym.name}")) }
                }
                is ExternalSym ->
                    if (target.asView) externalClassView(sym.dotted, target, ::emit, ::omit)
                    else emit(HttpMethod.ANY, Framework.DJANGO, handlerOf(sym.dotted))
                else -> omit(DiagnosticCode.UNRESOLVED_VIEW)
            }
        }

        private fun classView(
            facts: DjangoFacts,
            cls: ClassFact,
            target: ViewTarget,
            emit: (HttpMethod, Framework, Handler) -> Unit,
            omit: (DiagnosticCode) -> Unit,
        ) {
            val info = classInfo(facts, cls)
            val qualified = "${facts.module}.${cls.name}"
            val framework = if (info.isDrf) Framework.DRF else Framework.DJANGO
            val map = target.actionMap
            if (map != null) {
                map.forEach { (verb, action) ->
                    HttpMethod.parse(verb)?.let { emit(it, framework, Handler("${cls.name}.$action", "$qualified.$action")) }
                }
                return
            }
            val verbs = verbsOf(info)
            if (verbs.isEmpty()) omit(DiagnosticCode.UNRESOLVED_VIEW) else verbs.forEach { emit(it, framework, Handler(cls.name, qualified)) }
        }

        private fun externalClassView(
            dotted: String,
            target: ViewTarget,
            emit: (HttpMethod, Framework, Handler) -> Unit,
            omit: (DiagnosticCode) -> Unit,
        ) {
            val info = ClassInfo().also { external(it, dotted) }
            val framework = if (info.isDrf) Framework.DRF else Framework.DJANGO
            val handler = handlerOf(dotted)
            val map = target.actionMap
            if (map != null) {
                map.forEach { (verb, action) ->
                    HttpMethod.parse(verb)?.let { emit(it, framework, Handler("${handler.name}.$action", "$dotted.$action")) }
                }
                return
            }
            val verbs = verbsOf(info)
            if (verbs.isEmpty()) omit(DiagnosticCode.UNRESOLVED_VIEW) else verbs.forEach { emit(it, framework, handler) }
        }

        private fun verbsOf(info: ClassInfo): List<HttpMethod> {
            val defined = info.defined.filter { it in DjangoCatalog.HANDLER_NAMES }.mapNotNull { HttpMethod.parse(it) }
            val allowed = info.httpMethodNames?.toSet()
            return (defined + info.tableMethods).distinct().sorted().filter { allowed == null || it.name.lowercase() in allowed }
        }

        // --- Class analysis ------------------------------------------------------------------------------------------

        private fun classInfo(facts: DjangoFacts, cls: ClassFact): ClassInfo = ClassInfo().also { accumulate(facts, cls, it, HashSet()) }

        private fun accumulate(facts: DjangoFacts, cls: ClassFact, info: ClassInfo, seen: MutableSet<ClassFact>) {
            if (!seen.add(cls)) return
            info.defined += cls.methods
            cls.actions.forEach { info.actions.putIfAbsent(it.methodName, facts to it) }
            if (info.httpMethodNames == null) info.httpMethodNames = cls.httpMethodNames
            if (info.queryset == null) info.queryset = cls.queryset
            if (info.lookupField == null) info.lookupField = cls.lookupField
            if (info.lookupUrlKwarg == null) info.lookupUrlKwarg = cls.lookupUrlKwarg
            cls.bases.forEach { base ->
                when (val sym = lookup(facts, base)) {
                    is ClassSym -> accumulate(sym.facts, sym.cls, info, seen)
                    is ExternalSym -> external(info, sym.dotted)
                    else -> Unit
                }
            }
        }

        /** What a library base class contributes, by name: the library is not available to read. */
        private fun external(info: ClassInfo, dotted: String) {
            val last = dotted.substringAfterLast('.')
            if (dotted.startsWith("${DjangoCatalog.DRF_ROOT}.") || last in DjangoCatalog.DRF_CLASS_NAMES) info.isDrf = true
            DjangoCatalog.DJANGO_VIEW_METHODS[last]?.let { info.tableMethods += it }
            DjangoCatalog.DRF_VIEW_METHODS[last]?.let { info.tableMethods += it }
            DjangoCatalog.VIEWSET_ACTIONS[last]?.let { info.standardActions += it }
            if (last !in DjangoCatalog.DJANGO_VIEW_METHODS && last !in DjangoCatalog.DRF_VIEW_METHODS && last !in DjangoCatalog.VIEWSET_ACTIONS) {
                info.hasUnknownBase = true
            }
        }

        // --- DRF routers ---------------------------------------------------------------------------------------------

        private fun router(sym: RouterUrlsSym, ctx: Ctx, namespace: String?, at: DjangoFacts, loc: Loc) {
            val decl = sym.facts.routers.getValue(sym.name)
            val scoped = Ctx(ctx.prefix, ctx.params, ctx.problems, ctx.namespaces + listOfNotNull(namespace), ctx.chain, ctx.depth)
            registrationsByRouter[routerKey(sym.facts, sym.name)].orEmpty().forEach { (owner, registration) ->
                rows(owner, registration, decl, scoped)
            }
            if (decl.includeRootView) {
                rowEndpoints(
                    ExpandedRow(
                        "", emptyList(), DjangoCatalog.ROOT_VIEW_METHODS.map { it to DjangoCatalog.ROOT_VIEW_HANDLER },
                        DjangoCatalog.ROOT_VIEW_NAME, sym.facts.path, decl.loc,
                    ),
                    decl, scoped,
                )
            }
        }

        private class ExpandedRow(
            val pieceText: String,
            val params: List<PathParam>,
            /** Verb and the qualified handler it maps to. */
            val methods: List<Pair<HttpMethod, String>>,
            val name: String?,
            val ownerPath: String,
            val loc: Loc,
        )

        private fun rowEndpoints(row: ExpandedRow, decl: RouterDecl, ctx: Ctx) {
            val namespaced = row.name?.let { (ctx.namespaces + it).joinToString(":") }
            row.methods.forEach { (method, handlerName) ->
                val path = ctx.prefix + row.pieceText
                val params = ctx.params + row.params
                add(method, path, Framework.DRF, handlerOf(handlerName), row.ownerPath, row.loc, namespaced, params)
                if (options.emitFormatSuffixVariants && decl.includeFormatSuffixes) {
                    add(
                        method, path.trimEnd('/') + ".{${DjangoCatalog.FORMAT_SUFFIX_PARAM}}", Framework.DRF, handlerOf(handlerName),
                        row.ownerPath, row.loc, namespaced, params + PathParam(DjangoCatalog.FORMAT_SUFFIX_PARAM),
                    )
                }
            }
        }

        private fun rows(owner: DjangoFacts, registration: Registration, decl: RouterDecl, ctx: Ctx) {
            val at = source(owner, registration.loc)
            val info: ClassInfo
            val classFacts: DjangoFacts?
            val qualified: String
            when (val sym = lookup(owner, registration.viewset)) {
                is ClassSym -> {
                    info = classInfo(sym.facts, sym.cls)
                    classFacts = sym.facts
                    qualified = "${sym.facts.module}.${sym.cls.name}"
                }
                is ExternalSym -> {
                    info = ClassInfo().also { external(it, sym.dotted) }
                    classFacts = null
                    qualified = sym.dotted
                }
                else -> {
                    diagnostics += warning(DiagnosticCode.UNRESOLVED_VIEW, at, registration.viewset)
                    return
                }
            }
            val standard = info.standardActions + info.defined.filter { it in DjangoCatalog.STANDARD_ACTIONS }
            if (standard.isEmpty() && info.actions.isEmpty() && info.hasUnknownBase) {
                diagnostics += warning(DiagnosticCode.UNRESOLVED_VIEW, at, registration.viewset)
                return
            }
            // The prefix and trailing slash shape every row of this registration, so a doubt about them omits all of it.
            for (value in listOf(registration.prefix, decl.trailingSlash)) {
                if (!value.isExact) {
                    diagnostics += warning(value.reason ?: DiagnosticCode.DYNAMIC_VALUE, at, value.text)
                    return
                }
            }
            val basenameValue = registration.basename ?: info.queryset?.lowercase()?.let { StrValue.Known(it) }
            if (basenameValue != null && !basenameValue.isExact) diagnostics += warning(DiagnosticCode.DYNAMIC_VALUE, at, basenameValue.text)
            val basename = (basenameValue as? StrValue.Known)?.text
            val lookupName = info.lookupUrlKwarg ?: info.lookupField ?: DjangoCatalog.DEFAULT_LOOKUP_FIELD
            val lookupGroup = "(?P<$lookupName>${DjangoCatalog.LOOKUP_VALUE_REGEX})"
            val slash = decl.trailingSlash.text
            val prefix = registration.prefix.text

            /** The row's route as a template, or null (and a diagnostic) when the regular expression is not exactly convertible. */
            fun piece(rest: String, shown: String): RoutePiece? {
                val body = (prefix + rest).let { if (prefix.isEmpty()) it.removePrefix("/") else it }
                val piece = RouteText.fromRegex("^" + body + '$')
                if (piece.unsupported) diagnostics += warning(DiagnosticCode.UNSUPPORTED_REGEX, at, shown)
                return piece.takeUnless { it.unsupported }
            }

            fun standardRow(rest: String, mapping: List<Pair<HttpMethod, String>>, suffix: String) {
                val methods = mapping.filter { it.second in standard }.map { (verb, action) -> verb to "$qualified.$action" }
                if (methods.isEmpty()) return
                val piece = piece(rest, prefix) ?: return
                rowEndpoints(ExpandedRow(piece.text, piece.params, methods, basename?.let { "$it-$suffix" }, owner.path, registration.loc), decl, ctx)
            }

            standardRow(slash, DjangoCatalog.LIST_ROUTE_MAP, "list")
            dynamicRows(info, qualified, false, "", ::piece, basename, decl, ctx, at)
            standardRow("/$lookupGroup$slash", DjangoCatalog.DETAIL_ROUTE_MAP, "detail")
            dynamicRows(info, qualified, true, "/$lookupGroup", ::piece, basename, decl, ctx, at)
        }

        /** `@action` routes; DRF orders them by method name because it collects them with inspect.getmembers. */
        private fun dynamicRows(
            info: ClassInfo,
            qualified: String,
            detail: Boolean,
            lookupPart: String,
            piece: (String, String) -> RoutePiece?,
            basename: String?,
            decl: RouterDecl,
            ctx: Ctx,
            at: SourceRef,
        ) {
            info.actions.entries.sortedBy { it.key }.forEach { (_, owned) ->
                val (actionFacts, action) = owned
                val here = SourceRef(actionFacts.path, action.loc.offset, action.loc.line.coerceAtLeast(1))
                if (action.detail == null || !action.urlPath.isExact || action.dynamicMethods) {
                    if (action.detail == null || action.detail == detail) diagnostics += warning(DiagnosticCode.UNRESOLVED_VIEW, here, action.methodName)
                    return@forEach
                }
                if (action.detail != detail) return@forEach
                val rendered = piece("$lookupPart/${action.urlPath.text}${decl.trailingSlash.text}", action.urlPath.text) ?: return@forEach
                val name = (action.urlName as? StrValue.Known)?.text?.let { urlName -> basename?.let { "$it-$urlName" } }
                val methods = action.methods.mapNotNull { HttpMethod.parse(it) }.map { it to "$qualified.${action.methodName}" }
                rowEndpoints(ExpandedRow(rendered.text, rendered.params, methods, name, actionFacts.path, action.loc), decl, ctx)
            }
        }

        // --- Reference resolution ------------------------------------------------------------------------------------

        private fun findModule(name: String): DjangoFacts? = byModule[name] ?: bySuffix[name]?.minByOrNull { it.module.length }

        private fun lookup(facts: DjangoFacts, ref: String, depth: Int = 0): Sym {
            val head = ref.substringBefore('.')
            val origin = facts.imports[head] ?: return local(facts, ref)
            return resolveDotted(origin + ref.substring(head.length), depth)
        }

        private fun local(facts: DjangoFacts, ref: String): Sym {
            val parts = ref.split('.')
            return when {
                parts.size == 1 -> member(facts, parts[0]) ?: FunctionSym(facts, parts[0], null)
                parts.size == 2 && parts[1] == DjangoCatalog.ROUTER_URLS && parts[0] in facts.routers -> RouterUrlsSym(facts, parts[0])
                else -> ExternalSym("${facts.module}.$ref")
            }
        }

        private fun member(facts: DjangoFacts, name: String): Sym? = when {
            name in facts.patternLists -> ListSym(facts, name)
            name in facts.routers -> RouterSym(facts, name)
            else -> facts.classes[name]?.let { ClassSym(facts, it) } ?: facts.functions[name]?.let { FunctionSym(facts, name, it) }
        }

        private fun resolveDotted(dotted: String, depth: Int): Sym {
            if (depth > Limits.MAX_URLCONF_DEPTH) return ExternalSym(dotted)
            val parts = dotted.split('.')
            for (length in parts.size downTo 1) {
                val module = findModule(parts.subList(0, length).joinToString(".")) ?: continue
                val rest = parts.drop(length)
                if (rest.isEmpty()) return ModuleSym(module)
                val first = rest[0]
                val found: Sym? = when {
                    first in module.imports -> lookup(module, rest.joinToString("."), depth + 1).takeIf { it !is ExternalSym }
                    rest.size == 2 && rest[1] == DjangoCatalog.ROUTER_URLS && first in module.routers -> RouterUrlsSym(module, first)
                    rest.size == 1 -> member(module, first)
                    else -> null
                }
                if (found != null) return found
            }
            return ExternalSym(dotted)
        }

        // --- Small helpers -------------------------------------------------------------------------------------------

        private fun routerKey(facts: DjangoFacts, name: String) = "${facts.path}#$name"

        private fun listKey(facts: DjangoFacts, name: String) = "${facts.path}#list:$name"

        private fun source(facts: DjangoFacts, loc: Loc) = SourceRef(facts.path, loc.offset, loc.line.coerceAtLeast(1))

        private fun warning(code: DiagnosticCode, source: SourceRef?, vararg args: String) =
            Diagnostic(Severity.WARNING, code, args.toList(), source)

        private fun handlerOf(qualified: String) = Handler(qualified.substringAfterLast('.'), qualified)

        /** `shop:detail`: instance namespaces joined with the route name; null when the route has no (exactly known) name. */
        private fun routeName(owner: DjangoFacts, item: RouteItem, ctx: Ctx): String? {
            val name = item.name ?: return null
            if (name !is StrValue.Known) {
                diagnostics += warning(DiagnosticCode.DYNAMIC_VALUE, source(owner, item.loc), name.text)
                return null
            }
            return (ctx.namespaces + name.text).joinToString(":")
        }

        private fun add(
            method: HttpMethod,
            prefixPath: String,
            framework: Framework,
            handler: Handler,
            ownerPath: String,
            loc: Loc,
            routeName: String?,
            params: List<PathParam>,
        ) {
            val path = if (prefixPath.startsWith("/")) prefixPath else "/$prefixPath"
            endpoints += Endpoint(
                method = method,
                path = path,
                framework = framework,
                handler = handler,
                source = SourceRef(ownerPath, loc.offset, loc.line.coerceAtLeast(1)),
                routeName = routeName,
                pathParams = params,
            )
        }
    }
}
