package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyTargetExpression
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.BodyEncoding
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema

private class FormField(val name: String, val call: PyCallExpression, val file: PyFile, val kind: FieldKind)

/**
 * Django forms (`Form`, `ModelForm`) as the flat object a browser posts, and the views that are statically bound to one:
 * a class based view with `form_class` (or `model` + `fields` on a create / update view), or a view function that builds
 * exactly one form from `request.POST`. A form that reads files needs multipart and has no template.
 */
internal class DjangoForms(private val mapper: TypeMapper) {
    private val session = mapper.session
    private val symbols = session.symbols
    private val options = OptionReader(mapper)
    private val models = DjangoModels(mapper, options)
    private val scan = ViewScan(mapper)

    fun extract(target: Target, verb: HttpMethod): BodyResult {
        if (verb != HttpMethod.POST && verb != HttpMethod.PUT && verb != HttpMethod.PATCH) return BodyResult.NoBody
        return when (target) {
            is Target.Function -> fromFunction(target.fn)
            is Target.Member -> fromView(target, verb)
        }
    }

    private fun isForm(cls: PyClass) = mapper.hierarchy.inherits(cls, DjangoSchemaCatalog.formBases + DjangoSchemaCatalog.modelFormBases)

    // --- Views ---------------------------------------------------------------------------------------------------------------

    private fun fromFunction(function: PyFunction): BodyResult {
        val decision = scan.decide(scan.uses(function, GET_FORM, BODY_ATTRIBUTES, ::isForm))
        if (decision.ambiguous) return BodyResult.Unsupported(Gap.AMBIGUOUS, "several forms read request.POST in ${function.name}")
        val cls = decision.use?.cls ?: return BodyResult.NoBody
        return formBody(cls)
    }

    private fun fromView(target: Target.Member, verb: HttpMethod): BodyResult {
        val cls = target.cls
        val libs = mapper.hierarchy.info(cls).libraryBases
        if (libs.none { it in DjangoSchemaCatalog.formViews }) return BodyResult.NoBody
        val own = target.method ?: mapper.hierarchy.findMethod(cls, verb.name.lowercase())
        if (own != null) {
            // The project replaced the framework's handler: only what the code itself hands to a form counts.
            val decision = scan.decide(scan.uses(own, GET_FORM, BODY_ATTRIBUTES, ::isForm))
            if (decision.ambiguous) return BodyResult.Unsupported(Gap.AMBIGUOUS, "several forms read request.POST in ${own.name}")
            val use = decision.use ?: return BodyResult.NoBody
            return use.cls?.let { formBody(it) } ?: viewForm(cls, libs)
        }
        return viewForm(cls, libs)
    }

    /** The form of the view class: `form_class`, a decided `get_form_class()`, or the model form a create / update view builds. */
    private fun viewForm(cls: PyClass, libs: Set<String>): BodyResult {
        mapper.hierarchy.findMethod(cls, DjangoSchemaCatalog.METHOD_GET_FORM_CLASS)?.let { method ->
            return when (val returned = scan.returned(method, null, HttpMethod.POST)) {
                is ViewScan.Returned.Class -> formBody(returned.cls)
                else -> BodyResult.Unsupported(Gap.UNRESOLVED_SOURCE, "form of ${cls.name} is chosen at run time")
            }
        }
        attribute(cls, DjangoSchemaCatalog.ATTR_FORM_CLASS)?.let { (expression, file) ->
            val form = (symbols.resolve(expression, file) as? Sym.Cls)?.cls?.takeIf(::isForm)
                ?: return BodyResult.Unsupported(Gap.UNRESOLVED_CLASS, "form_class of ${cls.name}")
            return formBody(form)
        }
        if (libs.none { it in DjangoSchemaCatalog.modelFormViews }) return BodyResult.NoBody
        val modelExpression = attribute(cls, DjangoSchemaCatalog.ATTR_MODEL) ?: return BodyResult.NoBody
        val model = (symbols.resolve(modelExpression.first, modelExpression.second) as? Sym.Cls)?.cls
            ?: return BodyResult.Unsupported(Gap.UNRESOLVED_CLASS, "model of ${cls.name}")
        val fieldsExpression = attribute(cls, DjangoSchemaCatalog.ATTR_FIELDS)?.first ?: return BodyResult.NoBody
        return modelFormBody(model, PyLiterals.string(fieldsExpression) ?: PyLiterals.strings(fieldsExpression)?.joinToString(SEPARATOR) ?: return BodyResult.NoBody, emptyMap())
    }

    /** A class attribute of the view, from the nearest class of the hierarchy that sets it. */
    private fun attribute(cls: PyClass, name: String): Pair<com.jetbrains.python.psi.PyExpression, PyFile>? {
        for (c in mapper.hierarchy.baseFirst(cls).asReversed()) {
            val file = c.containingFile as? PyFile ?: continue
            for (statement in c.statementList.statements) {
                val assignment = statement as? PyAssignmentStatement ?: continue
                if ((assignment.targets.singleOrNull() as? PyTargetExpression)?.name != name) continue
                val value = assignment.assignedValue ?: continue
                return value to file
            }
        }
        return null
    }

    // --- Forms ---------------------------------------------------------------------------------------------------------------

    private fun formBody(cls: PyClass): BodyResult {
        session.enterClass()
        val chain = mapper.hierarchy.baseFirst(cls).filter { isForm(it) }
        val declared = LinkedHashMap<String, FormField>()
        for (c in chain) {
            val file = c.containingFile as? PyFile ?: continue
            for (statement in c.statementList.statements) {
                val assignment = statement as? PyAssignmentStatement ?: continue
                val name = (assignment.targets.singleOrNull() as? PyTargetExpression)?.name ?: continue
                val call = PyLiterals.unparen(assignment.assignedValue) as? PyCallExpression ?: continue
                val kind = fieldKind(call, file) ?: continue
                declared[name] = FormField(name, call, file, kind)
            }
        }
        val modelForm = mapper.hierarchy.inherits(cls, DjangoSchemaCatalog.modelFormBases)
        val properties = LinkedHashMap<String, Schema>()
        val required = ArrayList<String>()
        if (modelForm) {
            val meta = chain.asReversed().firstNotNullOfOrNull { it.findNestedClass(DjangoSchemaCatalog.META, false) }
                ?: return BodyResult.Unsupported(Gap.NO_SCHEMA_DECLARED, "${cls.name} has no Meta")
            val modelExpression = attributeOf(meta, DjangoSchemaCatalog.ATTR_MODEL)
            val model = modelExpression?.let { (symbols.resolve(it, it.containingFile as? PyFile) as? Sym.Cls)?.cls }
                ?: return BodyResult.Unsupported(Gap.UNRESOLVED_CLASS, "model of ${cls.name}")
            val fields = attributeOf(meta, DjangoSchemaCatalog.ATTR_FIELDS)
            val exclude = attributeOf(meta, "exclude")?.let { PyLiterals.strings(it) }.orEmpty().toSet()
            val spec = PyLiterals.string(fields) ?: PyLiterals.strings(fields)?.joinToString(SEPARATOR)
                ?: return BodyResult.Unsupported(Gap.NO_SCHEMA_DECLARED, "${cls.name} lists no fields")
            return modelFormBody(model, spec, declared, exclude, cls)
        }
        for (field in declared.values) addField(cls, field, properties, required) ?: return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "${cls.name}.${field.name}")
        return formResult(properties, required)
    }

    private fun attributeOf(meta: PyClass, name: String): com.jetbrains.python.psi.PyExpression? {
        for (statement in meta.statementList.statements) {
            val assignment = statement as? PyAssignmentStatement ?: continue
            if ((assignment.targets.singleOrNull() as? PyTargetExpression)?.name == name) return assignment.assignedValue
        }
        return null
    }

    private fun modelFormBody(model: PyClass, spec: String, declared: Map<String, FormField>, exclude: Set<String> = emptySet(), form: PyClass? = null): BodyResult {
        val info = models.info(model) ?: return BodyResult.Unsupported(Gap.UNRESOLVED_CLASS, "model ${model.name}")
        val everything = spec == DjangoSchemaCatalog.ALL_FIELDS
        val names = if (everything) info.fields.keys.toList() else spec.split(SEPARATOR).filter { it.isNotEmpty() }
        val properties = LinkedHashMap<String, Schema>()
        val required = ArrayList<String>()
        val owner = form ?: model
        for (name in names.filter { it !in exclude }) {
            session.check()
            val field = declared[name]
            if (field != null) {
                addField(owner, field, properties, required) ?: return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "${owner.name}.$name")
                continue
            }
            val modelField = info.fields[name] ?: continue
            val where = "${owner.name}.$name"
            val opts = models.options(modelField, forForm = true)
            if (opts.readOnly) continue
            if (opts.unresolved != null) {
                session.skipped(where, Gap.UNSUPPORTED_FIELD)
                continue
            }
            var kind = models.fieldKind(modelField, choices = opts.choices != null)
            if (kind == FieldKind.FILE) return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "$where is a file")
            val many = kind == FieldKind.MANY_PK_RELATED
            if (many) kind = FieldKind.PK_RELATED
            val pk = if (kind == FieldKind.PK_RELATED) models.relatedModel(modelField)?.let { models.primaryKeySchema(it, PyLiterals.string(modelField.keyword("to_field"))) } else null
            val schema = flatSchema(where, kind, opts, pk, many) ?: continue
            properties[name] = schema
            if (opts.required != false) required += name
        }
        for ((name, field) in declared) {
            if (name in properties || name in names) continue
            addField(owner, field, properties, required) ?: return BodyResult.Unsupported(Gap.NOT_REPRESENTABLE, "${owner.name}.$name")
        }
        recordValidators(owner)
        return formResult(properties, required)
    }

    private fun formResult(properties: Map<String, Schema>, required: List<String>) =
        BodyResult.Found(BodyEncoding.FORM_URLENCODED, Schema(types = listOf(JsonType.OBJECT), properties = properties, required = required))

    private fun recordValidators(cls: PyClass) {
        for (c in mapper.hierarchy.baseFirst(cls)) {
            for (method in c.methods) {
                val name = method.name ?: continue
                if (name == "clean" || name.startsWith("clean_")) session.validators += "${c.name}.$name"
            }
        }
    }

    private fun fieldKind(call: PyCallExpression, file: PyFile): FieldKind? = when (val sym = symbols.resolve(call.callee, file)) {
        is Sym.Lib -> DjangoSchemaCatalog.formFields[sym.name]
        is Sym.Cls -> mapper.hierarchy.info(sym.cls).libraryBases.firstNotNullOfOrNull { DjangoSchemaCatalog.formFields[it] }
            ?.let { if (listOf("to_python", "clean", "__init__").any { m -> sym.cls.findMethodByName(m, false, null) != null }) FieldKind.UNSUPPORTED else it }
        else -> null
    }

    /** Adds a declared form field; null when the form cannot be sent as a plain form because of this field. */
    private fun addField(owner: PyClass, field: FormField, properties: MutableMap<String, Schema>, required: MutableList<String>): Unit? {
        val where = "${owner.name}.${field.name}"
        val opts = options.fromCall(field.call, field.file)
        if (opts.disabled || field.kind == FieldKind.EXCLUDED) return Unit
        if (field.kind == FieldKind.FILE) return null
        if (opts.unresolved != null || field.kind == FieldKind.UNSUPPORTED) {
            session.skipped(where, Gap.UNSUPPORTED_FIELD)
            return Unit
        }
        var kind = field.kind
        val many = kind == FieldKind.MANY_PK_RELATED
        if (many) kind = FieldKind.PK_RELATED
        val pk = if (kind == FieldKind.PK_RELATED) queryset(field.call, field.file) else null
        // Form fields are required unless they say otherwise; `initial` is a starting value, not a default.
        val schema = flatSchema(where, kind, opts, pk, many) ?: return Unit
        properties[field.name] = schema
        if (opts.required != false) required += field.name
        return Unit
    }

    private fun queryset(call: PyCallExpression, file: PyFile): Schema? =
        models.primaryKeyOfQueryset(PyLiterals.keyword(call, "queryset") ?: PyLiterals.positional(call).firstOrNull(), file)

    private fun flatSchema(where: String, kind: FieldKind, opts: FieldOpts, pk: Schema?, many: Boolean): Schema? {
        var schema: Schema = when (kind) {
            FieldKind.PK_RELATED -> {
                val key = pk ?: run {
                    session.skipped(where, Gap.UNRESOLVED_CLASS)
                    return null
                }
                if (many) Schema.arrayOf(key) else key
            }
            else -> FieldSchemas.scalar(kind, opts) ?: run {
                session.skipped(where, Gap.UNSUPPORTED_FIELD)
                return null
            }
        }
        // A blank value is how a form leaves an optional text field empty.
        if (kind == FieldKind.CHAR || kind == FieldKind.EMAIL || kind == FieldKind.URL || kind == FieldKind.SLUG) {
            if (opts.required == false || opts.allowBlank) schema = schema.copy(minLength = null)
        }
        opts.description?.let { schema = schema.copy(description = it) }
        return schema
    }

    private companion object {
        const val GET_FORM = "get_form"
        const val SEPARATOR = ","
        val BODY_ATTRIBUTES = setOf("POST")
    }
}
