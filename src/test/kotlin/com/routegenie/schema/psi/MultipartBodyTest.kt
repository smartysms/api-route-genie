package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.BodyEncoding
import com.routegenie.schema.model.Confidence
import com.routegenie.schema.model.FileSpec
import com.routegenie.schema.model.MultipartTemplate
import com.routegenie.schema.model.Schema
import kotlin.random.Random

/**
 * File uploads read from Python source: FastAPI, DRF, Django forms and Flask. Every test names one declaration. The FastAPI,
 * DRF and Django declarations marked "verified" were also posted to the real frameworks (see the report of the change).
 */
class MultipartBodyTest : SchemaTestCase() {
    // --- helpers ---------------------------------------------------------------------------------------------------------

    private fun fa(code: String, handler: String = "main.up"): Endpoint = fastApi(handler, "main.py").also { add("main.py", code) }

    private fun drf(handler: String, file: String = "views.py") = endpoint(Framework.DRF, HttpMethod.POST, "/api/x/", handler, file)

    private fun django(handler: String, file: String = "views.py") = endpoint(Framework.DJANGO, HttpMethod.POST, "/form/", handler, file)

    private fun flask(code: String, handler: String = "app.up"): Endpoint = endpoint(Framework.FLASK, HttpMethod.POST, "/x", handler, "app.py").also { add("app.py", code) }

    private fun mp(e: Endpoint, random: Boolean = false, seed: Int = 1): MultipartTemplate? =
        runReadAction { service().multipartTemplateNow(e, random, Random(seed)) }

    /** `name=text` for a text part and `name=<file>` for a file part, in order. */
    private fun shown(e: Endpoint): List<String>? = mp(e)?.parts?.map { "${it.name}=${it.text ?: "<file>"}" }

    private fun root(e: Endpoint): Schema {
        val schema = body(e)!!.schema
        return schema.ref?.let { schema.defs.getValue(it.substringAfterLast('/')) } ?: schema
    }

    private fun spec(e: Endpoint, property: String): FileSpec = FileSpec.shape(root(e).properties.getValue(property))!!.spec

    private fun assertMultipart(e: Endpoint) {
        assertEquals(BodyEncoding.MULTIPART, body(e)!!.encoding)
        assertEquals("multipart/form-data", body(e)!!.encoding.contentType)
        // The text template must stay empty so the existing buttons never put a broken body in the editor.
        assertNull(runReadAction { service().templateNow(e) })
        assertNull(runReadAction { service().randomTemplateNow(e) })
    }

    private fun assertAbsent(e: Endpoint, reason: String? = null) {
        assertNull(body(e))
        assertNull(mp(e))
        if (reason != null) assertTrue(extraction(e).absentReason!!, extraction(e).absentReason!!.contains(reason))
    }

    // --- FastAPI (the first twelve are verified against FastAPI 0.137 / python-multipart) --------------------------------------

    fun testFastApiPlainUploadFile() {
        val e = fa("from fastapi import UploadFile\n\ndef up(file: UploadFile):\n    pass\n")
        assertMultipart(e)
        assertEquals(listOf("file=<file>"), shown(e))
        assertEquals(listOf("file"), root(e).required)
        assertEquals(FileSpec(), spec(e, "file"))
    }

    fun testFastApiUploadFileWithFileMarkerAndForm() {
        val e = fa("from fastapi import File, Form, UploadFile\n\ndef up(file: UploadFile = File(...), note: str = Form(...)):\n    pass\n")
        assertMultipart(e)
        assertEquals(listOf("file=<file>", "note=string"), shown(e))
    }

    fun testFastApiAnnotatedFileAndForm() {
        val e = fa(
            """
            from typing import Annotated
            from fastapi import File, Form, UploadFile

            def up(file: Annotated[UploadFile, File()], note: Annotated[str, Form()]):
                pass
            """,
        )
        assertMultipart(e)
        assertEquals(listOf("file=<file>", "note=string"), shown(e))
    }

    fun testFastApiBytesWithFile() {
        val e = fa("from fastapi import File\n\ndef up(data: bytes = File()):\n    pass\n")
        assertMultipart(e)
        assertEquals(listOf("data=<file>"), shown(e))
    }

    fun testFastApiListOfUploadFiles() {
        val e = fa("from fastapi import UploadFile\n\ndef up(files: list[UploadFile]):\n    pass\n")
        assertMultipart(e)
        assertEquals(listOf("files=<file>"), shown(e))
        assertTrue(FileSpec.shape(root(e).properties.getValue("files"))!!.many)
    }

    fun testFastApiOptionalUploadFileAndFormDefault() {
        val e = fa("from typing import Optional\nfrom fastapi import Form, UploadFile\n\ndef up(file: Optional[UploadFile] = None, tag: str = Form('x')):\n    pass\n")
        assertMultipart(e)
        assertEquals(emptyList<String>(), root(e).required)
        assertEquals(listOf("file=<file>", "tag=x"), shown(e))
    }

    fun testFastApiSeveralFilesBooleanAndNumber() {
        val e = fa(
            """
            from fastapi import File, Form, UploadFile

            def up(a: UploadFile, b: UploadFile = File(description="second"), flag: bool = Form(...), n: int = Form(...)):
                pass
            """,
        )
        assertMultipart(e)
        assertEquals(listOf("a=<file>", "b=<file>", "flag=true", "n=0"), shown(e))
        assertEquals("second", root(e).properties.getValue("b").description)
    }

    fun testFastApiFileInADependency() {
        val e = fa(
            """
            from fastapi import Depends, File, Form, UploadFile

            def dep(extra: str = Form(...), doc: UploadFile = File(...)):
                return extra

            def up(x: str = Depends(dep)):
                pass
            """,
        )
        assertMultipart(e)
        assertEquals(listOf("extra=string", "doc=<file>"), shown(e))
    }

    fun testFastApiAnnotatedListOfFilesAndListOfForm() {
        val e = fa(
            """
            from typing import Annotated
            from fastapi import File, Form, UploadFile

            def up(files: Annotated[list[UploadFile], File()], names: Annotated[list[str], Form()]):
                pass
            """,
        )
        assertMultipart(e)
        assertEquals(listOf("files=<file>", "names=string"), shown(e))
    }

    fun testFastApiListOfBytes() {
        val e = fa("from fastapi import File\n\ndef up(files: list[bytes] = File(...)):\n    pass\n")
        assertMultipart(e)
        assertEquals(listOf("files=<file>"), shown(e))
        assertTrue(FileSpec.shape(root(e).properties.getValue("files"))!!.many)
    }

    fun testFastApiUnionWithNoneAndFileDefault() {
        val e = fa("from fastapi import File, UploadFile\n\ndef up(file: UploadFile | None = File(None)):\n    pass\n")
        assertMultipart(e)
        assertEquals(emptyList<String>(), root(e).required)
        assertEquals(listOf("file=<file>"), shown(e))
    }

    fun testFastApiAliasOfTheFilePart() {
        val e = fa("from fastapi import File, UploadFile\n\ndef up(file: UploadFile = File(alias='upload')):\n    pass\n")
        assertEquals(listOf("upload=<file>"), shown(e))
    }

    fun testFastApiStarletteAndRenamedImports() {
        val starlette = fa("from starlette.datastructures import UploadFile as UF\n\ndef up(f: UF):\n    pass\n")
        assertEquals(listOf("f=<file>"), shown(starlette))
        val renamed = fastApi("main2.up", "main2.py").also { add("main2.py", "from fastapi import UploadFile as Up, File as F\n\ndef up(f: Up = F(...)):\n    pass\n") }
        assertEquals(listOf("f=<file>"), shown(renamed))
        val module = fastApi("main3.up", "main3.py").also { add("main3.py", "import fastapi\n\ndef up(f: fastapi.UploadFile):\n    pass\n") }
        assertEquals(listOf("f=<file>"), shown(module))
    }

    fun testFastApiBytesLengthLimitIsASizeLimit() {
        val e = fa("from fastapi import File\n\ndef up(data: bytes = File(max_length=1024)):\n    pass\n")
        assertEquals(1024L, spec(e, "data").maxBytes)
    }

    fun testFastApiFileMixedWithAJsonBodyIsAbsent() {
        val e = fa(
            """
            from fastapi import UploadFile
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def up(item: Item, file: UploadFile):
                pass
            """,
        )
        assertAbsent(e, "JSON body")
    }

    fun testFastApiFormModelWithAFileIsAbsent() {
        val e = fa(
            """
            from typing import Annotated
            from fastapi import Form, UploadFile
            from pydantic import BaseModel

            class M(BaseModel):
                username: str

            def up(data: Annotated[M, Form()], f: UploadFile):
                pass
            """,
        )
        assertAbsent(e, "form model")
    }

    fun testFastApiFormModelAloneStaysUrlEncoded() {
        val e = fa(
            """
            from typing import Annotated
            from fastapi import Form
            from pydantic import BaseModel

            class M(BaseModel):
                username: str
                age: int = 18

            def up(data: Annotated[M, Form()]):
                pass
            """,
        )
        assertEquals(BodyEncoding.FORM_URLENCODED, body(e)!!.encoding)
        assertEquals("username=string&age=18", runReadAction { service().templateNow(e)?.text })
        assertNull(mp(e))
    }

    fun testFastApiBytesWithoutFileIsAQueryParameter() {
        assertAbsent(fa("def up(data: bytes):\n    pass\n"))
    }

    fun testFastApiBytesInAJsonModelIsNotAFile() {
        val e = fa("from pydantic import BaseModel\n\nclass M(BaseModel):\n    name: str\n    blob: bytes\n\ndef up(m: M):\n    pass\n")
        assertEquals(BodyEncoding.JSON, body(e)!!.encoding)
        assertNull(FileSpec.of(body(e)!!.schema.defs.getValue("M").properties.getValue("blob")))
        assertNull(mp(e))
    }

    fun testFastApiFormFieldsWithoutFilesStayUrlEncoded() {
        val e = fa("from fastapi import Form\n\ndef up(a: str = Form(...), b: int = Form(3)):\n    pass\n")
        assertEquals(BodyEncoding.FORM_URLENCODED, body(e)!!.encoding)
        assertNull(mp(e))
    }

    fun testFastApiUploadWithGetIsNotAnalysed() {
        val e = fastApi("main.up", "main.py", method = HttpMethod.GET).also { add("main.py", "from fastapi import UploadFile\n\ndef up(f: UploadFile):\n    pass\n") }
        assertNull(body(e))
    }

    fun testFastApiFileThatIsNotBytesIsAbsent() {
        assertAbsent(fa("from fastapi import File\n\ndef up(n: int = File(...)):\n    pass\n"), "File()")
    }

    // --- DRF (verified against DRF 3.16) -----------------------------------------------------------------------------------

    private fun serializer(fields: String, view: String = "generics.CreateAPIView", extra: String = "", imports: String = ""): Endpoint {
        add("serializers.py", "from rest_framework import serializers\n$imports\n$fields")
        add("views.py", "from rest_framework import generics\nfrom rest_framework.views import APIView\nfrom rest_framework.parsers import JSONParser, MultiPartParser, FormParser\nfrom serializers import S\n\nclass C($view):\n    serializer_class = S\n$extra")
        return drf("views.C")
    }

    fun testDrfFileFieldAndCharField() {
        val e = serializer("class S(serializers.Serializer):\n    name = serializers.CharField()\n    doc = serializers.FileField()\n")
        assertMultipart(e)
        assertEquals(listOf("name=string", "doc=<file>"), shown(e))
        assertEquals(listOf("name", "doc"), root(e).required)
    }

    fun testDrfImageField() {
        val e = serializer("class S(serializers.Serializer):\n    title = serializers.CharField(max_length=20)\n    photo = serializers.ImageField()\n")
        assertMultipart(e)
        assertTrue(spec(e, "photo").image)
        assertEquals(listOf("title=string", "photo=<file>"), shown(e))
    }

    fun testDrfFileFieldIsNotAnImage() {
        val e = serializer("class S(serializers.Serializer):\n    doc = serializers.FileField()\n")
        assertFalse(spec(e, "doc").image)
    }

    fun testDrfListFieldOfFiles() {
        val e = serializer("class S(serializers.Serializer):\n    docs = serializers.ListField(child=serializers.FileField(), min_length=2, max_length=3)\n")
        assertMultipart(e)
        val shape = FileSpec.shape(root(e).properties.getValue("docs"))!!
        assertEquals(2, shape.minItems)
        assertEquals(3, shape.maxItems)
        assertEquals(listOf("docs=<file>", "docs=<file>"), shown(e))
    }

    fun testDrfFileExtensionValidator() {
        val e = serializer(
            "class S(serializers.Serializer):\n    doc = serializers.FileField(validators=[FileExtensionValidator(allowed_extensions=['pdf', 'PNG', '.txt'])])\n",
            imports = "from django.core.validators import FileExtensionValidator",
        )
        assertEquals(listOf(".pdf", ".png", ".txt"), spec(e, "doc").extensions)
        assertEquals(Confidence.HIGH, body(e)!!.confidence)
        assertEquals(emptyList<String>(), body(e)!!.validators)
    }

    fun testDrfExtensionsFromAConstant() {
        val e = serializer(
            "ALLOWED = ['jpg', 'jpeg']\n\nclass S(serializers.Serializer):\n    doc = serializers.FileField(validators=[FileExtensionValidator(ALLOWED)])\n",
            imports = "from django.core.validators import FileExtensionValidator",
        )
        assertEquals(listOf(".jpg", ".jpeg"), spec(e, "doc").extensions)
    }

    fun testDrfUnknownValidatorKeepsTheFieldAndMarksTheSchemaAsNecessaryOnly() {
        val e = serializer(
            "def check(value):\n    do_something(value)\n\nclass S(serializers.Serializer):\n    doc = serializers.FileField(validators=[check])\n",
        )
        assertMultipart(e)
        assertEquals(listOf("S.doc"), body(e)!!.validators)
        assertEquals(Confidence.MEDIUM, body(e)!!.confidence)
        assertEquals(FileSpec(), spec(e, "doc"))
    }

    fun testDrfSizeValidatorFunctionGivesMaxBytes() {
        val e = serializer(
            "MAX = 2 * 1024 * 1024\n\ndef small(value):\n    if value.size > MAX:\n        raise ValueError('too big')\n\nclass S(serializers.Serializer):\n    doc = serializers.FileField(validators=[small])\n",
        )
        assertEquals(2L * 1024 * 1024, spec(e, "doc").maxBytes)
        assertEquals(emptyList<String>(), body(e)!!.validators)
    }

    fun testDrfSizeAndExtensionInOneValidatorFunction() {
        val e = serializer(
            "import os\n\ndef check(f):\n    if f.size >= 1000:\n        raise ValueError('big')\n    if os.path.splitext(f.name)[1].lower() not in ['.png', '.gif']:\n        raise ValueError('type')\n\nclass S(serializers.Serializer):\n    doc = serializers.ImageField(validators=[check])\n",
        )
        assertEquals(999L, spec(e, "doc").maxBytes)
        assertEquals(listOf(".png", ".gif"), spec(e, "doc").extensions)
        assertEquals(emptyList<String>(), body(e)!!.validators)
    }

    fun testDrfValidatorFunctionWithAnotherRaiseIsOnlyPartlyUnderstood() {
        val e = serializer(
            "def check(f):\n    if f.size > 10:\n        raise ValueError('big')\n    if f.name.startswith('x'):\n        raise ValueError('name')\n\nclass S(serializers.Serializer):\n    doc = serializers.FileField(validators=[check])\n",
        )
        assertEquals(10L, spec(e, "doc").maxBytes)
        assertEquals(listOf("S.doc"), body(e)!!.validators)
    }

    fun testDrfMaxLengthOfAFileFieldIsTheNameLengthNotASize() {
        val e = serializer("class S(serializers.Serializer):\n    doc = serializers.FileField(max_length=100, allow_empty_file=True)\n")
        assertNull(spec(e, "doc").maxBytes)
        assertMultipart(e)
    }

    fun testDrfOptionalNullableFile() {
        val e = serializer("class S(serializers.Serializer):\n    n = serializers.IntegerField()\n    doc = serializers.FileField(required=False, allow_null=True)\n")
        assertEquals(listOf("n"), root(e).required)
        assertEquals(listOf("n=0", "doc=<file>"), shown(e))
    }

    fun testDrfNestedManySerializerWithFilesIsMultipartButNotRenderable() {
        val e = serializer(
            "class Child(serializers.Serializer):\n    name = serializers.CharField()\n    doc = serializers.FileField()\n\nclass S(serializers.Serializer):\n    items = Child(many=True)\n",
        )
        assertEquals(BodyEncoding.MULTIPART, body(e)!!.encoding)
        assertNull(mp(e))
    }

    fun testDrfModelSerializerFromModelFileFields() {
        add("models.py", "from django.db import models\n\nclass Doc(models.Model):\n    title = models.CharField(max_length=10)\n    attachment = models.FileField(upload_to='x/')\n    cover = models.ImageField(upload_to='y/', blank=True)\n")
        val e = serializer("from models import Doc\n\nclass S(serializers.ModelSerializer):\n    class Meta:\n        model = Doc\n        fields = ['title', 'attachment', 'cover']\n")
        assertMultipart(e)
        assertEquals(listOf("title", "attachment"), root(e).required)
        assertTrue(spec(e, "cover").image)
        assertEquals(listOf("title=string", "attachment=<file>", "cover=<file>"), shown(e))
    }

    fun testDrfModelFileFieldValidatorsCarryOver() {
        add("models.py", "from django.core.validators import FileExtensionValidator\nfrom django.db import models\n\nclass Doc(models.Model):\n    attachment = models.FileField(validators=[FileExtensionValidator(['pdf'])])\n")
        val e = serializer("from models import Doc\n\nclass S(serializers.ModelSerializer):\n    class Meta:\n        model = Doc\n        fields = ['attachment']\n")
        assertEquals(listOf(".pdf"), spec(e, "attachment").extensions)
    }

    fun testDrfParserClassesWithoutMultipartAreAbsent() {
        val e = serializer("class S(serializers.Serializer):\n    doc = serializers.FileField()\n", extra = "    parser_classes = [JSONParser]\n")
        assertAbsent(e, "accept no multipart")
    }

    fun testDrfParserClassesWithMultipartKeepTheBody() {
        val e = serializer("class S(serializers.Serializer):\n    doc = serializers.FileField()\n", extra = "    parser_classes = [MultiPartParser, FormParser]\n")
        assertMultipart(e)
    }

    fun testDrfParserClassesThatCannotBeReadAreAbsent() {
        val e = serializer("class S(serializers.Serializer):\n    doc = serializers.FileField()\n", extra = "    parser_classes = PARSERS\n")
        assertAbsent(e, "not a literal")
    }

    fun testDrfApiViewWithParserDecoratorOnAFunctionView() {
        add("serializers.py", "from rest_framework import serializers\n\nclass S(serializers.Serializer):\n    doc = serializers.FileField()\n")
        add(
            "views.py",
            """
            from rest_framework.decorators import api_view, parser_classes
            from rest_framework.parsers import JSONParser, MultiPartParser
            from serializers import S

            @api_view(["POST"])
            @parser_classes([JSONParser])
            def json_only(request):
                s = S(data=request.data)

            @api_view(["POST"])
            @parser_classes([MultiPartParser])
            def multi(request):
                s = S(data=request.data)

            @api_view(["POST"])
            def default(request):
                s = S(data=request.data)
            """,
        )
        assertAbsent(drf("views.json_only"), "accept no multipart")
        assertMultipart(drf("views.multi"))
        assertMultipart(drf("views.default"))
    }

    fun testDrfFilePathFieldIsNotAnUpload() {
        val e = serializer("class S(serializers.Serializer):\n    name = serializers.CharField()\n    where = serializers.FilePathField(path='/tmp')\n")
        assertEquals(BodyEncoding.JSON, body(e)!!.encoding)
    }

    fun testDrfSerializerWithoutFilesStaysJson() {
        val e = serializer("class S(serializers.Serializer):\n    name = serializers.CharField()\n")
        assertEquals(BodyEncoding.JSON, body(e)!!.encoding)
        assertNull(mp(e))
    }

    // --- Django forms (verified against Django 5.2) --------------------------------------------------------------------------

    private fun form(fields: String, imports: String = "", view: String = "FormView"): Endpoint {
        add("forms.py", "from django import forms\n$imports\n$fields")
        add("views.py", "from django.views.generic import FormView, CreateView\nfrom forms import F\n\nclass V($view):\n    form_class = F\n")
        return django("views.V")
    }

    fun testDjangoFileFieldAndCharField() {
        val e = form("class F(forms.Form):\n    name = forms.CharField()\n    doc = forms.FileField()\n")
        assertMultipart(e)
        assertEquals(listOf("name=string", "doc=<file>"), shown(e))
        assertEquals(listOf("name", "doc"), root(e).required)
    }

    fun testDjangoImageFieldAndOptionalFile() {
        val e = form("class F(forms.Form):\n    photo = forms.ImageField()\n    note = forms.CharField(required=False)\n    extra = forms.FileField(required=False)\n")
        assertTrue(spec(e, "photo").image)
        assertEquals(listOf("photo"), root(e).required)
        assertEquals(listOf("photo=<file>", "note=string", "extra=<file>"), shown(e))
    }

    fun testDjangoMultipleFileFieldRecipe() {
        val e = form(
            """
            class MultipleFileInput(forms.ClearableFileInput):
                allow_multiple_selected = True

            class MultipleFileField(forms.FileField):
                def __init__(self, *args, **kwargs):
                    kwargs.setdefault("widget", MultipleFileInput())
                    super().__init__(*args, **kwargs)

                def clean(self, data, initial=None):
                    single = super().clean
                    return [single(d, initial) for d in data]

            class F(forms.Form):
                docs = MultipleFileField()
                title = forms.CharField()
            """,
        )
        assertMultipart(e)
        assertTrue(FileSpec.shape(root(e).properties.getValue("docs"))!!.many)
        assertEquals(listOf("docs=<file>", "title=string"), shown(e))
    }

    fun testDjangoWidgetWithMultipleAttribute() {
        val e = form("class F(forms.Form):\n    docs = forms.FileField(widget=forms.ClearableFileInput(attrs={'multiple': True}))\n")
        assertTrue(FileSpec.shape(root(e).properties.getValue("docs"))!!.many)
    }

    fun testDjangoFileExtensionValidator() {
        val e = form(
            "class F(forms.Form):\n    doc = forms.FileField(validators=[FileExtensionValidator(allowed_extensions=['txt'])])\n    kind = forms.ChoiceField(choices=[('a', 'A'), ('b', 'B')])\n    tags = forms.MultipleChoiceField(choices=[('x', 'X'), ('y', 'Y')])\n",
            imports = "from django.core.validators import FileExtensionValidator",
        )
        assertEquals(listOf(".txt"), spec(e, "doc").extensions)
        assertEquals(listOf("doc=<file>", "kind=a", "tags=x"), shown(e))
    }

    fun testDjangoImageValidatorMarksAnImage() {
        val e = form(
            "class F(forms.Form):\n    doc = forms.FileField(validators=[validate_image_file_extension])\n",
            imports = "from django.core.validators import validate_image_file_extension",
        )
        assertTrue(spec(e, "doc").image)
    }

    fun testDjangoModelFormFileFields() {
        add("models.py", "from django.db import models\n\nclass Doc(models.Model):\n    title = models.CharField(max_length=10)\n    attachment = models.FileField(upload_to='x/')\n    cover = models.ImageField(upload_to='y/', blank=True)\n")
        val e = form("from models import Doc\n\nclass F(forms.ModelForm):\n    class Meta:\n        model = Doc\n        fields = ['title', 'attachment', 'cover']\n", view = "CreateView")
        assertMultipart(e)
        assertEquals(listOf("title", "attachment"), root(e).required)
        assertTrue(spec(e, "cover").image)
        assertEquals(listOf("title=string", "attachment=<file>", "cover=<file>"), shown(e))
    }

    fun testDjangoFilePathFieldIsNotAnUploadAndLeavesTheFormUrlEncoded() {
        val e = form("class F(forms.Form):\n    name = forms.CharField()\n    where = forms.FilePathField(path='/tmp')\n")
        assertEquals(BodyEncoding.FORM_URLENCODED, body(e)!!.encoding)
        assertEquals(setOf("name"), root(e).properties.keys)
    }

    fun testDjangoFormWithoutFilesStaysUrlEncoded() {
        val e = form("class F(forms.Form):\n    name = forms.CharField()\n")
        assertEquals(BodyEncoding.FORM_URLENCODED, body(e)!!.encoding)
        assertNull(mp(e))
    }

    fun testDjangoCustomFileFieldThatChangesConversionIsLeftOut() {
        val e = form("class Weird(forms.FileField):\n    def clean(self, data, initial=None):\n        return data\n\nclass F(forms.Form):\n    name = forms.CharField()\n    doc = Weird()\n")
        assertEquals(BodyEncoding.FORM_URLENCODED, body(e)!!.encoding)
        assertEquals(setOf("name"), root(e).properties.keys)
        assertFalse(body(e)!!.diagnostics.isEmpty())
    }

    // --- Flask -------------------------------------------------------------------------------------------------------------

    fun testFlaskFilesSubscript() {
        val e = flask("from flask import Flask, request\n\napp = Flask(__name__)\n\n@app.post('/x')\ndef up():\n    f = request.files['file']\n    return ''\n")
        assertMultipart(e)
        assertEquals(listOf("file=<file>"), shown(e))
        assertEquals(FileSpec(), spec(e, "file"))
    }

    fun testFlaskFilesGet() {
        val e = flask("from flask import request\n\ndef up():\n    f = request.files.get('photo')\n")
        assertEquals(listOf("photo=<file>"), shown(e))
    }

    fun testFlaskFilesGetlist() {
        val e = flask("from flask import request\n\ndef up():\n    for f in request.files.getlist('docs'):\n        pass\n")
        assertTrue(FileSpec.shape(root(e).properties.getValue("docs"))!!.many)
        assertEquals(listOf("docs=<file>"), shown(e))
    }

    fun testFlaskFilesWithFormFields() {
        val e = flask(
            """
            from flask import request

            def up():
                title = request.form['title']
                note = request.form.get('note')
                tags = request.form.getlist('tags')
                f = request.files['file']
            """,
        )
        assertMultipart(e)
        assertEquals(listOf("file=<file>", "title=string", "note=string", "tags=string"), shown(e))
    }

    fun testFlaskAllowedExtensionsFromAHelperFunction() {
        val e = flask(
            """
            from flask import request

            ALLOWED_EXTENSIONS = {'txt', 'PDF', 'png'}

            def allowed_file(filename):
                return '.' in filename and filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS

            def up():
                file = request.files['file']
                if not allowed_file(file.filename):
                    return 'bad'
            """,
        )
        assertEquals(setOf(".txt", ".pdf", ".png"), spec(e, "file").extensions.toSet())
        assertTrue(spec(e, "file").accept.isEmpty())
    }

    fun testFlaskAllowedExtensionsInTheHandlerItself() {
        val e = flask(
            """
            import os
            from flask import request

            ALLOWED_EXTENSIONS = ['jpg', 'jpeg']

            def up():
                file = request.files['image']
                if os.path.splitext(file.filename)[1].lower() in ALLOWED_EXTENSIONS:
                    pass
            """,
        )
        assertEquals(listOf(".jpg", ".jpeg"), spec(e, "image").extensions)
    }

    fun testFlaskNotInCheckIsNotAnAllowList() {
        val e = flask(
            """
            from flask import request

            ALLOWED_EXTENSIONS = {'png'}

            def up():
                file = request.files['file']
                if file.filename.rsplit('.', 1)[1].lower() not in ALLOWED_EXTENSIONS:
                    return 'bad'
            """,
        )
        assertEquals(emptyList<String>(), spec(e, "file").extensions)
    }

    fun testFlaskBlockListConstantIsNotAnAllowList() {
        val e = flask(
            """
            from flask import request

            BLOCKED_EXTENSIONS = {'exe'}

            def up():
                file = request.files['file']
                if file.filename.rsplit('.', 1)[1].lower() in BLOCKED_EXTENSIONS:
                    return 'bad'
            """,
        )
        assertEquals(emptyList<String>(), spec(e, "file").extensions)
    }

    fun testFlaskTwoFileFieldsGetNoExtensionsBecauseTheCheckIsAmbiguous() {
        val e = flask(
            """
            from flask import request

            ALLOWED_EXTENSIONS = {'png'}

            def up():
                a = request.files['a']
                b = request.files['b']
                if a.filename.rsplit('.', 1)[1].lower() in ALLOWED_EXTENSIONS:
                    pass
            """,
        )
        assertEquals(listOf("a=<file>", "b=<file>"), shown(e))
        assertEquals(emptyList<String>(), spec(e, "a").extensions)
    }

    fun testFlaskFormWithoutFilesStaysAbsent() {
        assertAbsent(flask("from flask import request\n\ndef up():\n    name = request.form['name']\n"), "no request body")
    }

    fun testFlaskFileNameThatIsNotALiteralIsLeftOutAndRecorded() {
        val e = flask("from flask import request\n\ndef up(name):\n    f = request.files[name]\n")
        assertAbsent(e)
    }

    fun testFlaskNameFromAModuleConstant() {
        val e = flask("from flask import request\n\nFIELD = 'upload'\n\ndef up():\n    f = request.files[FIELD]\n")
        assertEquals(listOf("upload=<file>"), shown(e))
    }

    fun testFlaskRenamedRequestImport() {
        val e = flask("from flask import request as req\n\ndef up():\n    f = req.files['file']\n")
        assertEquals(listOf("file=<file>"), shown(e))
    }

    fun testFlaskFlaskPydanticStillWinsWhenPresent() {
        val e = flask(
            """
            from flask_pydantic import validate
            from pydantic import BaseModel

            class M(BaseModel):
                name: str

            @validate(body=M)
            def up():
                pass
            """,
        )
        assertEquals(BodyEncoding.JSON, body(e)!!.encoding)
    }

    // --- The service ---------------------------------------------------------------------------------------------------------

    fun testRandomMultipartIsDeterministicForASeedAndVaries() {
        val e = fa("from fastapi import File, Form, UploadFile\n\ndef up(title: str = Form(..., min_length=3, max_length=9), docs: list[UploadFile] = File(...), n: int = Form(..., ge=1, le=99)):\n    pass\n")
        val a = mp(e, random = true, seed = 5)
        assertEquals(a, mp(e, random = true, seed = 5))
        val titles = (1..15).map { s -> mp(e, random = true, seed = s)!!.parts.first { it.name == "title" }.text }.toSet()
        assertTrue(titles.size > 3)
        assertTrue(a!!.parts.any { it.name == "docs" && it.file != null })
    }

    fun testTheSourceInterfaceAnswersNullByDefault() {
        val source = com.routegenie.client.BodyTemplateSource { null }
        val e = fa("from fastapi import UploadFile\n\ndef up(f: UploadFile):\n    pass\n")
        assertNull(kotlinx.coroutines.runBlocking { source.multipartTemplateFor(e, random = false) })
    }

    fun testTheServiceAnswersThroughTheSuspendApiToo() {
        val e = fa("from fastapi import UploadFile\n\ndef up(f: UploadFile):\n    pass\n")
        val parts = kotlinx.coroutines.runBlocking { service().multipartTemplateFor(e, random = false) }
        assertEquals(listOf("f"), parts!!.parts.map { it.name })
        assertNull(kotlinx.coroutines.runBlocking { service().templateFor(e) })
        assertNull(kotlinx.coroutines.runBlocking { service().randomTemplateFor(e) })
    }

    fun testAJsonEndpointHasNoMultipartTemplate() {
        val e = fa("from pydantic import BaseModel\n\nclass M(BaseModel):\n    name: str\n\ndef up(m: M):\n    pass\n")
        assertNull(mp(e))
        assertNull(mp(e, random = true))
    }
}
