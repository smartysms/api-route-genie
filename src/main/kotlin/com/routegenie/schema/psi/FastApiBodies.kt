package com.routegenie.schema.psi

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyNamedParameter
import com.jetbrains.python.psi.PyReferenceExpression
import com.routegenie.Limits
import com.routegenie.schema.model.BodyEncoding
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import com.routegenie.schema.psi.PydanticCatalog.Keys

/** Every fact about FastAPI's parameter declarations that body extraction relies on. */
internal object FastApiBodyCatalog {
    enum class Kind { BODY, QUERY, HEADER, COOKIE, PATH, FORM, FILE, DEPENDS }

    private val roots = listOf("fastapi", "fastapi.param_functions", "fastapi.params")

    val kinds: Map<String, Kind> = buildMap {
        for ((simple, kind) in listOf(
            "Body" to Kind.BODY, "Query" to Kind.QUERY, "Header" to Kind.HEADER, "Cookie" to Kind.COOKIE, "Path" to Kind.PATH,
            "Form" to Kind.FORM, "File" to Kind.FILE, "Depends" to Kind.DEPENDS, "Security" to Kind.DEPENDS,
        )) {
            roots.forEach { this["$it.$simple"] = kind }
        }
    }

    val uploadFile: Set<String> = setOf("fastapi.UploadFile", "fastapi.datastructures.UploadFile", "starlette.datastructures.UploadFile")

    /** Types the framework injects; they are never part of the request body. */
    val injected: Set<String> = setOf(
        "fastapi.Request", "fastapi.Response", "fastapi.BackgroundTasks", "fastapi.WebSocket", "fastapi.HTTPException",
        "starlette.requests.Request", "starlette.requests.HTTPConnection", "starlette.responses.Response", "starlette.websockets.WebSocket",
        "starlette.background.BackgroundTasks", "fastapi.security.SecurityScopes", "fastapi.requests.Request", "fastapi.responses.Response",
    )

    /** Dependencies that read a form: the body of such an endpoint is a form this extraction does not model. */
    val formDependencies: Set<String> = setOf("fastapi.security.OAuth2PasswordRequestForm", "fastapi.security.OAuth2PasswordRequestFormStrict", "fastapi.security.oauth2.OAuth2PasswordRequestForm")

    /** Library dependencies that only read headers or cookies (security schemes). */
    const val SECURITY_PREFIX = "fastapi.security."

    const val KEY_EMBED = "embed"
}

internal sealed interface BodyResult {
    data object NoBody : BodyResult
    class Unsupported(val reason: Gap, val detail: String) : BodyResult
    class Found(val encoding: BodyEncoding, val schema: Schema) : BodyResult
}

/** The request body a FastAPI handler declares: its body parameters, those of its dependencies, and form fields. */
internal class FastApiBodies(private val mapper: TypeMapper, private val pathNames: Set<String>) {
    private val session = mapper.session
    private val symbols = session.symbols

    private class Param(val key: String, val schema: Schema, val required: Boolean, val embed: Boolean)

    private val bodies = ArrayList<Param>()
    private val forms = ArrayList<Param>()
    private var unsupported: BodyResult.Unsupported? = null
    private val visited = HashSet<PyFunction>()

    fun extract(handler: PyFunction): BodyResult {
        collect(handler, 0)
        unsupported?.let { return it }
        if (forms.isNotEmpty() && bodies.isNotEmpty()) return BodyResult.Unsupported(Gap.AMBIGUOUS, "form fields and a JSON body")
        if (forms.isNotEmpty()) return BodyResult.Found(BodyEncoding.FORM_URLENCODED, objectOf(forms))
        if (bodies.isEmpty()) return BodyResult.NoBody
        val single = bodies.singleOrNull()
        val root = if (single != null && !single.embed) withoutNull(single.schema).copy(default = null) else objectOf(bodies)
        return BodyResult.Found(BodyEncoding.JSON, root)
    }

    private fun objectOf(params: List<Param>): Schema = Schema(
        types = listOf(JsonType.OBJECT),
        properties = params.associateTo(LinkedHashMap()) { it.key to it.schema },
        required = params.filter { it.required }.map { it.key },
    )

    private fun fail(reason: Gap, detail: String) {
        if (unsupported == null) unsupported = BodyResult.Unsupported(reason, detail)
    }

    private fun collect(function: PyFunction, depth: Int) {
        if (!visited.add(function) || depth > Limits.SCHEMA_MAX_NESTING) return
        for (parameter in function.parameterList.parameters) {
            session.check()
            val named = parameter.asNamed ?: continue
            if (named.isSelf || named.isPositionalContainer || named.isKeywordContainer) continue
            classify(named, depth)
        }
    }

    private fun classify(parameter: PyNamedParameter, depth: Int) {
        val name = parameter.name ?: return
        val file = parameter.containingFile as? PyFile ?: return
        val annotation = parameter.annotation?.value
        if (annotation != null && mentions(annotation, file, FastApiBodyCatalog.uploadFile)) return fail(Gap.NOT_REPRESENTABLE, "file upload in $name")
        if (annotation != null && isInjected(annotation, file)) return

        val diagnostics = session.diagnostics.size
        val validators = session.validators.toList()
        val typed = annotation?.let { mapper.map(it, file) }
        val defaultExpression = parameter.defaultValue
        val defaultCall = PyLiterals.unparen(defaultExpression) as? PyCallExpression
        val metaCalls = typed?.meta?.filterIsInstance<PyCallExpression>().orEmpty()
        fun kindOf(call: PyCallExpression?) = call?.let { FastApiBodyCatalog.kinds[symbols.libName(it.callee, file)] }
        val marker = if (kindOf(defaultCall) != null) defaultCall else metaCalls.lastOrNull { kindOf(it) != null }
        val kind = kindOf(marker)

        // Whatever is learned about parameters that turn out not to be the body must not leave notes behind.
        fun irrelevant() {
            while (session.diagnostics.size > diagnostics) session.diagnostics.removeAt(session.diagnostics.lastIndex)
            session.validators.retainAll(validators.toSet())
        }

        when (kind) {
            FastApiBodyCatalog.Kind.DEPENDS -> {
                irrelevant()
                dependency(marker!!, annotation, file, depth)
            }
            FastApiBodyCatalog.Kind.QUERY, FastApiBodyCatalog.Kind.HEADER, FastApiBodyCatalog.Kind.COOKIE, FastApiBodyCatalog.Kind.PATH -> irrelevant()
            FastApiBodyCatalog.Kind.FILE -> fail(Gap.NOT_REPRESENTABLE, "file upload in $name")
            FastApiBodyCatalog.Kind.FORM -> form(name, typed, marker!!, defaultExpression, file)
            FastApiBodyCatalog.Kind.BODY -> body(name, typed, marker!!, defaultExpression, file)
            null -> {
                if (name in pathNames) return irrelevant()
                if (typed == null) {
                    irrelevant()
                    return fail(Gap.UNRESOLVED_TYPE, "type of parameter $name decides query or body")
                }
                if (!isComplex(typed.schema)) return irrelevant()
                val ref = typed.schema.ref?.substringAfterLast('/')
                if (ref != null && ref in mapper.typedDictDefs) return fail(Gap.AMBIGUOUS, "TypedDict parameter $name without Body()")
                body(name, typed, null, defaultExpression, file)
            }
        }
    }

    /** FastAPI treats a parameter as body when its type is a model, a dataclass, a mapping or a sequence (or a union with one). */
    private fun isComplex(schema: Schema): Boolean {
        if (schema.ref != null) return true
        if (schema.types.any { it == JsonType.ARRAY || it == JsonType.OBJECT }) return true
        return schema.anyOf.any { isComplex(it) } || schema.oneOf.any { isComplex(it) }
    }

    private fun body(name: String, typed: Typed?, marker: PyCallExpression?, default: PyExpression?, file: PyFile) {
        var schema = typed?.schema ?: return fail(Gap.UNRESOLVED_TYPE, "type of body parameter $name")
        var key = name
        var embed = false
        val value = declaredDefault(marker, default)
        if (marker != null) {
            schema = Constraints.applyCall(schema, marker, mapper.constants) ?: return fail(Gap.UNRESOLVED_TYPE, "constraints of body parameter $name")
            embed = PyLiterals.bool(PyLiterals.keyword(marker, FastApiBodyCatalog.KEY_EMBED)) == true
            PyLiterals.string(PyLiterals.keyword(marker, Keys.ALIAS))?.let { key = it }
        }
        val required = value == null || PyLiterals.isEllipsis(value)
        if (!required) {
            (PyLiterals.json(value) ?: mapper.enumMemberValue(value, file))?.let { schema = schema.copy(default = it) }
        }
        bodies += Param(key, schema, required, embed)
    }

    /**
     * The default the parameter declares: the first argument of the marker (`Body(None)`, `Body(default=...)`), else the plain
     * default value written next to an `Annotated[..., Body()]` annotation. Null means none; `...` means required.
     */
    private fun declaredDefault(marker: PyCallExpression?, default: PyExpression?): PyExpression? {
        if (marker == null) return default
        val inMarker = PyLiterals.positional(marker).firstOrNull() ?: PyLiterals.keyword(marker, Keys.DEFAULT)
        val plain = if (PyLiterals.unparen(default) !== marker) default else null
        return inMarker ?: plain
    }

    private fun form(name: String, typed: Typed?, marker: PyCallExpression, default: PyExpression?, file: PyFile) {
        var schema = typed?.schema ?: return fail(Gap.UNRESOLVED_TYPE, "type of form parameter $name")
        schema = Constraints.applyCall(schema, marker, mapper.constants) ?: return fail(Gap.UNRESOLVED_TYPE, "constraints of form parameter $name")
        val ref = schema.ref?.substringAfterLast('/')
        if (ref != null) {
            // A model declared as `Annotated[Model, Form()]` contributes its fields.
            if (ref in mapper.typedDictDefs) return fail(Gap.NOT_REPRESENTABLE, "TypedDict form $name")
            val model = mapper.defs[ref] ?: return fail(Gap.UNRESOLVED_TYPE, "form model $name")
            for ((key, property) in model.properties) {
                if (!formRepresentable(property)) return fail(Gap.NOT_REPRESENTABLE, "form field $key")
                forms += Param(key, property, key in model.required, false)
            }
            return
        }
        if (!formRepresentable(schema)) return fail(Gap.NOT_REPRESENTABLE, "form field $name")
        val value = declaredDefault(marker, default)
        val required = value == null || PyLiterals.isEllipsis(value)
        if (!required) {
            (PyLiterals.json(value) ?: mapper.enumMemberValue(value, file))?.let { schema = schema.copy(default = it) }
        }
        val key = PyLiterals.string(PyLiterals.keyword(marker, Keys.ALIAS)) ?: name
        forms += Param(key, schema, required, false)
    }

    /** A form carries strings; scalars and lists of scalars are fine, anything nested is not. */
    private fun formRepresentable(schema: Schema): Boolean {
        if (schema.ref != null) return false
        if (schema.anyOf.isNotEmpty()) return schema.anyOf.all { Constraints.isNull(it) || formRepresentable(it) }
        val type = schema.types.firstOrNull { it != JsonType.NULL }
        return when (type) {
            JsonType.OBJECT -> false
            JsonType.ARRAY -> schema.items?.let { it.ref == null && it.types.none { t -> t == JsonType.ARRAY || t == JsonType.OBJECT } } ?: true
            else -> true
        }
    }

    // --- Dependencies ------------------------------------------------------------------------------------------------------

    private fun dependency(marker: PyCallExpression, annotation: PyExpression?, file: PyFile, depth: Int) {
        val target = PyLiterals.positional(marker).firstOrNull() ?: PyLiterals.keyword(marker, "dependency") ?: annotation
        if (target == null) return
        when (val sym = symbols.resolve(target, file)) {
            is Sym.Fn -> collect(sym.fn, depth + 1)
            is Sym.Cls -> classDependency(sym.cls, depth)
            is Sym.Lib -> when {
                sym.name in FastApiBodyCatalog.formDependencies -> fail(Gap.NOT_REPRESENTABLE, "form dependency ${sym.name}")
                sym.name.startsWith(FastApiBodyCatalog.SECURITY_PREFIX) -> Unit
                else -> session.skipped(sym.name, Gap.UNRESOLVED_CLASS)
            }
            // An instance such as `oauth2_scheme = OAuth2PasswordBearer(...)` only reads headers.
            is Sym.Value -> Unit
            else -> session.skipped(target.text.take(Limits.MAX_QUOTED_SOURCE_CHARS), Gap.UNRESOLVED_SOURCE)
        }
    }

    /** A class dependency contributes the parameters of its `__init__`; a model or dataclass contributes query parameters. */
    private fun classDependency(cls: PyClass, depth: Int) {
        val kind = mapper.kindOf(cls)
        if (kind == ClassKind.PYDANTIC || kind == ClassKind.DATACLASS) return
        mapper.hierarchy.findMethod(cls, INIT)?.let { collect(it, depth + 1) }
    }

    private fun isInjected(annotation: PyExpression, file: PyFile): Boolean {
        val name = symbols.libName(PyLiterals.unionOperands(annotation).firstOrNull(), file) ?: return false
        return name in FastApiBodyCatalog.injected
    }

    private fun mentions(annotation: PyExpression, file: PyFile, names: Set<String>): Boolean {
        val references = PsiTreeUtil.findChildrenOfType(annotation, PyReferenceExpression::class.java) + listOfNotNull(annotation as? PyReferenceExpression)
        return references.any { symbols.libName(it, file) in names }
    }

    /** `Optional[Model]` and `Model | None` as just `Model`: the body of a request is never JSON null by itself. */
    private fun withoutNull(schema: Schema): Schema {
        val branches = schema.anyOf.filterNot { Constraints.isNull(it) }
        return when {
            schema.anyOf.isEmpty() || branches.size == schema.anyOf.size -> schema
            branches.size == 1 -> branches.single()
            else -> schema.copy(anyOf = branches)
        }
    }

    private companion object {
        const val INIT = "__init__"
    }
}

/** The schema cut down to what [root] can reach: models mapped for parameters that were not the body are dropped. */
internal fun pruneDefs(root: Schema, defs: Map<String, Schema>): Schema {
    val reachable = LinkedHashSet<String>()
    val pending = ArrayDeque<Schema>()
    pending += root
    while (pending.isNotEmpty()) {
        val schema = pending.removeFirst()
        schema.ref?.substringAfterLast('/')?.let { name -> if (reachable.add(name)) defs[name]?.let { pending += it } }
        pending += schema.properties.values
        pending += schema.anyOf
        pending += schema.oneOf
        pending += schema.allOf
        pending += schema.prefixItems
        schema.items?.let { pending += it }
        (schema.additional as? com.routegenie.schema.model.Additional.OfSchema)?.let { pending += it.schema }
    }
    val kept = LinkedHashMap<String, Schema>()
    for ((name, schema) in defs) if (name in reachable) kept[name] = schema
    return root.copy(defs = kept)
}
