package com.routegenie.schema.model

import com.routegenie.json.JsonContent
import com.routegenie.json.JsonScanner
import com.routegenie.testing.SchemaChecker
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Property test of [RandomSampleGenerator] on thousands of generated schemas, fixed seeds and a few seconds of work.
 *
 * Schemas shaped like what the extractors produce (`extractorShaped`) must always give a complete, valid document or say why
 * not. Arbitrary schemas (contradicting limits, negative counts, dangling references) must still end quickly, never throw,
 * give the same answer for the same seed, explain every missing document with a gap, and write JSON that reads back.
 */
class RandomPropertyTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }

    private class Outcome(val result: SampleResult?, val error: Throwable?, val timedOut: Boolean)

    private fun guarded(schema: Schema, seed: Int, options: RandomOptions): Outcome {
        val future = pool.submit(Callable { RandomSampleGenerator.generate(schema, Random(seed), options) })
        return try {
            Outcome(future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), null, false)
        } catch (e: TimeoutException) {
            future.cancel(true)
            Outcome(null, null, true)
        } catch (e: ExecutionException) {
            Outcome(null, e.cause ?: e, false)
        }
    }

    private val optionSets = listOf(
        RandomOptions(), RandomOptions(optionalChance = 0.0), RandomOptions(optionalChance = 1.0, nullChance = 0.5),
        RandomOptions(nullChance = 1.0), RandomOptions(nullChance = 0.0),
    )

    /** Everything that must hold for any schema; the problems found, empty when all is well. */
    private fun anySchemaProblems(label: String, schema: Schema, seed: Int, options: RandomOptions): List<String> {
        val outcome = guarded(schema, seed, options)
        val where = "$label seed $seed ${SchemaWriter.text(schema, pretty = false).take(400)}"
        if (outcome.timedOut) return listOf("$where: no answer in $TIMEOUT_MS ms")
        outcome.error?.let { return listOf("$where: threw $it") }
        val result = outcome.result!!
        val problems = ArrayList<String>()
        val value = result.value
        if (value == null) {
            if (result.gaps.isEmpty()) problems += "$where: no value and no gap"
        } else {
            roundTrip(value)?.let { problems += "$where: JSON text $it" }
        }
        val again = RandomSampleGenerator.generate(schema, Random(seed), options)
        if (again.value != result.value || again.gaps != result.gaps) problems += "$where: another answer for the same seed"
        return problems
    }

    private fun roundTrip(value: Json): String? {
        for (pretty in listOf(true, false)) {
            val text = if (pretty) JsonText.pretty(value) else JsonText.compact(value)
            val parsed = try {
                StrictJson(text).parse()
            } catch (e: IllegalArgumentException) {
                return "does not parse (${e.message})"
            }
            if (parsed != value) return "reads back as another value"
            val index = JsonScanner.scan(JsonContent.text(text))
            val error = index.error
            index.close()
            if (error != null) return "is refused by the project's scanner (${error.code})"
        }
        return null
    }

    @Test
    fun `documents of extractor shaped schemas satisfy the schema or come with a gap`() {
        val problems = ArrayList<String>()
        for (i in 0 until EXTRACTOR_SCHEMAS) {
            val schema = SchemaGen(i + 100_000, wild = false, real = true).build()
            for (s in 0 until SEEDS) {
                val seed = s * 1013 + i
                val options = optionSets[(s + i) % optionSets.size]
                problems += anySchemaProblems("[real $i]", schema, seed, options)
                val value = RandomSampleGenerator.generate(schema, Random(seed), options)
                val document = value.value ?: continue
                // With no gap recorded the document has to be complete and valid.
                if (value.gaps.isEmpty()) {
                    val violations = SchemaChecker.violations(schema, document).filterNot { it.contains("nests deeper") }
                    if (violations.isNotEmpty()) problems += "[real $i] seed $seed ${SchemaWriter.text(schema, pretty = false).take(400)}: $violations in ${JsonText.compact(document).take(200)}"
                }
            }
            if (problems.size > MAX_PROBLEMS) break
        }
        assertTrue(problems.take(MAX_PROBLEMS).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `any schema at all ends quickly without an exception and the same seed gives the same answer`() {
        val problems = ArrayList<String>()
        for (i in 0 until ARBITRARY_SCHEMAS) {
            val schema = SchemaGen(i, wild = i % 3 == 0).build()
            for (s in 0 until 3) {
                val seed = s * 1009 + i
                problems += anySchemaProblems("[any $i]", schema, seed, optionSets[(s + i) % optionSets.size])
            }
            if (problems.size > MAX_PROBLEMS) break
        }
        assertTrue(problems.take(MAX_PROBLEMS).joinToString("\n"), problems.isEmpty())
    }

    // --- strict JSON reading, for the round trip ------------------------------------------------------------------------------

    private class StrictJson(private val s: String) {
        private var i = 0

        fun parse(): Json {
            ws()
            val v = value()
            ws()
            if (i != s.length) fail("trailing characters at $i")
            return v
        }

        private fun fail(m: String): Nothing = throw IllegalArgumentException(m)
        private fun ws() { while (i < s.length && s[i] in " \n\r\t") i++ }
        private fun value(): Json {
            if (i >= s.length) fail("eof")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> Json.Str(string())
                't' -> lit("true", Json.Bool(true))
                'f' -> lit("false", Json.Bool(false))
                'n' -> lit("null", Json.Null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Json): Json {
            if (!s.startsWith(word, i)) fail("bad literal at $i")
            i += word.length
            return v
        }

        private fun num(): Json {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("bad number")
            if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ } else fail("bad number at $start")
            if (i < s.length && s[i] == '.') {
                i++
                val d = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == d) fail("bad fraction at $start")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) fail("exponent form at $start: ${s.substring(start, minOf(s.length, i + 8))}")
            return Json.Num(BigDecimal(s.substring(start, i)))
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> fail("raw control character U+%04X".format(c.code))
                    c == '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): Json {
            i++
            val items = ArrayList<Json>()
            ws()
            if (s[i] == ']') { i++; return Json.Arr(items) }
            while (true) {
                ws()
                items += value()
                ws()
                if (i >= s.length) fail("eof in array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return Json.Arr(items) }
                fail("bad array at $i")
            }
        }

        private fun obj(): Json {
            i++
            val fields = LinkedHashMap<String, Json>()
            ws()
            if (s[i] == '}') { i++; return Json.Obj(fields) }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("object key expected at $i")
                val key = string()
                ws()
                if (i >= s.length || s[i] != ':') fail("colon expected at $i")
                i++
                ws()
                val v = value()
                if (fields.put(key, v) != null) fail("duplicate key '$key'")
                ws()
                if (i >= s.length) fail("eof in object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return Json.Obj(fields) }
                fail("bad object at $i")
            }
        }
    }


    // --- random schemas ----------------------------------------------------------------------------------------------------

    private class SchemaGen(seed: Int, private val wild: Boolean, private val real: Boolean = false) {
        private val r = Random(seed * 7919 + 13)
        private val defs = LinkedHashMap<String, Schema>()
        private var refNames: List<String> = emptyList()

        private fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
        private fun chance(percent: Int) = r.nextInt(100) < percent
        private fun bd(s: String) = BigDecimal(s)

        fun build(): Schema {
            refNames = listOf("A", "B", "C").take(r.nextInt(4))
            for (n in refNames) defs[n] = gen(1)
            val root = if (refNames.isNotEmpty() && chance(25)) Schema.ref(pick(refNames)) else gen(0)
            return root.copy(defs = LinkedHashMap(defs))
        }

        private fun gen(depth: Int): Schema {
            val roll = r.nextInt(100)
            val base = when {
                roll < 14 -> str()
                roll < 24 -> num(integer = true)
                roll < 32 -> num(integer = false)
                roll < 35 -> Schema.BOOLEAN
                roll < 37 -> Schema.NULL
                roll < 45 -> enumSchema()
                roll < 48 -> pick(VALUES).let { v -> if (real) Schema(types = listOf(v.jsonType()), constValue = v) else Schema(constValue = v) }
                roll < 49 -> Schema.EMPTY
                depth >= 4 -> str()
                roll < 61 -> array(depth)
                roll < 73 -> obj(depth)
                roll < 79 -> map(depth)
                roll < 85 -> union(depth, oneOf = false)
                roll < 88 -> union(depth, oneOf = !real)
                roll < 90 -> discriminated(depth)
                roll < 94 -> if (real) obj(depth) else allOf(depth)
                roll < 97 -> reference()
                else -> Schema.nullable(gen(depth + 1))
            }
            return if (real) base else decorate(base)
        }

        private fun decorate(s: Schema): Schema {
            var out = s
            if (chance(5)) out = out.copy(nullable = true)
            if (chance(3) && s.types.size == 1 && s.enumValues == null) out = out.copy(types = pick(TYPE_MIXES))
            if (wild && chance(3)) out = out.copy(readOnly = true)
            return out
        }

        private fun reference(): Schema {
            if (refNames.isEmpty()) return if (real) Schema.STRING else Schema.ref("Missing")
            if (!real && chance(8)) return Schema.ref("Missing")
            val ref = Schema.ref(pick(refNames))
            return if (wild && chance(10)) ref.copy(maxLength = 3, minimum = bd("1")) else ref
        }

        private fun lengths(): Pair<Int?, Int?> {
            var lo: Int? = if (chance(40)) pick(LENGTHS) else null
            var hi: Int? = if (chance(40)) pick(LENGTHS) else null
            if (!wild && lo != null && hi != null && lo > hi) { val t = lo; lo = hi; hi = t }
            if (!wild || real) { lo = lo?.coerceAtLeast(0); hi = hi?.coerceAtLeast(0) }
            if (real && lo != null && hi != null && lo > hi) { val t = lo; lo = hi; hi = t }
            return lo to hi
        }

        private fun str(): Schema {
            val (lo, hi) = lengths()
            return Schema.STRING.copy(
                minLength = lo,
                maxLength = hi,
                pattern = if (chance(25)) pick(PATTERNS) else null,
                format = if (chance(25)) pick(FORMATS) else null,
            ).let { if (real && it.format != null) it.copy(pattern = null) else it }
        }

        private fun num(integer: Boolean): Schema {
            var min: BigDecimal? = if (chance(40)) bd(pick(NUMS)) else null
            var max: BigDecimal? = if (chance(40)) bd(pick(NUMS)) else null
            var exMin: BigDecimal? = if (chance(12)) bd(pick(NUMS)) else null
            var exMax: BigDecimal? = if (chance(12)) bd(pick(NUMS)) else null
            val multiple: BigDecimal? = if (chance(25)) bd(pick(MULTIPLES)).let { if (real && it.signum() <= 0) null else it } else null
            if (!wild || real) {
                val lo = listOfNotNull(min, exMin).maxOrNull()
                val hi = listOfNotNull(max, exMax).minOrNull()
                if (lo != null && hi != null && lo >= hi) { min = hi; max = lo; exMin = null; exMax = null }
            }
            return (if (integer) Schema.INTEGER else Schema.NUMBER).copy(
                minimum = min, maximum = max, exclusiveMinimum = exMin, exclusiveMaximum = exMax, multipleOf = multiple,
            )
        }

        private fun enumSchema(): Schema {
            if (real) {
                val family = r.nextInt(3)
                val values: List<Json> = when (family) {
                    0 -> (0..r.nextInt(0, 4)).map { Json.Str(pick(listOf("a", "bb", "ccc", "red", "green", "kind"))) }
                    1 -> (0..r.nextInt(0, 4)).map { Json.num(pick(listOf(1, 2, 3, 10, -1))) }
                    else -> (0..r.nextInt(0, 3)).map { pick(VALUES) }
                }.distinct()
                val type = when (family) { 0 -> listOf(JsonType.STRING); 1 -> listOf(JsonType.INTEGER); else -> emptyList() }
                return Schema(types = type, enumValues = values)
            }
            val count = r.nextInt(if (wild) 0 else 1, 5)
            val values = (0 until count).map { pick(VALUES) }
            val types = when {
                chance(40) && values.isNotEmpty() -> listOf(values.first().jsonType().let { if (it == JsonType.NUMBER) JsonType.NUMBER else it })
                wild && chance(20) -> listOf(pick(JsonType.entries))
                else -> emptyList()
            }
            val extra = if (chance(20)) Schema.STRING.copy(minLength = pick(listOf(1, 2, 3)), maxLength = pick(listOf(2, 5, 10))) else Schema.EMPTY
            return extra.copy(types = types, enumValues = values)
        }

        private fun array(depth: Int): Schema {
            val kind = r.nextInt(10)
            val minItems = if (chance(40)) pick(ITEM_COUNTS).let { if (real) it.coerceAtLeast(0) else it } else null
            val maxItems = if (chance(35)) pick(ITEM_COUNTS).let { if (real) it.coerceAtLeast(0) else it } else null
            val unique = chance(30)
            val shaped = when {
                kind < 2 -> Schema(types = listOf(JsonType.ARRAY))
                kind < 4 -> (0..r.nextInt(3)).map { gen(depth + 1) }.let { prefix ->
                    if (real) Schema(types = listOf(JsonType.ARRAY), prefixItems = prefix, minItems = prefix.size, maxItems = prefix.size)
                    else Schema(types = listOf(JsonType.ARRAY), prefixItems = prefix, items = if (chance(30)) gen(depth + 1) else null)
                }
                else -> Schema.arrayOf(gen(depth + 1))
            }
            val lo = minItems
            val hi = maxItems
            if (real && shaped.prefixItems.isNotEmpty()) return shaped
            return if ((!wild || real) && lo != null && hi != null && lo > hi) shaped.copy(minItems = hi, maxItems = lo, uniqueItems = unique)
            else shaped.copy(minItems = lo, maxItems = hi, uniqueItems = unique)
        }

        private fun obj(depth: Int): Schema {
            val names = NAMES.shuffled(r).take(r.nextInt(0, 6))
            val properties = LinkedHashMap<String, Schema>()
            for (n in names) {
                var p = gen(depth + 1)
                if (wild && !real && chance(10)) p = p.copy(readOnly = true)
                properties[n] = p
            }
            val required = names.filter { chance(50) }.toMutableList()
            if (wild && !real && chance(10)) required += "ghost"
            val additional: Additional? = when (r.nextInt(8)) {
                0 -> Additional.Allowed
                1 -> Additional.Forbidden
                2 -> if (real) null else Additional.OfSchema(gen(depth + 1))
                else -> null
            }
            val minP = if (!real && chance(20)) pick(PROP_COUNTS) else null
            val maxP = if (!real && chance(20)) pick(PROP_COUNTS) else null
            return Schema(types = listOf(JsonType.OBJECT), properties = properties, required = required, additional = additional, minProperties = minP, maxProperties = maxP)
        }

        private fun map(depth: Int): Schema = Schema(
            types = listOf(JsonType.OBJECT),
            additional = Additional.OfSchema(gen(depth + 1)),
            minProperties = if (chance(30)) pick(PROP_COUNTS).let { if (real) it.coerceAtLeast(0) else it } else null,
            maxProperties = if (chance(30)) pick(PROP_COUNTS).let { if (real) it.coerceAtLeast(0) else it } else null,
        ).let { m ->
            val lo = m.minProperties
            val hi = m.maxProperties
            if (real && lo != null && hi != null && lo > hi) m.copy(minProperties = hi, maxProperties = lo) else m
        }

        private fun union(depth: Int, oneOf: Boolean): Schema {
            val branches = (0..r.nextInt(1, 3)).map { gen(depth + 1) }.toMutableList()
            if (chance(30)) branches += Schema.NULL
            val distinct = if (real) branches.distinct().let { if (it.size < 2) it + Schema.NULL else it } else branches
            return if (oneOf) Schema(oneOf = distinct) else Schema(anyOf = distinct)
        }

        private fun discriminated(depth: Int): Schema {
            val kinds = listOf("cat", "dog", "bird").take(r.nextInt(2, 4))
            val branches = kinds.map { k ->
                Schema(
                    types = listOf(JsonType.OBJECT),
                    properties = linkedMapOf("kind" to Schema(types = listOf(JsonType.STRING), constValue = Json.Str(k)), "x" to gen(depth + 2)),
                    required = listOf("kind"),
                )
            }
            return Schema(oneOf = branches, discriminator = "kind")
        }

        private fun allOf(depth: Int): Schema {
            val parts = ArrayList<Schema>()
            when (r.nextInt(4)) {
                0 -> repeat(r.nextInt(2, 4)) { parts += num(integer = chance(50)) }
                1 -> repeat(r.nextInt(2, 4)) { parts += str() }
                2 -> repeat(r.nextInt(2, 4)) { parts += obj(depth + 1) }
                else -> repeat(r.nextInt(2, 4)) { parts += gen(depth + 1) }
            }
            if (refNames.isNotEmpty() && chance(30)) parts += Schema.ref(pick(refNames))
            return Schema(allOf = parts)
        }

        companion object {
            val NUMS = listOf(
                "0", "1", "-1", "2", "3", "5", "10", "100", "1000", "0.5", "-0.5", "0.01", "0.001", "1.5", "2.5", "-100.25", "1E+3", "1E+10",
                "9223372036854775807", "-9223372036854775808", "100000000000000000000", "1E-7", "0.0000001", "1E+30", "12345.6789", "-1E+20",
            )
            val MULTIPLES = listOf("1", "2", "3", "7", "0.1", "0.01", "0.25", "0.3", "0.001", "1E+3", "1E-8", "0.5", "1.5", "10", "1000000007", "0", "-1", "1E+40")
            val LENGTHS = listOf(0, 1, 2, 3, 5, 12, 20, 50, 200, -1, 10_000, 10_001, 65_535)
            val ITEM_COUNTS = listOf(0, 1, 2, 3, 5, 40, -1)
            val PROP_COUNTS = listOf(0, 1, 2, 3, 5, 9, -1)
            val NAMES = listOf("a", "b", "c1", "with space", "\u00fcn\u00ef", "a.b", "\$ref", "", "key", "id", "x", "name")
            val TYPE_MIXES = listOf(
                listOf(JsonType.STRING, JsonType.INTEGER), listOf(JsonType.INTEGER, JsonType.NUMBER), listOf(JsonType.STRING, JsonType.NULL),
                listOf(JsonType.NULL), listOf(JsonType.BOOLEAN, JsonType.ARRAY), listOf(JsonType.OBJECT, JsonType.STRING),
            )
            val FORMATS = listOf(
                "email", "uuid", "date", "date-time", "time", "duration", "uri", "url", "hostname", "ipv4", "ipv6", "byte", "password", "binary",
                "regex", "json-pointer", "something-new", "idn-email", "uri-reference",
            )
            val PATTERNS = listOf(
                "^[a-z]+\$", "^\\d{3}-\\d{4}\$", "^(a|b|c)+\$", "^(?:foo|bar)baz?\$", "^a*?b+?\$", "^[^abc]{2,5}\$", "\\w+@\\w+\\.com",
                "^[\\p{L}]+\$", "^(?=x)x\$", "^(a)\\1\$", "^.{3}\$", "^\\s*\\S+\\s*\$", "^[A-Z][a-z]{2,}\$", "^(\\d{1,3}\\.){3}\\d{1,3}\$",
                "^\\+?[0-9]{7,15}\$", "^\u00e9+\$", "^[\u00e0-\u00ff]{3}\$", "([", "^\$", "", "^x{0}\$", "^(x{0}){5}\$", "^a{3,1}\$", "^[z-a]\$",
                "^[\\d\\w-]+\$", "^[^\\d]+\$", "^\\.\\*\$", "^a|b\$", "^\\\$\$", "^(?i)abc\$", "^\\bfoo\\b\$", "^[\\]]+\$", "^[a\\-z]+\$",
                "^\\/api\\/v[0-9]\$", "^(a+)+\$", "^a{2}\$", "^(ab){2,3}\$", "[0-9]{3}", "^[A-Z]{3}-\\d{4}\$", "^\\S+\$", "^[ -~]{4,6}\$",
                "^(red|green|blue)\$", "^\\u0041\$", "^\\x41\$", "^[\\w.+-]+@[\\w-]+\\.[\\w.]+\$", "^\\d+(\\.\\d+)?\$", "^-?\\d{1,3}\$",
                "^(?P<year>\\d{4})-(?P<month>\\d{2})\$", "^\\D+\$", "^\\W+\$", "^[\\s\\S]{2}\$", "^\\n\$", "^a\\nb\$", "^[\\n]\$", "^\\t{2}\$",
            )
            val VALUES: List<Json> = listOf(
                Json.Str("a"), Json.Str("bb"), Json.Str(""), Json.Str("\u00e9"), Json.num(1), Json.num(2), Json.Num(BigDecimal("2.5")), Json.num(-1),
                Json.Num(BigDecimal("1.0")), Json.Bool(true), Json.Bool(false), Json.Null, Json.Arr(listOf(Json.num(1))), Json.Obj(mapOf("k" to Json.Str("v"))),
                Json.Str("kind"), Json.Str("2024-01-01"),
            )
        }
    }


    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val EXTRACTOR_SCHEMAS = 4000
        const val ARBITRARY_SCHEMAS = 2000
        const val SEEDS = 4
        const val MAX_PROBLEMS = 20
    }
}
