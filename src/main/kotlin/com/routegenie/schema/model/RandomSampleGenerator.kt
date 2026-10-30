package com.routegenie.schema.model

import com.routegenie.Limits
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Base64
import kotlin.random.Random

/**
 * @param optionalChance chance (0..1) that an optional property is written; required properties are always written.
 * @param nullChance chance (0..1) that a value that may be null is null.
 */
data class RandomOptions(
    val optionalChance: Double = Limits.RANDOM_OPTIONAL_CHANCE,
    val nullChance: Double = Limits.RANDOM_NULL_CHANCE,
)

/**
 * Builds a different instance of a [Schema] on every call: each value is drawn from [Random] within what the schema allows.
 * The same rules as [SampleGenerator] apply to what is allowed: every limit of the schema (bounds, `multipleOf`, lengths,
 * pattern, format, item counts, uniqueness, required properties, enum and const) holds for the value, and where the schema
 * determines nothing (no type, raw bytes, a pattern outside the supported subset, a recursion that never ends, limits that
 * contradict each other) the node has no value and an enclosing object leaves that property out. Nothing is made up to fill
 * a gap. `default` and `examples` are ignored on purpose: a random body should not repeat them.
 *
 * The same [Random] seed gives the same document, which is what the tests use.
 */
object RandomSampleGenerator {
    fun generate(root: Schema, random: Random, options: RandomOptions = RandomOptions()): SampleResult {
        val run = Run(root, random, options)
        val value = run.node(root, 0, "$")
        return SampleResult(value, run.gaps)
    }

    private class Run(private val root: Schema, private val random: Random, private val options: RandomOptions) {
        val gaps = ArrayList<SampleGap>()
        private var nodes = 0
        private val refDepth = HashMap<String, Int>()

        private fun roll(chance: Double) = random.nextDouble() < chance

        private fun gap(path: String, reason: GapReason): Json? {
            gaps += SampleGap(path, reason)
            return null
        }

        fun node(schema: Schema, depth: Int, path: String): Json? {
            if (++nodes > Limits.SAMPLE_MAX_NODES || depth > Limits.SAMPLE_MAX_DEPTH) return gap(path, GapReason.LIMIT)
            schema.constValue?.let { return it }
            schema.enumValues?.let { values ->
                return if (values.isEmpty()) gap(path, GapReason.UNSATISFIABLE) else values[random.nextInt(values.size)]
            }
            schema.ref?.let { return reference(it, depth, path) }
            if (schema.allOf.isNotEmpty()) return node(SchemaMerge.allOf(schema, ::resolve), depth + 1, path)
            val branches = schema.oneOf.ifEmpty { schema.anyOf }
            if (branches.isNotEmpty()) return branch(branches, depth, path)
            if (schema.nullable && roll(options.nullChance)) return Json.Null
            return when (pickType(schema)) {
                JsonType.NULL -> Json.Null
                JsonType.BOOLEAN -> Json.Bool(random.nextBoolean())
                JsonType.INTEGER -> number(schema, integer = true, path)
                JsonType.NUMBER -> number(schema, integer = false, path)
                JsonType.STRING -> string(schema, path)
                JsonType.ARRAY -> array(schema, depth, path)
                JsonType.OBJECT -> obj(schema, depth, path)
                null -> gap(path, GapReason.NO_TYPE)
            }
        }

        private fun resolve(ref: String): Schema? {
            if (ref == "#") return root
            return root.defs[ref.substringAfterLast('/')]
        }

        private fun reference(ref: String, depth: Int, path: String): Json? {
            val target = resolve(ref) ?: return gap(path, GapReason.UNRESOLVED_REF)
            val entered = refDepth.getOrDefault(ref, 0)
            if (entered >= Limits.SAMPLE_MAX_REF_DEPTH) return gap(path, GapReason.RECURSION)
            refDepth[ref] = entered + 1
            try {
                return node(target, depth + 1, path)
            } finally {
                refDepth[ref] = entered
            }
        }

        /** One random branch that produces a value; null itself with [RandomOptions.nullChance], or when nothing else works. */
        private fun branch(branches: List<Schema>, depth: Int, path: String): Json? {
            val nullBranches = branches.filter(::isNullOnly)
            val others = branches.filterNot(::isNullOnly)
            if (nullBranches.isNotEmpty() && (others.isEmpty() || roll(options.nullChance))) return Json.Null
            val order = others.shuffled(random)
            for ((i, candidate) in order.withIndex()) {
                val saved = gaps.size
                val value = node(candidate, depth + 1, path)
                if (value != null) return value
                // A failed branch is not a gap of the whole node when another branch (or null) succeeds.
                val last = i == order.lastIndex
                if (!last || nullBranches.isNotEmpty()) while (gaps.size > saved) gaps.removeAt(gaps.lastIndex)
            }
            return if (nullBranches.isNotEmpty()) Json.Null else null
        }

        private fun isNullOnly(s: Schema) = s.types == listOf(JsonType.NULL) && s.enumValues == null

        private fun pickType(s: Schema): JsonType? {
            if (s.types.isEmpty()) return SampleGenerator.typeOf(s)
            val nonNull = s.types.filter { it != JsonType.NULL }
            if (nonNull.isEmpty() || (JsonType.NULL in s.types && roll(options.nullChance))) return JsonType.NULL
            return nonNull[random.nextInt(nonNull.size)]
        }

        // --- Objects and arrays ------------------------------------------------------------------------------------------

        private fun obj(s: Schema, depth: Int, path: String): Json? {
            val members = LinkedHashMap<String, Json>()
            val required = s.required.toHashSet()
            val skipped = ArrayList<Pair<String, Schema>>()
            for ((name, property) in s.properties) {
                if (property.readOnly) continue
                if (name !in required && !roll(options.optionalChance)) {
                    skipped += name to property
                    continue
                }
                node(property, depth + 1, "$path.$name")?.let { members[name] = it }
            }
            val extra = (s.additional as? Additional.OfSchema)?.schema
            if (s.properties.isEmpty() && extra != null) {
                val low = maxOf(s.minProperties ?: 1, 1)
                val high = minOf(s.maxProperties ?: Int.MAX_VALUE, Limits.RANDOM_MAP_MAX_ENTRIES)
                if (low > high) return gap(path, GapReason.UNSATISFIABLE)
                val count = if (high > low) random.nextInt(low, high + 1) else low
                repeat(count) {
                    val key = freshKey(members.keys)
                    node(extra, depth + 1, "$path.*")?.let { members[key] = it }
                }
            }
            // Properties that were skipped by chance are brought back when the schema demands more members.
            val needed = s.minProperties
            if (needed != null) {
                for ((name, property) in skipped) {
                    if (members.size >= needed) break
                    node(property, depth + 1, "$path.$name")?.let { members[name] = it }
                }
                if (members.size < needed) return gap(path, GapReason.UNSATISFIABLE)
            }
            val allowed = s.maxProperties
            if (allowed != null && members.size > allowed) {
                // Only optional members may go; the required ones have to stay.
                for (name in members.keys.toList().reversed()) {
                    if (members.size <= allowed) break
                    if (name !in required) members.remove(name)
                }
                if (members.size > allowed) return gap(path, GapReason.UNSATISFIABLE)
            }
            return Json.Obj(members)
        }

        private fun freshKey(taken: Set<String>): String {
            repeat(Limits.SAMPLE_UNIQUE_ATTEMPTS) {
                val key = word(3, 8)
                if (key !in taken) return key
            }
            return word(9, 12) + taken.size
        }

        private fun array(s: Schema, depth: Int, path: String): Json? {
            if (s.prefixItems.isNotEmpty()) {
                val out = ArrayList<Json>()
                for ((i, element) in s.prefixItems.withIndex()) out += node(element, depth + 1, "$path[$i]") ?: return null
                return Json.Arr(out)
            }
            val minItems = s.minItems ?: 0
            val maxItems = s.maxItems
            val item = s.items ?: return if (minItems > 0) gap(path, GapReason.NO_TYPE) else Json.Arr(emptyList())
            // An array with no minimum still gets an item, so the shape of the element is visible; a maximum of 0 stays empty.
            val low = if (maxItems != null && maxItems < 1) 0 else maxOf(minItems, 1)
            val high = minOf(maxItems ?: Int.MAX_VALUE, low + Limits.RANDOM_ARRAY_EXTRA_ITEMS)
            if (low > high) return gap(path, GapReason.UNSATISFIABLE)
            val count = if (high > low) random.nextInt(low, high + 1) else low
            val out = ArrayList<Json>()
            for (i in 0 until count) {
                val value = element(item, depth, "$path[]", out, s.uniqueItems) ?: break
                out += value
            }
            // Fewer items than drawn is fine, fewer than demanded is not.
            if (out.size < minItems) return gap(path, GapReason.UNSATISFIABLE)
            return Json.Arr(out)
        }

        /** A value for one more item; when items must be unique, a value that is not in [taken] yet (null if none was found). */
        private fun element(item: Schema, depth: Int, path: String, taken: List<Json>, unique: Boolean): Json? {
            val first = node(item, depth + 1, path) ?: return null
            if (!unique || first !in taken) return first
            repeat(Limits.RANDOM_UNIQUE_ATTEMPTS) {
                val value = node(item, depth + 1, path) ?: return null
                if (value !in taken) return value
            }
            return null
        }

        // --- Strings ------------------------------------------------------------------------------------------------------

        private fun string(s: Schema, path: String): Json? {
            val format = s.format
            if (format != null && SampleValues.hasNoSample(format)) return gap(path, GapReason.BINARY)
            val pattern = s.pattern
            if (pattern != null) {
                val text = PatternSampler.sampleRandom(pattern, random, s.minLength, s.maxLength) ?: return gap(path, GapReason.PATTERN)
                return Json.Str(text)
            }
            val minLength = s.minLength
            val maxLength = s.maxLength
            if (format != null && formatted(format) != null) {
                repeat(FORMAT_ATTEMPTS) {
                    val text = formatted(format) ?: return@repeat
                    if (fitsLength(text, minLength, maxLength)) return Json.Str(text)
                }
                return gap(path, GapReason.UNSATISFIABLE)
            }
            val low = minLength ?: minOf(Limits.RANDOM_STRING_MIN_CHARS, maxLength ?: Int.MAX_VALUE)
            if (low > Limits.SAMPLE_MAX_STRING_CHARS) return gap(path, GapReason.LIMIT)
            val high = minOf(maxLength ?: maxOf(low, Limits.RANDOM_STRING_MAX_CHARS), Limits.SAMPLE_MAX_STRING_CHARS)
            if (low > high) return gap(path, GapReason.UNSATISFIABLE)
            return Json.Str(word(low, high))
        }

        private fun fitsLength(text: String, min: Int?, max: Int?): Boolean {
            val length = text.codePointCount(0, text.length)
            return (min == null || length >= min) && (max == null || length <= max)
        }

        /** Lower-case letters in a pronounceable order ("kebuza"), [min]..[max] of them. */
        private fun word(min: Int, max: Int): String {
            val length = if (max > min) random.nextInt(min, max + 1) else min
            val out = StringBuilder(length)
            var vowel = random.nextBoolean()
            repeat(length) {
                val alphabet = if (vowel) VOWELS else CONSONANTS
                out.append(alphabet[random.nextInt(alphabet.length)])
                vowel = !vowel
            }
            return out.toString()
        }

        /** A random value in the shape of a known [format]; null for formats without one (the caller then writes a plain word). */
        private fun formatted(format: String): String? = when (format) {
            "email", "idn-email" -> "${word(3, 8)}.${word(2, 6)}@${word(3, 8)}.com"
            "uuid" -> uuid()
            "date" -> date().toString()
            "date-time" -> "${date()}T${time()}Z"
            "time" -> time()
            "duration" -> "PT${random.nextInt(1, 600)}M"
            "uri", "url", "iri", "uri-reference" -> "https://${word(3, 8)}.example.com/${word(3, 8)}"
            "hostname", "idn-hostname" -> "${word(3, 8)}.example.com"
            "ipv4" -> "${random.nextInt(1, 224)}.${random.nextInt(256)}.${random.nextInt(256)}.${random.nextInt(1, 255)}"
            "ipv6" -> (1..IPV6_GROUPS).joinToString(":") { random.nextInt(0x10000).toString(16) }
            "regex" -> ".*"
            "json-pointer" -> "/${word(3, 8)}"
            "password" -> word(8, 12)
            "byte" -> Base64.getEncoder().encodeToString(word(4, 12).toByteArray(Charsets.US_ASCII))
            else -> SampleValues.forFormat(format)
        }

        private fun uuid(): String {
            val high = (random.nextLong() and VERSION_MASK) or VERSION_4
            val low = (random.nextLong() and VARIANT_MASK) or VARIANT_RFC
            return java.util.UUID(high, low).toString()
        }

        private fun date(): LocalDate = LocalDate.ofEpochDay(random.nextLong(FIRST_DAY.toEpochDay(), LAST_DAY.toEpochDay() + 1))

        private fun time(): String = "%02d:%02d:%02d".format(random.nextInt(24), random.nextInt(60), random.nextInt(60))

        // --- Numbers ------------------------------------------------------------------------------------------------------

        /**
         * A multiple of a unit inside the bounds: the declared `multipleOf`, else 1 for integers, else a power of ten that is
         * made finer until the bounds leave room (0.01, 0.001, ...). Bounds, strictness and the unit are exact [BigDecimal]s.
         */
        private fun number(s: Schema, integer: Boolean, path: String): Json? {
            val multiple = s.multipleOf?.takeIf { it.signum() > 0 }
            val units: List<BigDecimal> = when {
                multiple != null -> listOf(multiple)
                integer -> listOf(BigDecimal.ONE)
                else -> (2..Limits.RANDOM_MAX_DECIMALS).map { BigDecimal.ONE.movePointLeft(it) }
            }
            var lower = s.minimum
            var lowerStrict = false
            s.exclusiveMinimum?.let { if (lower == null || it >= lower!!) { lower = it; lowerStrict = true } }
            var upper = s.maximum
            var upperStrict = false
            s.exclusiveMaximum?.let { if (upper == null || it <= upper!!) { upper = it; upperStrict = true } }
            for (unit in units) {
                val multiplier = multiplier(lower, lowerStrict, upper, upperStrict, unit, wholeOnly = integer && multiple != null) ?: continue
                val value = normalize(BigDecimal(multiplier).multiply(unit))
                return Json.Num(value)
            }
            return gap(path, GapReason.UNSATISFIABLE)
        }

        /** A whole number k with k*unit inside the bounds, or null when there is none. [wholeOnly] also demands k*unit to be whole. */
        private fun multiplier(lower: BigDecimal?, lowerStrict: Boolean, upper: BigDecimal?, upperStrict: Boolean, unit: BigDecimal, wholeOnly: Boolean): BigInteger? {
            val span = BigDecimal.valueOf(Limits.RANDOM_NUMBER_SPAN).divide(unit, 0, RoundingMode.CEILING).toBigInteger().max(BigInteger.ONE)
            val widest = BigDecimal.valueOf(Limits.RANDOM_NUMBER_MAX_WIDTH).divide(unit, 0, RoundingMode.CEILING).toBigInteger().max(BigInteger.ONE)
            var low: BigInteger? = lower?.let { bound ->
                if (lowerStrict) bound.divide(unit, 0, RoundingMode.FLOOR).toBigInteger().add(BigInteger.ONE)
                else bound.divide(unit, 0, RoundingMode.CEILING).toBigInteger()
            }
            var high: BigInteger? = upper?.let { bound ->
                if (upperStrict) bound.divide(unit, 0, RoundingMode.CEILING).toBigInteger().subtract(BigInteger.ONE)
                else bound.divide(unit, 0, RoundingMode.FLOOR).toBigInteger()
            }
            when {
                low == null && high == null -> { low = BigInteger.ZERO; high = span }
                low == null -> low = high!!.subtract(span)
                high == null -> high = low.add(span)
            }
            if (low!! > high!!) return null
            if (high.subtract(low) > widest) high = low.add(widest)
            repeat(if (wholeOnly) Limits.RANDOM_PATTERN_ATTEMPTS else 1) {
                val k = low.add(randomBelow(high.subtract(low).add(BigInteger.ONE)))
                if (!wholeOnly || isWhole(BigDecimal(k).multiply(unit))) return k
            }
            return null
        }

        private fun isWhole(v: BigDecimal) = v.signum() == 0 || v.stripTrailingZeros().scale() <= 0

        /** A uniformly random integer in 0 until [bound]; the bound is capped by the width limit, so it fits a Long. */
        private fun randomBelow(bound: BigInteger): BigInteger =
            if (bound.bitLength() < Long.SIZE_BITS) BigInteger.valueOf(random.nextLong(bound.toLong())) else BigInteger.ZERO

        private fun normalize(v: BigDecimal): BigDecimal {
            val stripped = if (v.signum() == 0) BigDecimal.ZERO else v.stripTrailingZeros()
            return if (stripped.scale() < 0) stripped.setScale(0) else stripped
        }
    }

    private const val FORMAT_ATTEMPTS = 8
    private const val IPV6_GROUPS = 8
    private const val VOWELS = "aeiou"
    private const val CONSONANTS = "bcdfghjklmnprstvz"
    private val FIRST_DAY = LocalDate.of(2000, 1, 1)
    private val LAST_DAY = LocalDate.of(2035, 12, 31)

    // UUID version 4 and the RFC 4122 variant, as bit masks over the two halves.
    private const val VERSION_MASK = -0xf001L
    private const val VERSION_4 = 0x4000L
    private const val VARIANT_MASK = 0x3fffffffffffffffL
    private const val VARIANT_RFC = Long.MIN_VALUE
}
