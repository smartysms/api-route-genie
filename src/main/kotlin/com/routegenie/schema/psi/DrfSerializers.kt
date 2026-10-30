package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyTargetExpression
import com.routegenie.Limits
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema

/** Every fact about Django REST Framework serializers and views that body extraction relies on. */
internal object DrfSchemaCatalog {
    private val roots = listOf("rest_framework.serializers", "rest_framework.fields", "rest_framework.relations")

    private fun names(vararg simple: String): Set<String> = roots.flatMap { r -> simple.map { "$r.$it" } }.toSet()

    val serializerBases: Set<String> = names("Serializer", "ModelSerializer", "HyperlinkedModelSerializer")
    val modelSerializerBases: Set<String> = names("ModelSerializer", "HyperlinkedModelSerializer")
    val hyperlinked: Set<String> = names("HyperlinkedModelSerializer")
    val unsupportedSerializers: Set<String> = names("BaseSerializer", "ListSerializer")

    val fields: Map<String, FieldKind> = buildMap {
        fun put(kind: FieldKind, vararg simple: String) = names(*simple).forEach { this[it] = kind }
        put(FieldKind.CHAR, "CharField")
        put(FieldKind.EMAIL, "EmailField")
        put(FieldKind.URL, "URLField")
        put(FieldKind.SLUG, "SlugField")
        put(FieldKind.UUID, "UUIDField")
        put(FieldKind.IP, "IPAddressField")
        put(FieldKind.REGEX, "RegexField")
        put(FieldKind.INTEGER, "IntegerField")
        put(FieldKind.FLOAT, "FloatField")
        put(FieldKind.DECIMAL, "DecimalField")
        put(FieldKind.BOOLEAN, "BooleanField")
        put(FieldKind.NULL_BOOLEAN, "NullBooleanField")
        put(FieldKind.DATETIME, "DateTimeField")
        put(FieldKind.DATE, "DateField")
        put(FieldKind.TIME, "TimeField")
        put(FieldKind.DURATION, "DurationField")
        put(FieldKind.CHOICE, "ChoiceField")
        put(FieldKind.MULTIPLE_CHOICE, "MultipleChoiceField")
        put(FieldKind.LIST, "ListField")
        put(FieldKind.DICT, "DictField", "HStoreField")
        put(FieldKind.JSON, "JSONField")
        put(FieldKind.FILE, "FileField")
        put(FieldKind.IMAGE, "ImageField")
        put(FieldKind.EXCLUDED, "SerializerMethodField", "ReadOnlyField", "HiddenField", "HyperlinkedIdentityField", "StringRelatedField")
        put(FieldKind.PK_RELATED, "PrimaryKeyRelatedField")
        put(FieldKind.SLUG_RELATED, "SlugRelatedField")
        put(FieldKind.URL_RELATED, "HyperlinkedRelatedField")
        put(FieldKind.UNSUPPORTED, "ModelField", "ManyRelatedField", "FilePathField")
    }

    // --- Views ------------------------------------------------------------------------------------------------------------

    val actionDecorator = "rest_framework.decorators.action"
    val apiView = "rest_framework.decorators.api_view"
    val extendSchema: Set<String> = setOf("drf_spectacular.utils.extend_schema")
    val extendSchemaView: Set<String> = setOf("drf_spectacular.utils.extend_schema_view")

    const val ATTR_SERIALIZER_CLASS = "serializer_class"
    const val METHOD_GET_SERIALIZER_CLASS = "get_serializer_class"
    const val METHOD_GET_SERIALIZER = "get_serializer"
    const val KEY_REQUEST = "request"
    const val KEY_DATA = "data"
    const val KEY_PARTIAL = "partial"
    const val REQUEST_DATA = "data"
    const val SELF_ACTION = "action"
    const val SELF_REQUEST = "request"
    const val REQUEST_METHOD = "method"

    const val META = "Meta"
    const val META_MODEL = "model"
    const val META_FIELDS = "fields"
    const val META_EXCLUDE = "exclude"
    const val META_READ_ONLY_FIELDS = "read_only_fields"
    const val META_EXTRA_KWARGS = "extra_kwargs"
    const val META_DEPTH = "depth"
    const val ALL_FIELDS = "__all__"
    const val VALIDATE_PREFIX = "validate"
    const val SOURCE = "source"
    const val SOURCE_ALL = "*"
    const val CHILD = "child"
    const val URL_FIELD = "url"
}

private class DeclaredField(val name: String, val call: PyCallExpression, val file: PyFile, val kind: FieldKind, val serializer: PyClass?)

/**
 * Serializers (`Serializer`, `ModelSerializer`) as object schemas of what a client sends: read-only and hidden fields are
 * left out, write-only ones kept, nested serializers and `many=True` become objects and arrays, and a ModelSerializer's
 * fields come from the project's Django model classes. [partial] drops every `required`, as PATCH does.
 */
internal class DrfSerializers(private val mapper: TypeMapper, private val partial: Boolean) {
    private val session = mapper.session
    private val symbols = session.symbols
    private val options = OptionReader(mapper)
    private val models = DjangoModels(mapper, options)
    private val files = FileFields(mapper)

    /** True when a file field was met: the body is then `multipart/form-data`. */
    var sawFile = false
        private set

    /** Why the serializer lists no fields at all (a ModelSerializer without `fields` or `exclude`); null when it does. */
    var undetermined: String? = null
        private set

    fun isSerializer(cls: PyClass): Boolean = mapper.hierarchy.inherits(cls, DrfSchemaCatalog.serializerBases)

    /** `$ref` to the schema of [cls], built on first use. */
    fun ref(cls: PyClass): Schema = mapper.define(cls) { build(cls) }

    private fun build(cls: PyClass): Schema {
        val chain = mapper.hierarchy.baseFirst(cls).filter { isSerializer(it) }
        val declared = declaredFields(chain)
        val isModelSerializer = mapper.hierarchy.inherits(cls, DrfSchemaCatalog.modelSerializerBases)
        val meta = MetaReader(cls)
        val model = meta.model()?.let { models.info(it) }
        val names = fieldNames(declared, meta, model, isModelSerializer) ?: run {
            undetermined = "${cls.name} lists no fields"
            return Schema(types = listOf(JsonType.OBJECT))
        }
        val properties = LinkedHashMap<String, Schema>()
        val required = ArrayList<String>()
        val extra = meta.extraKwargs()
        val readOnlyNames = meta.names(DrfSchemaCatalog.META_READ_ONLY_FIELDS)
        val depth = meta.attribute(DrfSchemaCatalog.META_DEPTH)?.let { PyLiterals.int(it) } ?: 0
        for (name in names) {
            session.check()
            val where = "${cls.name}.$name"
            val field = declared[name]
            val built = if (field != null) {
                declaredProperty(where, field)
            } else {
                val modelField = model?.fields?.get(name)
                when {
                    modelField != null -> modelProperty(where, modelField, extra[name], name in readOnlyNames, depth, mapper.hierarchy.inherits(cls, DrfSchemaCatalog.hyperlinked))
                    model != null && models.isReadOnlyMember(model, name) -> null
                    model != null && name == ID && model.primaryKey == null -> null
                    name == DrfSchemaCatalog.URL_FIELD && mapper.hierarchy.inherits(cls, DrfSchemaCatalog.hyperlinked) -> null
                    else -> {
                        session.skipped(where, Gap.UNSUPPORTED_FIELD)
                        null
                    }
                }
            } ?: continue
            properties[name] = built.schema
            if (built.required && !partial) required += name
        }
        recordValidators(chain)
        return Schema(types = listOf(JsonType.OBJECT), properties = properties, required = required)
    }

    private fun recordValidators(chain: List<PyClass>) {
        for (cls in chain) {
            for (method in cls.methods) {
                val name = method.name ?: continue
                if (name == DrfSchemaCatalog.VALIDATE_PREFIX || name.startsWith("${DrfSchemaCatalog.VALIDATE_PREFIX}_")) session.validators += "${cls.name}.$name"
            }
        }
    }

    // --- Which fields, in which order ------------------------------------------------------------------------------------

    /** Declared fields, base classes first; a redeclared field moves to the position of the redeclaration, as DRF's metaclass does. */
    private fun declaredFields(chain: List<PyClass>): Map<String, DeclaredField> {
        val out = LinkedHashMap<String, DeclaredField>()
        for (cls in chain) {
            val file = cls.containingFile as? PyFile ?: continue
            for (statement in cls.statementList.statements) {
                val assignment = statement as? PyAssignmentStatement ?: continue
                val name = (assignment.targets.singleOrNull() as? PyTargetExpression)?.name ?: continue
                val call = PyLiterals.unparen(assignment.assignedValue) as? PyCallExpression ?: continue
                val (kind, serializer) = classify(call, file) ?: continue
                out.remove(name)
                out[name] = DeclaredField(name, call, file, kind, serializer)
            }
        }
        return out
    }

    /** The field kind a constructor call makes, and the serializer class when it is a nested serializer. */
    private fun classify(call: PyCallExpression, file: PyFile): Pair<FieldKind, PyClass?>? = when (val sym = symbols.resolve(call.callee, file)) {
        is Sym.Lib -> DrfSchemaCatalog.fields[sym.name]?.let { it to null }
        is Sym.Cls -> when {
            isSerializer(sym.cls) -> FieldKind.SERIALIZER to sym.cls
            else -> customField(sym.cls)?.let { it to null }
        }
        else -> null
    }

    /** A project field class behaves like the library field it derives from, unless it takes over the conversion. */
    private fun customField(cls: PyClass): FieldKind? {
        val base = mapper.hierarchy.info(cls).libraryBases.firstNotNullOfOrNull { DrfSchemaCatalog.fields[it] } ?: return null
        val overrides = listOf("to_internal_value", "run_validation", "__init__").any { cls.findMethodByName(it, false, null) != null }
        return if (overrides) FieldKind.UNSUPPORTED else base
    }

    private fun fieldNames(declared: Map<String, DeclaredField>, meta: MetaReader, model: ModelInfo?, modelSerializer: Boolean): List<String>? {
        if (!modelSerializer) return declared.keys.toList()
        val fields = meta.attribute(DrfSchemaCatalog.META_FIELDS)
        val exclude = meta.names(DrfSchemaCatalog.META_EXCLUDE)
        val everything = {
            val pk = model?.let { (it.primaryKey?.name ?: ID) }
            (listOfNotNull(pk) + declared.keys + (model?.fields?.keys ?: emptySet<String>())).distinct()
        }
        if (fields != null) {
            if (PyLiterals.string(fields) == DrfSchemaCatalog.ALL_FIELDS) return everything()
            return PyLiterals.strings(fields)
        }
        if (exclude.isNotEmpty()) return everything().filterNot { it in exclude }
        return null
    }

    // --- One field ---------------------------------------------------------------------------------------------------------

    private class Prop(val schema: Schema, val required: Boolean)

    private fun declaredProperty(where: String, field: DeclaredField): Prop? {
        val opts = options.fromCall(field.call, field.file)
        if (opts.readOnly || field.kind == FieldKind.EXCLUDED) return null
        if (PyLiterals.string(PyLiterals.keyword(field.call, DrfSchemaCatalog.SOURCE)) == DrfSchemaCatalog.SOURCE_ALL) {
            session.skipped(where, Gap.UNSUPPORTED_FIELD)
            return null
        }
        return assemble(where, field.kind, opts, field.call, field.file, field.serializer, relationPk = relationPk(field), fileCall = field.call)
    }

    private fun modelProperty(where: String, field: ModelField, extra: Map<String, PyExpression>?, readOnlyListed: Boolean, depth: Int, hyperlinked: Boolean): Prop? {
        val opts = models.options(field, forForm = false)
        applyExtra(opts, extra)
        if (readOnlyListed) opts.readOnly = true
        if (opts.readOnly) return null
        val relation = field.kind == ModelKind.FOREIGN_KEY || field.kind == ModelKind.MANY_TO_MANY
        if (relation && depth > 0) return null
        var kind = models.fieldKind(field, choices = opts.choices != null)
        if (hyperlinked && relation) kind = FieldKind.URL_RELATED
        if (kind == FieldKind.MANY_PK_RELATED) {
            opts.many = true
            kind = FieldKind.PK_RELATED
            if (opts.required == null) opts.required = true
            if (PyLiterals.bool(field.keyword("blank")) != true) opts.allowEmpty = false
        }
        val childField = if (kind == FieldKind.LIST) arrayChild(field) else null
        return assemble(where, kind, opts, null, field.file, null, relationPk = if (kind == FieldKind.PK_RELATED) modelPk(field) else null, listChild = childField, fileCall = field.call)
    }

    private fun applyExtra(opts: FieldOpts, extra: Map<String, PyExpression>?) {
        if (extra == null) return
        fun bool(key: String) = PyLiterals.bool(extra[key])
        bool("read_only")?.let { opts.readOnly = it }
        bool("write_only")?.let { opts.writeOnly = it }
        bool("required")?.let { opts.required = it }
        bool("allow_null")?.let { opts.allowNull = it }
        bool("allow_blank")?.let { opts.allowBlank = it }
        extra["default"]?.let {
            opts.default = it
            opts.hasDefault = true
            opts.required = false
        }
        PyLiterals.int(extra["min_length"])?.let { opts.minLength = it }
        PyLiterals.int(extra["max_length"])?.let { opts.maxLength = it }
        PyLiterals.number(extra["min_value"])?.let { opts.minValue = it }
        PyLiterals.number(extra["max_value"])?.let { opts.maxValue = it }
    }

    private fun arrayChild(field: ModelField): Schema? {
        val base = PyLiterals.positional(field.call).firstOrNull() ?: field.keyword("base_field")
        val call = PyLiterals.unparen(base) as? PyCallExpression ?: return null
        val kind = when (val sym = symbols.resolve(call.callee, field.file)) {
            is Sym.Lib -> DjangoSchemaCatalog.modelFields[sym.name]
            else -> null
        } ?: return null
        val child = ModelField("item", kind, call, field.owner, field.file)
        val opts = models.options(child, forForm = false)
        return FieldSchemas.scalar(models.fieldKind(child, opts.choices != null), opts)
    }

    private fun relationPk(field: DeclaredField): Schema? = models.primaryKeyOfQueryset(PyLiterals.keyword(field.call, "queryset"), field.file)

    private fun modelPk(field: ModelField): Schema? {
        val related = models.relatedModel(field) ?: return null
        return models.primaryKeySchema(related, PyLiterals.string(field.keyword("to_field")))
    }

    /** Turns a classified field and its options into the schema of the request member; null leaves the field out. */
    private fun assemble(
        where: String,
        kind: FieldKind,
        opts: FieldOpts,
        call: PyCallExpression?,
        file: PyFile,
        serializer: PyClass?,
        relationPk: Schema? = null,
        listChild: Schema? = null,
        fileCall: PyCallExpression? = null,
    ): Prop? {
        if (opts.unresolved != null) {
            session.skipped(where, Gap.UNSUPPORTED_FIELD)
            return null
        }
        var schema: Schema = when (kind) {
            FieldKind.SERIALIZER -> {
                val cls = serializer ?: return skip(where)
                val nested = ref(cls)
                if (opts.many) Schema.arrayOf(nested).copy(minItems = if (opts.allowEmpty == false) 1 else null) else nested
            }
            FieldKind.PK_RELATED -> {
                val pk = relationPk ?: return skip(where)
                if (opts.many) Schema.arrayOf(pk).copy(minItems = if (opts.allowEmpty == false) 1 else null) else pk
            }
            FieldKind.LIST -> {
                val child = listChild ?: call?.let { childOf(where, it, file) }
                Schema.arrayOf(child).copy(minItems = opts.minLength, maxItems = opts.maxLength)
            }
            FieldKind.DICT -> Schema(types = listOf(JsonType.OBJECT), additional = call?.let { childOf(where, it, file) }?.let { com.routegenie.schema.model.Additional.OfSchema(it) })
            FieldKind.FILE, FieldKind.IMAGE -> fileSchema(where, kind, fileCall, file)
            FieldKind.UNSUPPORTED -> return skip(where)
            else -> FieldSchemas.scalar(kind, opts) ?: return skip(where)
        }
        if (opts.many && kind != FieldKind.SERIALIZER && kind != FieldKind.PK_RELATED && kind != FieldKind.LIST) schema = Schema.arrayOf(schema)
        if (opts.allowNull) schema = Schema.nullable(schema)
        opts.description?.let { schema = schema.copy(description = it) }
        if (opts.writeOnly) schema = schema.copy(writeOnly = true)
        val required = opts.required ?: !opts.hasDefault
        if (opts.hasDefault) {
            val json = PyLiterals.json(opts.default) ?: opts.default?.let { mapper.enumMemberValue(it, file) }
            if (json != null) schema = schema.copy(default = json) else session.skipped(where, Gap.UNRESOLVED_DEFAULT)
        }
        return Prop(schema, required)
    }

    /** One uploaded file; what the field's validators state about it is in the extension member. */
    private fun fileSchema(where: String, kind: FieldKind, call: PyCallExpression?, file: PyFile): Schema {
        sawFile = true
        return files.spec(call, file, image = kind == FieldKind.IMAGE, where = where).toSchema()
    }

    private fun skip(where: String, reason: Gap = Gap.UNSUPPORTED_FIELD): Prop? {
        session.skipped(where, reason)
        return null
    }

    /** `ListField(child=CharField())`: the schema of the child field call, when it is a plain field. */
    private fun childOf(where: String, call: PyCallExpression, file: PyFile): Schema? {
        val child = PyLiterals.unparen(PyLiterals.keyword(call, DrfSchemaCatalog.CHILD) ?: PyLiterals.positional(call).firstOrNull()) as? PyCallExpression ?: return null
        val (kind, _) = classify(child, file) ?: return null
        if (kind == FieldKind.FILE || kind == FieldKind.IMAGE) return fileSchema("$where[]", kind, child, file)
        if (kind == FieldKind.SERIALIZER || kind == FieldKind.LIST || kind == FieldKind.DICT || kind == FieldKind.PK_RELATED) return null
        return FieldSchemas.scalar(kind, options.fromCall(child, file))
    }

    // --- Meta -------------------------------------------------------------------------------------------------------------

    /** Attributes of the `Meta` class that applies to [cls]: the nearest one in the hierarchy, with its own bases. */
    private inner class MetaReader(cls: PyClass) {
        private val metas: List<PyClass> = metaChain(cls)

        private fun metaChain(start: PyClass): List<PyClass> {
            val nearest = mapper.hierarchy.baseFirst(start).asReversed().firstNotNullOfOrNull { it.findNestedClass(DrfSchemaCatalog.META, false) } ?: return emptyList()
            val out = ArrayList<PyClass>()
            fun walk(meta: PyClass, depth: Int) {
                if (depth > Limits.SCHEMA_MAX_NESTING) return
                out += meta
                for (base in mapper.hierarchy.info(meta).projectBases) walk(base, depth + 1)
            }
            walk(nearest, 0)
            return out
        }

        fun attribute(name: String): PyExpression? {
            for (meta in metas) {
                for (statement in meta.statementList.statements) {
                    val assignment = statement as? PyAssignmentStatement ?: continue
                    if ((assignment.targets.singleOrNull() as? PyTargetExpression)?.name == name) return assignment.assignedValue
                }
            }
            return null
        }

        fun names(name: String): Set<String> = PyLiterals.strings(attribute(name))?.toSet() ?: emptySet()

        fun model(): PyClass? {
            val expression = attribute(DrfSchemaCatalog.META_MODEL) ?: return null
            val file = (metas.firstOrNull()?.containingFile as? PyFile) ?: return null
            return (symbols.resolve(expression, expression.containingFile as? PyFile ?: file) as? Sym.Cls)?.cls
        }

        fun extraKwargs(): Map<String, Map<String, PyExpression>> {
            val dict = PyLiterals.unparen(attribute(DrfSchemaCatalog.META_EXTRA_KWARGS)) as? PyDictLiteralExpression ?: return emptyMap()
            val out = HashMap<String, Map<String, PyExpression>>()
            for (pair in dict.elements) {
                val key = PyLiterals.string(pair.key) ?: continue
                val inner = PyLiterals.unparen(pair.value) as? PyDictLiteralExpression ?: continue
                val values = HashMap<String, PyExpression>()
                for (entry in inner.elements) {
                    val k = PyLiterals.string(entry.key) ?: continue
                    entry.value?.let { values[k] = it }
                }
                out[key] = values
            }
            return out
        }
    }

    private companion object {
        const val ID = "id"
    }
}
