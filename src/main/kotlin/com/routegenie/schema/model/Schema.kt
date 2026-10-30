package com.routegenie.schema.model

import com.routegenie.model.Diagnostic
import java.math.BigDecimal

enum class JsonType(val keyword: String) {
    NULL("null"),
    BOOLEAN("boolean"),
    INTEGER("integer"),
    NUMBER("number"),
    STRING("string"),
    ARRAY("array"),
    OBJECT("object");

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun of(keyword: String): JsonType? = byKeyword[keyword]
    }
}

/** What `additionalProperties` says; Kotlin `null` on [Schema.additional] means the keyword is absent. */
sealed interface Additional {
    data object Allowed : Additional
    data object Forbidden : Additional
    data class OfSchema(val schema: Schema) : Additional
}

/**
 * The JSON Schema subset the request-body extraction produces and the sample generator and validator consume. One
 * immutable class with optional parts keeps every consumer a plain `when`-free walk. Property order is declaration order.
 * Recursive models are expressed with [ref] (`#/$defs/Name`) and the root's [defs]; the tree itself is always finite.
 * Never holds PSI, VirtualFile or Project.
 */
data class Schema(
    /** More than one type means "any of these"; `null` among them is the JSON null. */
    val types: List<JsonType> = emptyList(),
    val format: String? = null,
    val description: String? = null,
    val properties: Map<String, Schema> = emptyMap(),
    val required: List<String> = emptyList(),
    val additional: Additional? = null,
    val items: Schema? = null,
    val prefixItems: List<Schema> = emptyList(),
    val enumValues: List<Json>? = null,
    val constValue: Json? = null,
    val default: Json? = null,
    val examples: List<Json> = emptyList(),
    val oneOf: List<Schema> = emptyList(),
    val anyOf: List<Schema> = emptyList(),
    val allOf: List<Schema> = emptyList(),
    val ref: String? = null,
    val defs: Map<String, Schema> = emptyMap(),
    val minimum: BigDecimal? = null,
    val maximum: BigDecimal? = null,
    val exclusiveMinimum: BigDecimal? = null,
    val exclusiveMaximum: BigDecimal? = null,
    val multipleOf: BigDecimal? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
    val uniqueItems: Boolean = false,
    val minProperties: Int? = null,
    val maxProperties: Int? = null,
    /** JSON Schema `propertyNames`: what every key of the object must satisfy (a Python `dict[int, X]` has number-like keys). */
    val propertyNames: Schema? = null,
    /** OpenAPI 3.0 style flag; the writer turns it into a `null` member of `type`. */
    val nullable: Boolean = false,
    val readOnly: Boolean = false,
    val writeOnly: Boolean = false,
    val deprecated: Boolean = false,
    /** Name of the property that selects a branch of a `oneOf` (OpenAPI `discriminator.propertyName`). */
    val discriminator: String? = null,
    /** `x-...` members, kept apart from the standard keywords. */
    val extensions: Map<String, Json> = emptyMap(),
) {
    /** True when the schema says nothing: it accepts every value, so a sample cannot be derived from it. */
    val isUnconstrained: Boolean
        get() = this == EMPTY

    /** The schema of an enclosing object's property, with `required` bookkeeping done by the caller. */
    fun withDefs(newDefs: Map<String, Schema>): Schema = copy(defs = newDefs)

    companion object {
        /** `{}`: any value. */
        val EMPTY = Schema()

        fun of(type: JsonType, format: String? = null) = Schema(types = listOf(type), format = format)

        val STRING = of(JsonType.STRING)
        val INTEGER = of(JsonType.INTEGER)
        val NUMBER = of(JsonType.NUMBER)
        val BOOLEAN = of(JsonType.BOOLEAN)
        val NULL = of(JsonType.NULL)

        fun ref(name: String) = Schema(ref = "$DEFS_PREFIX$name")

        /** `anyOf [inner, null]`: what Pydantic emits for `Optional[X]` and DRF for `allow_null=True`. */
        fun nullable(inner: Schema): Schema =
            if (inner.anyOf.any { it.types == listOf(JsonType.NULL) }) inner else Schema(anyOf = listOf(inner, NULL))

        fun arrayOf(items: Schema?) = Schema(types = listOf(JsonType.ARRAY), items = items)

        const val DEFS_PREFIX = "#/\$defs/"
    }
}

/** How the body is carried on the wire. [contentType] is the exact header value a request uses. */
enum class BodyEncoding(val contentType: String) {
    JSON("application/json"),
    FORM_URLENCODED("application/x-www-form-urlencoded"),
}

/** How sure the extraction is that the schema is what the server enforces; a gap or a custom validator lowers it. */
enum class Confidence { HIGH, MEDIUM }

/**
 * The request body an endpoint's code declares: a schema plus how it is encoded. [diagnostics] are the internal records of
 * everything that was left out or only approximated; [validators] name the fields that custom validators touch (their
 * code cannot be evaluated, so the schema is only a necessary condition for them).
 */
data class RequestBodySchema(
    val encoding: BodyEncoding,
    val schema: Schema,
    val confidence: Confidence = Confidence.HIGH,
    val diagnostics: List<Diagnostic> = emptyList(),
    val validators: List<String> = emptyList(),
)
