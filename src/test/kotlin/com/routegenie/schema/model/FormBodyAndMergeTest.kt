package com.routegenie.schema.model

import com.routegenie.Limits
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class FormBodyAndMergeTest {
    @Test
    fun `scalars become name equals value`() {
        assertEquals("a=string&b=0&c=true", FormBody.encode(Json.obj("a" to Json.Str("string"), "b" to Json.num(0), "c" to Json.Bool(true))))
    }

    @Test
    fun `lists repeat the name`() {
        assertEquals("t=a&t=b", FormBody.encode(Json.obj("t" to Json.arr(Json.Str("a"), Json.Str("b")))))
    }

    @Test
    fun `null members are left out`() {
        assertEquals("a=1", FormBody.encode(Json.obj("a" to Json.num(1), "b" to Json.Null)))
    }

    @Test
    fun `names and values are percent encoded with plus for space`() {
        assertEquals("a+b=x%26y+z", FormBody.encode(Json.obj("a b" to Json.Str("x&y z"))))
        assertEquals("n=caf%C3%A9", FormBody.encode(Json.obj("n" to Json.Str("café"))))
    }

    @Test
    fun `nested values cannot be a form`() {
        assertNull(FormBody.encode(Json.obj("a" to Json.obj("b" to Json.num(1)))))
        assertNull(FormBody.encode(Json.obj("a" to Json.arr(Json.arr()))))
        assertNull(FormBody.encode(Json.Str("x")))
    }

    @Test
    fun `an empty object is an empty form`() {
        assertEquals("", FormBody.encode(Json.obj()))
    }

    @Test
    fun `merge unions properties and keeps the stricter number bound`() {
        val a = Schema(types = listOf(JsonType.OBJECT), properties = mapOf("x" to Schema.STRING), required = listOf("x"), maximum = BigDecimal(5))
        val b = Schema(types = listOf(JsonType.OBJECT), properties = mapOf("y" to Schema.STRING), required = listOf("y"), maximum = BigDecimal(3))
        val m = SchemaMerge.allOf(Schema(allOf = listOf(a, b))) { null }
        assertEquals(listOf("x", "y"), m.properties.keys.toList())
        assertEquals(listOf("x", "y"), m.required)
        assertEquals(BigDecimal(3), m.maximum)
    }

    @Test
    fun `merge forbids extras when any part does`() {
        val m = SchemaMerge.allOf(Schema(allOf = listOf(Schema(additional = Additional.Allowed), Schema(additional = Additional.Forbidden)))) { null }
        assertEquals(Additional.Forbidden, m.additional)
    }

    @Test
    fun `merge leaves out a reference it cannot find`() {
        val m = SchemaMerge.allOf(Schema(allOf = listOf(Schema.ref("Gone"), Schema.STRING))) { null }
        assertEquals(listOf(JsonType.STRING), m.types)
    }

    @Test
    fun `merge survives a reference cycle`() {
        val defs = mapOf("A" to Schema(allOf = listOf(Schema.ref("A"))))
        val m = SchemaMerge.allOf(Schema(allOf = listOf(Schema.ref("A")))) { defs[it.substringAfterLast('/')] }
        assertEquals(emptyList<JsonType>(), m.types)
    }

    @Test
    fun `a 500 field model samples within the budget`() {
        val props = LinkedHashMap<String, Schema>()
        for (i in 0 until 500) {
            props["field_$i"] = when (i % 5) {
                0 -> Schema.STRING.copy(minLength = 1)
                1 -> Schema.INTEGER.copy(minimum = BigDecimal(1))
                2 -> Schema.arrayOf(Schema.of(JsonType.STRING, "email"))
                3 -> Schema(enumValues = listOf(Json.Str("a"), Json.Str("b")))
                else -> Schema(types = listOf(JsonType.OBJECT), properties = mapOf("n" to Schema.NUMBER), required = listOf("n"))
            }
        }
        val schema = Schema(types = listOf(JsonType.OBJECT), properties = props, required = props.keys.toList())
        PerfBudget.assertWithin("sample-500-fields", Limits.BUDGET_SAMPLE_500_FIELDS_MS) {
            val value = SampleGenerator.generate(schema).value
            JsonText.pretty(value!!)
        }
    }
}
