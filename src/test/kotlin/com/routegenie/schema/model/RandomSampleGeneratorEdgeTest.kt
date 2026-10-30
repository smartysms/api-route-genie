package com.routegenie.schema.model

import com.routegenie.Limits
import com.routegenie.testing.SchemaChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Edge cases found by an adversarial run against [RandomSampleGenerator]: limits that are negative, tuples with item limits,
 * work that grew with the square of the input or with the product of nested repeats, maps with bounds and key types, numbers
 * finer than the usual decimals, and decimals written as text. Each document is also read back by [SchemaChecker].
 */
class RandomSampleGeneratorEdgeTest {
    private val seeds = 0 until 150

    private fun bd(s: String) = BigDecimal(s)

    private fun generate(schema: Schema, seed: Int) = RandomSampleGenerator.generate(schema, Random(seed))

    private fun assertAlwaysValid(label: String, schema: Schema) {
        for (seed in seeds) {
            val result = generate(schema, seed)
            val value = result.value
            assertNotNull("$label seed $seed: no value, gaps ${result.gaps}", value)
            val violations = SchemaChecker.violations(schema, value!!)
            assertTrue("$label seed $seed: $violations in ${JsonText.compact(value)}", violations.isEmpty())
        }
    }

    private fun assertNoValue(label: String, schema: Schema) {
        for (seed in 0 until 10) {
            val result = generate(schema, seed)
            assertNull("$label seed $seed: a value was invented: ${result.value?.let { JsonText.compact(it) }}", result.value)
            assertTrue("$label: a gap is recorded", result.gaps.isNotEmpty())
        }
    }

    private inline fun assertFast(label: String, limitMs: Long, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$label took $ms ms (limit $limitMs ms)", ms <= limitMs)
    }

    @Test
    fun `a negative length limit neither crashes nor asks for anything`() {
        assertAlwaysValid("minLength -1", Schema.STRING.copy(minLength = -1))
        assertAlwaysValid("minLength -1 with a pattern", Schema.STRING.copy(minLength = -3, pattern = "^[a-z]{2}$"))
        assertNoValue("maxLength -1", Schema.STRING.copy(maxLength = -1))
        assertNoValue("maxLength -1 with a pattern", Schema.STRING.copy(maxLength = -1, pattern = "^[a-z]{2}$"))
        assertNoValue("maxItems -1 without items", Schema(types = listOf(JsonType.ARRAY), maxItems = -1))
    }

    @Test
    fun `a tuple keeps to its item limits and stays unique when asked`() {
        val positions = listOf(Schema.INTEGER, Schema.STRING, Schema.BOOLEAN)
        val tuple = Schema(types = listOf(JsonType.ARRAY), prefixItems = positions)
        assertAlwaysValid("tuple of three", tuple.copy(minItems = 3, maxItems = 3))
        for (seed in seeds) assertEquals(2, ((generate(tuple.copy(maxItems = 2), seed).value) as Json.Arr).items.size)
        for (seed in seeds) {
            val items = ((generate(tuple.copy(minItems = 1, maxItems = 2), seed).value) as Json.Arr).items
            assertTrue(items.size in 1..2)
        }
        assertNoValue("more items than positions and no item schema", tuple.copy(minItems = 5))
        for (seed in seeds) {
            val items = ((generate(tuple.copy(items = Schema.STRING, minItems = 5), seed).value) as Json.Arr).items
            assertEquals(5, items.size)
            assertTrue(items[3] is Json.Str && items[4] is Json.Str)
        }
        assertAlwaysValid("two booleans that differ", Schema(types = listOf(JsonType.ARRAY), prefixItems = listOf(Schema.BOOLEAN, Schema.BOOLEAN), uniqueItems = true, minItems = 2, maxItems = 2))
    }

    @Test
    fun `a unique array of many items takes linear time`() {
        val ints = Schema.INTEGER.copy(minimum = bd("0"), maximum = bd("1000000"))
        assertFast("30000 unique integers", 4_000) {
            val items = (generate(Schema.arrayOf(ints).copy(minItems = 30_000, uniqueItems = true), 1).value as Json.Arr).items
            assertEquals(30_000, items.size)
            assertEquals(30_000, items.toSet().size)
        }
    }

    @Test
    fun `nested repeats of empty groups end quickly instead of multiplying`() {
        for (pattern in listOf("((((a{0}){1000}){1000}){1000}){1000}", "(((x?){1000}){1000}){1000}")) {
            assertFast(pattern, 5_000) {
                val result = generate(Schema.STRING.copy(pattern = pattern), 1)
                // An answer or a recorded gap is fine; what matters is that it comes.
                assertTrue(result.value != null || result.gaps.isNotEmpty())
            }
        }
    }

    @Test
    fun `a pattern is not sampled with spaces at its ends`() {
        val schema = Schema.STRING.copy(pattern = "^[ -~]{2,5}$")
        for (seed in 0 until 400) {
            val text = (generate(schema, seed).value as Json.Str).value
            assertEquals("'$text' has an edge space", text.trim(), text)
        }
        // Where the pattern needs a space, it still gets one.
        assertEquals(" ", (generate(Schema.STRING.copy(pattern = "^[ ]$"), 1).value as Json.Str).value)
    }

    @Test
    fun `maps meet their bounds`() {
        val map = Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.INTEGER))
        for (seed in seeds) assertEquals(emptyMap<String, Json>(), (generate(map.copy(maxProperties = 0), seed).value as Json.Obj).fields)
        for (seed in seeds) assertEquals(5, (generate(map.copy(minProperties = 5), seed).value as Json.Obj).fields.size)
        assertAlwaysValid("between 4 and 6", map.copy(minProperties = 4, maxProperties = 6))
        assertNoValue("5 to 2", map.copy(minProperties = 5, maxProperties = 2))
    }

    @Test
    fun `map keys are values of the key type written as text`() {
        val numbers = Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.STRING), propertyNames = Schema.INTEGER.copy(minimum = bd("10"), maximum = bd("99")))
        assertAlwaysValid("integer keys", numbers)
        for (seed in seeds) {
            val keys = (generate(numbers, seed).value as Json.Obj).fields.keys
            assertTrue(keys.isNotEmpty() && keys.all { it.toIntOrNull() in 10..99 })
        }
        val colors = Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.INTEGER), propertyNames = Schema(types = listOf(JsonType.STRING), enumValues = listOf(Json.str("red"), Json.str("green"))))
        assertAlwaysValid("enum keys", colors)
        for (seed in seeds) assertTrue((generate(colors, seed).value as Json.Obj).fields.keys.all { it == "red" || it == "green" })
        // Two names exist, so three entries cannot be unique: the map has fewer, never a repeat.
        for (seed in seeds) assertTrue((generate(colors.copy(minProperties = 1), seed).value as Json.Obj).fields.size in 1..2)
        assertAlwaysValid("uuid keys", colors.copy(propertyNames = Schema.of(JsonType.STRING, "uuid")))
        assertAlwaysValid("boolean keys", colors.copy(propertyNames = Schema.BOOLEAN))
        assertNoValue("a key type that has no value", colors.copy(propertyNames = Schema.EMPTY, minProperties = 1))
    }

    @Test
    fun `the plain sample of a map also uses the key type`() {
        val numbers = Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.STRING), propertyNames = Schema.INTEGER)
        val value = SampleGenerator.generate(numbers).value as Json.Obj
        assertEquals(setOf("0"), value.fields.keys)
    }

    @Test
    fun `numbers finer than the usual decimals are still found`() {
        assertAlwaysValid("equal tiny bounds", Schema.NUMBER.copy(minimum = bd("0.000000001"), maximum = bd("0.000000002")))
        assertAlwaysValid("exclusive 0 to 1E-7", Schema.NUMBER.copy(exclusiveMinimum = bd("0"), exclusiveMaximum = bd("1E-7")))
        assertNoValue("no number between equal exclusive bounds", Schema.NUMBER.copy(exclusiveMinimum = bd("0.5"), exclusiveMaximum = bd("0.5")))
    }

    @Test
    fun `a decimal written as text is a decimal inside the bounds of the number`() {
        val text = Schema.of(JsonType.STRING, SampleValues.DECIMAL).copy(minimum = bd("0"), maximum = bd("100"), multipleOf = bd("0.05"))
        for (seed in seeds) {
            val value = (generate(text, seed).value as Json.Str).value
            val number = BigDecimal(value)
            assertTrue("$value outside 0..100", number >= bd("0") && number <= bd("100"))
            assertEquals("$value is not a multiple of 0.05", 0, number.remainder(bd("0.05")).signum())
        }
        val decimal = Schema(anyOf = listOf(Schema.NUMBER.copy(minimum = bd("1"), maximum = bd("9")), text.copy(minimum = bd("1"), maximum = bd("9"), multipleOf = null)))
        assertEquals("both forms appear", setOf(true, false), seeds.map { generate(decimal, it).value is Json.Str }.toSet())
        assertAlwaysValid("number or text", decimal)
        assertEquals(Json.Str("0"), SampleGenerator.generate(text.copy(minimum = null, maximum = null, multipleOf = null)).value)
    }

    @Test
    fun `a long maximum does not make long text`() {
        for (seed in seeds) {
            val text = (generate(Schema.STRING.copy(maxLength = 65_535), seed).value as Json.Str).value
            assertTrue("${text.length} characters", text.length <= Limits.RANDOM_STRING_MAX_CHARS)
        }
        for (seed in seeds) {
            val text = (generate(Schema.STRING.copy(minLength = 500, maxLength = 600), seed).value as Json.Str).value
            assertTrue("${text.length} characters", text.length in 500..600)
        }
    }

    @Test
    fun `a formatted string under a tight length limit is found`() {
        assertAlwaysValid("url of at most 30 characters", Schema.of(JsonType.STRING, "uri").copy(maxLength = 30))
        assertAlwaysValid("email of at most 22 characters", Schema.of(JsonType.STRING, "email").copy(maxLength = 22))
    }

    @Test
    fun `a form leaves null list items out instead of giving up`() {
        val body = Json.obj("a" to Json.arr(Json.num(1), Json.Null, Json.num(2)), "b" to Json.Null)
        assertEquals("a=1&a=2", FormBody.encode(body))
        val schema = Schema(types = listOf(JsonType.OBJECT), properties = linkedMapOf("n" to Schema.arrayOf(Schema.nullable(Schema.INTEGER)).copy(minItems = 3, maxItems = 3)), required = listOf("n"))
        for (seed in 0 until 300) {
            val template = BodyRenderer.renderRandom(RequestBodySchema(BodyEncoding.FORM_URLENCODED, schema), Random(seed), RandomOptions(nullChance = 0.5))
            assertNotNull("seed $seed gave no form", template)
        }
    }
}
