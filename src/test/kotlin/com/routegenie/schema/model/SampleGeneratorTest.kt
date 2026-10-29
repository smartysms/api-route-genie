package com.routegenie.schema.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SampleGeneratorTest {
    private fun obj(vararg props: Pair<String, Schema>, required: List<String> = emptyList(), defs: Map<String, Schema> = emptyMap()) =
        Schema(types = listOf(JsonType.OBJECT), properties = linkedMapOf(*props), required = required, defs = defs)

    private fun gen(s: Schema, all: Boolean = true): String? =
        SampleGenerator.generate(s, SampleOptions(includeOptional = all)).value?.let { JsonText.compact(it) }

    private fun result(s: Schema, all: Boolean = true) = SampleGenerator.generate(s, SampleOptions(includeOptional = all))

    private fun dec(v: String) = BigDecimal(v)

    private val baseModel = obj(
        "name" to Schema.STRING.copy(minLength = 1),
        "price" to Schema.NUMBER.copy(minimum = dec("0")),
        "tags" to Schema.arrayOf(Schema.STRING).copy(uniqueItems = true),
        "kind" to Schema(enumValues = listOf(Json.Str("a"), Json.Str("b"))),
        "email" to Schema.of(JsonType.STRING, "email"),
        "n" to Schema.INTEGER,
        required = listOf("name", "price"),
    )

    @Test
    fun `required only gives the required members`() {
        assertEquals("{\"name\":\"string\",\"price\":0}", gen(baseModel, all = false))
    }

    @Test
    fun `all members keep declaration order`() {
        assertEquals(
            "{\"name\":\"string\",\"price\":0,\"tags\":[\"string\"],\"kind\":\"a\",\"email\":\"user@example.com\",\"n\":0}",
            gen(baseModel),
        )
    }

    @Test
    fun `string length bounds are met`() {
        assertEquals("\"stringstri\"", gen(Schema.STRING.copy(minLength = 10)))
        assertEquals("\"str\"", gen(Schema.STRING.copy(maxLength = 3)))
        assertEquals("\"string\"", gen(Schema.STRING.copy(minLength = 2, maxLength = 20)))
    }

    @Test
    fun `known formats give realistic fixed values`() {
        val expected = mapOf(
            "email" to "user@example.com", "uuid" to "123e4567-e89b-12d3-a456-426614174000", "date" to "2024-01-01",
            "date-time" to "2024-01-01T00:00:00Z", "time" to "12:00:00", "uri" to "https://example.com", "hostname" to "example.com",
            "ipv4" to "192.0.2.1", "ipv6" to "2001:db8::1", "duration" to "PT1H",
        )
        for ((format, value) in expected) assertEquals(format, "\"$value\"", gen(Schema.of(JsonType.STRING, format)))
    }

    @Test
    fun `an unknown format falls back to the plain string`() {
        assertEquals("\"string\"", gen(Schema.of(JsonType.STRING, "x-custom")))
    }

    @Test
    fun `binary data has no sample and the member is left out`() {
        val s = obj("name" to Schema.STRING, "blob" to Schema.of(JsonType.STRING, "binary"))
        assertEquals("{\"name\":\"string\"}", gen(s))
        assertTrue(result(s).gaps.any { it.reason == GapReason.BINARY && it.path == "$.blob" })
    }

    @Test
    fun `integer with bounds and multiple`() {
        assertEquals("5", gen(Schema.INTEGER.copy(exclusiveMinimum = dec("0"), maximum = dec("100"), multipleOf = dec("5"))))
    }

    @Test
    fun `zero is preferred when it is valid`() {
        assertEquals("0", gen(Schema.INTEGER))
        assertEquals("0", gen(Schema.NUMBER.copy(minimum = dec("-5"), maximum = dec("5"))))
    }

    @Test
    fun `lower bound is used when zero is out of range`() {
        assertEquals("1", gen(Schema.INTEGER.copy(minimum = dec("1"))))
        assertEquals("18", gen(Schema.INTEGER.copy(minimum = dec("18"))))
        assertEquals("-3", gen(Schema.INTEGER.copy(maximum = dec("-3"))))
    }

    @Test
    fun `exclusive bounds step inside`() {
        assertEquals("1", gen(Schema.INTEGER.copy(exclusiveMinimum = dec("0"))))
        assertEquals("-1", gen(Schema.INTEGER.copy(exclusiveMaximum = dec("0"))))
        assertEquals("1", gen(Schema.NUMBER.copy(exclusiveMinimum = dec("0"))))
    }

    @Test
    fun `a narrow open interval of numbers uses its middle`() {
        assertEquals("0.15", gen(Schema.NUMBER.copy(exclusiveMinimum = dec("0.1"), exclusiveMaximum = dec("0.2"))))
    }

    @Test
    fun `multiple of a fraction is exact`() {
        assertEquals("0.1", gen(Schema.NUMBER.copy(minimum = dec("0.1"), multipleOf = dec("0.05"))))
        assertEquals("0.5", gen(Schema.NUMBER.copy(minimum = dec("0.5"), maximum = dec("1"), multipleOf = dec("0.05"))))
    }

    @Test
    fun `an integer bound that is a fraction rounds inward`() {
        assertEquals("2", gen(Schema.INTEGER.copy(minimum = dec("1.5"))))
    }

    @Test
    fun `unsatisfiable numbers have no sample`() {
        val s = Schema.INTEGER.copy(minimum = dec("5"), maximum = dec("3"))
        assertNull(gen(s))
        assertEquals(GapReason.UNSATISFIABLE, result(s).gaps.single().reason)
    }

    @Test
    fun `booleans and null`() {
        assertEquals("true", gen(Schema.BOOLEAN))
        assertEquals("null", gen(Schema.NULL))
    }

    @Test
    fun `priority is examples then const then default then enum`() {
        val base = Schema(enumValues = listOf(Json.Str("e")), default = Json.Str("d"), constValue = Json.Str("c"), examples = listOf(Json.Str("x")))
        assertEquals("\"x\"", gen(base))
        assertEquals("\"c\"", gen(base.copy(examples = emptyList())))
        assertEquals("\"d\"", gen(base.copy(examples = emptyList(), constValue = null)))
        assertEquals("\"e\"", gen(base.copy(examples = emptyList(), constValue = null, default = null)))
    }

    @Test
    fun `a default of null is respected`() {
        val s = obj("tax" to Schema.nullable(Schema.NUMBER).copy(default = Json.Null))
        assertEquals("{\"tax\":null}", gen(s))
    }

    @Test
    fun `optional fields can be dropped without touching defaults of required ones`() {
        val s = obj("a" to Schema.STRING, "b" to Schema.INTEGER.copy(default = Json.num(7)), required = listOf("a"))
        assertEquals("{\"a\":\"string\"}", gen(s, all = false))
        assertEquals("{\"a\":\"string\",\"b\":7}", gen(s))
    }

    @Test
    fun `any of takes the first non null branch`() {
        assertEquals("0", gen(Schema(anyOf = listOf(Schema.NULL, Schema.NUMBER))))
        assertEquals("\"string\"", gen(Schema.nullable(Schema.STRING)))
    }

    @Test
    fun `a nullable type list uses the non null type`() {
        assertEquals("\"string\"", gen(Schema(types = listOf(JsonType.NULL, JsonType.STRING))))
    }

    @Test
    fun `one of with a discriminator sets the tag from the const`() {
        val cat = obj("kind" to Schema(constValue = Json.Str("cat")), "lives" to Schema.INTEGER, required = listOf("kind"))
        val dog = obj("kind" to Schema(constValue = Json.Str("dog")), required = listOf("kind"))
        val s = Schema(oneOf = listOf(cat, dog), discriminator = "kind")
        assertEquals("{\"kind\":\"cat\",\"lives\":0}", gen(s))
    }

    @Test
    fun `a failing branch falls through to the next`() {
        val s = Schema(anyOf = listOf(Schema.of(JsonType.STRING, "binary"), Schema.INTEGER))
        assertEquals("0", gen(s))
        assertTrue(result(s).gaps.isEmpty())
    }

    @Test
    fun `array has one element`() {
        assertEquals("[\"string\"]", gen(Schema.arrayOf(Schema.STRING)))
    }

    @Test
    fun `array respects min and max items`() {
        assertEquals("[0,0,0]", gen(Schema.arrayOf(Schema.INTEGER).copy(minItems = 3)))
        assertEquals("[]", gen(Schema.arrayOf(Schema.INTEGER).copy(maxItems = 0)))
        assertEquals("[0,0]", gen(Schema.arrayOf(Schema.INTEGER).copy(minItems = 5, maxItems = 2)))
    }

    @Test
    fun `unique items are made distinct`() {
        assertEquals("[0,1,2]", gen(Schema.arrayOf(Schema.INTEGER).copy(minItems = 3, uniqueItems = true)))
        assertEquals("[\"a\",\"b\"]", gen(Schema.arrayOf(Schema(enumValues = listOf(Json.Str("a"), Json.Str("b")))).copy(minItems = 2, uniqueItems = true)))
    }

    @Test
    fun `unique items that cannot be distinct are reported`() {
        val s = Schema.arrayOf(Schema(enumValues = listOf(Json.Str("a")))).copy(minItems = 2, uniqueItems = true)
        assertEquals("[\"a\",\"a\"]", gen(s))
        assertTrue(result(s).gaps.any { it.reason == GapReason.UNSATISFIABLE })
    }

    @Test
    fun `array of unknown items is empty and not invented`() {
        assertEquals("[]", gen(Schema.arrayOf(null)))
        assertEquals("[]", gen(Schema.arrayOf(Schema.EMPTY)))
    }

    @Test
    fun `required items of unknown type cannot be sampled`() {
        assertNull(gen(Schema.arrayOf(null).copy(minItems = 1)))
    }

    @Test
    fun `tuple gives one value per position`() {
        val s = Schema(types = listOf(JsonType.ARRAY), prefixItems = listOf(Schema.INTEGER, Schema.STRING), minItems = 2, maxItems = 2)
        assertEquals("[0,\"string\"]", gen(s))
    }

    @Test
    fun `a map with typed values has one entry`() {
        assertEquals("{\"key\":0}", gen(Schema(types = listOf(JsonType.OBJECT), additional = Additional.OfSchema(Schema.INTEGER))))
    }

    @Test
    fun `a plain map with any values has no entry`() {
        assertEquals("{}", gen(Schema(types = listOf(JsonType.OBJECT), additional = Additional.Allowed)))
    }

    @Test
    fun `nested objects and arrays of objects`() {
        val address = obj("city" to Schema.STRING, required = listOf("city"))
        val s = obj("home" to address, "others" to Schema.arrayOf(address), required = listOf("home"))
        assertEquals("{\"home\":{\"city\":\"string\"},\"others\":[{\"city\":\"string\"}]}", gen(s))
        assertEquals("{\"home\":{\"city\":\"string\"}}", gen(s, all = false))
    }

    @Test
    fun `read only members are skipped and write only kept`() {
        val s = obj("id" to Schema.INTEGER.copy(readOnly = true), "password" to Schema.STRING.copy(writeOnly = true))
        assertEquals("{\"password\":\"string\"}", gen(s))
    }

    @Test
    fun `unconstrained members are left out and recorded`() {
        val s = obj("a" to Schema.STRING, "any" to Schema.EMPTY)
        assertEquals("{\"a\":\"string\"}", gen(s))
        assertEquals(listOf(SampleGap("$.any", GapReason.NO_TYPE)), result(s).gaps)
    }

    @Test
    fun `an unconstrained root has no sample`() {
        assertNull(gen(Schema.EMPTY))
    }

    @Test
    fun `type is inferred from the keywords when it is not written`() {
        assertEquals("{\"a\":0}", gen(Schema(properties = mapOf("a" to Schema.INTEGER))))
        assertEquals("[0]", gen(Schema(items = Schema.INTEGER)))
        assertEquals("\"string\"", gen(Schema(minLength = 1)))
        assertEquals("1", gen(Schema(minimum = dec("1"))))
    }

    @Test
    fun `a recursive model with a default stops at the default`() {
        val node = obj("value" to Schema.INTEGER, "children" to Schema.arrayOf(Schema.ref("Node")).copy(default = Json.Arr(emptyList())), required = listOf("value"))
        val root = Schema(ref = "#/\$defs/Node", defs = mapOf("Node" to node))
        assertEquals("{\"value\":0,\"children\":[]}", gen(root))
    }

    @Test
    fun `a recursive optional member is cut and nothing loops`() {
        val node = obj("value" to Schema.INTEGER, "children" to Schema.arrayOf(Schema.ref("Node")), required = listOf("value"))
        val root = Schema(ref = "#/\$defs/Node", defs = mapOf("Node" to node))
        val text = gen(root)!!
        assertTrue(text, text.startsWith("{\"value\":0,\"children\":[{\"value\":0,\"children\":["))
        assertTrue(result(root).gaps.any { it.reason == GapReason.RECURSION })
    }

    @Test
    fun `a nullable recursive member ends in null`() {
        val node = obj("next" to Schema.nullable(Schema.ref("Node")), required = listOf("next"))
        val root = Schema(ref = "#/\$defs/Node", defs = mapOf("Node" to node))
        assertEquals("{\"next\":{\"next\":{\"next\":null}}}", gen(root))
    }

    @Test
    fun `a required infinite recursion terminates`() {
        val node = obj("next" to Schema.ref("Node"), required = listOf("next"))
        val root = Schema(ref = "#/\$defs/Node", defs = mapOf("Node" to node))
        val r = result(root)
        assertTrue(r.value != null)
        assertTrue(r.gaps.any { it.reason == GapReason.RECURSION })
    }

    @Test
    fun `a reference to nothing is a gap`() {
        val s = obj("a" to Schema.STRING, "b" to Schema.ref("Missing"))
        assertEquals("{\"a\":\"string\"}", gen(s))
        assertEquals(GapReason.UNRESOLVED_REF, result(s).gaps.single().reason)
    }

    @Test
    fun `all of merges properties and required`() {
        val a = obj("x" to Schema.STRING, required = listOf("x"))
        val b = obj("y" to Schema.INTEGER, required = listOf("y"))
        assertEquals("{\"x\":\"string\",\"y\":0}", gen(Schema(allOf = listOf(a, b))))
    }

    @Test
    fun `all of follows references`() {
        val base = obj("id" to Schema.INTEGER, required = listOf("id"))
        val s = Schema(allOf = listOf(Schema.ref("Base"), obj("name" to Schema.STRING)), defs = mapOf("Base" to base))
        assertEquals("{\"id\":0,\"name\":\"string\"}", gen(s))
    }

    @Test
    fun `all of keeps the stricter bound`() {
        val s = Schema(allOf = listOf(Schema.INTEGER.copy(minimum = dec("1")), Schema.INTEGER.copy(minimum = dec("10"), maximum = dec("20"))))
        assertEquals("10", gen(s))
    }

    @Test
    fun `an object whose members all fail still counts as an object`() {
        assertEquals("{}", gen(obj("any" to Schema.EMPTY)))
    }

    @Test
    fun `sampling is deterministic`() {
        val first = gen(baseModel)
        repeat(100) { assertEquals(first, gen(baseModel)) }
    }

    @Test
    fun `enum members of any type`() {
        assertEquals("3", gen(Schema(enumValues = listOf(Json.num(3), Json.num(4)))))
        assertEquals("[1]", gen(Schema(enumValues = listOf(Json.arr(Json.num(1))))))
    }

    @Test
    fun `an empty enum cannot be sampled`() {
        assertNull(gen(Schema(enumValues = emptyList())))
    }

    @Test
    fun `required only keeps nested required members`() {
        val inner = obj("a" to Schema.STRING, "b" to Schema.STRING, required = listOf("a"))
        val s = obj("in" to inner, required = listOf("in"))
        assertEquals("{\"in\":{\"a\":\"string\"}}", gen(s, all = false))
    }

    @Test
    fun `required only of an all optional model is an empty object`() {
        assertEquals("{}", gen(obj("a" to Schema.STRING), all = false))
    }

    @Test
    fun `pretty rendering of a nested sample is stable`() {
        val s = obj("a" to Schema.STRING, "list" to Schema.arrayOf(obj("n" to Schema.INTEGER, required = listOf("n"))))
        val text = JsonText.pretty(SampleGenerator.generate(s).value!!)
        assertEquals("{\n  \"a\": \"string\",\n  \"list\": [\n    {\n      \"n\": 0\n    }\n  ]\n}", text)
    }

    @Test
    fun `a pattern is satisfied`() {
        assertEquals("\"AAA-0000\"", gen(Schema.STRING.copy(pattern = "^[A-Z]{3}-\\d{4}$")))
    }

    @Test
    fun `a pattern outside the subset leaves the member out`() {
        val s = obj("code" to Schema.STRING.copy(pattern = "^(?=.*\\d).+$"), "n" to Schema.INTEGER)
        assertEquals("{\"n\":0}", gen(s))
        assertEquals(GapReason.PATTERN, result(s).gaps.single().reason)
    }

    @Test
    fun `a pattern that clashes with the length has no sample`() {
        assertNull(gen(Schema.STRING.copy(pattern = "^ab$", minLength = 5)))
    }
}
