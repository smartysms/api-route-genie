package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyTargetExpression
import com.routegenie.Limits
import com.routegenie.schema.model.Schema
import java.util.IdentityHashMap

/** The Django model field classes, by what they hold. */
internal enum class ModelKind {
    AUTO, INTEGER, POSITIVE_INTEGER, FLOAT, DECIMAL, BOOLEAN, NULL_BOOLEAN,
    CHAR, TEXT, EMAIL, URL, SLUG, UUID, IP, DATE, DATETIME, TIME, DURATION,
    FILE, JSON, ARRAY, HSTORE, FOREIGN_KEY, MANY_TO_MANY, UNSUPPORTED,
}

/** Every fact about Django models, forms and generic views that body extraction relies on. */
internal object DjangoSchemaCatalog {
    private val modelRoots = listOf(
        "django.db.models", "django.db.models.fields", "django.db.models.fields.related", "django.db.models.fields.files",
        "django.db.models.fields.json", "django.contrib.postgres.fields", "django.contrib.postgres.fields.array",
        "django.contrib.postgres.fields.hstore", "django.contrib.postgres.fields.jsonb",
    )
    private val formRoots = listOf("django.forms", "django.forms.fields", "django.forms.models")

    private fun names(roots: List<String>, vararg simple: String): Set<String> = roots.flatMap { r -> simple.map { "$r.$it" } }.toSet()

    val modelBases: Set<String> = setOf("django.db.models.Model", "django.db.models.base.Model")
    val choiceClasses: Set<String> = names(listOf("django.db.models", "django.db.models.enums"), "TextChoices", "IntegerChoices", "Choices")

    val modelFields: Map<String, ModelKind> = buildMap {
        fun put(kind: ModelKind, vararg simple: String) = names(modelRoots, *simple).forEach { this[it] = kind }
        put(ModelKind.AUTO, "AutoField", "BigAutoField", "SmallAutoField")
        put(ModelKind.INTEGER, "IntegerField", "BigIntegerField", "SmallIntegerField")
        put(ModelKind.POSITIVE_INTEGER, "PositiveIntegerField", "PositiveBigIntegerField", "PositiveSmallIntegerField")
        put(ModelKind.FLOAT, "FloatField")
        put(ModelKind.DECIMAL, "DecimalField")
        put(ModelKind.BOOLEAN, "BooleanField")
        put(ModelKind.NULL_BOOLEAN, "NullBooleanField")
        put(ModelKind.CHAR, "CharField")
        put(ModelKind.TEXT, "TextField")
        put(ModelKind.EMAIL, "EmailField")
        put(ModelKind.URL, "URLField")
        put(ModelKind.SLUG, "SlugField")
        put(ModelKind.UUID, "UUIDField")
        put(ModelKind.IP, "GenericIPAddressField")
        put(ModelKind.DATE, "DateField")
        put(ModelKind.DATETIME, "DateTimeField")
        put(ModelKind.TIME, "TimeField")
        put(ModelKind.DURATION, "DurationField")
        put(ModelKind.FILE, "FileField", "ImageField")
        put(ModelKind.JSON, "JSONField")
        put(ModelKind.ARRAY, "ArrayField")
        put(ModelKind.HSTORE, "HStoreField")
        put(ModelKind.FOREIGN_KEY, "ForeignKey", "OneToOneField")
        put(ModelKind.MANY_TO_MANY, "ManyToManyField")
        put(ModelKind.UNSUPPORTED, "BinaryField", "FilePathField")
    }

    /** Defaults of `max_length` that the field classes themselves set. */
    val defaultMaxLength: Map<ModelKind, Int> = mapOf(ModelKind.EMAIL to 254, ModelKind.URL to 200, ModelKind.SLUG to 50)

    val validators: Map<String, String> = buildMap {
        for ((simple, key) in listOf("MinValueValidator" to "min_value", "MaxValueValidator" to "max_value", "MinLengthValidator" to "min_length", "MaxLengthValidator" to "max_length", "RegexValidator" to "pattern")) {
            this["django.core.validators.$simple"] = key
        }
    }

    // --- Forms -----------------------------------------------------------------------------------------------------------

    val formBases: Set<String> = names(formRoots, "Form", "BaseForm")
    val modelFormBases: Set<String> = names(formRoots, "ModelForm", "BaseModelForm")

    val formFields: Map<String, FieldKind> = buildMap {
        fun put(kind: FieldKind, vararg simple: String) = names(formRoots, *simple).forEach { this[it] = kind }
        put(FieldKind.CHAR, "CharField")
        put(FieldKind.EMAIL, "EmailField")
        put(FieldKind.URL, "URLField")
        put(FieldKind.SLUG, "SlugField")
        put(FieldKind.UUID, "UUIDField")
        put(FieldKind.IP, "GenericIPAddressField", "IPAddressField")
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
        put(FieldKind.CHOICE, "ChoiceField", "TypedChoiceField")
        put(FieldKind.MULTIPLE_CHOICE, "MultipleChoiceField", "TypedMultipleChoiceField")
        put(FieldKind.JSON, "JSONField")
        put(FieldKind.FILE, "FileField", "ImageField", "FilePathField")
        put(FieldKind.PK_RELATED, "ModelChoiceField")
        put(FieldKind.MANY_PK_RELATED, "ModelMultipleChoiceField")
        put(FieldKind.EXCLUDED, "HiddenInput")
        put(FieldKind.UNSUPPORTED, "MultiValueField", "SplitDateTimeField", "ComboField", "InlineForeignKeyField")
    }

    // --- Generic class based views ---------------------------------------------------------------------------------------

    val formViews: Set<String> = names(listOf("django.views.generic", "django.views.generic.edit"), "FormView", "CreateView", "UpdateView")
    val modelFormViews: Set<String> = names(listOf("django.views.generic", "django.views.generic.edit"), "CreateView", "UpdateView")

    const val ATTR_FORM_CLASS = "form_class"
    const val ATTR_MODEL = "model"
    const val ATTR_FIELDS = "fields"
    const val METHOD_GET_FORM_CLASS = "get_form_class"
    const val META = "Meta"
    const val ALL_FIELDS = "__all__"
}

internal class ModelField(
    val name: String,
    val kind: ModelKind,
    val call: PyCallExpression,
    val owner: PyClass,
    val file: PyFile,
) {
    fun keyword(name: String) = PyLiterals.keyword(call, name)
}

internal class ModelInfo(val cls: PyClass, val fields: Map<String, ModelField>, val members: Set<String>) {
    val primaryKey: ModelField? get() = fields.values.firstOrNull { PyLiterals.bool(it.keyword("primary_key")) == true }
}

/** Reads Django model classes from the project and derives what a serializer or form makes of each field. */
internal class DjangoModels(private val mapper: TypeMapper, private val options: OptionReader) {
    private val symbols = mapper.session.symbols
    private val infos = IdentityHashMap<PyClass, ModelInfo?>()

    fun isModel(cls: PyClass): Boolean = mapper.hierarchy.inherits(cls, DjangoSchemaCatalog.modelBases)

    fun info(cls: PyClass): ModelInfo? {
        if (infos.containsKey(cls)) return infos[cls]
        val info = read(cls)
        infos[cls] = info
        return info
    }

    private fun read(cls: PyClass): ModelInfo? {
        if (!isModel(cls)) return null
        mapper.session.enterClass()
        val fields = LinkedHashMap<String, ModelField>()
        val members = HashSet<String>()
        for (c in mapper.hierarchy.baseFirst(cls)) {
            val file = c.containingFile as? PyFile ?: continue
            for (statement in c.statementList.statements) {
                when (statement) {
                    is PyFunction -> statement.name?.let { members += it }
                    is PyAssignmentStatement -> {
                        val name = (statement.targets.singleOrNull() as? PyTargetExpression)?.name ?: continue
                        members += name
                        val call = PyLiterals.unparen(statement.assignedValue) as? PyCallExpression ?: continue
                        val kind = kindOf(call, file) ?: continue
                        fields[name] = ModelField(name, kind, call, c, file)
                    }
                    else -> Unit
                }
            }
        }
        return ModelInfo(cls, fields, members)
    }

    /** The kind of a field constructor call: a Django field class, or a project class derived from one. */
    private fun kindOf(call: PyCallExpression, file: PyFile): ModelKind? {
        return when (val sym = symbols.resolve(call.callee, file)) {
            is Sym.Lib -> DjangoSchemaCatalog.modelFields[sym.name]
            is Sym.Cls -> mapper.hierarchy.info(sym.cls).libraryBases.firstNotNullOfOrNull { DjangoSchemaCatalog.modelFields[it] }
            else -> null
        }
    }

    /** The serializer / form field kind a model field turns into. */
    fun fieldKind(field: ModelField, choices: Boolean): FieldKind = if (choices) FieldKind.CHOICE else when (field.kind) {
        ModelKind.AUTO -> FieldKind.EXCLUDED
        ModelKind.INTEGER -> FieldKind.INTEGER
        ModelKind.POSITIVE_INTEGER -> FieldKind.POSITIVE_INTEGER
        ModelKind.FLOAT -> FieldKind.FLOAT
        ModelKind.DECIMAL -> FieldKind.DECIMAL
        ModelKind.BOOLEAN -> FieldKind.BOOLEAN
        ModelKind.NULL_BOOLEAN -> FieldKind.NULL_BOOLEAN
        ModelKind.CHAR, ModelKind.TEXT -> FieldKind.CHAR
        ModelKind.EMAIL -> FieldKind.EMAIL
        ModelKind.URL -> FieldKind.URL
        ModelKind.SLUG -> FieldKind.SLUG
        ModelKind.UUID -> FieldKind.UUID
        ModelKind.IP -> FieldKind.IP
        ModelKind.DATE -> FieldKind.DATE
        ModelKind.DATETIME -> FieldKind.DATETIME
        ModelKind.TIME -> FieldKind.TIME
        ModelKind.DURATION -> FieldKind.DURATION
        ModelKind.FILE -> FieldKind.FILE
        ModelKind.JSON -> FieldKind.JSON
        ModelKind.ARRAY -> FieldKind.LIST
        ModelKind.HSTORE -> FieldKind.DICT
        ModelKind.FOREIGN_KEY -> FieldKind.PK_RELATED
        ModelKind.MANY_TO_MANY -> FieldKind.MANY_PK_RELATED
        ModelKind.UNSUPPORTED -> FieldKind.UNSUPPORTED
    }

    /**
     * What a ModelSerializer (or ModelForm) derives for [field]: read-only for generated values, not required when the model
     * supplies a default or allows blank and null, choices as an enum, and the length limits of the model field.
     */
    fun options(field: ModelField, forForm: Boolean): FieldOpts {
        val o = FieldOpts()
        val file = field.file
        val blank = PyLiterals.bool(field.keyword("blank")) == true
        val nullable = PyLiterals.bool(field.keyword("null")) == true
        val defaulted = field.keyword("default") != null || field.keyword("db_default") != null
        val generated = field.kind == ModelKind.AUTO ||
            PyLiterals.bool(field.keyword("editable")) == false ||
            PyLiterals.bool(field.keyword("auto_now")) == true || PyLiterals.bool(field.keyword("auto_now_add")) == true
        if (generated) {
            o.readOnly = true
            return o
        }
        field.keyword("choices")?.let { expression ->
            val values = options.choices(expression, file, 0)
            if (values == null) o.unresolved = "choices cannot be read" else o.choices = values
        }
        o.allowNull = nullable && !forForm
        if (forForm) {
            // A form field is required unless the model allows blank; booleans are never required (an unchecked box sends nothing).
            o.required = !blank && field.kind != ModelKind.BOOLEAN
        } else if (defaulted || blank || nullable) {
            o.required = false
        }
        if (blank && (field.kind == ModelKind.CHAR || field.kind == ModelKind.TEXT || field.kind == ModelKind.EMAIL || field.kind == ModelKind.URL || field.kind == ModelKind.SLUG)) {
            o.allowBlank = true
        }
        field.keyword("default")?.let {
            o.default = it
            o.hasDefault = true
        }
        o.maxLength = PyLiterals.int(field.keyword("max_length")) ?: DjangoSchemaCatalog.defaultMaxLength[field.kind]
        o.maxDigits = PyLiterals.int(field.keyword("max_digits"))
        o.decimalPlaces = PyLiterals.int(field.keyword("decimal_places"))
        o.protocol = PyLiterals.string(field.keyword("protocol"))
        if ((field.keyword("protocol") != null && o.protocol == null) || (field.keyword("max_digits") != null && o.maxDigits == null) || (field.keyword("decimal_places") != null && o.decimalPlaces == null)) {
            o.unresolved = "decimal or protocol option is not a literal"
        }
        if (field.kind == ModelKind.TEXT) o.maxLength = null
        PyLiterals.string(field.keyword("help_text"))?.let { o.description = it }
        options.validators(field.keyword("validators"), field.file, o)
        return o
    }

    // --- Relations -------------------------------------------------------------------------------------------------------

    /** The model class a ForeignKey / OneToOneField / ManyToManyField points at. */
    fun relatedModel(field: ModelField): PyClass? {
        val target = PyLiterals.positional(field.call).firstOrNull() ?: field.keyword("to") ?: return null
        PyLiterals.string(target)?.let { return byName(it, field) }
        return (symbols.resolve(target, field.file) as? Sym.Cls)?.cls
    }

    private fun byName(name: String, field: ModelField): PyClass? {
        if (name == SELF) return field.owner
        if (!name.contains('.')) return (symbols.lookup(field.file, name) as? Sym.Cls)?.cls
        val label = name.substringBeforeLast('.')
        val model = name.substringAfterLast('.')
        val module = mapper.session.moduleFile(field.file, "$label.models", 0) ?: return null
        return (symbols.lookup(module, model) as? Sym.Cls)?.cls
    }

    /** The JSON type of the key that references [model]: its primary key (or `to_field`), integer when it is the implicit id. */
    fun primaryKeySchema(model: PyClass, toField: String? = null, depth: Int = 0): Schema? {
        if (depth > Limits.SCHEMA_MAX_NESTING) return null
        val info = info(model) ?: return null
        val pk = if (toField != null) info.fields[toField] else info.primaryKey
        if (pk == null) return if (toField == null) Schema.INTEGER else null
        if (pk.kind == ModelKind.FOREIGN_KEY) {
            val target = relatedModel(pk) ?: return null
            return primaryKeySchema(target, PyLiterals.string(pk.keyword("to_field")), depth + 1)
        }
        val kind = if (pk.kind == ModelKind.AUTO) FieldKind.INTEGER else fieldKind(pk, choices = false)
        return FieldSchemas.scalar(kind, FieldOpts().also { it.allowBlank = false })
    }

    /** The primary key type of the model that the `queryset` of a related field names (`Author.objects.all()`). */
    fun primaryKeyOfQueryset(queryset: com.jetbrains.python.psi.PyExpression?, file: PyFile): Schema? {
        var current: com.jetbrains.python.psi.PyExpression? = queryset
        while (current is PyCallExpression) current = current.callee
        while (current is com.jetbrains.python.psi.PyReferenceExpression && current.referencedName != "objects" && current.qualifier != null) current = current.qualifier
        val reference = (current as? com.jetbrains.python.psi.PyReferenceExpression)?.qualifier ?: return null
        val model = (symbols.resolve(reference, file) as? Sym.Cls)?.cls ?: return null
        return primaryKeySchema(model)
    }

    /** A model field name that is a method or property of the model: DRF makes it read-only. */
    fun isReadOnlyMember(info: ModelInfo, name: String): Boolean = name in info.members && name !in info.fields

    companion object {
        private const val SELF = "self"
    }
}
