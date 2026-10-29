package com.routegenie.schema.psi

import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod

class DjangoFormAndFlaskBodyTest : SchemaTestCase() {
    private fun django(handler: String, file: String, method: HttpMethod = HttpMethod.POST, name: String = handler.substringAfterLast('.')) =
        endpoint(Framework.DJANGO, method, "/form/", handler, file, name)

    private fun flask(handler: String, file: String, method: HttpMethod = HttpMethod.POST, name: String = handler.substringAfterLast('.')) =
        endpoint(Framework.FLASK, method, "/x", handler, file, name)

    // --- Django forms ---------------------------------------------------------------------------------------------------

    fun testFormViewBoundToFormClass() {
        add(
            "forms.py",
            """
            from django import forms

            class ContactForm(forms.Form):
                email = forms.EmailField()
                message = forms.CharField(max_length=500, required=False)
                age = forms.IntegerField(min_value=18)
            """,
        )
        add(
            "views.py",
            """
            from django.views.generic import FormView
            from forms import ContactForm

            class Contact(FormView):
                form_class = ContactForm
            """,
        )
        val e = django("views.Contact", "views.py")
        assertEquals("email=user%40example.com&message=string&age=18", template(e))
        assertEquals("application/x-www-form-urlencoded", contentType(e))
        assertEquals(listOf("email", "age"), body(e)!!.schema.required)
        assertNull(body(e)!!.schema.properties.getValue("message").minLength)
    }

    fun testFormFieldKinds() {
        add(
            "forms.py",
            """
            from django import forms

            class F(forms.Form):
                agree = forms.BooleanField()
                kind = forms.ChoiceField(choices=[("a", "A"), ("b", "B")])
                many = forms.MultipleChoiceField(choices=[("x", "X"), ("y", "Y")])
                when = forms.DateField()
                at = forms.DateTimeField()
                price = forms.DecimalField(min_value=2)
                site = forms.URLField()
                code = forms.RegexField(regex=r"^[0-9]{3}$")
                secret = forms.CharField(disabled=True)
                note = forms.CharField(required=False, initial="x")
            """,
        )
        add("views.py", "from django.views.generic import FormView\nfrom forms import F\n\nclass V(FormView):\n    form_class = F\n")
        assertEquals(
            "agree=true&kind=a&many=x&when=2024-01-01&at=2024-01-01T00%3A00%3A00Z&price=2&site=https%3A%2F%2Fexample.com&code=000&note=string",
            template(django("views.V", "views.py")),
        )
    }

    fun testCreateViewBuildsAModelForm() {
        add(
            "models.py",
            """
            from django.db import models

            class Author(models.Model):
                name = models.CharField(max_length=10)

            class Book(models.Model):
                title = models.CharField(max_length=100)
                summary = models.TextField(blank=True)
                pages = models.IntegerField(default=1)
                published = models.BooleanField(default=False)
                author = models.ForeignKey(Author, on_delete=models.CASCADE)
                created = models.DateTimeField(auto_now_add=True)
            """,
        )
        add(
            "views.py",
            """
            from django.views.generic import CreateView
            from models import Book

            class BookCreate(CreateView):
                model = Book
                fields = ["title", "summary", "pages", "published", "author"]
            """,
        )
        val e = django("views.BookCreate", "views.py")
        assertEquals("title=string&summary=string&pages=0&published=true&author=0", template(e))
        assertEquals(listOf("title", "pages", "author"), body(e)!!.schema.required)
    }

    fun testModelFormWithExcludeAndDeclaredField() {
        add("models.py", "from django.db import models\n\nclass Item(models.Model):\n    a = models.IntegerField()\n    b = models.CharField(max_length=3)\n    c = models.IntegerField()\n")
        add(
            "forms.py",
            """
            from django import forms
            from models import Item

            class ItemForm(forms.ModelForm):
                extra = forms.CharField()

                class Meta:
                    model = Item
                    fields = "__all__"
                    exclude = ["c"]
            """,
        )
        add("views.py", "from django.views.generic import UpdateView\nfrom forms import ItemForm\n\nclass V(UpdateView):\n    form_class = ItemForm\n")
        assertEquals("a=0&b=str&extra=string", template(django("views.V", "views.py", HttpMethod.PUT)))
    }

    fun testFunctionViewBuildsOneForm() {
        add("forms.py", "from django import forms\n\nclass A(forms.Form):\n    a = forms.IntegerField()\n\nclass B(forms.Form):\n    b = forms.CharField()\n")
        add(
            "views.py",
            """
            from django.views.decorators.http import require_POST
            from forms import A, B

            @require_POST
            def one(request):
                form = A(request.POST)

            @require_POST
            def two(request):
                form = A(request.POST)
                other = B(request.POST)

            @require_POST
            def none(request):
                value = request.POST["a"]
            """,
        )
        assertEquals("a=0", template(django("views.one", "views.py")))
        assertNull(template(django("views.two", "views.py")))
        assertNull(template(django("views.none", "views.py")))
    }

    fun testFileFormHasNoTemplate() {
        add("forms.py", "from django import forms\n\nclass F(forms.Form):\n    name = forms.CharField()\n    doc = forms.FileField()\n")
        add("views.py", "from django.views.generic import FormView\nfrom forms import F\n\nclass V(FormView):\n    form_class = F\n")
        assertNull(template(django("views.V", "views.py")))
    }

    fun testGetFormClassThatIsNotDecidedHasNoTemplate() {
        add("forms.py", "from django import forms\n\nclass A(forms.Form):\n    a = forms.IntegerField()\n")
        add(
            "views.py",
            """
            from django.views.generic import FormView
            from forms import A

            class V(FormView):
                def get_form_class(self):
                    if self.request.user.is_staff:
                        return A
                    return A
            """,
        )
        assertNull(template(django("views.V", "views.py")))
    }

    fun testGetFormClassWithSingleReturn() {
        add("forms.py", "from django import forms\n\nclass A(forms.Form):\n    a = forms.IntegerField()\n")
        add("views.py", "from django.views.generic import FormView\nfrom forms import A\n\nclass V(FormView):\n    def get_form_class(self):\n        return A\n")
        assertEquals("a=0", template(django("views.V", "views.py")))
    }

    fun testModelChoiceFieldUsesThePrimaryKey() {
        add("models.py", "from django.db import models\n\nclass Team(models.Model):\n    name = models.CharField(max_length=3)\n")
        add(
            "forms.py",
            """
            from django import forms
            from models import Team

            class F(forms.Form):
                team = forms.ModelChoiceField(queryset=Team.objects.all())
                others = forms.ModelMultipleChoiceField(queryset=Team.objects.all())
            """,
        )
        add("views.py", "from django.views.generic import FormView\nfrom forms import F\n\nclass V(FormView):\n    form_class = F\n")
        assertEquals("team=0&others=0", template(django("views.V", "views.py")))
    }

    fun testPlainViewsAndGetAreNotForms() {
        add("views.py", "from django.views.generic import View\n\nclass V(View):\n    def post(self, request):\n        return None\n")
        assertNull(template(django("views.V", "views.py")))
        add("forms.py", "from django import forms\n\nclass A(forms.Form):\n    a = forms.IntegerField()\n")
        add("views2.py", "from django.views.generic import FormView\nfrom forms import A\n\nclass V(FormView):\n    form_class = A\n")
        assertNull(template(django("views2.V", "views2.py", HttpMethod.GET)))
    }

    // --- Flask ----------------------------------------------------------------------------------------------------------------

    fun testPlainFlaskHandlerDeclaresNothing() {
        add(
            "app.py",
            """
            from flask import Flask, request

            app = Flask(__name__)

            @app.post("/x")
            def create():
                data = request.get_json()
                name = data["name"]
                age = data.get("age", 0)
                return {}
            """,
        )
        val e = flask("app.create", "app.py")
        assertNull(template(e))
        assertTrue(extraction(e).absentReason!!.contains("no request body"))
    }

    fun testFlaskPydanticBody() {
        add(
            "app.py",
            """
            from flask import Flask
            from flask_pydantic import validate
            from pydantic import BaseModel

            app = Flask(__name__)

            class Item(BaseModel):
                name: str
                qty: int = 1

            @app.post("/x")
            @validate(body=Item)
            def create():
                pass

            @app.post("/y")
            @validate()
            def create_two(body: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\",\"qty\":1}", compact(template(flask("app.create", "app.py"))))
        assertEquals("{\"name\":\"string\",\"qty\":1}", compact(template(flask("app.create_two", "app.py"))))
    }

    fun testFlaskPydanticForm() {
        add(
            "app.py",
            """
            from flask import Flask
            from flask_pydantic import validate
            from pydantic import BaseModel

            class Login(BaseModel):
                user: str
                password: str

            @validate(form=Login)
            def login():
                pass
            """,
        )
        val e = flask("app.login", "app.py")
        assertEquals("user=string&password=string", template(e))
        assertEquals("application/x-www-form-urlencoded", contentType(e))
    }

    fun testFlaskMethodViewWithValidate() {
        add(
            "views.py",
            """
            from flask.views import MethodView
            from flask_pydantic import validate
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            class ItemAPI(MethodView):
                @validate(body=Item)
                def post(self):
                    pass

                def put(self):
                    pass
            """,
        )
        assertEquals("{\"name\":\"string\"}", compact(template(flask("views.ItemAPI", "views.py"))))
        assertNull(template(flask("views.ItemAPI", "views.py", HttpMethod.PUT)))
    }

    fun testMarshmallowAndOtherFlaskLibrariesAreNotRecognised() {
        add(
            "app.py",
            """
            from flask import Flask
            from webargs.flaskparser import use_args
            from marshmallow import Schema, fields

            class S(Schema):
                name = fields.Str()

            @use_args(S(), location="json")
            def create(args):
                pass
            """,
        )
        assertNull(template(flask("app.create", "app.py")))
    }
}
