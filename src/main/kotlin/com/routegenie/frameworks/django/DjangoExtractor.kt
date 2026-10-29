package com.routegenie.frameworks.django

import com.intellij.psi.PsiElement
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyBoolLiteralExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyExpressionStatement
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStarArgument
import com.jetbrains.python.psi.PyStarExpression
import com.jetbrains.python.psi.PyStatement
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.routegenie.Limits
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.Lit
import com.routegenie.frameworks.common.PyExprBuilder
import com.routegenie.model.HttpMethod

/**
 * One pass over a Python file that produces [DjangoFacts]. Framework objects are recognized by the import origin written
 * in the file (`from django.urls import path`), never by resolve(), so it works without an interpreter and stays cheap.
 * Strings stay unevaluated [Expr] trees and imports stay as written: nothing here looks at another file or at where this
 * file lives, so the result depends on the text alone.
 */
internal class DjangoExtractor(private val file: PyFile) {
    private val lineStarts = lineStarts(file.viewProvider.contents)

    /** Local name to the dotted thing it stands for as written; relative imports keep their leading dots and never match a root. */
    private val imports = LinkedHashMap<String, String>()
    private val starRoots = HashSet<String>()
    private val lists = LinkedHashMap<String, List<ListItem>>()
    private val routers = LinkedHashMap<String, RouterDecl>()
    private val registrations = ArrayList<Registration>()
    private val functions = LinkedHashMap<String, FunctionFact>()
    private val classes = LinkedHashMap<String, ClassFact>()
    private var rootUrlconf: Expr? = null
    private var appName: Expr? = null

    fun extract(): DjangoFacts {
        val table = PyExprBuilder.importsOf(file, recursive = false)
        table.bindings.forEach { imports[it.local] = originOf(it) }
        table.stars.filter { it.level == 0 }.forEach { starRoots += it.module.substringBefore('.') }
        val statements = file.statements
        statements.forEach { visit(it) }
        return DjangoFacts(
            imports = table.bindings,
            stars = table.stars,
            consts = PyExprBuilder.constsOf(file),
            rootUrlconf = rootUrlconf,
            appName = appName,
            patternLists = lists.toMap(),
            routers = routers.toMap(),
            registrations = registrations.toList(),
            functions = functions.toMap(),
            classes = classes.toMap(),
        )
    }

    // --- Imports -----------------------------------------------------------------------------------------------------

    private fun originOf(binding: ImportBinding): String {
        binding.dotted?.let { return if (binding.aliased) it else binding.local }
        val name = binding.name.orEmpty()
        val source = RELATIVE_DOT.repeat(binding.level) + binding.module
        return if (source.isEmpty()) name else if (source.endsWith(RELATIVE_DOT)) source + name else "$source.$name"
    }

    /** True when [ref] (as written) stands for something imported from [root], including a bare name from a star import. */
    private fun isFrom(ref: String, root: String): Boolean {
        val head = ref.substringBefore('.')
        val origin = imports[head] ?: return '.' !in ref && root in starRoots
        return origin == root || origin.startsWith("$root.")
    }

    /** [ref] with its first segment replaced by what it was imported as, so `p` from `import path as p` reads as `django.urls.path`. */
    private fun canonical(ref: String): String {
        val head = ref.substringBefore('.')
        return imports[head]?.let { it + ref.substring(head.length) } ?: ref
    }

    private fun lastName(ref: String) = canonical(ref).substringAfterLast('.')

    // --- Statements --------------------------------------------------------------------------------------------------

    private fun visit(statement: PyStatement) {
        when (statement) {
            is PyAssignmentStatement -> assignment(statement)
            is PyAugAssignmentStatement -> augmented(statement)
            is PyExpressionStatement -> (statement.expression as? PyCallExpression)?.let { callStatement(it) }
            is PyClass -> classFact(statement)?.let { classes[statement.name ?: return] = it }
            is PyFunction -> functionFact(statement)?.let { functions[statement.name ?: return] = it }
            is PyIfStatement -> {
                val parts = listOf(statement.ifPart) + statement.elifParts.toList()
                parts.forEach { part -> part.statementList.statements.forEach { visit(it) } }
                statement.elsePart?.statementList?.statements?.forEach { visit(it) }
            }
        }
    }

    private fun assignment(statement: PyAssignmentStatement) {
        val name = (statement.targets.firstOrNull() as? PyTargetExpression)?.name ?: return
        val value = statement.assignedValue ?: return
        when {
            name == DjangoCatalog.SETTING_ROOT_URLCONF -> rootUrlconf = PyExprBuilder.build(value)
            name == DjangoCatalog.APP_NAME -> appName = PyExprBuilder.build(value)
            routerDecl(value)?.also { routers[name] = it } != null -> Unit
            looksLikePatterns(value) -> {
                val items = ArrayList<ListItem>()
                collect(value, items)
                val previous = lists[name].orEmpty()
                val merged = items.flatMap { if (it is SpliceItem && it.ref == name) previous else listOf(it) }
                if (name == DjangoCatalog.URLPATTERNS || merged.any { it is RouteItem }) lists[name] = merged
            }
        }
    }

    private fun augmented(statement: PyAugAssignmentStatement) {
        val name = (statement.target as? PyReferenceExpression)?.name ?: return
        if (statement.operation?.text != "+=") return
        val items = ArrayList<ListItem>()
        collect(statement.value, items)
        if (name == DjangoCatalog.URLPATTERNS || name in lists) lists[name] = lists[name].orEmpty() + items
    }

    private fun callStatement(call: PyCallExpression) {
        val ref = calleeRef(call) ?: return
        val primitive = primitiveOf(ref) ?: return
        val head = ref.substringBeforeLast('.', "")
        when (primitive.kind) {
            DjangoCatalog.Kind.LIST_MUTATOR -> if (head in lists) {
                val items = ArrayList<ListItem>()
                collect(argOf(call, primitive.view), items)
                lists[head] = lists.getValue(head) + items
            }
            DjangoCatalog.Kind.REGISTER_METHOD -> if (head.isNotEmpty()) registration(call, head, primitive)
            else -> Unit
        }
    }

    private fun registration(call: PyCallExpression, router: String, primitive: DjangoCatalog.Primitive) {
        val prefix = argOf(call, primitive.prefix) ?: return
        val viewset = refText(argOf(call, primitive.view)) ?: return
        val knownRouter = router in routers || router.substringBefore('.') in routers
        val importedRouter = imports[router.substringBefore('.')]?.let { !isDjangoOwned(it) } == true
        // admin.site.register(Model, ModelAdmin) has the same shape; a string prefix is what tells a router apart.
        if (!knownRouter && !(importedRouter && prefix is PyStringLiteralExpression)) return
        registrations += Registration(
            router = router,
            prefix = PyExprBuilder.build(prefix),
            viewset = viewset,
            basename = argOf(call, primitive.basename)?.let { PyExprBuilder.build(it) },
            loc = loc(call),
        )
    }

    private fun isDjangoOwned(origin: String) = origin == DjangoCatalog.DJANGO_ROOT || origin.startsWith("${DjangoCatalog.DJANGO_ROOT}.")

    // --- URL pattern lists -------------------------------------------------------------------------------------------

    private fun looksLikePatterns(value: PyExpression): Boolean = when (value) {
        is PyListLiteralExpression, is PyTupleExpression, is PyBinaryExpression, is PyParenthesizedExpression -> true
        is PyCallExpression -> calleeRef(value)?.let { primitiveOf(it)?.kind == DjangoCatalog.Kind.UNWRAP } == true
        is PyReferenceExpression -> refText(value)?.endsWith(".${DjangoCatalog.ROUTER_URLS}") == true
        else -> false
    }

    private fun collect(expression: PyExpression?, out: MutableList<ListItem>) {
        when (expression) {
            null -> Unit
            is PyParenthesizedExpression -> collect(expression.containedExpression, out)
            is PyListLiteralExpression -> expression.elements.forEach { collect(it, out) }
            is PyTupleExpression -> expression.elements.forEach { collect(it, out) }
            is PyStarExpression -> collect(expression.expression, out)
            is PyBinaryExpression ->
                if (expression.isOperator("+")) {
                    collect(expression.leftExpression, out)
                    collect(expression.rightExpression, out)
                } else {
                    out += opaque(expression)
                }
            is PyReferenceExpression -> out += refText(expression)?.let { SpliceItem(it, loc(expression)) } ?: opaque(expression)
            is PyCallExpression -> collectCall(expression, out)
            else -> out += opaque(expression)
        }
    }

    private fun collectCall(call: PyCallExpression, out: MutableList<ListItem>) {
        val primitive = calleeRef(call)?.let { primitiveOf(it) }
        when (primitive?.kind) {
            DjangoCatalog.Kind.ROUTE_PATH -> out += routeItem(RouteKind.PATH, call, primitive)
            DjangoCatalog.Kind.ROUTE_REGEX -> out += routeItem(RouteKind.REGEX, call, primitive)
            DjangoCatalog.Kind.UNWRAP -> call.positional().forEach { collect(it, out) }
            DjangoCatalog.Kind.IGNORED -> Unit
            else -> out += opaque(call)
        }
    }

    private fun opaque(expression: PsiElement) = OpaqueItem(expression.text.take(Limits.MAX_QUOTED_SOURCE_CHARS), loc(expression))

    private fun routeItem(kind: RouteKind, call: PyCallExpression, primitive: DjangoCatalog.Primitive) = RouteItem(
        kind = kind,
        route = PyExprBuilder.build(argOf(call, primitive.route)),
        target = target(argOf(call, primitive.view)),
        name = argOf(call, primitive.routeName)?.let { PyExprBuilder.build(it) },
        loc = loc(call),
    )

    private fun target(view: PyExpression?): Target {
        if (view is PyCallExpression) {
            val ref = calleeRef(view)
            val primitive = ref?.let { primitiveOf(it) }
            return when (primitive?.kind) {
                DjangoCatalog.Kind.INCLUDE -> includeTarget(view, primitive)
                DjangoCatalog.Kind.AS_VIEW ->
                    ViewTarget(ref.substringBeforeLast('.', "").ifEmpty { null }, asView = true, actionMap = actionMap(view, primitive))
                else -> ViewTarget(null, asView = false)
            }
        }
        return ViewTarget(refText(view), asView = false)
    }

    private fun actionMap(asViewCall: PyCallExpression, primitive: DjangoCatalog.Primitive): List<Pair<Expr, Expr>>? {
        val dict = argOf(asViewCall, primitive.view) as? PyDictLiteralExpression ?: return null
        return dict.elements.map { entry -> PyExprBuilder.build(entry.key) to PyExprBuilder.build(entry.value) }
    }

    private fun includeTarget(call: PyCallExpression, primitive: DjangoCatalog.Primitive): IncludeTarget {
        val argument = argOf(call, primitive.view)
        var appName: Expr? = null
        var spec = specOf(argument)
        val tuple = (argument as? PyParenthesizedExpression)?.containedExpression ?: argument
        if (tuple is PyTupleExpression && tuple.elements.size == 2) {
            spec = specOf(tuple.elements[0])
            appName = PyExprBuilder.build(tuple.elements[1])
        }
        return IncludeTarget(spec, appName, argOf(call, primitive.namespace)?.let { PyExprBuilder.build(it) })
    }

    private fun specOf(expression: PyExpression?): IncludeSpec = when (expression) {
        null -> OpaqueSpec("")
        is PyListLiteralExpression -> InlineSpec(ArrayList<ListItem>().also { collect(expression, it) })
        is PyReferenceExpression -> refText(expression)?.let { RefSpec(it) } ?: OpaqueSpec(expression.text.take(Limits.MAX_QUOTED_SOURCE_CHARS))
        is PyStringLiteralExpression -> ModuleSpec(PyExprBuilder.build(expression))
        is PyCallExpression, is PyBinaryExpression -> ModuleSpec(PyExprBuilder.build(expression))
        else -> OpaqueSpec(expression.text.take(Limits.MAX_QUOTED_SOURCE_CHARS))
    }

    // --- DRF routers -------------------------------------------------------------------------------------------------

    private fun routerDecl(value: PyExpression): RouterDecl? {
        val call = value as? PyCallExpression ?: return null
        val primitive = calleeRef(call)?.let { primitiveOf(it) }?.takeIf { it.kind == DjangoCatalog.Kind.ROUTER_CLASS } ?: return null
        fun option(role: String) = primitive.options[role]?.let { call.getKeywordArgument(it) }
        val trailing: Expr = when (val expr = option(DjangoCatalog.OPT_TRAILING_SLASH)) {
            null -> Lit(DjangoCatalog.DEFAULT_TRAILING_SLASH)
            is PyBoolLiteralExpression -> Lit(if (expr.value) DjangoCatalog.DEFAULT_TRAILING_SLASH else "")
            else -> PyExprBuilder.build(expr)
        }
        // Only routers that list the option support it; for them it is on unless switched off with a literal.
        fun flag(role: String) = role in primitive.options && ((option(role) as? PyBoolLiteralExpression)?.value ?: true)
        return RouterDecl(
            kind = primitive.name,
            trailingSlash = trailing,
            includeRootView = flag(DjangoCatalog.OPT_ROOT_VIEW),
            includeFormatSuffixes = flag(DjangoCatalog.OPT_FORMAT_SUFFIXES),
            loc = loc(call),
        )
    }

    // --- Views -------------------------------------------------------------------------------------------------------

    private fun functionFact(function: PyFunction): FunctionFact? {
        val name = function.name ?: return null
        val steps = ArrayList<MethodStep>()
        var api = false
        for (decorator in function.decoratorList?.decorators.orEmpty()) {
            val primitive = refText(decorator.callee)?.let { primitiveOf(it) }
                ?.takeIf { it.kind == DjangoCatalog.Kind.METHODS_DECORATOR } ?: continue
            api = api || primitive.apiView
            val fixed = primitive.fixedMethods
            val argument = argOf(decorator, primitive.methods)
            steps += when {
                fixed != null -> FixedMethods(fixed.map { it.name })
                argument == null && primitive.defaultMethods != null -> DefaultMethods(primitive.defaultMethods.map { it.name })
                argument == null -> DynamicMethods
                else -> methodList(argument)?.let { ListedMethods(it) } ?: DynamicMethods
            }
        }
        if (!api && steps.isEmpty()) return null
        return FunctionFact(name, steps, api, loc(function))
    }

    /** The elements of a list or tuple literal, unevaluated; null for other shapes. */
    private fun methodList(expression: PyExpression): List<Expr>? {
        val elements = when (expression) {
            is PyListLiteralExpression -> expression.elements
            is PyTupleExpression -> expression.elements
            is PyParenthesizedExpression -> return expression.containedExpression?.let { methodList(it) }
            else -> return null
        }
        return elements.map { PyExprBuilder.build(it) }
    }

    private fun classFact(cls: PyClass): ClassFact? {
        val name = cls.name ?: return null
        val bases = cls.superClassExpressions.mapNotNull { refText(it) }
        if (bases.isEmpty()) return null
        var httpMethodExprs: List<Expr>? = null
        var queryset: String? = null
        var lookupField: Expr? = null
        var lookupUrlKwarg: Expr? = null
        cls.statementList.statements.filterIsInstance<PyAssignmentStatement>().forEach { statement ->
            val attribute = (statement.targets.firstOrNull() as? PyTargetExpression)?.name ?: return@forEach
            val value = statement.assignedValue ?: return@forEach
            when (attribute) {
                DjangoCatalog.ATTR_HTTP_METHOD_NAMES -> httpMethodExprs = methodList(value)
                DjangoCatalog.ATTR_QUERYSET -> queryset = modelOfQueryset(value)
                DjangoCatalog.ATTR_LOOKUP_FIELD -> lookupField = PyExprBuilder.build(value)
                DjangoCatalog.ATTR_LOOKUP_URL_KWARG -> lookupUrlKwarg = PyExprBuilder.build(value)
            }
        }
        val methods = cls.methods
        return ClassFact(
            name = name,
            bases = bases,
            methods = methods.mapNotNull { it.name }.toSet(),
            httpMethodExprs = httpMethodExprs,
            actions = methods.mapNotNull { actionFact(it) },
            queryset = queryset,
            lookupField = lookupField,
            lookupUrlKwarg = lookupUrlKwarg,
            loc = loc(cls),
        )
    }

    /** `Book.objects.all()` gives `Book`; DRF derives the default basename from the queryset's model. */
    private fun modelOfQueryset(value: PyExpression): String? {
        val ref = (if (value is PyCallExpression) calleeRef(value) else refText(value)) ?: return null
        val parts = ref.split('.')
        return parts.firstOrNull()?.takeIf { parts.getOrNull(1) == DjangoCatalog.QUERYSET_MANAGER }
    }

    private fun actionFact(method: PyFunction): ActionFact? {
        val name = method.name ?: return null
        val (decorator, primitive) = method.decoratorList?.decorators.orEmpty().firstNotNullOfOrNull { d ->
            refText(d.callee)?.let { primitiveOf(it) }?.takeIf { it.kind == DjangoCatalog.Kind.ACTION_DECORATOR }?.let { d to it }
        } ?: return null
        fun option(role: String) = primitive.options[role]?.let { decorator.getKeywordArgument(it) }
        val methodsExpr = argOf(decorator, primitive.methods)
        return ActionFact(
            methodName = name,
            detail = (option(DjangoCatalog.OPT_DETAIL) as? PyBoolLiteralExpression)?.value,
            methodsPresent = methodsExpr != null,
            methodExprs = methodsExpr?.let { methodList(it) },
            defaultMethods = primitive.defaultMethods.orEmpty().map { it.name },
            urlPath = option(DjangoCatalog.OPT_URL_PATH)?.let { PyExprBuilder.build(it) } ?: Lit(name),
            urlName = option(DjangoCatalog.OPT_URL_NAME)?.let { PyExprBuilder.build(it) } ?: Lit(name.replace('_', '-')),
            loc = loc(method),
        )
    }

    // --- Expression helpers ------------------------------------------------------------------------------------------

    private fun refText(expression: PyExpression?): String? =
        (expression as? PyReferenceExpression)?.asQualifiedName()?.let(QualifiedName::toString)

    /** The catalog primitive [ref] denotes, when its import origin matches; methods on objects need a dotted reference. */
    private fun primitiveOf(ref: String): DjangoCatalog.Primitive? {
        val primitive = DjangoCatalog.primitive(lastName(ref)) ?: return null
        val root = primitive.root ?: return primitive.takeIf { '.' in ref }
        return primitive.takeIf { isFrom(ref, root) }
    }

    private fun argOf(call: PyCallExpression, arg: DjangoCatalog.Arg?): PyExpression? {
        if (arg == null) return null
        return arg.keywords.firstNotNullOfOrNull { call.getKeywordArgument(it) } ?: arg.position?.let { call.positional().getOrNull(it) }
    }

    private fun calleeRef(call: PyCallExpression): String? = refText(call.callee)

    private fun PyCallExpression.positional(): List<PyExpression> =
        arguments.filter { it !is PyKeywordArgument && it !is PyStarArgument }

    private fun loc(element: PsiElement): Loc {
        val offset = element.textOffset
        val index = lineStarts.binarySearch(offset)
        val line = if (index >= 0) index + 1 else -index - 1
        return Loc(offset, line.coerceAtLeast(1))
    }

    private companion object {
        const val RELATIVE_DOT = "."

        fun lineStarts(text: CharSequence): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            for (i in text.indices) if (text[i] == '\n') starts += i + 1
            return starts.toIntArray()
        }
    }
}
