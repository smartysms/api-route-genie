package com.routegenie.schema.psi

import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.Additional
import com.routegenie.schema.model.Confidence

/**
 * Items that research 04 marked UNVERIFIED for the frameworks covered here, each pinned by a fixture: what the extraction
 * does today is asserted, so a later change of behavior is a deliberate one.
 */
class Research04FixturesTest : SchemaTestCase() {
    private fun route(code: String, handler: String = "main.create", path: String = "/items") =
        fastApi(handler, "main.py", path).also { add("main.py", code) }

    private fun drf(handler: String, file: String, method: HttpMethod = HttpMethod.POST, name: String = handler.substringAfterLast('.')) =
        endpoint(Framework.DRF, method, "/api/", handler, file, name)

    fun testFormAndJsonBodyCannotBeMixed() {
        val e = route(
            """
            from fastapi import Body, Form
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def create(item: Item, token: str = Form(...)):
                pass
            """,
        )
        assertNull(template(e))
        assertTrue(extraction(e).absentReason!!.contains("AMBIGUOUS"))
    }

    fun testUnresolvableDependsInsideADependencyIsSkipped() {
        val e = route(
            """
            from fastapi import Depends
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def first(second=Depends(lambda: None), item: Item = None):
                pass

            def a(b=Depends(lambda: None)):
                pass

            def create(x=Depends(first), y=Depends(a)):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
    }

    fun testSelfReferencingDependencyDoesNotLoop() {
        val e = route(
            """
            from fastapi import Depends

            def dep(again=Depends(dep)):
                pass

            def create(x=Depends(dep)):
                pass
            """,
        )
        assertNull(template(e))
    }

    fun testLegacyConfigExtraForbid() {
        val e = route(
            """
            from pydantic import BaseModel, Extra

            class Item(BaseModel):
                name: str

                class Config:
                    extra = Extra.forbid

            def create(item: Item):
                pass
            """,
        )
        assertEquals(Additional.Forbidden, body(e)!!.schema.defs.getValue("Item").additional)
    }

    fun testLookaroundPatternHasNoSampleAndThrowsNothing() {
        val e = route(
            """
            from pydantic import BaseModel, Field

            class Item(BaseModel):
                name: str
                password: str = Field(pattern=r"^(?=.*[0-9]).{8,}$")

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
    }

    fun testDiscriminatedUnionTakesTheFirstVariantWithItsTag() {
        val e = route(
            """
            from typing import Literal, Union
            from pydantic import BaseModel, Field

            class Cat(BaseModel):
                pet_type: Literal["cat"]
                meows: int

            class Dog(BaseModel):
                pet_type: Literal["dog"]
                barks: float

            class Owner(BaseModel):
                pet: Union[Cat, Dog] = Field(discriminator="pet_type")

            def create(owner: Owner):
                pass
            """,
        )
        assertEquals("{\"pet\":{\"pet_type\":\"cat\",\"meows\":0}}", compact(template(e)))
        val pet = body(e)!!.schema.defs.getValue("Owner").properties.getValue("pet")
        assertEquals("pet_type", pet.discriminator)
        assertEquals(2, pet.oneOf.size)
    }

    fun testUnresolvedConstructsNeverThrow() {
        val e = route(
            """
            from pydantic import BaseModel, Field, create_model

            opts = {}

            def make_base():
                return BaseModel

            class Weird(make_base()):
                x: int

            class Item(BaseModel):
                name: str
                f: int = Field(**opts)
                nested: Weird

            def create(item: Item):
                pass
            """,
        )
        // `Weird` is not known to be a model and `Field(**opts)` hides what `f` accepts: both are left out, nothing is invented.
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
        assertEquals(Confidence.MEDIUM, body(e)!!.confidence)
    }

    fun testDataclassInitVarsAndTotalFalseTypedDicts() {
        val e = route(
            """
            from dataclasses import InitVar, dataclass
            from typing import Annotated, TypedDict
            from fastapi import Body

            @dataclass
            class P:
                x: int
                scale: InitVar[int] = 1

            class T(TypedDict, total=False):
                a: int
                b: str

            def create(p: P, t: Annotated[T, Body()]):
                pass
            """,
        )
        assertEquals("{\"p\":{\"x\":0},\"t\":{\"a\":0,\"b\":\"string\"}}", compact(template(e)))
        val schema = body(e)!!.schema
        assertEquals(emptyList<String>(), schema.defs.getValue("T").required)
        assertEquals(listOf("x"), schema.defs.getValue("P").required)
    }

    fun testNamedTupleFieldIsLeftOut() {
        val e = route(
            """
            from typing import NamedTuple
            from pydantic import BaseModel

            class Point(NamedTuple):
                x: int
                y: int

            class Item(BaseModel):
                name: str
                where: Point

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
    }

    fun testExtendSchemaViewOnTheClass() {
        add("serializers.py", "from rest_framework import serializers\n\nclass A(serializers.Serializer):\n    a = serializers.IntegerField()\n\nclass B(serializers.Serializer):\n    b = serializers.IntegerField()\n")
        add(
            "views.py",
            """
            from drf_spectacular.utils import extend_schema, extend_schema_view
            from rest_framework import viewsets
            from serializers import A, B

            @extend_schema_view(
                create=extend_schema(request=A),
                update=extend_schema(request=B),
            )
            class V(viewsets.ModelViewSet):
                pass
            """,
        )
        assertEquals("{\"a\":0}", compact(template(drf("views.V.create", "views.py", HttpMethod.POST, "V.create"))))
        assertEquals("{\"b\":0}", compact(template(drf("views.V.update", "views.py", HttpMethod.PUT, "V.update"))))
    }

    fun testDepthMakesRelationsReadOnly() {
        add(
            "models.py",
            """
            from django.db import models

            class Author(models.Model):
                name = models.CharField(max_length=5)

            class Book(models.Model):
                title = models.CharField(max_length=9)
                author = models.ForeignKey(Author, on_delete=models.CASCADE)
            """,
        )
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Book

            class Flat(serializers.ModelSerializer):
                class Meta:
                    model = Book
                    fields = ["title", "author"]

            class Deep(serializers.ModelSerializer):
                class Meta:
                    model = Book
                    fields = ["title", "author"]
                    depth = 1
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import Flat, Deep\n\nclass F(generics.CreateAPIView):\n    serializer_class = Flat\n\nclass D(generics.CreateAPIView):\n    serializer_class = Deep\n")
        assertEquals("{\"title\":\"string\",\"author\":0}", compact(template(drf("views.F", "views.py"))))
        assertEquals("{\"title\":\"string\"}", compact(template(drf("views.D", "views.py"))))
    }

    fun testHyperlinkedModelSerializerRelationsAreUrls() {
        add(
            "models.py",
            """
            from django.db import models

            class Author(models.Model):
                name = models.CharField(max_length=5)

            class Book(models.Model):
                title = models.CharField(max_length=9)
                author = models.ForeignKey(Author, on_delete=models.CASCADE)
            """,
        )
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Book

            class S(serializers.HyperlinkedModelSerializer):
                class Meta:
                    model = Book
                    fields = ["url", "title", "author"]
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        assertEquals("{\"title\":\"string\",\"author\":\"https://example.com\"}", compact(template(drf("views.C", "views.py"))))
    }

    fun testDefaultMaxLengthsOfModelFields() {
        add(
            "models.py",
            """
            from django.db import models

            class Contact(models.Model):
                mail = models.EmailField()
                site = models.URLField()
                tag = models.SlugField()
                note = models.TextField()
                flag = models.BooleanField()
            """,
        )
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Contact

            class S(serializers.ModelSerializer):
                class Meta:
                    model = Contact
                    fields = "__all__"
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        val schema = body(drf("views.C", "views.py"))!!.schema.defs.getValue("S")
        assertEquals(254, schema.properties.getValue("mail").maxLength)
        assertEquals(200, schema.properties.getValue("site").maxLength)
        assertEquals(50, schema.properties.getValue("tag").maxLength)
        assertNull(schema.properties.getValue("note").maxLength)
        // A BooleanField without a default is required: DRF only relaxes it for blank, null or a default (UNVERIFIED for old Django).
        assertTrue("flag" in schema.required)
    }

    fun testValidatorsOfModelFieldsBecomeBounds() {
        add(
            "models.py",
            """
            from django.core.validators import MaxValueValidator, MinValueValidator
            from django.db import models

            class Rating(models.Model):
                stars = models.IntegerField(validators=[MinValueValidator(1), MaxValueValidator(5)])
                count = models.PositiveIntegerField()
            """,
        )
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Rating

            class S(serializers.ModelSerializer):
                class Meta:
                    model = Rating
                    fields = ["stars", "count"]
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        val schema = body(drf("views.C", "views.py"))!!.schema.defs.getValue("S")
        assertEquals(java.math.BigDecimal(1), schema.properties.getValue("stars").minimum)
        assertEquals(java.math.BigDecimal(5), schema.properties.getValue("stars").maximum)
        assertEquals(java.math.BigDecimal.ZERO, schema.properties.getValue("count").minimum)
        assertEquals("{\"stars\":1,\"count\":0}", compact(template(drf("views.C", "views.py"))))
    }

    fun testNestedWritableSerializerInsideAListSerializerMany() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class Line(serializers.Serializer):
                sku = serializers.CharField()
                qty = serializers.IntegerField(min_value=1)

            class Order(serializers.Serializer):
                lines = Line(many=True, allow_empty=False)
                note = serializers.CharField(required=False, allow_null=True)
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import Order\n\nclass C(generics.CreateAPIView):\n    serializer_class = Order\n")
        assertEquals("{\"lines\":[{\"sku\":\"string\",\"qty\":1}],\"note\":\"string\"}", compact(template(drf("views.C", "views.py"))))
        assertEquals(1, body(drf("views.C", "views.py"))!!.schema.defs.getValue("Order").properties.getValue("lines").minItems)
    }

    fun testDjangoFormInitialIsNotADefault() {
        add(
            "forms.py",
            """
            from django import forms

            class F(forms.Form):
                name = forms.CharField(initial="Anonymous")
            """,
        )
        add("views.py", "from django.views.generic import FormView\nfrom forms import F\n\nclass V(FormView):\n    form_class = F\n")
        val e = endpoint(Framework.DJANGO, HttpMethod.POST, "/f/", "views.V", "views.py")
        assertEquals("name=string", template(e))
        assertEquals(listOf("name"), body(e)!!.schema.required)
    }

    fun testFlaskRequestJsonKeysAreNotAnInferredSchema() {
        add(
            "app.py",
            """
            from flask import Flask, request

            app = Flask(__name__)

            @app.post("/x")
            def create():
                payload = request.json
                return {"a": payload["a"], "b": payload.get("b", 0)}
            """,
        )
        val e = endpoint(Framework.FLASK, HttpMethod.POST, "/x", "app.create", "app.py")
        assertNull(template(e))
        assertNull(body(e))
    }
}
