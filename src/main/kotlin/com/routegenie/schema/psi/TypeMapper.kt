package com.routegenie.schema.psi

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.LanguageLevel
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyElementGenerator
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyNoneLiteralExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PySubscriptionExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.routegenie.Limits
import com.routegenie.schema.model.Additional
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import com.routegenie.schema.model.jsonType
import java.util.IdentityHashMap

/**
 * A mapped annotation. [meta] are the `Annotated[...]` metadata that carry meaning beyond a constraint (a `Field(...)`, a
 * FastAPI `Body(...)`); constraints inside them are already applied to [schema]. [requiredMarker] is true/false for
 * `Required[...]` / `NotRequired[...]` of a TypedDict.
 */
internal class Typed(val schema: Schema, val meta: List<PyExpression> = emptyList(), val requiredMarker: Boolean? = null) {
    fun withSchema(changed: Schema) = Typed(changed, meta, requiredMarker)
}

internal enum class ClassKind { PYDANTIC, DATACLASS, TYPED_DICT, ENUM, OTHER }

/**
 * Python annotations to [Schema]: scalars, containers, unions, literals, enums and models (which become `$defs`). A type
 * that cannot be determined from the code gives null, never a guess; the caller then leaves that field out.
 */
internal class TypeMapper(val session: Session) {
    val hierarchy = Hierarchy(session)

    /** The `$defs` collected while mapping, by name. */
    val defs = LinkedHashMap<String, Schema>()
    val typedDictDefs = HashSet<String>()

    private val symbols = session.symbols
    private val defNames = IdentityHashMap<PyClass, String>()
    private val enumMembers = IdentityHashMap<PyClass, Map<String, Json>?>()
    private val kinds = IdentityHashMap<PyClass, ClassKind>()
    private val models = PydanticModels(this)

    fun map(e: PyExpression?, file: PyFile, depth: Int = 0): Typed? {
        session.node()
        if (depth > Limits.SCHEMA_RESOLVE_DEPTH) return null
        return when (val expression = PyLiterals.unparen(e)) {
            is PyStringLiteralExpression -> forward(expression, file, depth)
            is PyNoneLiteralExpression -> Typed(Schema.NULL)
            is PyBinaryExpression -> if (expression.operator == PyTokenTypes.OR) union(PyLiterals.unionOperands(expression), file, depth) else null
            is PySubscriptionExpression -> subscript(expression, file, depth)
            is PyReferenceExpression -> named(expression, file, depth)
            is PyCallExpression -> constrained(expression, file, depth)
            else -> null
        }
    }

    // --- Names ---------------------------------------------------------------------------------------------------------

    private fun named(e: PyReferenceExpression, file: PyFile, depth: Int): Typed? = when (val sym = symbols.resolve(e, file)) {
        is Sym.Lib -> libType(sym.name)
        is Sym.Cls -> classType(sym.cls)
        is Sym.Value -> valueType(sym.expr, depth)
        is Sym.TypeAlias -> map(sym.expr, sym.expr.containingFile as? PyFile ?: file, depth + 1)
        else -> null
    }

    private fun libType(name: String): Typed? {
        PydanticCatalog.scalar(name)?.let { return Typed(it) }
        return when (name) {
            in PydanticCatalog.lists, in PydanticCatalog.tuples -> Typed(Schema(types = listOf(JsonType.ARRAY)))
            in PydanticCatalog.sets -> Typed(Schema(types = listOf(JsonType.ARRAY), uniqueItems = true))
            in PydanticCatalog.maps -> Typed(Schema(types = listOf(JsonType.OBJECT)))
            else -> null
        }
    }

    /** `Alias = <type expression>` and `UserId = NewType("UserId", int)`. */
    private fun valueType(value: PyExpression, depth: Int): Typed? {
        val file = value.containingFile as? PyFile ?: return null
        val expression = PyLiterals.unparen(value)
        if (expression is PyCallExpression && symbols.libName(expression.callee, file) in PydanticCatalog.newType) {
            return map(PyLiterals.positional(expression).getOrNull(1), file, depth + 1)
        }
        // A string or number assigned to a name is a constant, not a type.
        if (expression is PyStringLiteralExpression) return null
        return map(expression, file, depth + 1)
    }

    fun kindOf(cls: PyClass): ClassKind = kinds.getOrPut(cls) {
        val libs = hierarchy.info(cls).libraryBases
        val file = cls.containingFile as? PyFile
        when {
            libs.any { it in PydanticCatalog.enumBases || it in PydanticCatalog.enumFlagBases } -> ClassKind.ENUM
            libs.any { it in PydanticCatalog.typedDictBases } -> ClassKind.TYPED_DICT
            libs.any { it in PydanticCatalog.baseModels } -> ClassKind.PYDANTIC
            file != null && isDataclass(cls, file) -> ClassKind.DATACLASS
            else -> ClassKind.OTHER
        }
    }

    fun isDataclass(cls: PyClass, file: PyFile): Boolean =
        cls.decoratorList?.decorators.orEmpty().any { symbols.libName(it.callee, file) in PydanticCatalog.dataclassDecorators }

    fun isV1(cls: PyClass): Boolean = hierarchy.info(cls).libraryBases.any { base -> PydanticCatalog.v1Roots.any { base.startsWith("$it.") } }

    private fun classType(cls: PyClass): Typed? = when (kindOf(cls)) {
        ClassKind.ENUM -> enumSchema(cls)?.let { Typed(it) }
        ClassKind.PYDANTIC, ClassKind.DATACLASS, ClassKind.TYPED_DICT -> modelRef(cls)?.let { Typed(it) }
        ClassKind.OTHER -> hierarchy.info(cls).libraryBases.firstNotNullOfOrNull { PydanticCatalog.scalar(it) }?.let { Typed(it) }
    }

    // --- Models ---------------------------------------------------------------------------------------------------------

    /** `$ref` to the definition of [cls], which is built on first use; recursive models refer to themselves. */
    fun modelRef(cls: PyClass): Schema? {
        val ref = define(cls) { models.build(cls, kindOf(cls)) }
        if (kindOf(cls) == ClassKind.TYPED_DICT) typedDictDefs += defNames.getValue(cls)
        return ref
    }

    /**
     * `$ref` to the definition of [cls], built by [build] on first use. The name is reserved before building, so a model that
     * refers to itself (directly or through others) finds its own reference instead of recursing.
     */
    fun define(cls: PyClass, build: () -> Schema): Schema {
        defNames[cls]?.let { return Schema.ref(it) }
        session.enterClass()
        val name = uniqueName(cls.name ?: "Model")
        defNames[cls] = name
        defs[name] = Schema.EMPTY
        defs[name] = build()
        return Schema.ref(name)
    }

    private fun uniqueName(base: String): String {
        if (base !in defs) return base
        var n = 2
        while ("${base}_$n" in defs) n++
        return "${base}_$n"
    }

    // --- Enums ----------------------------------------------------------------------------------------------------------

    /** Member name to value of an enum class; null when a member's value is not a plain literal (the enum is then unknown). */
    fun enumMembersOf(cls: PyClass): Map<String, Json>? {
        if (enumMembers.containsKey(cls)) return enumMembers[cls]
        val members = readEnumMembers(cls)
        enumMembers[cls] = members
        return members
    }

    private fun readEnumMembers(cls: PyClass): Map<String, Json>? {
        val libs = hierarchy.info(cls).libraryBases
        if (libs.any { it in PydanticCatalog.enumFlagBases }) return null
        val file = cls.containingFile as? PyFile ?: return null
        val textual = libs.any { it in PydanticCatalog.enumStringBases }
        val integral = !textual && libs.none { it == "builtins.str" }
        val members = LinkedHashMap<String, Json>()
        var counter = 0L
        for (statement in cls.statementList.statements) {
            val assignment = statement as? PyAssignmentStatement ?: continue
            val name = (assignment.targets.singleOrNull() as? PyTargetExpression)?.name ?: continue
            if (name.startsWith("_")) continue
            val value = assignment.assignedValue ?: continue
            val call = PyLiterals.unparen(value) as? PyCallExpression
            val json: Json? = if (call != null && symbols.libName(call.callee, file) in PydanticCatalog.enumAuto) {
                when {
                    textual -> Json.Str(name.lowercase())
                    integral -> Json.num(++counter)
                    else -> null
                }
            } else {
                PyLiterals.json(value)?.also { if (it is Json.Num && integral) counter = it.value.toLong() }
            }
            if (json == null || json is Json.Arr || json is Json.Obj) return null
            members[name] = json
        }
        return members.takeIf { it.isNotEmpty() }
    }

    private fun enumSchema(cls: PyClass): Schema? {
        val members = enumMembersOf(cls) ?: return null
        return valuesSchema(members.values.distinct())
    }

    /** `enum` (or `const` for one value) with a `type` when all values share one. */
    fun valuesSchema(values: List<Json>): Schema {
        val types = values.map { it.jsonType() }.map { if (it == JsonType.INTEGER) JsonType.INTEGER else it }.distinct()
        val type = when {
            types.size == 1 -> types
            types.all { it == JsonType.INTEGER || it == JsonType.NUMBER } -> listOf(JsonType.NUMBER)
            else -> emptyList()
        }
        return if (values.size == 1) Schema(types = type, constValue = values.single()) else Schema(types = type, enumValues = values)
    }

    /** The value of the enum member that [e] (`Color.RED`) names, when it names one. */
    fun enumMemberValue(e: PyExpression?, file: PyFile): Json? {
        val reference = PyLiterals.unparen(e) as? PyReferenceExpression ?: return null
        val qualifier = reference.qualifier ?: return null
        val cls = (symbols.resolve(qualifier, file) as? Sym.Cls)?.cls ?: return null
        if (kindOf(cls) != ClassKind.ENUM) return null
        return enumMembersOf(cls)?.get(reference.referencedName)
    }

    // --- Typing constructs ------------------------------------------------------------------------------------------------

    private fun subscript(e: PySubscriptionExpression, file: PyFile, depth: Int): Typed? {
        val name = symbols.libName(e.operand, file) ?: return null
        val index = PyLiterals.unparen(e.indexExpression)
        val args: List<PyExpression> = if (index is PyTupleExpression) index.elements.toList() else listOfNotNull(index)
        return when (name) {
            in PydanticCatalog.optional -> map(args.singleOrNull(), file, depth + 1)?.let { it.withSchema(Schema.nullable(it.schema)) }
            in PydanticCatalog.union -> union(args, file, depth)
            in PydanticCatalog.literal -> literal(args, file)
            in PydanticCatalog.annotated -> annotated(args, file, depth)
            in PydanticCatalog.transparent -> map(args.firstOrNull(), file, depth + 1)?.let {
                Typed(it.schema, it.meta, if (name in PydanticCatalog.required) true else if (name in PydanticCatalog.notRequired) false else it.requiredMarker)
            }
            in PydanticCatalog.lists -> map(args.singleOrNull(), file, depth + 1)?.let { Typed(Schema.arrayOf(it.schema)) }
            in PydanticCatalog.sets -> map(args.singleOrNull(), file, depth + 1)?.let { Typed(Schema.arrayOf(it.schema).copy(uniqueItems = true)) }
            in PydanticCatalog.tuples -> tuple(args, file, depth)
            in PydanticCatalog.maps -> map(args.getOrNull(1), file, depth + 1)?.let { Typed(Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(it.schema))) }
            else -> null
        }
    }

    private fun union(operands: List<PyExpression>, file: PyFile, depth: Int): Typed? {
        val branches = ArrayList<Schema>()
        for (operand in operands) {
            val mapped = map(operand, file, depth + 1) ?: return null
            // A nested union is spliced in, so `Optional[Union[A, B]]` is one flat choice.
            val s = mapped.schema
            val flat = if (s.anyOf.isNotEmpty() && s.copy(anyOf = emptyList()) == Schema.EMPTY) s.anyOf else listOf(s)
            for (b in flat) if (b !in branches) branches += b
        }
        return when (branches.size) {
            0 -> null
            1 -> Typed(branches.single())
            else -> Typed(Schema(anyOf = branches))
        }
    }

    private fun annotated(args: List<PyExpression>, file: PyFile, depth: Int): Typed? {
        var inner = map(args.firstOrNull(), file, depth + 1) ?: return null
        val kept = ArrayList(inner.meta)
        for (metadata in args.drop(1)) {
            val call = PyLiterals.unparen(metadata) as? PyCallExpression
            val callee = call?.let { symbols.libName(it.callee, file) } ?: symbols.libName(metadata, file)
            when {
                callee == null -> if (call != null) kept += call
                callee in PydanticCatalog.validatorMarkers -> session.validators += VALIDATOR_ANNOTATED
                callee in PydanticCatalog.skipSchema -> kept += metadata
                callee in PydanticCatalog.annotatedTypes && call != null -> {
                    val key = PydanticCatalog.annotatedTypes.getValue(callee)
                    val value = PyLiterals.positional(call).firstOrNull()
                    inner = inner.withSchema(Constraints.apply(inner.schema) { k -> if (k == key) value else null })
                }
                callee in PydanticCatalog.stringConstraints && call != null -> inner = inner.withSchema(Constraints.applyCall(inner.schema, call))
                callee in PydanticCatalog.fieldFunctions && call != null -> {
                    inner = inner.withSchema(Constraints.applyCall(inner.schema, call))
                    kept += call
                }
                call != null -> kept += call
            }
        }
        return Typed(inner.schema, kept, inner.requiredMarker)
    }

    private fun tuple(args: List<PyExpression>, file: PyFile, depth: Int): Typed? {
        if (args.isEmpty()) return Typed(Schema(types = listOf(JsonType.ARRAY)))
        if (args.size == 2 && PyLiterals.isEllipsis(args[1])) {
            return map(args[0], file, depth + 1)?.let { Typed(Schema.arrayOf(it.schema)) }
        }
        val items = args.map { map(it, file, depth + 1)?.schema ?: return null }
        return Typed(Schema(types = listOf(JsonType.ARRAY), prefixItems = items, minItems = items.size, maxItems = items.size))
    }

    private fun literal(args: List<PyExpression>, file: PyFile): Typed? {
        val values = ArrayList<Json>()
        for (arg in args) {
            val value = PyLiterals.json(arg) ?: enumMemberValue(arg, file) ?: return null
            if (value is Json.Arr || value is Json.Obj) return null
            if (value !in values) values += value
        }
        return if (values.isEmpty()) null else Typed(valuesSchema(values))
    }

    /** `conint(gt=0)`, `constr(min_length=1)`, `conlist(int, min_length=1)`. */
    private fun constrained(call: PyCallExpression, file: PyFile, depth: Int): Typed? {
        val kind = PydanticCatalog.constrainedFunctions[symbols.libName(call.callee, file)] ?: return null
        var schema = kind.base
        if (kind == PydanticCatalog.Constrained.LIST || kind == PydanticCatalog.Constrained.SET) {
            val item = PyLiterals.keyword(call, "item_type") ?: PyLiterals.positional(call).firstOrNull()
            schema = schema.copy(items = map(item, file, depth + 1)?.schema ?: return null)
        }
        return Typed(Constraints.applyCall(schema, call))
    }

    private fun forward(literal: PyStringLiteralExpression, file: PyFile, depth: Int): Typed? {
        val text = PyLiterals.string(literal)?.trim() ?: return null
        if (text.isEmpty() || text.length > Limits.SCHEMA_MAX_FORWARD_REF_CHARS) return null
        val parsed = runCatching {
            PyElementGenerator.getInstance(session.project).createExpressionFromText(LanguageLevel.forElement(file), text)
        }.getOrNull() ?: return null
        if (PsiTreeUtil.hasErrorElements(parsed)) return null
        return map(parsed, file, depth + 1)
    }

    private companion object {
        const val VALIDATOR_ANNOTATED = "annotated"
    }
}
