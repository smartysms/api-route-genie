package com.routegenie.testing

import com.routegenie.schema.model.Additional
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import com.routegenie.schema.model.jsonType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * An independent check that a JSON value satisfies a [Schema] (the subset the extraction produces). It exists for tests: the
 * generators are only trusted when a second, separately written reader agrees with them. It is deliberately strict where the
 * schema is strict and says where a value breaks the schema, as `$.items[1].name: ...`.
 */
object SchemaChecker {
    /** All the ways [value] breaks [root]; empty when the value is valid. */
    fun violations(root: Schema, value: Json): List<String> {
        val out = ArrayList<String>()
        check(root, root, value, "$", out, 0)
        return out
    }

    private fun check(root: Schema, s: Schema, v: Json, path: String, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH) {
            out += "$path: schema nests deeper than $MAX_DEPTH"
            return
        }
        s.ref?.let { ref ->
            val target = if (ref == "#") root else root.defs[ref.substringAfterLast('/')]
            if (target == null) out += "$path: unresolved $ref" else check(root, target, v, path, out, depth + 1)
        }
        for (part in s.allOf) check(root, part, v, path, out, depth + 1)
        if (s.anyOf.isNotEmpty() && s.anyOf.none { valid(root, it, v, depth) }) out += "$path: matches none of anyOf"
        if (s.oneOf.isNotEmpty() && s.oneOf.none { valid(root, it, v, depth) }) out += "$path: matches none of oneOf"
        s.constValue?.let { if (it != v) out += "$path: is not the const" }
        s.enumValues?.let { if (v !in it) out += "$path: is not one of the enum members" }
        if (s.types.isNotEmpty() && s.types.none { typeMatches(it, v) } && !(s.nullable && v == Json.Null)) {
            out += "$path: type ${v.jsonType()} is not one of ${s.types}"
            return
        }
        if (s.nullable && v == Json.Null) return
        when (v) {
            is Json.Num -> number(s, v.value, path, out)
            is Json.Str -> string(s, v.value, path, out)
            is Json.Arr -> array(root, s, v, path, out, depth)
            is Json.Obj -> obj(root, s, v, path, out, depth)
            else -> Unit
        }
    }

    private fun valid(root: Schema, s: Schema, v: Json, depth: Int): Boolean {
        val tmp = ArrayList<String>()
        check(root, s, v, "$", tmp, depth + 1)
        return tmp.isEmpty()
    }

    private fun typeMatches(t: JsonType, v: Json): Boolean = when (t) {
        JsonType.NULL -> v == Json.Null
        JsonType.BOOLEAN -> v is Json.Bool
        JsonType.INTEGER -> v is Json.Num && (v.value.signum() == 0 || v.value.stripTrailingZeros().scale() <= 0)
        JsonType.NUMBER -> v is Json.Num
        JsonType.STRING -> v is Json.Str
        JsonType.ARRAY -> v is Json.Arr
        JsonType.OBJECT -> v is Json.Obj
    }

    private fun number(s: Schema, n: BigDecimal, path: String, out: MutableList<String>) {
        s.minimum?.let { if (n < it) out += "$path: $n < minimum $it" }
        s.maximum?.let { if (n > it) out += "$path: $n > maximum $it" }
        s.exclusiveMinimum?.let { if (n <= it) out += "$path: $n <= exclusiveMinimum $it" }
        s.exclusiveMaximum?.let { if (n >= it) out += "$path: $n >= exclusiveMaximum $it" }
        s.multipleOf?.let { if (it.signum() > 0 && n.remainder(it).signum() != 0) out += "$path: $n is not a multiple of $it" }
    }

    private fun string(s: Schema, text: String, path: String, out: MutableList<String>) {
        val length = text.codePointCount(0, text.length)
        s.minLength?.let { if (length < it) out += "$path: length $length < minLength $it" }
        s.maxLength?.let { if (length > it) out += "$path: length $length > maxLength $it" }
        s.pattern?.let { p ->
            val regex = runCatching { Regex(p.replace("(?P<", "(?<")) }.getOrNull()
            if (regex != null && !regex.containsMatchIn(text)) out += "$path: '$text' does not match /$p/"
        }
        s.format?.let { f -> if (!formatOk(f, text)) out += "$path: '$text' is not a valid $f" }
    }

    private fun formatOk(format: String, text: String): Boolean = when (format) {
        "email", "idn-email" -> Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$").matches(text)
        "uuid" -> Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE).matches(text)
        "date" -> runCatching { LocalDate.parse(text) }.isSuccess
        "date-time" -> runCatching { OffsetDateTime.parse(text) }.isSuccess
        "time" -> runCatching { LocalTime.parse(text) }.isSuccess
        "duration" -> Regex("^P(?!$)(\\d+Y)?(\\d+M)?(\\d+W)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+S)?)?$").matches(text)
        "uri", "url", "iri", "uri-reference" -> runCatching { java.net.URI(text) }.map { it.scheme != null && it.host != null }.getOrDefault(false)
        "hostname", "idn-hostname" -> Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$").matches(text)
        "ipv4" -> Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(text)?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
        "ipv6" -> runCatching { java.net.InetAddress.getByName(text) is java.net.Inet6Address }.getOrDefault(false) && text.contains(':')
        "byte" -> runCatching { java.util.Base64.getDecoder().decode(text) }.isSuccess
        else -> true
    }

    private fun array(root: Schema, s: Schema, v: Json.Arr, path: String, out: MutableList<String>, depth: Int) {
        s.minItems?.let { if (v.items.size < it) out += "$path: ${v.items.size} items < minItems $it" }
        s.maxItems?.let { if (v.items.size > it) out += "$path: ${v.items.size} items > maxItems $it" }
        if (s.uniqueItems && v.items.toSet().size != v.items.size) out += "$path: items are not unique"
        if (s.prefixItems.isNotEmpty() && v.items.size != s.prefixItems.size && s.items == null) {
            out += "$path: a tuple of ${s.prefixItems.size} has ${v.items.size} items"
        }
        v.items.forEachIndexed { i, item ->
            val element = s.prefixItems.getOrNull(i) ?: s.items
            if (element != null) check(root, element, item, "$path[$i]", out, depth + 1)
        }
    }

    private fun obj(root: Schema, s: Schema, v: Json.Obj, path: String, out: MutableList<String>, depth: Int) {
        for (name in s.required) if (name !in v.fields) out += "$path: required '$name' is missing"
        s.minProperties?.let { if (v.fields.size < it) out += "$path: ${v.fields.size} members < minProperties $it" }
        s.maxProperties?.let { if (v.fields.size > it) out += "$path: ${v.fields.size} members > maxProperties $it" }
        for ((name, value) in v.fields) {
            val property = s.properties[name]
            when {
                property != null -> check(root, property, value, "$path.$name", out, depth + 1)
                s.additional == Additional.Forbidden -> out += "$path: unexpected member '$name'"
                s.additional is Additional.OfSchema -> check(root, (s.additional as Additional.OfSchema).schema, value, "$path.$name", out, depth + 1)
            }
        }
    }

    private const val MAX_DEPTH = 64
}
