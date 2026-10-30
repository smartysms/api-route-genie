package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonText
import com.routegenie.schema.model.RandomSampleGenerator
import com.routegenie.testing.SchemaChecker
import java.math.BigDecimal
import kotlin.random.Random

/**
 * Declarations whose random bodies were refused by the real Python libraries in an adversarial run (Pydantic 2, DRF and Django
 * forms checked against the installed packages). Each one is extracted through the real path, and 150 random bodies must hold
 * what the declaration demands.
 */
class RandomBodyRegressionTest : SchemaTestCase() {
    private val seeds = 0 until 150

    private fun pydantic(code: String, file: String = "main.py"): com.routegenie.model.Endpoint {
        add(file, code)
        return fastApi("${file.removeSuffix(".py")}.create", file)
    }

    private fun drf(serializers: String, target: String = "S"): com.routegenie.model.Endpoint {
        add("serializers.py", serializers)
        add(
            "views.py",
            """
            from rest_framework.views import APIView
            from serializers import $target

            class V(APIView):
                serializer_class = $target

                def post(self, request):
                    s = $target(data=request.data)
                    s.is_valid(raise_exception=True)
            """,
        )
        return endpoint(Framework.DRF, HttpMethod.POST, "/api/x/", "views.V", "views.py", "V")
    }

    private fun form(forms: String): com.routegenie.model.Endpoint {
        add("forms.py", forms)
        add("formviews.py", "from django.views.generic import FormView\nfrom forms import F\n\nclass V(FormView):\n    form_class = F\n")
        return endpoint(Framework.DJANGO, HttpMethod.POST, "/form/", "formviews.V", "formviews.py", "V")
    }

    /** The generated documents of [e], each already checked against the extracted schema. */
    private fun documents(e: com.routegenie.model.Endpoint): List<Json.Obj> {
        val schema = body(e)!!.schema
        return seeds.map { seed ->
            val value = RandomSampleGenerator.generate(schema, Random(seed)).value
            assertNotNull("seed $seed gave no document", value)
            val violations = SchemaChecker.violations(schema, value!!)
            assertTrue("seed $seed: $violations in ${JsonText.compact(value)}", violations.isEmpty())
            value as Json.Obj
        }
    }

    private fun decimalOf(v: Json?): BigDecimal = when (v) {
        is Json.Num -> v.value
        is Json.Str -> BigDecimal(v.value)
        else -> throw AssertionError("not a decimal: $v")
    }

    fun testADecimalIsAlwaysWrittenAsADecimal() {
        val e = pydantic(
            """
            from decimal import Decimal
            from pydantic import BaseModel, Field

            class Item(BaseModel):
                plain: Decimal
                price: Decimal = Field(max_digits=5, decimal_places=2)
                bounded: Decimal = Field(ge=0, le=100)

            def create(item: Item):
                pass
            """,
        )
        val docs = documents(e)
        val kinds = docs.flatMap { it.fields.values }.map { it is Json.Str }.toSet()
        assertEquals("both the number and the text form appear", setOf(true, false), kinds)
        for (doc in docs) {
            decimalOf(doc.fields["plain"])
            val price = decimalOf(doc.fields["price"])
            assertTrue("$price has more than 2 decimals or 3 whole digits", price.stripTrailingZeros().scale() <= 2 && price.abs() < BigDecimal(1000))
            val bounded = decimalOf(doc.fields["bounded"])
            assertTrue("$bounded outside 0..100", bounded >= BigDecimal.ZERO && bounded <= BigDecimal(100))
        }
    }

    fun testAMapWithTypedKeysGetsKeysOfThatType() {
        val e = pydantic(
            """
            from enum import Enum
            from uuid import UUID
            from pydantic import BaseModel

            class Color(str, Enum):
                RED = "red"
                GREEN = "green"

            class Item(BaseModel):
                by_number: dict[int, str]
                by_color: dict[Color, int]
                by_id: dict[UUID, int]
                by_name: dict[str, int]

            def create(item: Item):
                pass
            """,
        )
        val item = body(e)!!.schema.defs.getValue("Item")
        assertNull(item.properties.getValue("by_name").propertyNames)
        assertNotNull(item.properties.getValue("by_number").propertyNames)
        for (doc in documents(e)) {
            val numbers = (doc.fields["by_number"] as Json.Obj).fields.keys
            assertTrue("keys $numbers", numbers.all { it.toLongOrNull() != null })
            assertTrue((doc.fields["by_color"] as Json.Obj).fields.keys.all { it == "red" || it == "green" })
            assertTrue((doc.fields["by_id"] as Json.Obj).fields.keys.all { Regex("[0-9a-f-]{36}").matches(it) })
        }
        // The plain sample (the fixed one) gets a key of the right type as well.
        assertEquals("{\"by_number\":{\"0\":\"string\"},\"by_color\":{\"red\":0},\"by_id\":{\"123e4567-e89b-12d3-a456-426614174000\":0},\"by_name\":{\"key\":0}}", compact(template(e)))
    }

    fun testConstantsInLimitsAreReadAndExpressionsAreNotDropped() {
        val e = pydantic(
            """
            from pydantic import BaseModel, Field

            LOW = 10
            HIGH = 20
            NAME = r"^[a-c]{3}$"

            class Item(BaseModel):
                a: int = Field(ge=LOW, le=HIGH)
                b: int = Field(ge=-HIGH, le=-LOW)
                c: str = Field(pattern=NAME)
                d: int = Field(1, ge=int("5"))
                e: str

            def create(item: Item):
                pass
            """,
        )
        val item = body(e)!!.schema.defs.getValue("Item")
        assertEquals(BigDecimal(10), item.properties.getValue("a").minimum)
        assertEquals(BigDecimal(20), item.properties.getValue("a").maximum)
        assertEquals(BigDecimal(-20), item.properties.getValue("b").minimum)
        assertEquals("^[a-c]{3}$", item.properties.getValue("c").pattern)
        // A limit written as an expression cannot be read: the field is unknown (and so left out of a sample), not unlimited.
        assertTrue(item.properties.getValue("d").types.isEmpty())
        for (doc in documents(e)) assertFalse("d must not be invented", "d" in doc.fields)
    }

    fun testTypesThatOnlyAcceptCertainValues() {
        val e = pydantic(
            """
            from ipaddress import IPv4Network, IPv4Interface
            from pydantic import BaseModel, UUID1, UUID3, UUID4, UUID5, PostgresDsn, RedisDsn, MongoDsn, AmqpDsn, FileUrl, FtpUrl, WebsocketUrl

            class Item(BaseModel):
                u1: UUID1
                u3: UUID3
                u4: UUID4
                u5: UUID5
                pg: PostgresDsn
                redis: RedisDsn
                mongo: MongoDsn
                amqp: AmqpDsn
                file: FileUrl
                ftp: FtpUrl
                ws: WebsocketUrl
                net: IPv4Network
                iface: IPv4Interface

            def create(item: Item):
                pass
            """,
        )
        for (doc in documents(e)) {
            fun text(name: String) = (doc.fields.getValue(name) as Json.Str).value
            assertEquals('1', text("u1")[14])
            assertEquals('3', text("u3")[14])
            assertEquals('4', text("u4")[14])
            assertEquals('5', text("u5")[14])
            assertTrue(text("pg").startsWith("postgres"))
            assertTrue(text("redis").startsWith("redis"))
            assertTrue(text("mongo").startsWith("mongodb://"))
            assertTrue(text("amqp").startsWith("amqp"))
            assertTrue(text("file").startsWith("file://"))
            assertTrue(text("ftp").startsWith("ftp://"))
            assertTrue(text("ws").startsWith("ws"))
            assertTrue(text("net").contains(".0/"))
            assertTrue(text("iface").endsWith("/24"))
        }
    }

    fun testADjangoFormCheckboxThatIsRequiredIsAlwaysTicked() {
        val e = form(
            """
            from django import forms

            class F(forms.Form):
                agree = forms.BooleanField()
                news = forms.BooleanField(required=False)
            """,
        )
        val bodies = seeds.map { seed -> runReadAction { service().randomTemplateNow(e, Random(seed))?.text } }
        assertTrue(bodies.all { it != null && it.startsWith("agree=true") })
        assertTrue("the optional box varies", bodies.any { it!!.contains("news=true") } && bodies.any { it!!.contains("news=false") })
    }

    fun testAnIpFieldKeepsToItsProtocol() {
        val drfEndpoint = drf(
            """
            from rest_framework import serializers

            class S(serializers.Serializer):
                v4 = serializers.IPAddressField(protocol="IPv4")
                v6 = serializers.IPAddressField(protocol="IPv6")
                both = serializers.IPAddressField()
            """,
        )
        for (doc in documents(drfEndpoint)) {
            assertTrue((doc.fields["v4"] as Json.Str).value.matches(Regex("[0-9]+(\\.[0-9]+){3}")))
            assertTrue((doc.fields["v6"] as Json.Str).value.contains(':'))
        }
    }

    fun testADecimalFieldKeepsToItsDigits() {
        val e = drf(
            """
            from rest_framework import serializers

            class S(serializers.Serializer):
                a = serializers.DecimalField(max_digits=3, decimal_places=2)
                b = serializers.DecimalField(max_digits=4, decimal_places=1, max_value=50)
                c = serializers.DecimalField(max_digits=3, decimal_places=0)
            """,
        )
        for (doc in documents(e)) {
            val a = decimalOf(doc.fields["a"])
            assertTrue("a=$a", a.abs() < BigDecimal(10) && a.stripTrailingZeros().scale() <= 2)
            val b = decimalOf(doc.fields["b"])
            assertTrue("b=$b", b.abs() < BigDecimal(1000) && b <= BigDecimal(50) && b.stripTrailingZeros().scale() <= 1)
            val c = decimalOf(doc.fields["c"])
            assertTrue("c=$c", c.abs() < BigDecimal(1000) && c.stripTrailingZeros().scale() <= 0)
        }
        val form = form(
            """
            from django import forms

            class F(forms.Form):
                d = forms.DecimalField(max_digits=3, decimal_places=0)
            """,
        )
        assertTrue(body(form)!!.schema.properties.getValue("d").multipleOf == BigDecimal.ONE)
    }

    fun testFieldValidatorsAndUnreadableDateFormats() {
        val e = drf(
            """
            from django.core.validators import MinValueValidator, RegexValidator
            from rest_framework import serializers

            class S(serializers.Serializer):
                a = serializers.IntegerField(validators=[MinValueValidator(5000)])
                code = serializers.CharField(validators=[RegexValidator(r"^[0-9]{4}$")])
                born = serializers.DateField(input_formats=["%d/%m/%Y"])
                seen = serializers.DateField(input_formats=["iso-8601"])
            """,
        )
        val s = body(e)!!.schema.defs.getValue("S")
        assertEquals(BigDecimal(5000), s.properties.getValue("a").minimum)
        assertEquals("^[0-9]{4}\$", s.properties.getValue("code").pattern)
        assertFalse("a date in another layout is unknown, not an ISO date", "born" in s.properties)
        assertTrue("seen" in s.properties)
        for (doc in documents(e)) {
            assertTrue(decimalOf(doc.fields["a"]) >= BigDecimal(5000))
            assertTrue((doc.fields["code"] as Json.Str).value.matches(Regex("[0-9]{4}")))
        }
    }

    fun testSchemaOfAModelWithUnusualLimitsStaysAnObject() {
        val e = pydantic(
            """
            from pydantic import BaseModel, Field

            class Item(BaseModel):
                tiny: float = Field(gt=0, lt=1e-7)
                label: str = Field(max_length=65535)

            def create(item: Item):
                pass
            """,
        )
        for (doc in documents(e)) {
            assertTrue((doc.fields["label"] as Json.Str).value.length <= 12)
            assertTrue(decimalOf(doc.fields["tiny"]) > BigDecimal.ZERO)
        }
    }
}
