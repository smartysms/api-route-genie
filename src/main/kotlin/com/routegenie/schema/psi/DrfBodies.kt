package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyTargetExpression
import com.routegenie.frameworks.django.DjangoCatalog
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.BodyEncoding
import com.routegenie.schema.model.Schema

/**
 * The serializer a Django REST Framework view uses for the request body of one endpoint. Resolution order: an explicit
 * declaration (`@extend_schema(request=...)`, `@action(serializer_class=...)`), the serializer the method itself builds from
 * `request.data`, then the view's `get_serializer_class()` (only when it is statically decided) or `serializer_class`.
 * A view that reads `request.data` with no serializer, or whose serializer depends on runtime values, has no body.
 */
internal class DrfBodies(private val mapper: TypeMapper) {
    private val session = mapper.session
    private val symbols = session.symbols
    private val scan = ViewScan(mapper)

    fun extract(target: Target, verb: HttpMethod): BodyResult {
        return when (target) {
            is Target.Function -> fromFunction(target.fn, verb)
            is Target.Member -> fromView(target, verb)
        }
    }

    // --- Function views (@api_view) -----------------------------------------------------------------------------------------

    private fun fromFunction(function: PyFunction, verb: HttpMethod): BodyResult {
        declared(function)?.let { return it.toResult(verb, function) }
        val decision = scan.decide(scan.uses(function, DrfSchemaCatalog.METHOD_GET_SERIALIZER, BODY_ATTRIBUTES, ::isSerializer))
        if (decision.ambiguous) return BodyResult.Unsupported(Gap.AMBIGUOUS, "several serializers read request.data in ${function.name}")
        val use = decision.use ?: return BodyResult.NoBody
        val cls = use.cls ?: return BodyResult.NoBody
        return serializerBody(cls, partial = use.partial)
    }

    // --- Class based views and viewsets -------------------------------------------------------------------------------------

    private fun fromView(target: Target.Member, verb: HttpMethod): BodyResult {
        val cls = target.cls
        val libs = mapper.hierarchy.info(cls).libraryBases
        val action = target.member ?: standardAction(libs, verb)
        val handler = target.method ?: projectHandler(cls, target.member, verb)
        handler?.let { function ->
            declared(function)?.let { return it.toResult(verb, function) }
            classDeclared(cls, listOfNotNull(target.member, verb.name.lowercase()))?.let { return it.toResult(verb, function) }
            val actionKeyword = actionSerializer(function)
            if (actionKeyword != null) return actionKeyword.let { serializerBody(it, partial = action == PARTIAL_UPDATE) }
            val decision = scan.decide(scan.uses(function, DrfSchemaCatalog.METHOD_GET_SERIALIZER, BODY_ATTRIBUTES, ::isSerializer))
            if (decision.ambiguous) return BodyResult.Unsupported(Gap.AMBIGUOUS, "several serializers read request.data in ${function.name}")
            val use = decision.use
            if (use != null) {
                val serializer = use.cls ?: viewSerializer(cls, action, verb) ?: return unresolvedViewSerializer(cls)
                return serializerBody(serializer, use.partial || action == PARTIAL_UPDATE)
            }
            // A method that does not hand request.data to a serializer is a handler the code declares no schema for, unless the
            // framework's own implementation of the standard action is still the one in use.
            return if (calledSuper(function)) libraryAction(cls, libs, action, verb) else BodyResult.NoBody
        }
        classDeclared(cls, listOfNotNull(target.member, verb.name.lowercase()))?.let { return it.toResult(verb, null) }
        return libraryAction(cls, libs, action, verb)
    }

    /** The standard action a library view provides for [verb]: `create` for POST and so on; null when it provides none. */
    private fun libraryAction(cls: PyClass, libs: Set<String>, action: String?, verb: HttpMethod): BodyResult {
        if (action == null || action !in BODY_ACTIONS) return BodyResult.NoBody
        if (!providesAction(libs, action, verb)) return BodyResult.NoBody
        val serializer = viewSerializer(cls, action, verb) ?: return unresolvedViewSerializer(cls)
        return serializerBody(serializer, partial = action == PARTIAL_UPDATE)
    }

    private fun unresolvedViewSerializer(cls: PyClass): BodyResult = BodyResult.Unsupported(Gap.UNRESOLVED_SOURCE, "serializer of ${cls.name} is not statically known")

    private fun providesAction(libs: Set<String>, action: String, verb: HttpMethod): Boolean {
        val simple = libs.map { it.substringAfterLast('.') }
        if (simple.any { action in DjangoCatalog.VIEWSET_ACTIONS[it].orEmpty() }) return true
        return simple.any { name -> DjangoCatalog.DRF_VIEW_METHODS[name].orEmpty().contains(verb) }
    }

    private fun standardAction(libs: Set<String>, verb: HttpMethod): String? {
        val simple = libs.map { it.substringAfterLast('.') }
        if (simple.none { it in DjangoCatalog.DRF_VIEW_METHODS }) return null
        return when (verb) {
            HttpMethod.POST -> DjangoCatalog.ACTION_CREATE
            HttpMethod.PUT -> DjangoCatalog.ACTION_UPDATE
            HttpMethod.PATCH -> PARTIAL_UPDATE
            else -> null
        }
    }

    /** The project's own method for this endpoint: the verb on an APIView (`post`), or an override of the generic view's action. */
    private fun projectHandler(cls: PyClass, member: String?, verb: HttpMethod): PyFunction? {
        val names = listOfNotNull(member, verb.name.lowercase(), standardAction(mapper.hierarchy.info(cls).libraryBases, verb))
        return names.firstNotNullOfOrNull { mapper.hierarchy.findMethod(cls, it) }
    }

    private fun calledSuper(function: PyFunction): Boolean = function.text.contains(SUPER_CALL)

    // --- Where the serializer is named ----------------------------------------------------------------------------------------

    /** `@action(serializer_class=X)` on the method. */
    private fun actionSerializer(function: PyFunction): PyClass? {
        val file = function.containingFile as? PyFile ?: return null
        for (decorator in function.decoratorList?.decorators.orEmpty()) {
            if (symbols.libName(decorator.callee, file) != DrfSchemaCatalog.actionDecorator) continue
            val expression = PyLiterals.keyword(decorator, DrfSchemaCatalog.ATTR_SERIALIZER_CLASS) ?: continue
            return (symbols.resolve(expression, file) as? Sym.Cls)?.cls?.takeIf(::isSerializer)
        }
        return null
    }

    /** The serializer the view class names for an action: `get_serializer_class()` when it is decided, else `serializer_class`. */
    private fun viewSerializer(cls: PyClass, action: String?, verb: HttpMethod): PyClass? {
        mapper.hierarchy.findMethod(cls, DrfSchemaCatalog.METHOD_GET_SERIALIZER_CLASS)?.let { method ->
            when (val returned = scan.returned(method, action, verb)) {
                is ViewScan.Returned.Class -> return returned.cls.takeIf(::isSerializer)
                ViewScan.Returned.Super, null -> Unit
                ViewScan.Returned.Unknown -> return null
            }
        }
        for (c in mapper.hierarchy.baseFirst(cls).asReversed()) {
            val file = c.containingFile as? PyFile ?: continue
            for (statement in c.statementList.statements) {
                val assignment = statement as? PyAssignmentStatement ?: continue
                if ((assignment.targets.singleOrNull() as? PyTargetExpression)?.name != DrfSchemaCatalog.ATTR_SERIALIZER_CLASS) continue
                return (symbols.resolve(assignment.assignedValue, file) as? Sym.Cls)?.cls?.takeIf(::isSerializer)
            }
        }
        return null
    }

    // --- drf-spectacular declarations ----------------------------------------------------------------------------------------

    private sealed interface Declared {
        class Request(val cls: PyClass, val many: Boolean) : Declared
        data object None : Declared
        data object Unreadable : Declared
    }

    private fun Declared.toResult(verb: HttpMethod, function: PyFunction?): BodyResult = when (this) {
        is Declared.Request -> serializerBody(cls, partial = verb == HttpMethod.PATCH, many = many)
        Declared.None -> BodyResult.NoBody
        Declared.Unreadable -> BodyResult.Unsupported(Gap.UNSUPPORTED_BODY, "extend_schema request of ${function?.name ?: "the view"}")
    }

    private fun declared(function: PyFunction): Declared? {
        val file = function.containingFile as? PyFile ?: return null
        for (decorator in function.decoratorList?.decorators.orEmpty()) {
            if (symbols.libName(decorator.callee, file) in DrfSchemaCatalog.extendSchema) return request(decorator, file)
        }
        return null
    }

    /** `@extend_schema_view(create=extend_schema(request=X))` on the class: the entry for one of [names]. */
    private fun classDeclared(cls: PyClass, names: List<String>): Declared? {
        val file = cls.containingFile as? PyFile ?: return null
        for (decorator in cls.decoratorList?.decorators.orEmpty()) {
            if (symbols.libName(decorator.callee, file) !in DrfSchemaCatalog.extendSchemaView) continue
            for (name in names) {
                val entry = PyLiterals.unparen(PyLiterals.keyword(decorator, name)) as? PyCallExpression ?: continue
                if (symbols.libName(entry.callee, file) in DrfSchemaCatalog.extendSchema) return request(entry, file)
            }
        }
        return null
    }

    private fun request(call: PyCallExpression, file: PyFile): Declared? {
        val expression = PyLiterals.keyword(call, DrfSchemaCatalog.KEY_REQUEST) ?: return null
        if (PyLiterals.isNone(expression)) return Declared.None
        return serializerOf(expression, file) ?: Declared.Unreadable
    }

    private fun serializerOf(expression: PyExpression, file: PyFile): Declared.Request? {
        val e = PyLiterals.unparen(expression)
        PyLiterals.elements(e)?.singleOrNull()?.let { return serializerOf(it, file)?.let { inner -> Declared.Request(inner.cls, true) } }
        if (e is PyCallExpression) {
            val cls = (symbols.resolve(e.callee, file) as? Sym.Cls)?.cls?.takeIf(::isSerializer) ?: return null
            return Declared.Request(cls, PyLiterals.bool(PyLiterals.keyword(e, "many")) == true)
        }
        val cls = (symbols.resolve(e, file) as? Sym.Cls)?.cls?.takeIf(::isSerializer) ?: return null
        return Declared.Request(cls, false)
    }

    // --- The schema ---------------------------------------------------------------------------------------------------------------

    private fun isSerializer(cls: PyClass) = mapper.hierarchy.inherits(cls, DrfSchemaCatalog.serializerBases)

    private fun serializerBody(cls: PyClass, partial: Boolean, many: Boolean = false): BodyResult {
        if (mapper.hierarchy.inherits(cls, DrfSchemaCatalog.unsupportedSerializers) && !isSerializer(cls)) {
            return BodyResult.Unsupported(Gap.UNSUPPORTED_BODY, "${cls.name} converts data by custom code")
        }
        val serializers = DrfSerializers(mapper, partial)
        val ref = serializers.ref(cls)
        if (serializers.sawFile) return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "${cls.name} has a file field")
        serializers.undetermined?.let { return BodyResult.Unsupported(Gap.NO_SCHEMA_DECLARED, it) }
        return BodyResult.Found(BodyEncoding.JSON, if (many) Schema.arrayOf(ref) else ref)
    }

    private companion object {
        val BODY_ATTRIBUTES = setOf(DrfSchemaCatalog.REQUEST_DATA)
        const val PARTIAL_UPDATE = DjangoCatalog.ACTION_PARTIAL_UPDATE
        const val SUPER_CALL = "super("
        val BODY_ACTIONS = setOf(DjangoCatalog.ACTION_CREATE, DjangoCatalog.ACTION_UPDATE, PARTIAL_UPDATE)
    }
}
