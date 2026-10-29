package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.routegenie.schema.model.BodyEncoding
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
}

internal class FlaskBodies(private val mapper: TypeMapper) {
    private val session = mapper.session
    private val symbols = session.symbols

    fun extract(handler: PyFunction): BodyResult {
        val file = handler.containingFile as? PyFile ?: return BodyResult.NoBody
        val decorator = handler.decoratorList?.decorators.orEmpty().firstOrNull { symbols.libName(it.callee, file) in FlaskSchemaCatalog.validate }
            ?: return BodyResult.NoBody
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

    private fun isFlat(schema: Schema): Boolean {
        if (schema.ref != null) return false
        if (schema.anyOf.isNotEmpty()) return schema.anyOf.all { Constraints.isNull(it) || isFlat(it) }
        val type = schema.types.firstOrNull { it != JsonType.NULL }
        if (type == JsonType.OBJECT) return false
        if (type == JsonType.ARRAY) return schema.items?.let { it.ref == null && it.types.none { t -> t == JsonType.ARRAY || t == JsonType.OBJECT } } ?: true
        return true
    }
}
