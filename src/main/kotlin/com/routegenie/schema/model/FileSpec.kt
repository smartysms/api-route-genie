package com.routegenie.schema.model

import java.math.BigDecimal

/**
 * What the code says about an uploaded file, and nothing more: every member is "not declared" (empty, false or null) unless
 * a static fact determines it. A file field is a [Schema] of type string with `format: binary` and the extension member
 * [KEY] holding these facts as a JSON object (every key optional), so the schema model needs no new type and a JSON body's
 * `bytes` (`format: binary` without the extension) stays what it was: not a multipart file.
 *
 * @param accept MIME types or MIME wildcards (the type `image` with a star as subtype); empty when the code names none.
 * @param extensions lower-case extensions with the dot (`.png`); empty when the code names none.
 * @param image true when the field only takes images (`ImageField`, a PIL check).
 * @param maxBytes the size limit in bytes when a validator or a recognisable check states one.
 */
data class FileSpec(
    val accept: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val image: Boolean = false,
    val maxBytes: Long? = null,
    val minWidth: Int? = null,
    val maxWidth: Int? = null,
    val minHeight: Int? = null,
    val maxHeight: Int? = null,
) {
    /** The `x-routegenie-file` object; keys with nothing declared are left out. */
    fun toJson(): Json.Obj {
        val out = LinkedHashMap<String, Json>()
        if (accept.isNotEmpty()) out[ACCEPT] = Json.Arr(accept.map { Json.Str(it) })
        if (extensions.isNotEmpty()) out[EXTENSIONS] = Json.Arr(extensions.map { Json.Str(it) })
        if (image) out[IMAGE] = Json.Bool(true)
        maxBytes?.let { out[MAX_BYTES] = Json.num(it) }
        minWidth?.let { out[MIN_WIDTH] = Json.num(it) }
        maxWidth?.let { out[MAX_WIDTH] = Json.num(it) }
        minHeight?.let { out[MIN_HEIGHT] = Json.num(it) }
        maxHeight?.let { out[MAX_HEIGHT] = Json.num(it) }
        return Json.Obj(out)
    }

    /** The schema of one file field. */
    fun toSchema(description: String? = null): Schema =
        Schema(types = listOf(JsonType.STRING), format = BINARY, description = description, extensions = mapOf(KEY to toJson()))

    companion object {
        /** The schema extension that marks a string schema as an uploaded file. */
        const val KEY = "x-routegenie-file"
        const val BINARY = "binary"
        const val ACCEPT = "accept"
        const val EXTENSIONS = "extensions"
        const val IMAGE = "image"
        const val MAX_BYTES = "maxBytes"
        const val MIN_WIDTH = "minWidth"
        const val MAX_WIDTH = "maxWidth"
        const val MIN_HEIGHT = "minHeight"
        const val MAX_HEIGHT = "maxHeight"

        /** The spec of [schema] when it is one file field (string, `format: binary`, the extension present); null otherwise. */
        fun of(schema: Schema): FileSpec? {
            if (schema.format != BINARY || JsonType.STRING !in schema.types) return null
            val member = schema.extensions[KEY] as? Json.Obj ?: return null
            return parse(member)
        }

        /** Reads the object; a member of the wrong shape counts as not declared, never as a guess. */
        fun parse(member: Json.Obj): FileSpec {
            fun texts(key: String): List<String> =
                (member.fields[key] as? Json.Arr)?.items?.mapNotNull { (it as? Json.Str)?.value?.takeIf(String::isNotEmpty) }.orEmpty().distinct()

            fun whole(key: String): BigDecimal? =
                (member.fields[key] as? Json.Num)?.value?.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 }

            fun int(key: String): Int? = whole(key)?.takeIf { it <= BigDecimal(Int.MAX_VALUE) }?.toInt()
            return FileSpec(
                accept = texts(ACCEPT),
                extensions = texts(EXTENSIONS),
                image = (member.fields[IMAGE] as? Json.Bool)?.value == true,
                maxBytes = whole(MAX_BYTES)?.takeIf { it <= BigDecimal(Long.MAX_VALUE) }?.toLong(),
                minWidth = int(MIN_WIDTH),
                maxWidth = int(MAX_WIDTH),
                minHeight = int(MIN_HEIGHT),
                maxHeight = int(MAX_HEIGHT),
            )
        }

        /**
         * What a property is for a multipart body: one file, a list of files, or neither (null). A `null` branch of an optional
         * file (`Optional[UploadFile]`, `allow_null`) is looked through.
         */
        fun shape(schema: Schema): FileShape? {
            val core = withoutNull(schema)
            of(core)?.let { return FileShape(it, many = false, minItems = null, maxItems = null) }
            if (JsonType.ARRAY in core.types) {
                val item = core.items?.let { withoutNull(it) } ?: return null
                of(item)?.let { return FileShape(it, many = true, minItems = core.minItems, maxItems = core.maxItems) }
            }
            return null
        }

        /** True when [schema] is a file or has one anywhere inside (nested objects and lists included; `$ref`s are not followed). */
        fun containsFile(schema: Schema): Boolean {
            if (of(schema) != null) return true
            if (schema.properties.values.any(::containsFile)) return true
            if (schema.anyOf.any(::containsFile) || schema.oneOf.any(::containsFile) || schema.allOf.any(::containsFile)) return true
            return schema.items?.let(::containsFile) == true || schema.prefixItems.any(::containsFile)
        }

        private fun withoutNull(schema: Schema): Schema {
            val branches = schema.anyOf.filterNot { it.types == listOf(JsonType.NULL) && it.enumValues == null }
            return if (schema.anyOf.size > branches.size && branches.size == 1) branches.single() else schema
        }
    }
}

/** A multipart property that is a file ([many] false) or a list of files. */
data class FileShape(val spec: FileSpec, val many: Boolean, val minItems: Int?, val maxItems: Int?)

/** One part of a multipart body: a text field ([text] set) or an uploaded file ([file] set); exactly one of them is non-null. */
data class TemplatePart(val name: String, val text: String?, val file: FileSpec?) {
    init {
        require((text == null) != (file == null)) { "a part is either text or a file" }
    }
}

/** The parts a multipart request should send, in declaration order. No bytes: the content is made later from each [FileSpec]. */
data class MultipartTemplate(val parts: List<TemplatePart>)
