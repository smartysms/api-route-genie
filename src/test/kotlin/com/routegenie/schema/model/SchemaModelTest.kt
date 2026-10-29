package com.routegenie.schema.model

import com.routegenie.testing.LeakProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference
import java.math.BigDecimal

class SchemaModelTest {
    private fun obj(vararg props: Pair<String, Schema>, required: List<String> = emptyList()) =
        Schema(types = listOf(JsonType.OBJECT), properties = linkedMapOf(*props), required = required)

    private fun dec(v: String) = BigDecimal(v)

    @Test
    fun `root text declares the 2020-12 dialect first`() {
        val text = Schema.STRING.toJsonSchemaText()
        assertTrue(text, text.startsWith("{\n  \"\$schema\": \"https://json-schema.org/draft/2020-12/schema\""))
    }

    @Test
    fun `a single type is a string and several types an array`() {
        assertEquals("{\"type\":\"string\"}", Schema(types = listOf(JsonType.STRING)).let { SchemaWriter.toJson(it) }.let { JsonText.compact(it) })
        assertEquals("{\"type\":[\"string\",\"null\"]}", JsonText.compact(SchemaWriter.toJson(Schema(types = listOf(JsonType.STRING, JsonType.NULL)))))
    }

    @Test
    fun `nullable adds null to the type list`() {
        assertEquals("{\"type\":[\"integer\",\"null\"]}", JsonText.compact(SchemaWriter.toJson(Schema.INTEGER.copy(nullable = true))))
    }

    @Test
    fun `nullable without a type adds nothing because every value is already allowed`() {
        assertEquals("{}", JsonText.compact(SchemaWriter.toJson(Schema(nullable = true))))
    }

    @Test
    fun `properties keep declaration order and required follows`() {
        val s = obj("zeta" to Schema.STRING, "alpha" to Schema.INTEGER, required = listOf("zeta"))
        assertEquals(
            "{\"type\":\"object\",\"properties\":{\"zeta\":{\"type\":\"string\"},\"alpha\":{\"type\":\"integer\"}},\"required\":[\"zeta\"]}",
            JsonText.compact(SchemaWriter.toJson(s)),
        )
    }

    @Test
    fun `enum const default and examples are written`() {
        val s = Schema(enumValues = listOf(Json.Str("a"), Json.Str("b")), constValue = Json.Str("a"), default = Json.Null, examples = listOf(Json.num(1)))
        assertEquals("{\"enum\":[\"a\",\"b\"],\"const\":\"a\",\"default\":null,\"examples\":[1]}", JsonText.compact(SchemaWriter.toJson(s)))
    }

    @Test
    fun `numeric constraints are written as plain numbers`() {
        val s = Schema.NUMBER.copy(minimum = dec("0"), exclusiveMaximum = dec("1E+2"), multipleOf = dec("0.05"))
        assertEquals("{\"type\":\"number\",\"minimum\":0,\"exclusiveMaximum\":100,\"multipleOf\":0.05}", JsonText.compact(SchemaWriter.toJson(s)))
    }

    @Test
    fun `length item and property bounds are written`() {
        val s = Schema(minLength = 1, maxLength = 5, pattern = "^a", minItems = 2, maxItems = 3, uniqueItems = true, minProperties = 1, maxProperties = 4)
        assertEquals(
            "{\"pattern\":\"^a\",\"minLength\":1,\"maxLength\":5,\"minItems\":2,\"maxItems\":3,\"uniqueItems\":true,\"minProperties\":1,\"maxProperties\":4}".let {
                // Keyword order is fixed by the writer; compare as sets of members to stay independent of it.
                it.removeSurrounding("{", "}").split(",").toSet()
            },
            JsonText.compact(SchemaWriter.toJson(s)).removeSurrounding("{", "}").split(",").toSet(),
        )
    }

    @Test
    fun `defs and ref are written with the defs last`() {
        val s = Schema(ref = "#/\$defs/Node", defs = mapOf("Node" to obj("v" to Schema.INTEGER)))
        val text = JsonText.compact(SchemaWriter.toJson(s))
        assertTrue(text, text.startsWith("{\"\$ref\":\"#/\$defs/Node\""))
        assertTrue(text, text.endsWith("\"\$defs\":{\"Node\":{\"type\":\"object\",\"properties\":{\"v\":{\"type\":\"integer\"}}}}}"))
    }

    @Test
    fun `additional properties in its three forms`() {
        assertEquals("{\"additionalProperties\":false}", JsonText.compact(SchemaWriter.toJson(Schema(additional = Additional.Forbidden))))
        assertEquals("{\"additionalProperties\":true}", JsonText.compact(SchemaWriter.toJson(Schema(additional = Additional.Allowed))))
        assertEquals("{\"additionalProperties\":{\"type\":\"integer\"}}", JsonText.compact(SchemaWriter.toJson(Schema(additional = Additional.OfSchema(Schema.INTEGER)))))
    }

    @Test
    fun `optional is anyOf with null and is not nested twice`() {
        val once = Schema.nullable(Schema.STRING)
        assertEquals("{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}", JsonText.compact(SchemaWriter.toJson(once)))
        assertEquals(once, Schema.nullable(once))
    }

    @Test
    fun `discriminator is written as an object`() {
        val s = Schema(oneOf = listOf(Schema.ref("Cat"), Schema.ref("Dog")), discriminator = "kind")
        assertEquals("{\"oneOf\":[{\"\$ref\":\"#/\$defs/Cat\"},{\"\$ref\":\"#/\$defs/Dog\"}],\"discriminator\":{\"propertyName\":\"kind\"}}", JsonText.compact(SchemaWriter.toJson(s)))
    }

    @Test
    fun `flags and extensions are written`() {
        val s = Schema(readOnly = true, writeOnly = true, deprecated = true, extensions = mapOf("x-note" to Json.Str("n")))
        assertEquals("{\"readOnly\":true,\"writeOnly\":true,\"deprecated\":true,\"x-note\":\"n\"}", JsonText.compact(SchemaWriter.toJson(s)))
    }

    @Test
    fun `pretty text uses two space indent and empty containers stay on one line`() {
        val json = Json.obj("a" to Json.arr(Json.num(1), Json.obj()), "b" to Json.Arr(emptyList()), "c" to Json.Str("x"))
        assertEquals("{\n  \"a\": [\n    1,\n    {}\n  ],\n  \"b\": [],\n  \"c\": \"x\"\n}", JsonText.pretty(json))
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\n\\t\\u0001\"", JsonText.quoted("a\"b\\c\n\t\u0001"))
        assertEquals("\"café\"", JsonText.quoted("café"))
    }

    @Test
    fun `numbers never use an exponent`() {
        assertEquals("100", JsonText.number(dec("1E+2")))
        assertEquals("0.5", JsonText.number(dec("0.5")))
        assertEquals("10.0", JsonText.number(dec("10.0")))
    }

    @Test
    fun `numbers compare by value`() {
        assertEquals(Json.Num(dec("1")), Json.Num(dec("1.0")))
        assertEquals(Json.Num(dec("1")).hashCode(), Json.Num(dec("1.00")).hashCode())
        assertFalse(Json.Num(dec("1")) == Json.Num(dec("2")))
    }

    @Test
    fun `json type of a number follows its value`() {
        assertEquals(JsonType.INTEGER, Json.num(3).jsonType())
        assertEquals(JsonType.NUMBER, Json.Num(dec("0.5")).jsonType())
        assertEquals(JsonType.NULL, Json.Null.jsonType())
    }

    @Test
    fun `unconstrained schema is recognised`() {
        assertTrue(Schema.EMPTY.isUnconstrained)
        assertFalse(Schema.STRING.isUnconstrained)
    }

    @Test
    fun `body encodings carry their exact content type`() {
        assertEquals("application/json", BodyEncoding.JSON.contentType)
        assertEquals("application/x-www-form-urlencoded", BodyEncoding.FORM_URLENCODED.contentType)
    }

    @Test
    fun `schemas are collectable once released`() {
        var schema: Schema? = obj("a" to Schema.STRING)
        val ref = WeakReference(schema)
        assertNotNull(ref.get())
        schema = null
        LeakProbe.assertCollected("schema", ref)
    }
}
