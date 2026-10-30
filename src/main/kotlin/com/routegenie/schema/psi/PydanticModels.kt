package com.routegenie.schema.psi

import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PySubscriptionExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTypeDeclarationStatement
import com.routegenie.schema.model.Additional
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import com.routegenie.schema.psi.PydanticCatalog.Keys

/** One annotated name in a class body. */
internal class FieldDecl(val name: String, val annotation: PyExpression?, val default: PyExpression?, val owner: PyClass)

/** The model options that change what a client sends. */
private class ModelConfig {
    var extra: String? = null
    var generator: ((String) -> String)? = null
    var generatorUnresolved = false
    var v1Signal = false
}

/**
 * Object schemas of Pydantic models (v1 and v2), dataclasses and TypedDicts: fields in declaration order with base classes
 * first, aliases, defaults, constraints and nested models. Version differences that change the request are handled where
 * they matter: `Optional[X]` without a default is required in v2 and optional in v1.
 */
internal class PydanticModels(private val mapper: TypeMapper) {
    private val session = mapper.session
    private val symbols = session.symbols

    fun build(cls: PyClass, kind: ClassKind): Schema {
        val chain = mapper.hierarchy.baseFirst(cls).filter { mapper.kindOf(it) == kind }
        val config = if (kind == ClassKind.PYDANTIC) readConfig(chain) else ModelConfig()
        val decls = LinkedHashMap<String, FieldDecl>()
        for (c in chain) {
            for (decl in declaredFields(c, kind)) decls[decl.name] = decl
            recordValidators(c)
        }
        val v1 = kind == ClassKind.PYDANTIC && (mapper.isV1(cls) || config.v1Signal || decls.values.any { usesV1Keywords(it) })
        val properties = LinkedHashMap<String, Schema>()
        val required = ArrayList<String>()
        for (decl in decls.values) {
            session.check()
            val built = field(decl, kind, config, v1, cls) ?: continue
            properties[built.key] = built.schema
            if (built.required) required += built.key
        }
        val additional = when (config.extra) {
            PydanticCatalog.EXTRA_FORBID -> Additional.Forbidden
            PydanticCatalog.EXTRA_ALLOW -> Additional.Allowed
            else -> null
        }
        return Schema(types = listOf(JsonType.OBJECT), properties = properties, required = required, additional = additional)
    }

    // --- Declarations ---------------------------------------------------------------------------------------------------

    private fun declaredFields(cls: PyClass, kind: ClassKind): List<FieldDecl> {
        val file = cls.containingFile as? PyFile ?: return emptyList()
        val out = ArrayList<FieldDecl>()
        for (statement in cls.statementList.statements) {
            val decl = when (statement) {
                is PyAssignmentStatement -> {
                    val name = (statement.targets.singleOrNull() as? PyTargetExpression)?.name
                    val annotation = statement.annotation?.value
                    if (name == null || annotation == null) null else FieldDecl(name, annotation, statement.assignedValue, cls)
                }
                is PyTypeDeclarationStatement -> {
                    val target = statement.target
                    val name = (target as? PyTargetExpression)?.name ?: (target as? PyReferenceExpression)?.referencedName
                    val annotation = statement.annotation?.value
                    if (name == null || annotation == null) null else FieldDecl(name, annotation, null, cls)
                }
                else -> null
            } ?: continue
            if (decl.name.startsWith("_") || decl.name == Keys.ATTR_MODEL_CONFIG || isNonField(decl.annotation, file, kind)) continue
            out += decl
        }
        return out
    }

    /** `ClassVar[...]`, and for dataclasses `InitVar[...]` and `KW_ONLY`: names that are not fields. */
    private fun isNonField(annotation: PyExpression?, file: PyFile, kind: ClassKind): Boolean {
        val head = (PyLiterals.unparen(annotation) as? PySubscriptionExpression)?.operand ?: annotation
        val name = symbols.libName(head, file) ?: return false
        return name in PydanticCatalog.classVar || (kind == ClassKind.DATACLASS && name in PydanticCatalog.dataclassSkipTypes)
    }

    private fun recordValidators(cls: PyClass) {
        val file = cls.containingFile as? PyFile ?: return
        for (method in cls.methods) {
            val decorated = method.decoratorList?.decorators.orEmpty().any { symbols.libName(it.callee, file) in PydanticCatalog.validatorDecorators }
            if (decorated) session.validators += "${cls.name}.${method.name}"
        }
    }

    // --- Options ---------------------------------------------------------------------------------------------------------

    private fun readConfig(chain: List<PyClass>): ModelConfig {
        val config = ModelConfig()
        for (cls in chain) {
            val file = cls.containingFile as? PyFile ?: continue
            for (statement in cls.statementList.statements) {
                val assignment = statement as? PyAssignmentStatement ?: continue
                if ((assignment.targets.singleOrNull() as? PyTargetExpression)?.name != Keys.ATTR_MODEL_CONFIG) continue
                val value = PyLiterals.unparen(assignment.assignedValue)
                when (value) {
                    is PyCallExpression -> if (symbols.libName(value.callee, file) in PydanticCatalog.configDict) {
                        for (key in value.arguments.mapNotNull { (it as? com.jetbrains.python.psi.PyKeywordArgument)?.keyword }) {
                            applyOption(config, key, PyLiterals.keyword(value, key), file)
                        }
                    }
                    is PyDictLiteralExpression -> for (pair in value.elements) {
                        PyLiterals.string(pair.key)?.let { applyOption(config, it, pair.value, file) }
                    }
                    else -> Unit
                }
            }
            cls.findNestedClass(Keys.NESTED_CONFIG, false)?.let { nested ->
                for (statement in nested.statementList.statements) {
                    val assignment = statement as? PyAssignmentStatement ?: continue
                    val name = (assignment.targets.singleOrNull() as? PyTargetExpression)?.name ?: continue
                    applyOption(config, name, assignment.assignedValue, file)
                }
            }
        }
        return config
    }

    private fun applyOption(config: ModelConfig, key: String, value: PyExpression?, file: PyFile) {
        when (key) {
            Keys.CONFIG_EXTRA -> config.extra = optionWord(value)
            Keys.CONFIG_ALIAS_GENERATOR -> {
                if (PyLiterals.isNone(value)) {
                    config.generator = null
                    config.generatorUnresolved = false
                } else {
                    val generator = PydanticCatalog.aliasGenerators[symbols.libName(value, file)]
                    config.generator = generator
                    config.generatorUnresolved = generator == null
                }
            }
            in V1_ONLY_OPTIONS -> config.v1Signal = true
        }
    }

    /** `"forbid"` or `Extra.forbid`: the word the option is set to. */
    private fun optionWord(value: PyExpression?): String? =
        PyLiterals.string(value) ?: (PyLiterals.unparen(value) as? PyReferenceExpression)?.referencedName

    private fun usesV1Keywords(decl: FieldDecl): Boolean {
        val call = PyLiterals.unparen(decl.default) as? PyCallExpression ?: return false
        return V1_FIELD_KEYWORDS.any { PyLiterals.keyword(call, it) != null }
    }

    // --- One field ---------------------------------------------------------------------------------------------------------

    private class Built(val key: String, val schema: Schema, val required: Boolean)

    private fun field(decl: FieldDecl, kind: ClassKind, config: ModelConfig, v1: Boolean, model: PyClass): Built? {
        val file = decl.owner.containingFile as? PyFile ?: return null
        val where = "${model.name}.${decl.name}"
        val typed = mapper.map(decl.annotation, file)
        if (typed != null && typed.meta.any { it is PyReferenceExpression && symbols.libName(it, file) in PydanticCatalog.skipSchema }) return null

        val calls = ArrayList<PyCallExpression>()
        typed?.meta?.forEach { m -> if (m is PyCallExpression && symbols.libName(m.callee, file) in PydanticCatalog.fieldFunctions) calls += m }
        var default: PyExpression? = null
        var factory = false
        var requiredMarker = false
        var defaultCall: PyCallExpression? = null

        fun readFieldCall(call: PyCallExpression) {
            PyLiterals.positional(call).firstOrNull()?.let { if (PyLiterals.isEllipsis(it)) requiredMarker = true else default = it }
            PyLiterals.keyword(call, Keys.DEFAULT)?.let { if (PyLiterals.isEllipsis(it)) requiredMarker = true else default = it }
            if (PyLiterals.keyword(call, Keys.DEFAULT_FACTORY) != null) factory = true
        }
        calls.forEach(::readFieldCall)
        // `Field(**options)` hides constraints and the default: what the field accepts cannot be told, so it is left out.
        val dynamic = (calls + listOfNotNull(PyLiterals.unparen(decl.default) as? PyCallExpression)).any { call ->
            call.arguments.any { it is com.jetbrains.python.psi.PyStarArgument } && symbols.libName(call.callee, file) in PydanticCatalog.fieldFunctions
        }
        if (dynamic) {
            session.skipped(where, Gap.UNSUPPORTED_FIELD)
            return null
        }

        val assigned = PyLiterals.unparen(decl.default)
        if (assigned != null) {
            val call = assigned as? PyCallExpression
            val callee = call?.let { symbols.libName(it.callee, file) }
            when {
                call != null && callee in PydanticCatalog.fieldFunctions -> {
                    defaultCall = call
                    if (PyLiterals.bool(PyLiterals.keyword(call, Keys.INIT)) == false) return null
                    readFieldCall(call)
                }
                call != null && callee == PydanticCatalog.dataclassField -> {
                    if (PyLiterals.bool(PyLiterals.keyword(call, Keys.INIT)) == false) return null
                    PyLiterals.keyword(call, Keys.DEFAULT)?.let { default = it }
                    if (PyLiterals.keyword(call, Keys.DEFAULT_FACTORY) != null) factory = true
                }
                call != null && callee in PydanticCatalog.privateAttr -> return null
                else -> default = assigned
            }
        }

        var schema = typed?.schema ?: Schema.EMPTY.also { session.skipped(where, Gap.UNRESOLVED_TYPE) }
        defaultCall?.let { call ->
            val constrained = Constraints.applyCall(schema, call, mapper.constants)
            if (constrained == null) {
                // A limit written as an expression cannot be read, and a limit that is dropped would let a sample break it.
                session.skipped(where, Gap.UNRESOLVED_TYPE)
                schema = Schema.EMPTY
            } else {
                schema = constrained
            }
        }
        (defaultCall ?: calls.lastOrNull())?.let { schema = discriminated(schema, it) }

        val defaultExpression = default
        var defaultJson: Json? = null
        if (defaultExpression != null) {
            defaultJson = PyLiterals.json(defaultExpression) ?: mapper.enumMemberValue(defaultExpression, file)
            if (defaultJson == null) session.skipped(where, Gap.UNRESOLVED_DEFAULT)
        }
        defaultJson?.let { schema = schema.copy(default = it) }

        val hasDefault = defaultExpression != null || factory
        val required = when {
            kind == ClassKind.TYPED_DICT -> typed?.requiredMarker ?: totalOf(decl.owner)
            requiredMarker -> true
            hasDefault -> false
            v1 && isNullable(schema) -> false
            else -> true
        }
        val key = when (kind) {
            ClassKind.PYDANTIC -> aliasOf(decl.name, defaultCall, calls, config, file) ?: run {
                session.skipped(where, Gap.UNRESOLVED_ALIAS)
                return null
            }
            else -> decl.name
        }
        return Built(key, schema, required)
    }

    private fun isNullable(schema: Schema) = schema.anyOf.any { Constraints.isNull(it) }

    /** `Field(discriminator="kind")` on a union: the branches become a `oneOf` that names the property. */
    private fun discriminated(schema: Schema, call: PyCallExpression): Schema {
        val property = PyLiterals.string(PyLiterals.keyword(call, Keys.DISCRIMINATOR)) ?: return schema
        val branches = schema.anyOf.filterNot { Constraints.isNull(it) }
        if (branches.size < 2) return schema
        val choice = Schema(oneOf = branches, discriminator = property)
        return if (branches.size == schema.anyOf.size) schema.copy(anyOf = emptyList(), oneOf = branches, discriminator = property) else schema.copy(anyOf = listOf(choice, Schema.NULL))
    }

    /** The key the field has in a request; null when an alias generator that cannot be evaluated decides it. */
    private fun aliasOf(name: String, defaultCall: PyCallExpression?, metaCalls: List<PyCallExpression>, config: ModelConfig, file: PyFile): String? {
        for (call in listOfNotNull(defaultCall) + metaCalls.asReversed()) {
            val validation = PyLiterals.keyword(call, Keys.VALIDATION_ALIAS)
            if (validation != null) return validationAlias(validation, file)
            PyLiterals.keyword(call, Keys.ALIAS)?.let { expression -> return PyLiterals.string(expression) }
        }
        if (config.generatorUnresolved) return null
        return config.generator?.invoke(name) ?: name
    }

    /** A plain string, or the first choice of `AliasChoices("a", "b")`; a path into nested data has no flat key. */
    private fun validationAlias(expression: PyExpression, file: PyFile): String? {
        PyLiterals.string(expression)?.let { return it }
        val call = PyLiterals.unparen(expression) as? PyCallExpression ?: return null
        if (symbols.libName(call.callee, file) !in PydanticCatalog.aliasChoices) return null
        return PyLiterals.positional(call).firstOrNull()?.let { PyLiterals.string(it) }
    }

    /** `class T(TypedDict, total=False)`. */
    private fun totalOf(cls: PyClass): Boolean {
        val total = cls.superClassExpressionList?.getKeywordArgument("total")?.valueExpression
        return PyLiterals.bool(total) != false
    }

    private companion object {
        val V1_ONLY_OPTIONS = setOf("orm_mode", "allow_population_by_field_name", "allow_mutation", "schema_extra", "fields", "anystr_strip_whitespace")
        val V1_FIELD_KEYWORDS = listOf(Keys.REGEX, Keys.MIN_ITEMS, Keys.MAX_ITEMS, Keys.UNIQUE_ITEMS)
    }
}
