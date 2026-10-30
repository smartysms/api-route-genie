package com.routegenie.schema.model

import com.routegenie.Limits
import java.math.BigDecimal
import java.math.RoundingMode

/** Why part of a schema produced no sample value. Internal: logged and counted, not shown. */
enum class GapReason { NO_TYPE, BINARY, PATTERN, RECURSION, UNRESOLVED_REF, UNSATISFIABLE, LIMIT }

/** A place in the schema (a path such as `$.items.name`) that has no sample, and why. */
data class SampleGap(val path: String, val reason: GapReason)

/** [value] is null when nothing could be derived from the schema at all. */
data class SampleResult(val value: Json?, val gaps: List<SampleGap>)

/**
 * @param includeOptional false keeps only the properties that are required.
 */
data class SampleOptions(val includeOptional: Boolean = true)

/**
 * Builds one deterministic instance of a [Schema]: the same schema always yields the same bytes. Order of preference per
 * node: `examples[0]`, `const`, `default`, `enum[0]`, then a value by type that satisfies every constraint of the schema.
 * Where the schema determines nothing (no type, a format that means raw bytes, a pattern outside the supported subset, a
 * recursion that never ends) the node has no sample and an enclosing object leaves that property out; a value is never made
 * up to fill the gap.
 */
object SampleGenerator {
    fun generate(root: Schema, options: SampleOptions = SampleOptions()): SampleResult {
        val run = Run(root, options)
        val value = run.node(root, 0, "$", 0)
        return SampleResult(value, run.gaps)
    }

    private class Run(private val root: Schema, private val options: SampleOptions) {
        val gaps = ArrayList<SampleGap>()
        private var nodes = 0
        private val refDepth = HashMap<String, Int>()

        fun node(schema: Schema, depth: Int, path: String, variant: Int): Json? {
            if (++nodes > Limits.SAMPLE_MAX_NODES || depth > Limits.SAMPLE_MAX_DEPTH) return gap(path, GapReason.LIMIT)
            schema.examples.firstOrNull()?.let { return it }
            schema.constValue?.let { return it }
            schema.default?.let { return it }
            schema.enumValues?.let { values -> return values.getOrNull(variant % maxOf(values.size, 1)) ?: gap(path, GapReason.UNSATISFIABLE) }
            schema.ref?.let { return reference(it, depth, path, variant) }
            if (schema.allOf.isNotEmpty()) return node(SchemaMerge.allOf(schema, ::resolve), depth + 1, path, variant)
            val branches = schema.oneOf.ifEmpty { schema.anyOf }
            if (branches.isNotEmpty()) return branch(branches, depth, path, variant)
            return when (typeOf(schema)) {
                JsonType.NULL -> Json.Null
                JsonType.BOOLEAN -> Json.Bool(variant % 2 == 0)
                JsonType.INTEGER -> number(schema, integer = true, variant, path)
                JsonType.NUMBER -> number(schema, integer = false, variant, path)
                JsonType.STRING -> string(schema, variant, path)
                JsonType.ARRAY -> array(schema, depth, path)
                JsonType.OBJECT -> obj(schema, depth, path)
                null -> gap(path, GapReason.NO_TYPE)
            }
        }

        private fun gap(path: String, reason: GapReason): Json? {
            gaps += SampleGap(path, reason)
            return null
        }

        private fun resolve(ref: String): Schema? {
            if (ref == "#") return root
            return root.defs[ref.substringAfterLast('/')]
        }

        private fun reference(ref: String, depth: Int, path: String, variant: Int): Json? {
            val target = resolve(ref) ?: return gap(path, GapReason.UNRESOLVED_REF)
            val entered = refDepth.getOrDefault(ref, 0)
            if (entered >= Limits.SAMPLE_MAX_REF_DEPTH) return gap(path, GapReason.RECURSION)
            refDepth[ref] = entered + 1
            try {
                return node(target, depth + 1, path, variant)
            } finally {
                refDepth[ref] = entered
            }
        }

        /** The first branch that has a sample, preferring one that is not null; null itself only when nothing else works. */
        private fun branch(branches: List<Schema>, depth: Int, path: String, variant: Int): Json? {
            val ordered = branches.filter { !isNullOnly(it) } + branches.filter { isNullOnly(it) }
            for (candidate in ordered) {
                val saved = gaps.size
                val value = node(candidate, depth + 1, path, variant)
                if (value != null) return value
                // A failed branch is not a gap of the whole node when another branch succeeds.
                if (ordered.last() !== candidate) while (gaps.size > saved) gaps.removeAt(gaps.lastIndex)
            }
            return null
        }

        private fun isNullOnly(s: Schema) = s.types == listOf(JsonType.NULL) && s.enumValues == null

        // --- Objects and arrays ------------------------------------------------------------------------------------------

        private fun obj(s: Schema, depth: Int, path: String): Json? {
            val members = LinkedHashMap<String, Json>()
            val requiredSet = s.required.toHashSet()
            // Required properties are decided first so that the recursion budget is spent on what must be there.
            for ((name, property) in s.properties) {
                if (property.readOnly) continue
                val required = name in requiredSet
                if (!required && !options.includeOptional) continue
                node(property, depth + 1, "$path.$name", 0)?.let { members[name] = it }
            }
            val extra = (s.additional as? Additional.OfSchema)?.schema
            if (s.properties.isEmpty() && extra != null) {
                // The key is a stand-in word unless the key type says more (a `dict[int, X]` needs a number as its key).
                val names = s.propertyNames
                val key = if (names == null) SampleValues.MAP_KEY else node(names, depth + 1, "$path.<name>", 0)?.let { SampleValues.keyText(it) }
                if (key != null) node(extra, depth + 1, "$path.*", 0)?.let { members[key] = it }
            }
            return Json.Obj(members)
        }

        private fun array(s: Schema, depth: Int, path: String): Json? {
            val max = s.maxItems
            if (s.prefixItems.isNotEmpty()) {
                val out = ArrayList<Json>()
                for ((i, element) in s.prefixItems.withIndex()) out += node(element, depth + 1, "$path[$i]", 0) ?: return null
                return Json.Arr(out)
            }
            val wanted = maxOf(s.minItems ?: 0, 1).let { if (max != null) minOf(it, max) else it }
            if (wanted <= 0) return Json.Arr(emptyList())
            val itemSchema = s.items ?: return if ((s.minItems ?: 0) > 0) gap(path, GapReason.NO_TYPE) else Json.Arr(emptyList())
            val out = ArrayList<Json>()
            for (i in 0 until wanted) {
                val value = distinct(itemSchema, depth, "$path[]", out, i, s.uniqueItems) ?: break
                out += value
            }
            // An item with no sample makes the array empty rather than invented; that is valid unless items are demanded.
            if (out.isEmpty() && (s.minItems ?: 0) > 0) return gap(path, GapReason.UNSATISFIABLE)
            return Json.Arr(out)
        }

        private fun distinct(item: Schema, depth: Int, path: String, taken: List<Json>, index: Int, unique: Boolean): Json? {
            val first = node(item, depth + 1, path, if (unique) index else 0) ?: return null
            if (!unique || first !in taken) return first
            for (attempt in 1..Limits.SAMPLE_UNIQUE_ATTEMPTS) {
                val value = node(item, depth + 1, path, index + attempt) ?: break
                if (value !in taken) return value
            }
            gaps += SampleGap(path, GapReason.UNSATISFIABLE)
            return first
        }

        // --- Strings ------------------------------------------------------------------------------------------------------

        private fun string(s: Schema, variant: Int, path: String): Json? {
            val format = s.format
            if (format != null && SampleValues.hasNoSample(format)) return gap(path, GapReason.BINARY)
            val fromFormat = format?.let { SampleValues.forFormat(it) }
            val text = when {
                s.pattern != null -> PatternSampler.sample(s.pattern, s.minLength, s.maxLength) ?: return gap(path, GapReason.PATTERN)
                fromFormat != null -> fromFormat
                else -> SampleValues.STRING + (if (variant > 0) variant.toString() else "")
            }
            val fitted = fit(text, s.minLength, s.maxLength, padding = s.pattern == null && fromFormat == null) ?: return gap(path, GapReason.UNSATISFIABLE)
            return Json.Str(fitted)
        }

        private fun fit(text: String, min: Int?, max: Int?, padding: Boolean): String? {
            var out = text
            if (min != null && out.codePointCount(0, out.length) < min) {
                if (!padding || min > Limits.SAMPLE_MAX_STRING_CHARS) return null
                val builder = StringBuilder(out)
                while (builder.codePointCount(0, builder.length) < min) builder.append(SampleValues.STRING)
                out = builder.toString()
                // Cut back to the exact length; the base text is ASCII, so characters are code points.
                if (out.length > min) out = out.substring(0, min)
            }
            if (max != null && out.codePointCount(0, out.length) > max) {
                if (!padding) return null
                out = out.substring(0, max.coerceAtLeast(0))
            }
            return out
        }

        // --- Numbers ------------------------------------------------------------------------------------------------------

        private fun number(s: Schema, integer: Boolean, variant: Int, path: String): Json? {
            val multiple = s.multipleOf?.takeIf { it.signum() > 0 }
            // The grid values must sit on: the declared multiple, else whole numbers for an integer, else any value.
            val unit = multiple ?: if (integer) BigDecimal.ONE else null
            val gap = multiple?.max(BigDecimal.ONE) ?: BigDecimal.ONE
            val low = s.minimum ?: s.exclusiveMinimum
            val high = s.maximum ?: s.exclusiveMaximum
            val candidates = ArrayList<BigDecimal>()
            candidates += BigDecimal.ZERO
            s.minimum?.let { candidates += align(it, unit, RoundingMode.CEILING) }
            s.exclusiveMinimum?.let { candidates += align(it.add(gap), unit, RoundingMode.CEILING) }
            candidates += BigDecimal.ONE
            s.maximum?.let { candidates += align(it, unit, RoundingMode.FLOOR) }
            s.exclusiveMaximum?.let { candidates += align(it.subtract(gap), unit, RoundingMode.FLOOR) }
            if (low != null && high != null) {
                val middle = low.add(high).divide(TWO)
                candidates += align(middle, unit, RoundingMode.CEILING)
                candidates += align(middle, unit, RoundingMode.FLOOR)
            }
            val shift = (multiple ?: BigDecimal.ONE).multiply(BigDecimal.valueOf(variant.toLong()))
            for (candidate in candidates) {
                for (value in listOf(candidate.add(shift), candidate)) {
                    val normal = normalize(value, integer) ?: continue
                    if (fits(s, normal, multiple)) return Json.Num(normal)
                }
            }
            return gap(path, GapReason.UNSATISFIABLE)
        }

        private fun align(v: BigDecimal, unit: BigDecimal?, mode: RoundingMode): BigDecimal =
            if (unit == null) v else v.divide(unit, 0, mode).multiply(unit)

        private fun normalize(v: BigDecimal, integer: Boolean): BigDecimal? {
            val stripped = if (v.signum() == 0) BigDecimal.ZERO else v.stripTrailingZeros()
            val plain = if (stripped.scale() < 0) stripped.setScale(0) else stripped
            return if (integer && plain.scale() > 0) null else plain
        }

        private fun fits(s: Schema, v: BigDecimal, multiple: BigDecimal?): Boolean =
            (s.minimum == null || v >= s.minimum) &&
                (s.maximum == null || v <= s.maximum) &&
                (s.exclusiveMinimum == null || v > s.exclusiveMinimum) &&
                (s.exclusiveMaximum == null || v < s.exclusiveMaximum) &&
                (multiple == null || v.remainder(multiple).signum() == 0)
    }

    /** The JSON type a schema is about: its first non-null declared type, or the one its keywords imply. Null when it says nothing. */
    internal fun typeOf(s: Schema): JsonType? {
        s.types.firstOrNull { it != JsonType.NULL }?.let { return it }
        if (s.types.isNotEmpty()) return JsonType.NULL
        return when {
            s.properties.isNotEmpty() || s.additional is Additional.OfSchema || s.minProperties != null -> JsonType.OBJECT
            s.items != null || s.prefixItems.isNotEmpty() || s.minItems != null || s.uniqueItems -> JsonType.ARRAY
            s.minimum != null || s.maximum != null || s.exclusiveMinimum != null || s.exclusiveMaximum != null || s.multipleOf != null -> JsonType.NUMBER
            s.minLength != null || s.maxLength != null || s.pattern != null || s.format != null -> JsonType.STRING
            else -> null
        }
    }

    private val TWO = BigDecimal(2)
}
