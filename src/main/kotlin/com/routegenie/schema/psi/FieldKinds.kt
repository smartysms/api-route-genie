package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.routegenie.Limits
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import java.math.BigDecimal

/** What a serializer, form or model field means for a request body, independent of which framework declared it. */
internal enum class FieldKind {
    CHAR, EMAIL, URL, SLUG, UUID, IP, REGEX,
    INTEGER, POSITIVE_INTEGER, FLOAT, DECIMAL, BOOLEAN, NULL_BOOLEAN,
    DATETIME, DATE, TIME, DURATION,
    CHOICE, MULTIPLE_CHOICE,
    LIST, DICT, JSON,
    FILE,

    /** Read-only or hidden: never part of a request. */
    EXCLUDED,
    PK_RELATED, SLUG_RELATED, URL_RELATED, MANY_PK_RELATED,

    /** A nested serializer, handled by the serializer reader. */
    SERIALIZER,

    /** Known, but what it accepts cannot be told from the code. */
    UNSUPPORTED,
}

/** The options of one field, from its call (declared field) or derived from a model field, ready to become a schema. */
internal class FieldOpts {
    var readOnly = false
    var writeOnly = false
    var required: Boolean? = null
    var default: PyExpression? = null
    var hasDefault = false
    var allowNull = false
    var allowBlank = false
    var minLength: Int? = null
    var maxLength: Int? = null
    var minValue: BigDecimal? = null
    var maxValue: BigDecimal? = null
    var choices: List<Json>? = null
    var pattern: String? = null
    var many = false
    var description: String? = null
    var disabled = false
    var allowEmpty: Boolean? = null

    /** Set when an option that decides the type was written in a way that cannot be read (choices from a function, say). */
    var unresolved: String? = null
}

/** Reads the keywords of a field call into [FieldOpts]; the names are the ones DRF fields and Django form fields share. */
internal class OptionReader(private val mapper: TypeMapper) {
    private val symbols = mapper.session.symbols

    fun fromCall(call: PyCallExpression, file: PyFile): FieldOpts {
        val o = FieldOpts()
        fun kw(name: String) = PyLiterals.keyword(call, name)
        o.readOnly = PyLiterals.bool(kw("read_only")) == true
        o.writeOnly = PyLiterals.bool(kw("write_only")) == true
        o.disabled = PyLiterals.bool(kw("disabled")) == true
        o.required = PyLiterals.bool(kw("required"))
        kw("default")?.let {
            o.default = it
            o.hasDefault = true
        }
        o.allowNull = PyLiterals.bool(kw("allow_null")) == true
        o.allowBlank = PyLiterals.bool(kw("allow_blank")) == true
        o.many = PyLiterals.bool(kw("many")) == true
        o.minLength = PyLiterals.int(kw("min_length"))
        o.maxLength = PyLiterals.int(kw("max_length"))
        o.minValue = PyLiterals.number(kw("min_value"))
        o.maxValue = PyLiterals.number(kw("max_value"))
        o.description = PyLiterals.string(kw("help_text"))
        o.allowEmpty = PyLiterals.bool(kw("allow_empty"))
        for ((name, present) in listOf("min_length" to kw("min_length"), "max_length" to kw("max_length"), "min_value" to kw("min_value"), "max_value" to kw("max_value"))) {
            if (present != null && !isLiteralNumber(present)) o.unresolved = "$name is not a literal"
        }
        kw("choices")?.let { expression ->
            val values = choices(expression, file, 0)
            if (values == null) o.unresolved = "choices cannot be read" else o.choices = values
        }
        o.pattern = PyLiterals.string(kw("regex") ?: PyLiterals.positional(call).firstOrNull())
        return o
    }

    private fun isLiteralNumber(e: PyExpression) = PyLiterals.number(e) != null

    /** Keys of a choices declaration: a list of values, of `(value, label)` pairs, grouped pairs, or a dict. */
    fun choices(expression: PyExpression?, file: PyFile, depth: Int): List<Json>? {
        if (depth > Limits.SCHEMA_MAX_NESTING) return null
        val e = PyLiterals.unparen(expression) ?: return null
        if (e is PyDictLiteralExpression) {
            return e.elements.map { PyLiterals.json(it.key)?.takeIf(::isScalar) ?: return null }
        }
        PyLiterals.elements(e)?.let { items ->
            val out = ArrayList<Json>()
            for (item in items) {
                val scalar = PyLiterals.json(item)
                if (scalar != null && isScalar(scalar)) {
                    out += scalar
                    continue
                }
                val pair = PyLiterals.elements(item) ?: return null
                if (pair.size != 2) return null
                val key = PyLiterals.json(pair[0])
                if (key != null && isScalar(key) && PyLiterals.elements(pair[1]) == null) {
                    out += key
                } else {
                    // ("Group", (("a", "A"), ("b", "B")))
                    out += choices(pair[1], file, depth + 1) ?: return null
                }
            }
            return out.distinct()
        }
        val reference = e as? PyReferenceExpression ?: return null
        val name = reference.referencedName ?: return null
        val qualifier = reference.qualifier
        if (qualifier == null) {
            // A name written inside a class body (`choices=FORMATS` next to the field) is a class attribute before it is a module name.
            classAttribute(com.intellij.psi.util.PsiTreeUtil.getParentOfType(reference, com.jetbrains.python.psi.PyClass::class.java), name)?.let {
                return choices(it, file, depth + 1)
            }
            return (symbols.resolve(reference, file) as? Sym.Value)?.let { choices(it.expr, it.expr.containingFile as? PyFile ?: file, depth + 1) }
        }
        val cls = (symbols.resolve(qualifier, file) as? Sym.Cls)?.cls ?: return null
        val classFile = cls.containingFile as? PyFile ?: return null
        if (name == CHOICES_PROPERTY) return choicesOfEnum(cls)
        return classAttribute(cls, name)?.let { choices(it, classFile, depth + 1) }
    }

    /** The value assigned to [name] in [cls] or the nearest project base class that assigns it. */
    private fun classAttribute(cls: com.jetbrains.python.psi.PyClass?, name: String): PyExpression? {
        if (cls == null) return null
        return mapper.hierarchy.baseFirst(cls).asReversed().firstNotNullOfOrNull { c ->
            c.statementList.statements.filterIsInstance<PyAssignmentStatement>()
                .firstOrNull { (it.targets.singleOrNull() as? PyTargetExpression)?.name == name }?.assignedValue
        }
    }

    /** `Color.choices` of a Django `TextChoices` / `IntegerChoices`: the first element of each member's value. */
    private fun choicesOfEnum(cls: com.jetbrains.python.psi.PyClass): List<Json>? {
        if (mapper.hierarchy.info(cls).libraryBases.none { it in DjangoSchemaCatalog.choiceClasses }) return null
        val out = ArrayList<Json>()
        for (statement in cls.statementList.statements) {
            val assignment = statement as? PyAssignmentStatement ?: continue
            val name = (assignment.targets.singleOrNull() as? PyTargetExpression)?.name ?: continue
            if (name.startsWith("_")) continue
            val value = assignment.assignedValue ?: continue
            val first = PyLiterals.elements(value)?.firstOrNull() ?: value
            out += PyLiterals.json(first)?.takeIf(::isScalar) ?: return null
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun isScalar(json: Json) = json is Json.Str || json is Json.Num || json is Json.Bool

    private companion object {
        const val CHOICES_PROPERTY = "choices"
    }
}

/** The schema a scalar field kind accepts; null when the options leave it undetermined. */
internal object FieldSchemas {
    private const val SLUG_PATTERN = "^[-a-zA-Z0-9_]+$"

    fun scalar(kind: FieldKind, o: FieldOpts): Schema? {
        val base: Schema = when (kind) {
            FieldKind.CHAR -> Schema.STRING.copy(minLength = if (o.allowBlank) o.minLength else maxOf(1, o.minLength ?: 1), maxLength = o.maxLength)
            FieldKind.EMAIL -> Schema.of(JsonType.STRING, "email").copy(minLength = if (o.allowBlank) o.minLength else maxOf(1, o.minLength ?: 1), maxLength = o.maxLength)
            FieldKind.URL -> Schema.of(JsonType.STRING, "uri").copy(minLength = if (o.allowBlank) o.minLength else maxOf(1, o.minLength ?: 1), maxLength = o.maxLength)
            FieldKind.SLUG -> Schema.STRING.copy(pattern = SLUG_PATTERN, minLength = if (o.allowBlank) o.minLength else maxOf(1, o.minLength ?: 1), maxLength = o.maxLength)
            FieldKind.UUID -> Schema.of(JsonType.STRING, "uuid")
            FieldKind.IP -> Schema(anyOf = listOf(Schema.of(JsonType.STRING, "ipv4"), Schema.of(JsonType.STRING, "ipv6")))
            FieldKind.REGEX -> Schema.STRING.copy(pattern = o.pattern ?: return null, minLength = o.minLength, maxLength = o.maxLength)
            FieldKind.INTEGER -> Schema.INTEGER.copy(minimum = o.minValue, maximum = o.maxValue)
            FieldKind.POSITIVE_INTEGER -> Schema.INTEGER.copy(minimum = o.minValue?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO, maximum = o.maxValue)
            FieldKind.FLOAT, FieldKind.DECIMAL -> Schema.NUMBER.copy(minimum = o.minValue, maximum = o.maxValue)
            FieldKind.BOOLEAN -> Schema.BOOLEAN
            FieldKind.NULL_BOOLEAN -> Schema.nullable(Schema.BOOLEAN)
            FieldKind.DATETIME -> Schema.of(JsonType.STRING, "date-time")
            FieldKind.DATE -> Schema.of(JsonType.STRING, "date")
            FieldKind.TIME -> Schema.of(JsonType.STRING, "time")
            FieldKind.DURATION -> Schema.of(JsonType.STRING, "duration")
            FieldKind.CHOICE -> choice(o.choices ?: return null)
            FieldKind.MULTIPLE_CHOICE -> Schema.arrayOf(choice(o.choices ?: return null)).copy(uniqueItems = true, minItems = if (o.allowEmpty == false) 1 else null)
            FieldKind.JSON -> Schema.EMPTY
            FieldKind.SLUG_RELATED -> Schema.STRING
            FieldKind.URL_RELATED -> Schema.of(JsonType.STRING, "uri")
            else -> return null
        }
        return base
    }

    private fun choice(values: List<Json>): Schema {
        val types = values.map { value ->
            when (value) {
                is Json.Str -> JsonType.STRING
                is Json.Bool -> JsonType.BOOLEAN
                is Json.Num -> if (value.value.stripTrailingZeros().scale() <= 0) JsonType.INTEGER else JsonType.NUMBER
                else -> JsonType.NULL
            }
        }.distinct()
        return Schema(types = if (types.size == 1) types else emptyList(), enumValues = values)
    }
}
