package com.routegenie.schema.psi

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PySubscriptionExpression
import com.routegenie.schema.model.BodyEncoding
import com.routegenie.schema.model.FileSpec
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema

/**
 * Flask has no standard request schema: `request.get_json()` returns whatever the client sent. The only statically certain
 * declaration is flask-pydantic's `@validate(body=Model)` (or `form=Model`), which is what this catalog names.
 */
internal object FlaskSchemaCatalog {
    val validate: Set<String> = setOf("flask_pydantic.validate", "flask_pydantic.core.validate")
    const val BODY = "body"
    const val FORM = "form"
    val parameterNames = setOf(BODY, FORM)

    private val requestRoots = listOf("flask.request", "flask.globals.request")
    val files: Set<String> = requestRoots.map { "$it.files" }.toSet()
    val form: Set<String> = requestRoots.map { "$it.form" }.toSet()
    const val GET = "get"
    const val GET_LIST = "getlist"

    /** Words of a constant's name that say it lists the extensions an upload may have, and words that say it lists the ones it may not. */
    val allowWords = listOf("allow")
    val denyWords = listOf("disallow", "block", "forbid", "deny", "ban", "reject")
}

internal class FlaskBodies(private val mapper: TypeMapper) {
    private val session = mapper.session
    private val symbols = session.symbols

    fun extract(handler: PyFunction): BodyResult {
        val file = handler.containingFile as? PyFile ?: return BodyResult.NoBody
        val decorator = handler.decoratorList?.decorators.orEmpty().firstOrNull { symbols.libName(it.callee, file) in FlaskSchemaCatalog.validate }
            ?: return uploads(handler, file)
        val call = decorator as? PyCallExpression
        val bodyArgument = call?.let { PyLiterals.keyword(it, FlaskSchemaCatalog.BODY) }
        val formArgument = call?.let { PyLiterals.keyword(it, FlaskSchemaCatalog.FORM) }
        // `@validate()` with models given as annotations of parameters named `body` or `form`.
        val bodyType = bodyArgument ?: namedParameter(handler, FlaskSchemaCatalog.BODY)
        val formType = formArgument ?: namedParameter(handler, FlaskSchemaCatalog.FORM)
        if (bodyType != null && formType != null) return BodyResult.Unsupported(Gap.AMBIGUOUS, "body and form models")
        if (bodyType != null) {
            val typed = mapper.map(bodyType, file) ?: return BodyResult.Unsupported(Gap.UNRESOLVED_TYPE, "body model of ${handler.name}")
            return BodyResult.Found(BodyEncoding.JSON, typed.schema)
        }
        if (formType != null) return form(formType, file)
        return BodyResult.NoBody
    }

    private fun namedParameter(handler: PyFunction, name: String) =
        handler.parameterList.findParameterByName(name)?.annotation?.value

    private fun form(expression: com.jetbrains.python.psi.PyExpression, file: PyFile): BodyResult {
        val typed = mapper.map(expression, file) ?: return BodyResult.Unsupported(Gap.UNRESOLVED_TYPE, "form model")
        val name = typed.schema.ref?.substringAfterLast('/') ?: return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "form model is not a class")
        val model = mapper.defs[name] ?: return BodyResult.Unsupported(Gap.UNRESOLVED_TYPE, "form model")
        for (property in model.properties.values) {
            if (!isFlat(property)) return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "nested form field")
        }
        return BodyResult.Found(BodyEncoding.FORM_URLENCODED, model.copy(additional = null).let { Schema(types = listOf(JsonType.OBJECT), properties = it.properties, required = it.required) })
    }

    // --- Uploads: `request.files` ------------------------------------------------------------------------------------------

    /**
     * A handler that reads `request.files["name"]`, `request.files.get("name")` or `.getlist("name")` takes
     * `multipart/form-data`. Its form fields are the names it reads from `request.form` the same way (text, since a form
     * carries nothing else). Every field is listed as required: a part the handler happens to tolerate missing is still safe to
     * send. Only the handler's own text is read, so a helper that reads `request.files` is not seen; a name that is not a
     * literal is skipped and recorded. Without a file the handler stays without a body, as before.
     */
    private fun uploads(handler: PyFunction, file: PyFile): BodyResult {
        val fileNames = LinkedHashMap<String, Boolean>()
        val formNames = LinkedHashMap<String, Boolean>()
        fun record(store: MutableMap<String, Boolean>, index: PyExpression?, many: Boolean) {
            val name = index?.let { PyLiterals.string(mapper.constants(it)) }
            if (name == null) {
                session.skipped(handler.name ?: "handler", Gap.UNRESOLVED_SOURCE)
                return
            }
            store[name] = (store[name] ?: false) || many
        }
        for (e in PsiTreeUtil.findChildrenOfType(handler, PySubscriptionExpression::class.java)) {
            val source = symbols.libName(e.operand, file) ?: continue
            val store = if (source in FlaskSchemaCatalog.files) fileNames else if (source in FlaskSchemaCatalog.form) formNames else continue
            record(store, e.indexExpression, many = false)
        }
        for (call in PsiTreeUtil.findChildrenOfType(handler, PyCallExpression::class.java)) {
            val callee = symbols.libName(call.callee, file) ?: continue
            val method = callee.substringAfterLast('.')
            if (method != FlaskSchemaCatalog.GET && method != FlaskSchemaCatalog.GET_LIST) continue
            val source = callee.substringBeforeLast('.')
            val store = if (source in FlaskSchemaCatalog.files) fileNames else if (source in FlaskSchemaCatalog.form) formNames else continue
            record(store, PyLiterals.positional(call).firstOrNull() ?: PyLiterals.keyword(call, "key"), many = method == FlaskSchemaCatalog.GET_LIST)
        }
        if (fileNames.isEmpty()) return BodyResult.NoBody
        val extensions = if (fileNames.size == 1) allowedExtensions(handler, file) else emptyList()
        val properties = LinkedHashMap<String, Schema>()
        for ((name, many) in fileNames) {
            val one = FileSpec(extensions = extensions).toSchema()
            properties[name] = if (many) Schema.arrayOf(one) else one
        }
        for ((name, many) in formNames) {
            if (name !in properties) properties[name] = if (many) Schema.arrayOf(Schema.STRING) else Schema.STRING
        }
        return BodyResult.Found(BodyEncoding.MULTIPART, Schema(types = listOf(JsonType.OBJECT), properties = properties, required = properties.keys.toList()))
    }

    /**
     * The extensions an `in ALLOWED_EXTENSIONS` check lists, when the handler or a project function it calls compares the end
     * of a file name with a constant whose name says it allows and whose value is a literal set, list or tuple of strings.
     * Entries are lower-cased and given their dot. A `not in` check and a constant named like a block list do not count.
     */
    private fun allowedExtensions(handler: PyFunction, file: PyFile): List<String> {
        val functions = ArrayList<PyFunction>()
        functions += handler
        for (call in PsiTreeUtil.findChildrenOfType(handler, PyCallExpression::class.java)) {
            val target = (symbols.resolve(call.callee, file) as? Sym.Fn)?.fn ?: continue
            if (target !in functions && functions.size < MAX_HELPERS) functions += target
        }
        for (function in functions) {
            val home = function.containingFile as? PyFile ?: continue
            for (comparison in PsiTreeUtil.findChildrenOfType(function, PyBinaryExpression::class.java)) {
                if (!comparison.isOperator("in") || !UploadChecks.endsWithExtension(comparison.leftExpression)) continue
                val reference = comparison.rightExpression as? PyReferenceExpression ?: continue
                val name = reference.referencedName?.lowercase() ?: continue
                if (FlaskSchemaCatalog.allowWords.none { it in name } || FlaskSchemaCatalog.denyWords.any { it in name }) continue
                val value = (symbols.resolve(reference, home) as? Sym.Value)?.expr ?: continue
                val entries = PyLiterals.strings(value) ?: continue
                val extensions = UploadChecks.extensions(entries)
                if (extensions.isNotEmpty()) return extensions
            }
        }
        return emptyList()
    }

    private fun isFlat(schema: Schema): Boolean {
        if (schema.ref != null) return false
        if (schema.anyOf.isNotEmpty()) return schema.anyOf.all { Constraints.isNull(it) || isFlat(it) }
        val type = schema.types.firstOrNull { it != JsonType.NULL }
        if (type == JsonType.OBJECT) return false
        if (type == JsonType.ARRAY) return schema.items?.let { it.ref == null && it.types.none { t -> t == JsonType.ARRAY || t == JsonType.OBJECT } } ?: true
        return true
    }

    private companion object {
        const val MAX_HELPERS = 8
    }
}
