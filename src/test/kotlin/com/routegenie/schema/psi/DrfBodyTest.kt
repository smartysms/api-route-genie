package com.routegenie.schema.psi

import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod

class DrfBodyTest : SchemaTestCase() {
    private fun drf(handler: String, file: String, method: HttpMethod = HttpMethod.POST, name: String = handler.substringAfterLast('.'), path: String = "/api/x/") =
        endpoint(Framework.DRF, method, path, handler, file, name)

    private val models = """
        from django.db import models

        class User(models.Model):
            name = models.CharField(max_length=20)

        class Author(models.Model):
            name = models.CharField(max_length=50)

        class Tag(models.Model):
            code = models.UUIDField(primary_key=True)
    """.trimIndent()

    fun testPlainSerializerFollowsDeclarationOrder() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class AuthorSerializer(serializers.Serializer):
                name = serializers.CharField()

            class BookSerializer(serializers.Serializer):
                id = serializers.IntegerField(read_only=True)
                title = serializers.CharField(max_length=100)
                isbn = serializers.CharField(required=False, allow_blank=True)
                status = serializers.ChoiceField(choices=["draft", "published"], default="draft")
                tags = serializers.ListField(child=serializers.CharField(), required=False)
                author = AuthorSerializer()
                created = serializers.DateTimeField(read_only=True)
            """,
        )
        add(
            "views.py",
            """
            from rest_framework.views import APIView
            from serializers import BookSerializer

            class BookView(APIView):
                serializer_class = BookSerializer

                def post(self, request):
                    serializer = BookSerializer(data=request.data)
                    serializer.is_valid(raise_exception=True)
            """,
        )
        val e = drf("views.BookView", "views.py")
        assertEquals(
            "{\"title\":\"string\",\"isbn\":\"string\",\"status\":\"draft\",\"tags\":[\"string\"],\"author\":{\"name\":\"string\"}}",
            compact(template(e)),
        )
        val book = body(e)!!.schema.defs.getValue("BookSerializer")
        assertEquals(listOf("title", "author"), book.required)
        assertEquals(setOf("BookSerializer", "AuthorSerializer"), body(e)!!.schema.defs.keys)
    }

    fun testPatchDropsEveryRequired() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class S(serializers.Serializer):
                title = serializers.CharField()
                n = serializers.IntegerField()
            """,
        )
        add(
            "views.py",
            """
            from rest_framework import viewsets
            from serializers import S

            class V(viewsets.ModelViewSet):
                serializer_class = S
            """,
        )
        val put = drf("views.V.update", "views.py", HttpMethod.PUT, "V.update")
        val patch = drf("views.V.partial_update", "views.py", HttpMethod.PATCH, "V.partial_update")
        assertEquals(listOf("title", "n"), body(put)!!.schema.defs.getValue("S").required)
        assertEquals(emptyList<String>(), body(patch)!!.schema.defs.getValue("S").required)
        assertEquals("{\"title\":\"string\",\"n\":0}", compact(template(patch)))
    }

    fun testModelSerializerAllFieldsFromTheProjectModel() {
        add("models.py", models + "\n\n" + """
        class Book(models.Model):
            name = models.CharField(max_length=50)
            bio = models.TextField(blank=True)
            owner = models.ForeignKey(User, on_delete=models.CASCADE)
            slug = models.SlugField(unique=True)
            created = models.DateTimeField(auto_now_add=True)
        """.trimIndent())
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Book

            class BookSerializer(serializers.ModelSerializer):
                class Meta:
                    model = Book
                    fields = "__all__"
            """,
        )
        add(
            "views.py",
            """
            from rest_framework import generics
            from serializers import BookSerializer

            class BookCreate(generics.CreateAPIView):
                serializer_class = BookSerializer
            """,
        )
        val e = drf("views.BookCreate", "views.py")
        assertEquals("{\"name\":\"string\",\"bio\":\"string\",\"owner\":0,\"slug\":\"a\"}", compact(template(e)))
        val book = body(e)!!.schema.defs.getValue("BookSerializer")
        assertEquals(listOf("name", "owner", "slug"), book.required)
        assertEquals(50, book.properties.getValue("name").maxLength)
    }

    fun testMetaFieldsListReadOnlyAndExtraKwargs() {
        add("models.py", models + "\n\n" + """
        class Account(models.Model):
            username = models.CharField(max_length=30)
            password = models.CharField(max_length=128)
            is_staff = models.BooleanField(default=False)
            email = models.EmailField(blank=True)
        """.trimIndent())
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Account

            class AccountSerializer(serializers.ModelSerializer):
                class Meta:
                    model = Account
                    fields = ["id", "username", "password", "is_staff", "email"]
                    read_only_fields = ["is_staff"]
                    extra_kwargs = {"password": {"write_only": True}}
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import AccountSerializer\n\nclass Create(generics.CreateAPIView):\n    serializer_class = AccountSerializer\n")
        val e = drf("views.Create", "views.py")
        assertEquals("{\"username\":\"string\",\"password\":\"string\",\"email\":\"user@example.com\"}", compact(template(e)))
        val schema = body(e)!!.schema.defs.getValue("AccountSerializer")
        assertTrue(schema.properties.getValue("password").writeOnly)
        assertEquals(listOf("username", "password"), schema.required)
    }

    fun testMetaExcludeAndDeclaredFieldOverride() {
        add("models.py", models + "\n\n" + """
        class Item(models.Model):
            a = models.IntegerField()
            b = models.IntegerField()
            c = models.IntegerField(null=True)
        """.trimIndent())
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Item

            class ItemSerializer(serializers.ModelSerializer):
                b = serializers.CharField()

                class Meta:
                    model = Item
                    exclude = ["a"]
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import ItemSerializer\n\nclass Create(generics.CreateAPIView):\n    serializer_class = ItemSerializer\n")
        val e = drf("views.Create", "views.py")
        assertEquals("{\"b\":\"string\",\"c\":0}", compact(template(e)))
    }

    fun testNestedManyReadOnlyAndWriteOnlyFields() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class Child(serializers.Serializer):
                n = serializers.IntegerField(min_value=3)

            class Parent(serializers.Serializer):
                kids = Child(many=True)
                ro = Child(read_only=True)
                secret = serializers.CharField(write_only=True)
                computed = serializers.SerializerMethodField()
                hidden = serializers.HiddenField(default="x")
                nullable = serializers.IntegerField(allow_null=True, required=False)
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import Parent\n\nclass Create(generics.CreateAPIView):\n    serializer_class = Parent\n")
        val e = drf("views.Create", "views.py")
        assertEquals("{\"kids\":[{\"n\":3}],\"secret\":\"string\",\"nullable\":0}", compact(template(e)))
    }

    fun testFieldConstraintsAndFormats() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class S(serializers.Serializer):
                code = serializers.RegexField(regex=r"^[A-Z]{3}-[0-9]{4}$")
                mail = serializers.EmailField()
                site = serializers.URLField()
                uid = serializers.UUIDField()
                when = serializers.DateTimeField()
                day = serializers.DateField()
                ratio = serializers.FloatField(min_value=0.5)
                price = serializers.DecimalField(max_digits=5, decimal_places=2, min_value=1)
                flag = serializers.BooleanField()
                slug = serializers.SlugField()
                data = serializers.DictField(child=serializers.IntegerField())
                anything = serializers.JSONField()
                many = serializers.MultipleChoiceField(choices=["a", "b"])
                ip = serializers.IPAddressField()
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass Create(generics.CreateAPIView):\n    serializer_class = S\n")
        val e = drf("views.Create", "views.py")
        assertEquals(
            "{\"code\":\"AAA-0000\",\"mail\":\"user@example.com\",\"site\":\"https://example.com\"," +
                "\"uid\":\"123e4567-e89b-12d3-a456-426614174000\",\"when\":\"2024-01-01T00:00:00Z\",\"day\":\"2024-01-01\"," +
                "\"ratio\":0.5,\"price\":1,\"flag\":true,\"slug\":\"a\",\"data\":{\"key\":0},\"many\":[\"a\"],\"ip\":\"192.0.2.1\"}",
            compact(template(e)),
        )
    }

    fun testChoicesFromAModelFieldAndFromTextChoices() {
        add("models.py", """
            from django.db import models

            class Kind(models.TextChoices):
                A = "a", "Alpha"
                B = "b", "Beta"

            STATUS = [("open", "Open"), ("closed", "Closed")]

            class Ticket(models.Model):
                kind = models.CharField(max_length=1, choices=Kind.choices)
                status = models.CharField(max_length=10, choices=STATUS, default="open")
        """.trimIndent())
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Ticket

            class TicketSerializer(serializers.ModelSerializer):
                class Meta:
                    model = Ticket
                    fields = ["kind", "status"]
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import TicketSerializer\n\nclass Create(generics.CreateAPIView):\n    serializer_class = TicketSerializer\n")
        val e = drf("views.Create", "views.py")
        assertEquals("{\"kind\":\"a\",\"status\":\"open\"}", compact(template(e)))
        assertEquals(listOf("kind"), body(e)!!.schema.defs.getValue("TicketSerializer").required)
    }

    fun testRelationsUseThePrimaryKeyOfTheTarget() {
        add("models.py", models + "\n\n" + """
        class Post(models.Model):
            author = models.ForeignKey(Author, on_delete=models.CASCADE)
            tags = models.ManyToManyField(Tag)
            editor = models.ForeignKey("User", null=True, on_delete=models.SET_NULL)
        """.trimIndent())
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Post, Tag

            class PostSerializer(serializers.ModelSerializer):
                reviewers = serializers.PrimaryKeyRelatedField(queryset=Tag.objects.all(), many=True)

                class Meta:
                    model = Post
                    fields = ["author", "tags", "editor", "reviewers"]
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import PostSerializer\n\nclass Create(generics.CreateAPIView):\n    serializer_class = PostSerializer\n")
        val e = drf("views.Create", "views.py")
        val uuid = "\"123e4567-e89b-12d3-a456-426614174000\""
        assertEquals("{\"author\":0,\"tags\":[$uuid],\"editor\":0,\"reviewers\":[$uuid]}", compact(template(e)))
    }

    fun testViewSetUsesSerializerClassOfTheViewSet() {
        add("serializers.py", "from rest_framework import serializers\n\nclass S(serializers.Serializer):\n    n = serializers.IntegerField()\n")
        add("views.py", "from rest_framework import viewsets\nfrom serializers import S\n\nclass V(viewsets.ModelViewSet):\n    serializer_class = S\n")
        assertEquals("{\"n\":0}", compact(template(drf("views.V.create", "views.py", HttpMethod.POST, "V.create"))))
        assertNull(template(drf("views.V.destroy", "views.py", HttpMethod.DELETE, "V.destroy")))
    }

    fun testGetSerializerClassIsEvaluatedForTheAction() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class Read(serializers.Serializer):
                a = serializers.IntegerField()

            class Write(serializers.Serializer):
                b = serializers.CharField()
            """,
        )
        add(
            "views.py",
            """
            from rest_framework import viewsets
            from serializers import Read, Write

            class V(viewsets.ModelViewSet):
                def get_serializer_class(self):
                    if self.action in ("create", "update"):
                        return Write
                    return Read
            """,
        )
        assertEquals("{\"b\":\"string\"}", compact(template(drf("views.V.create", "views.py", HttpMethod.POST, "V.create"))))
        assertEquals("{\"b\":\"string\"}", compact(template(drf("views.V.update", "views.py", HttpMethod.PUT, "V.update"))))
        assertEquals("{\"a\":0}", compact(template(drf("views.V.partial_update", "views.py", HttpMethod.PATCH, "V.partial_update"))))
    }

    fun testGetSerializerClassDependingOnTheRequestIsNotGuessed() {
        add("serializers.py", "from rest_framework import serializers\n\nclass A(serializers.Serializer):\n    a = serializers.IntegerField()\n")
        add(
            "views.py",
            """
            from rest_framework import viewsets
            from serializers import A

            class V(viewsets.ModelViewSet):
                def get_serializer_class(self):
                    if self.request.user.is_staff:
                        return A
                    return A
            """,
        )
        assertNull(template(drf("views.V.create", "views.py", HttpMethod.POST, "V.create")))
    }

    fun testActionSerializerClassKeyword() {
        add("serializers.py", "from rest_framework import serializers\n\nclass Pw(serializers.Serializer):\n    password = serializers.CharField()\n\nclass Main(serializers.Serializer):\n    x = serializers.IntegerField()\n")
        add(
            "views.py",
            """
            from rest_framework import viewsets
            from rest_framework.decorators import action
            from serializers import Main, Pw

            class V(viewsets.ModelViewSet):
                serializer_class = Main

                @action(detail=True, methods=["post"], serializer_class=Pw)
                def set_password(self, request, pk=None):
                    pass

                @action(detail=False, methods=["post"])
                def untyped(self, request):
                    data = request.data
            """,
        )
        assertEquals("{\"password\":\"string\"}", compact(template(drf("views.V.set_password", "views.py", HttpMethod.POST, "V.set_password"))))
        assertNull(template(drf("views.V.untyped", "views.py", HttpMethod.POST, "V.untyped")))
    }

    fun testApiViewMethodUsesTheSerializerItBuilds() {
        add("serializers.py", "from rest_framework import serializers\n\nclass A(serializers.Serializer):\n    a = serializers.IntegerField()\n\nclass B(serializers.Serializer):\n    b = serializers.IntegerField()\n")
        add(
            "views.py",
            """
            from rest_framework.views import APIView
            from rest_framework.decorators import api_view
            from serializers import A, B

            class One(APIView):
                def post(self, request):
                    s = A(data=request.data)

            class Raw(APIView):
                def post(self, request):
                    value = request.data["a"]

            class Two(APIView):
                def post(self, request):
                    s = A(data=request.data)
                    t = B(data=request.data)

            @api_view(["POST"])
            def fn(request):
                s = B(data=request.data, partial=True)
            """,
        )
        assertEquals("{\"a\":0}", compact(template(drf("views.One", "views.py"))))
        assertNull(template(drf("views.Raw", "views.py")))
        assertNull(template(drf("views.Two", "views.py")))
        assertEquals("{\"b\":0}", compact(template(drf("views.fn", "views.py"))))
    }

    fun testExtendSchemaDeclaresTheRequest() {
        add("serializers.py", "from rest_framework import serializers\n\nclass A(serializers.Serializer):\n    a = serializers.IntegerField()\n")
        add(
            "views.py",
            """
            from drf_spectacular.utils import extend_schema, extend_schema_view
            from rest_framework.views import APIView
            from serializers import A

            class One(APIView):
                @extend_schema(request=A)
                def post(self, request):
                    return request.data

            class Many(APIView):
                @extend_schema(request=A(many=True))
                def post(self, request):
                    return request.data

            class Nothing(APIView):
                @extend_schema(request=None)
                def post(self, request):
                    return request.data
            """,
        )
        assertEquals("{\"a\":0}", compact(template(drf("views.One", "views.py"))))
        assertEquals("[{\"a\":0}]", compact(template(drf("views.Many", "views.py"))))
        assertNull(template(drf("views.Nothing", "views.py")))
    }

    fun testFileFieldMeansNoTemplate() {
        add("serializers.py", "from rest_framework import serializers\n\nclass S(serializers.Serializer):\n    name = serializers.CharField()\n    doc = serializers.FileField()\n")
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        assertNull(template(drf("views.C", "views.py")))
    }

    fun testInheritedSerializerFieldsComeFirst() {
        add(
            "serializers.py",
            """
            from rest_framework import serializers

            class Base(serializers.Serializer):
                id = serializers.IntegerField()
                name = serializers.CharField()

            class Child(Base):
                extra = serializers.CharField()
                name = serializers.IntegerField()
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import Child\n\nclass C(generics.CreateAPIView):\n    serializer_class = Child\n")
        assertEquals("{\"id\":0,\"extra\":\"string\",\"name\":0}", compact(template(drf("views.C", "views.py"))))
    }

    fun testCustomValidatorsAreRecorded() {
        add("serializers.py", "from rest_framework import serializers\n\nclass S(serializers.Serializer):\n    a = serializers.IntegerField()\n\n    def validate_a(self, value):\n        return value\n\n    def validate(self, data):\n        return data\n")
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        val result = body(drf("views.C", "views.py"))!!
        assertEquals(listOf("S.validate_a", "S.validate"), result.validators)
        assertEquals(com.routegenie.schema.model.Confidence.MEDIUM, result.confidence)
    }

    fun testModelPropertyInFieldsIsReadOnlyAndUnknownNamesAreLeftOut() {
        add("models.py", """
            from django.db import models

            class Thing(models.Model):
                name = models.CharField(max_length=5)

                @property
                def label(self):
                    return self.name
        """.trimIndent())
        add(
            "serializers.py",
            """
            from rest_framework import serializers
            from models import Thing

            class S(serializers.ModelSerializer):
                class Meta:
                    model = Thing
                    fields = ["name", "label", "reverse_things"]
            """,
        )
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        val e = drf("views.C", "views.py")
        assertEquals("{\"name\":\"strin\"}", compact(template(e)))
        assertEquals(listOf("S.reverse_things"), body(e)!!.diagnostics.map { it.args.first() })
    }

    fun testSerializerWithoutMetaFieldsIsNotGuessed() {
        add("models.py", "from django.db import models\n\nclass Thing(models.Model):\n    name = models.CharField(max_length=5)\n")
        add("serializers.py", "from rest_framework import serializers\nfrom models import Thing\n\nclass S(serializers.ModelSerializer):\n    class Meta:\n        model = Thing\n")
        add("views.py", "from rest_framework import generics\nfrom serializers import S\n\nclass C(generics.CreateAPIView):\n    serializer_class = S\n")
        assertNull(template(drf("views.C", "views.py")))
    }

    fun testViewWithoutAnySerializerHasNoTemplate() {
        add("views.py", "from rest_framework.views import APIView\n\nclass C(APIView):\n    def post(self, request):\n        return request.data\n")
        assertNull(template(drf("views.C", "views.py")))
    }
}
