package com.routegenie.schema.model

import com.routegenie.Limits
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.SchemaChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

/**
 * The random generator is trusted only because a second reader ([SchemaChecker]) agrees with every document it writes: each
 * schema below is generated many times with different seeds and every result must satisfy the schema.
 */
class RandomSampleGeneratorTest {
    private val seeds = 0 until 300

    private fun bd(s: String) = BigDecimal(s)

    private fun obj(vararg props: Pair<String, Schema>, required: List<String> = props.map { it.first }, additional: Additional? = null) =
        Schema(types = listOf(JsonType.OBJECT), properties = linkedMapOf(*props), required = required, additional = additional)

    private fun strings(vararg values: String) = Schema(types = listOf(JsonType.STRING), enumValues = values.map { Json.str(it) })

    /** Generates [seeds] documents and requires each to be a valid, complete instance of [schema]. */
    private fun assertAlwaysValid(label: String, schema: Schema, options: RandomOptions = RandomOptions()) {
        for (seed in seeds) {
            val result = RandomSampleGenerator.generate(schema, Random(seed), options)
            val value = result.value
            assertNotNull("$label seed $seed: no value, gaps ${result.gaps}", value)
            val violations = SchemaChecker.violations(schema, value!!)
            assertTrue("$label seed $seed: $violations in ${JsonText.compact(value)}", violations.isEmpty())
            assertTrue("$label seed $seed: gaps ${result.gaps}", result.gaps.isEmpty())
        }
    }

    private fun assertNoValue(label: String, schema: Schema, reason: GapReason) {
        for (seed in 0 until 20) {
            val result = RandomSampleGenerator.generate(schema, Random(seed))
            assertNull("$label seed $seed: a value was invented: ${result.value?.let { JsonText.compact(it) }}", result.value)
            assertTrue("$label: $reason expected in ${result.gaps}", result.gaps.any { it.reason == reason })
        }
    }

    // --- scalars ------------------------------------------------------------------------------------------------------

    @Test
    fun `booleans come out both ways`() {
        val values = seeds.map { RandomSampleGenerator.generate(Schema.BOOLEAN, Random(it)).value }.toSet()
        assertEquals(setOf<Json?>(Json.Bool(true), Json.Bool(false)), values)
    }

    @Test
    fun `integers and numbers stay within every bound`() {
        assertAlwaysValid("plain integer", Schema.INTEGER)
        assertAlwaysValid("ge le", Schema.INTEGER.copy(minimum = bd("1"), maximum = bd("10")))
        assertAlwaysValid("gt lt", Schema.INTEGER.copy(exclusiveMinimum = bd("0"), exclusiveMaximum = bd("5")))
        assertAlwaysValid("only min", Schema.INTEGER.copy(minimum = bd("100")))
        assertAlwaysValid("only max", Schema.INTEGER.copy(maximum = bd("-100")))
        assertAlwaysValid("only strict min", Schema.INTEGER.copy(exclusiveMinimum = bd("7")))
        assertAlwaysValid("equal bounds", Schema.INTEGER.copy(minimum = bd("7"), maximum = bd("7")))
        assertAlwaysValid("one value between strict bounds", Schema.INTEGER.copy(exclusiveMinimum = bd("1"), exclusiveMaximum = bd("3")))
        assertAlwaysValid("fractional bounds on an integer", Schema.INTEGER.copy(minimum = bd("0.5"), maximum = bd("2.5")))
        assertAlwaysValid("int32 range", Schema.INTEGER.copy(minimum = bd("-2147483648"), maximum = bd("2147483647")))
        assertAlwaysValid("int64 range", Schema.INTEGER.copy(minimum = bd("-9223372036854775808"), maximum = bd("9223372036854775807")))
        assertAlwaysValid("beyond int64", Schema.INTEGER.copy(minimum = bd("100000000000000000000000"), maximum = bd("100000000000000000000100")))
        assertAlwaysValid("multiple of 5", Schema.INTEGER.copy(multipleOf = bd("5"), minimum = bd("1"), maximum = bd("100")))
        assertAlwaysValid("multiple of 7 unbounded", Schema.INTEGER.copy(multipleOf = bd("7")))
        assertAlwaysValid("multiple of 0.5 on an integer", Schema.INTEGER.copy(multipleOf = bd("0.5"), minimum = bd("0"), maximum = bd("10")))
        assertAlwaysValid("number", Schema.NUMBER)
        assertAlwaysValid("number gt 0 lt 1", Schema.NUMBER.copy(exclusiveMinimum = bd("0"), exclusiveMaximum = bd("1")))
        assertAlwaysValid("number tiny range", Schema.NUMBER.copy(minimum = bd("0.0011"), maximum = bd("0.0019")))
        assertAlwaysValid("number negative range", Schema.NUMBER.copy(minimum = bd("-5.5"), maximum = bd("-5.25")))
        assertAlwaysValid("number multiple of 0.25", Schema.NUMBER.copy(multipleOf = bd("0.25"), minimum = bd("0"), maximum = bd("3")))
        assertAlwaysValid("number multiple of 0.01 (money)", Schema.NUMBER.copy(multipleOf = bd("0.01"), minimum = bd("0.01"), maximum = bd("9999.99")))
        assertAlwaysValid("integer or number", Schema(types = listOf(JsonType.INTEGER, JsonType.NUMBER), minimum = bd("0"), maximum = bd("3")))
    }

    @Test
    fun `numbers that cannot exist give no value`() {
        assertNoValue("no integer between 1 and 2", Schema.INTEGER.copy(exclusiveMinimum = bd("1"), exclusiveMaximum = bd("2")), GapReason.UNSATISFIABLE)
        assertNoValue("min above max", Schema.NUMBER.copy(minimum = bd("5"), maximum = bd("4")), GapReason.UNSATISFIABLE)
        assertNoValue("no multiple of 10 in 11..19", Schema.INTEGER.copy(multipleOf = bd("10"), minimum = bd("11"), maximum = bd("19")), GapReason.UNSATISFIABLE)
    }

    @Test
    fun `numbers vary and are plain integers where the type says integer`() {
        val values = seeds.mapNotNull { RandomSampleGenerator.generate(Schema.INTEGER.copy(minimum = bd("1"), maximum = bd("1000")), Random(it)).value as? Json.Num }
        assertTrue("many different integers expected, got ${values.toSet().size}", values.toSet().size > 100)
        assertTrue(values.all { it.value.scale() <= 0 })
        val floats = seeds.mapNotNull { RandomSampleGenerator.generate(Schema.NUMBER, Random(it)).value as? Json.Num }
        assertTrue("fractions appear for number", floats.any { it.value.stripTrailingZeros().scale() > 0 })
    }

    // --- strings ------------------------------------------------------------------------------------------------------

    @Test
    fun `strings respect their length limits`() {
        assertAlwaysValid("plain", Schema.STRING)
        assertAlwaysValid("min 20", Schema.STRING.copy(minLength = 20))
        assertAlwaysValid("max 2", Schema.STRING.copy(maxLength = 2))
        assertAlwaysValid("max 0", Schema.STRING.copy(maxLength = 0))
        assertAlwaysValid("exactly 5", Schema.STRING.copy(minLength = 5, maxLength = 5))
        assertAlwaysValid("1..1", Schema.STRING.copy(minLength = 1, maxLength = 1))
        assertAlwaysValid("long", Schema.STRING.copy(minLength = 500, maxLength = 600))
        assertNoValue("min above max", Schema.STRING.copy(minLength = 5, maxLength = 2), GapReason.UNSATISFIABLE)
        assertNoValue("beyond the limit", Schema.STRING.copy(minLength = Limits.SAMPLE_MAX_STRING_CHARS + 1), GapReason.LIMIT)
    }

    @Test
    fun `strings vary and are never empty without a reason`() {
        val strings = seeds.mapNotNull { (RandomSampleGenerator.generate(Schema.STRING, Random(it)).value as? Json.Str)?.value }
        assertTrue(strings.toSet().size > 200)
        assertTrue(strings.none { it.isEmpty() })
        assertTrue(strings.all { it.length in Limits.RANDOM_STRING_MIN_CHARS..Limits.RANDOM_STRING_MAX_CHARS })
    }

    @Test
    fun `patterns are matched and drawn at random`() {
        assertAlwaysValid("code", Schema.STRING.copy(pattern = "^[A-Z]{3}-\\d{4}$"))
        assertAlwaysValid("alternation", Schema.STRING.copy(pattern = "^(red|green|blue)$"))
        assertAlwaysValid("phone", Schema.STRING.copy(pattern = "^\\+?[0-9]{7,15}$"))
        assertAlwaysValid("with length", Schema.STRING.copy(pattern = "^[a-z]+$", minLength = 4, maxLength = 8))
        assertAlwaysValid("optional group", Schema.STRING.copy(pattern = "^[a-z]{2}(-[A-Z]{2})?$"))
        assertAlwaysValid("negated class", Schema.STRING.copy(pattern = "^[^0-9 ]{5}$"))
        assertAlwaysValid("unanchored", Schema.STRING.copy(pattern = "[0-9]{3}"))
        assertAlwaysValid("named group", Schema.STRING.copy(pattern = "^(?P<year>\\d{4})-(?P<month>\\d{2})$"))
        val codes = seeds.mapNotNull { (RandomSampleGenerator.generate(Schema.STRING.copy(pattern = "^[A-Z]{3}-\\d{4}$"), Random(it)).value as? Json.Str)?.value }
        assertTrue("codes vary, got ${codes.toSet().size}", codes.toSet().size > 200)
        val colors = seeds.mapNotNull { (RandomSampleGenerator.generate(Schema.STRING.copy(pattern = "^(red|green|blue)$"), Random(it)).value as? Json.Str)?.value }.toSet()
        assertEquals(setOf("red", "green", "blue"), colors)
    }

    @Test
    fun `a pattern outside the supported subset gives no value`() {
        assertNoValue("look-ahead", Schema.STRING.copy(pattern = "^(?=.*\\d)[a-z0-9]+$"), GapReason.PATTERN)
        assertNoValue("back reference", Schema.STRING.copy(pattern = "^(a)\\1$"), GapReason.PATTERN)
        assertNoValue("invalid regex", Schema.STRING.copy(pattern = "([unclosed"), GapReason.PATTERN)
    }

    @Test
    fun `every known format is valid for its kind and varies`() {
        val formats = listOf("email", "uuid", "date", "date-time", "time", "duration", "uri", "url", "hostname", "ipv4", "ipv6", "byte", "password", "json-pointer", "regex")
        for (format in formats) assertAlwaysValid("format $format", Schema.STRING.copy(format = format))
        for (format in listOf("email", "uuid", "date", "date-time", "ipv4", "ipv6", "uri")) {
            val values = seeds.mapNotNull { (RandomSampleGenerator.generate(Schema.STRING.copy(format = format), Random(it)).value as? Json.Str)?.value }.toSet()
            assertTrue("$format varies: ${values.size}", values.size > 100)
        }
        assertAlwaysValid("unknown format is a plain string", Schema.STRING.copy(format = "something-new"))
        assertNoValue("binary", Schema.STRING.copy(format = "binary"), GapReason.BINARY)
    }

    @Test
    fun `a format and a length limit that cannot meet give no value`() {
        assertNoValue("uuid in 5 characters", Schema.STRING.copy(format = "uuid", maxLength = 5), GapReason.UNSATISFIABLE)
    }

    // --- enums, constants, nulls ----------------------------------------------------------------------------------------

    @Test
    fun `enums and constants`() {
        assertAlwaysValid("string enum", strings("a", "b", "c"))
        assertAlwaysValid("int enum", Schema(types = listOf(JsonType.INTEGER), enumValues = listOf(Json.num(1), Json.num(2), Json.num(3))))
        assertAlwaysValid("mixed enum", Schema(enumValues = listOf(Json.str("a"), Json.num(3), Json.Null, Json.Bool(true))))
        assertAlwaysValid("const", Schema(constValue = Json.str("fixed")))
        val picked = seeds.mapNotNull { RandomSampleGenerator.generate(strings("a", "b", "c"), Random(it)).value }.toSet()
        assertEquals(3, picked.size)
        assertNoValue("empty enum", Schema(enumValues = emptyList()), GapReason.UNSATISFIABLE)
    }

    @Test
    fun `defaults and examples are not repeated`() {
        val schema = Schema.STRING.copy(default = Json.str("the-default"), examples = listOf(Json.str("the-example")))
        val values = seeds.mapNotNull { (RandomSampleGenerator.generate(schema, Random(it)).value as? Json.Str)?.value }
        assertTrue(values.none { it == "the-default" || it == "the-example" })
    }

    @Test
    fun `null appears for nullable values at the chosen chance and never otherwise`() {
        val optional = Schema.nullable(Schema.INTEGER)
        assertAlwaysValid("optional int", optional)
        assertAlwaysValid("types with null", Schema(types = listOf(JsonType.STRING, JsonType.NULL)))
        assertAlwaysValid("nullable flag", Schema.STRING.copy(nullable = true))
        val always = seeds.map { RandomSampleGenerator.generate(optional, Random(it), RandomOptions(nullChance = 1.0)).value }
        assertTrue(always.all { it == Json.Null })
        val never = seeds.map { RandomSampleGenerator.generate(optional, Random(it), RandomOptions(nullChance = 0.0)).value }
        assertTrue(never.none { it == Json.Null })
        val some = seeds.map { RandomSampleGenerator.generate(optional, Random(it)).value }
        assertTrue("null is rare but happens", some.count { it == Json.Null } in 1..(seeds.count() / 3))
        assertAlwaysValid("null only", Schema.NULL)
    }

    // --- arrays ------------------------------------------------------------------------------------------------------

    @Test
    fun `arrays respect their counts and uniqueness`() {
        val int = Schema.INTEGER.copy(minimum = bd("0"), maximum = bd("1000"))
        assertAlwaysValid("plain", Schema.arrayOf(int))
        assertAlwaysValid("min 3", Schema.arrayOf(int).copy(minItems = 3))
        assertAlwaysValid("max 1", Schema.arrayOf(int).copy(maxItems = 1))
        assertAlwaysValid("exactly 4", Schema.arrayOf(int).copy(minItems = 4, maxItems = 4))
        assertAlwaysValid("max 0", Schema.arrayOf(int).copy(maxItems = 0))
        assertAlwaysValid("min 10", Schema.arrayOf(int).copy(minItems = 10))
        assertAlwaysValid("unique", Schema.arrayOf(int).copy(uniqueItems = true, minItems = 3))
        assertAlwaysValid("unique from three values", Schema.arrayOf(strings("a", "b", "c")).copy(uniqueItems = true, minItems = 3, maxItems = 3))
        assertAlwaysValid("unique booleans", Schema.arrayOf(Schema.BOOLEAN).copy(uniqueItems = true, minItems = 2))
        assertAlwaysValid("empty by default without an item type", Schema(types = listOf(JsonType.ARRAY)))
        assertAlwaysValid("nested arrays", Schema.arrayOf(Schema.arrayOf(Schema.arrayOf(int))))
        assertNoValue("unique but only two values for three items", Schema.arrayOf(Schema.BOOLEAN).copy(uniqueItems = true, minItems = 3), GapReason.UNSATISFIABLE)
        assertNoValue("min above max", Schema.arrayOf(int).copy(minItems = 5, maxItems = 2), GapReason.UNSATISFIABLE)
    }

    @Test
    fun `arrays are not empty unless the schema says so and their sizes vary`() {
        val sizes = seeds.mapNotNull { ((RandomSampleGenerator.generate(Schema.arrayOf(Schema.INTEGER), Random(it)).value) as? Json.Arr)?.items?.size }.toSet()
        assertEquals(setOf(1, 2, 3), sizes)
    }

    @Test
    fun `tuples have exactly their positions`() {
        val tuple = Schema(types = listOf(JsonType.ARRAY), prefixItems = listOf(Schema.INTEGER, Schema.STRING, Schema.BOOLEAN), minItems = 3, maxItems = 3)
        assertAlwaysValid("tuple", tuple)
        val first = RandomSampleGenerator.generate(tuple, Random(1)).value as Json.Arr
        assertTrue(first.items[0] is Json.Num && first.items[1] is Json.Str && first.items[2] is Json.Bool)
    }

    // --- objects -----------------------------------------------------------------------------------------------------

    @Test
    fun `required properties are always present and optional ones follow the chance`() {
        val schema = obj("id" to Schema.INTEGER, "name" to Schema.STRING, "note" to Schema.STRING, "age" to Schema.INTEGER, required = listOf("id", "name"))
        assertAlwaysValid("mixed", schema)
        val none = seeds.map { RandomSampleGenerator.generate(schema, Random(it), RandomOptions(optionalChance = 0.0)).value as Json.Obj }
        assertTrue(none.all { it.fields.keys == setOf("id", "name") })
        val all = seeds.map { RandomSampleGenerator.generate(schema, Random(it), RandomOptions(optionalChance = 1.0)).value as Json.Obj }
        assertTrue(all.all { it.fields.keys == setOf("id", "name", "note", "age") })
        val default = seeds.map { (RandomSampleGenerator.generate(schema, Random(it)).value as Json.Obj).fields.keys }.toSet()
        assertTrue("optional members come and go: ${default.size} shapes", default.size > 2)
    }

    @Test
    fun `read only members are never written and member order is the declared one`() {
        val schema = obj("b" to Schema.STRING, "id" to Schema.INTEGER.copy(readOnly = true), "a" to Schema.STRING)
        val value = RandomSampleGenerator.generate(schema, Random(3)).value as Json.Obj
        assertEquals(listOf("b", "a"), value.fields.keys.toList())
    }

    @Test
    fun `nested objects and lists of objects`() {
        val address = obj("street" to Schema.STRING.copy(minLength = 3, maxLength = 20), "zip" to Schema.STRING.copy(pattern = "^\\d{5}$"))
        val item = obj(
            "sku" to Schema.STRING.copy(pattern = "^[A-Z]{3}-\\d{4}$"),
            "qty" to Schema.INTEGER.copy(minimum = bd("1"), maximum = bd("10")),
            "price" to Schema.NUMBER.copy(exclusiveMinimum = bd("0"), exclusiveMaximum = bd("1000")),
            "tags" to Schema.arrayOf(Schema.STRING).copy(minItems = 1, maxItems = 3, uniqueItems = true),
            required = listOf("sku", "qty", "price"),
        )
        val order = obj(
            "id" to Schema.STRING.copy(format = "uuid"),
            "placed" to Schema.STRING.copy(format = "date-time"),
            "customer" to address,
            "items" to Schema.arrayOf(item).copy(minItems = 1),
            "attributes" to Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.INTEGER)),
            "history" to Schema.arrayOf(Schema.arrayOf(address)),
            required = listOf("id", "placed", "customer", "items"),
        )
        assertAlwaysValid("order", order)
        var deep: Schema = Schema.STRING
        repeat(10) { deep = obj("next" to deep) }
        assertAlwaysValid("ten levels", deep)
    }

    @Test
    fun `maps have a few entries with generated keys`() {
        val map = Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.INTEGER))
        assertAlwaysValid("map", map)
        assertAlwaysValid("map of maps", Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(map)))
        assertAlwaysValid("map with limits", map.copy(minProperties = 2, maxProperties = 2))
        assertAlwaysValid("map of lists", Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.arrayOf(Schema.STRING))))
        val sizes = seeds.map { ((RandomSampleGenerator.generate(map, Random(it)).value) as Json.Obj).fields.size }.toSet()
        assertEquals(setOf(1, 2, 3), sizes)
        assertNoValue("min above max", map.copy(minProperties = 5, maxProperties = 2), GapReason.UNSATISFIABLE)
    }

    @Test
    fun `property limits are met by bringing back or dropping optional members`() {
        val schema = obj("a" to Schema.STRING, "b" to Schema.STRING, "c" to Schema.STRING, "d" to Schema.STRING, required = emptyList())
        assertAlwaysValid("at least three", schema.copy(minProperties = 3), RandomOptions(optionalChance = 0.0))
        assertAlwaysValid("at most one", schema.copy(maxProperties = 1), RandomOptions(optionalChance = 1.0))
        assertNoValue("more required than allowed", schema.copy(required = listOf("a", "b"), maxProperties = 1), GapReason.UNSATISFIABLE)
        assertNoValue("more demanded than declared", schema.copy(minProperties = 9), GapReason.UNSATISFIABLE)
    }

    @Test
    fun `an object that forbids more members gets none`() {
        val schema = obj("a" to Schema.STRING, additional = Additional.Forbidden)
        assertAlwaysValid("closed", schema)
    }

    // --- unions, discriminators, references --------------------------------------------------------------------------

    @Test
    fun `unions pick every branch over time and only valid ones`() {
        val union = Schema(anyOf = listOf(Schema.INTEGER, Schema.STRING, Schema.BOOLEAN))
        assertAlwaysValid("anyOf", union)
        val kinds = seeds.map { RandomSampleGenerator.generate(union, Random(it)).value!!.jsonType() }.toSet()
        assertEquals(setOf(JsonType.INTEGER, JsonType.STRING, JsonType.BOOLEAN), kinds)
        assertAlwaysValid("types list", Schema(types = listOf(JsonType.INTEGER, JsonType.STRING)))
        assertAlwaysValid("union with a null branch", Schema(anyOf = listOf(Schema.INTEGER, Schema.STRING, Schema.NULL)))
    }

    @Test
    fun `a union survives a branch that has no value`() {
        val union = Schema(anyOf = listOf(Schema.EMPTY, Schema.STRING.copy(format = "binary"), Schema.INTEGER))
        for (seed in seeds) {
            val result = RandomSampleGenerator.generate(union, Random(seed))
            assertEquals(JsonType.INTEGER, result.value?.jsonType())
            assertTrue("failed branches leave no gap: ${result.gaps}", result.gaps.isEmpty())
        }
    }

    @Test
    fun `discriminated unions write one consistent variant`() {
        val cat = obj("kind" to Schema(constValue = Json.str("cat")), "lives" to Schema.INTEGER.copy(minimum = bd("1"), maximum = bd("9")))
        val dog = obj("kind" to Schema(constValue = Json.str("dog")), "good" to Schema.BOOLEAN)
        val pet = Schema(oneOf = listOf(Schema.ref("Cat"), Schema.ref("Dog")), discriminator = "kind")
        val root = obj("pet" to pet).copy(defs = mapOf("Cat" to cat, "Dog" to dog))
        assertAlwaysValid("pet", root)
        val kinds = seeds.map { (((RandomSampleGenerator.generate(root, Random(it)).value as Json.Obj).fields["pet"]) as Json.Obj).fields["kind"] }.toSet()
        assertEquals(setOf<Json?>(Json.str("cat"), Json.str("dog")), kinds)
    }

    @Test
    fun `references and allOf are followed`() {
        val address = obj("street" to Schema.STRING)
        val root = obj("home" to Schema.ref("Address"), "work" to Schema.nullable(Schema.ref("Address")), "both" to Schema(allOf = listOf(Schema.ref("Address"), obj("zip" to Schema.INTEGER))))
            .copy(defs = mapOf("Address" to address))
        assertAlwaysValid("refs", root)
        val both = (RandomSampleGenerator.generate(root, Random(5)).value as Json.Obj).fields["both"] as Json.Obj
        assertEquals(setOf("street", "zip"), both.fields.keys)
    }

    @Test
    fun `a dangling reference gives no value`() {
        assertNoValue("dangling", Schema.ref("Missing"), GapReason.UNRESOLVED_REF)
    }

    @Test
    fun `recursion stops and the document stays valid`() {
        val node = obj(
            "value" to Schema.INTEGER,
            "children" to Schema.arrayOf(Schema.ref("Node")),
            "parent" to Schema.nullable(Schema.ref("Node")),
            required = listOf("value", "children"),
        )
        val root = Schema.ref("Node").copy(defs = mapOf("Node" to node))
        for (seed in seeds) {
            val result = RandomSampleGenerator.generate(root, Random(seed))
            val value = result.value
            assertNotNull(value)
            assertTrue(SchemaChecker.violations(root, value!!).toString(), SchemaChecker.violations(root, value).isEmpty())
            assertTrue(depthOf(value) <= Limits.SAMPLE_MAX_REF_DEPTH * 3 + 3)
        }
    }

    @Test
    fun `a required self reference cannot be finished and does not run away`() {
        val node = obj("next" to Schema.ref("Node"))
        val root = Schema.ref("Node").copy(defs = mapOf("Node" to node))
        val result = RandomSampleGenerator.generate(root, Random(1))
        assertTrue(result.gaps.any { it.reason == GapReason.RECURSION })
    }

    @Test
    fun `a schema that says nothing gives no value`() {
        assertNoValue("empty", Schema.EMPTY, GapReason.NO_TYPE)
        val property = obj("known" to Schema.INTEGER, "anything" to Schema.EMPTY, required = listOf("known"))
        val value = RandomSampleGenerator.generate(property, Random(1)).value as Json.Obj
        assertEquals(setOf("known"), value.fields.keys)
    }

    // --- the generator itself ------------------------------------------------------------------------------------------

    @Test
    fun `the same seed gives the same document and other seeds differ`() {
        val schema = obj("name" to Schema.STRING, "n" to Schema.INTEGER, "tags" to Schema.arrayOf(Schema.STRING), required = listOf("name", "n", "tags"))
        assertEquals(RandomSampleGenerator.generate(schema, Random(42)).value, RandomSampleGenerator.generate(schema, Random(42)).value)
        val documents = (0 until 50).map { JsonText.compact(RandomSampleGenerator.generate(schema, Random(it)).value!!) }.toSet()
        assertTrue(documents.size > 45)
    }

    @Test
    fun `the unseeded generator differs between calls`() {
        val schema = obj("name" to Schema.STRING.copy(minLength = 12, maxLength = 12))
        val a = RandomSampleGenerator.generate(schema, Random.Default).value
        val b = RandomSampleGenerator.generate(schema, Random.Default).value
        assertNotEquals(a, b)
    }

    @Test
    fun `huge schemas stop at the node limit instead of running forever`() {
        val wide = Schema(types = listOf(JsonType.OBJECT), properties = (1..2000).associate { "p$it" to Schema.arrayOf(Schema.arrayOf(Schema.STRING.copy(minLength = 1))) }, required = (1..2000).map { "p$it" })
        val result = RandomSampleGenerator.generate(wide, Random(1))
        assertNotNull(result.value)
    }

    @Test
    fun `rendering a document as JSON text round trips through the writer`() {
        val schema = obj("a" to Schema.STRING, "n" to Schema.NUMBER, "ok" to Schema.BOOLEAN)
        val text = JsonText.pretty(RandomSampleGenerator.generate(schema, Random(1)).value!!)
        assertTrue(text.startsWith("{") && text.trimEnd().endsWith("}"))
        assertFalse(text.contains("E+"))
    }

    @Test
    fun `speed stays within the budget on a large schema`() {
        val big = Schema(
            types = listOf(JsonType.OBJECT),
            properties = (1..500).associate { i ->
                "field$i" to when (i % 5) {
                    0 -> Schema.STRING.copy(format = "email")
                    1 -> Schema.INTEGER.copy(minimum = bd("0"), maximum = bd("100"))
                    2 -> Schema.arrayOf(Schema.STRING).copy(minItems = 2)
                    3 -> Schema.STRING.copy(pattern = "^[A-Z]{3}-\\d{4}$")
                    else -> Schema.nullable(Schema.NUMBER.copy(exclusiveMinimum = bd("0")))
                }
            },
            required = (1..500).map { "field$it" },
        )
        PerfBudget.assertWithin("random-500-fields", Limits.BUDGET_RANDOM_SAMPLE_500_FIELDS_MS, warmups = 3, runs = 7) {
            RandomSampleGenerator.generate(big, Random(7))
        }
    }

    private fun depthOf(v: Json): Int = when (v) {
        is Json.Obj -> 1 + (v.fields.values.maxOfOrNull { depthOf(it) } ?: 0)
        is Json.Arr -> 1 + (v.items.maxOfOrNull { depthOf(it) } ?: 0)
        else -> 0
    }
}
