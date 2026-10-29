package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.BodyEncoding
import com.routegenie.schema.model.Confidence

class FastApiBodyTest : SchemaTestCase() {
    private fun route(code: String, handler: String = "main.create", file: String = "main.py", path: String = "/items") =
        fastApi(handler, file, path).also { add(file, code) }

    fun testPydanticV2RequiredAndOptional() {
        val e = route(
            """
            from fastapi import FastAPI
            from pydantic import BaseModel

            app = FastAPI()

            class Item(BaseModel):
                name: str
                price: float
                tax: float | None = None
                note: str | None

            @app.post("/items")
            def create(item: Item):
                return item
            """,
        )
        assertEquals("{\"name\":\"string\",\"price\":0,\"tax\":null,\"note\":\"string\"}", compact(template(e)))
        assertEquals("application/json", contentType(e))
        val schema = body(e)!!.schema
        assertEquals(listOf("name", "price", "note"), schema.defs.getValue("Item").required)
    }

    fun testPydanticV1OptionalWithoutDefaultIsNotRequired() {
        val e = route(
            """
            from typing import Optional
            from pydantic.v1 import BaseModel

            class Item(BaseModel):
                name: str
                note: Optional[str]

            def create(item: Item):
                pass
            """,
        )
        assertEquals(listOf("name"), body(e)!!.schema.defs.getValue("Item").required)
        assertEquals("{\"name\":\"string\",\"note\":\"string\"}", compact(template(e)))
    }

    fun testV1IsRecognisedFromTheLegacyConfigOptions() {
        val e = route(
            """
            from typing import Optional
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str
                note: Optional[str]

                class Config:
                    orm_mode = True

            def create(item: Item):
                pass
            """,
        )
        assertEquals(listOf("name"), body(e)!!.schema.defs.getValue("Item").required)
    }

    fun testNestedModelsAcrossThreeFiles() {
        add(
            "app/__init__.py", "",
        )
        add(
            "app/models/__init__.py", "",
        )
        add(
            "app/models/address.py",
            """
            from pydantic import BaseModel

            class Address(BaseModel):
                city: str
                zip_code: str = "00000"
            """,
        )
        add(
            "app/models/user.py",
            """
            from typing import List
            from pydantic import BaseModel
            from .address import Address

            class User(BaseModel):
                name: str
                addresses: List[Address]
            """,
        )
        val e = fastApi("app.main.create", "app/main.py", "/users").also {
            add(
                "app/main.py",
                """
                from fastapi import FastAPI
                from app.models.user import User

                app = FastAPI()

                @app.post("/users")
                def create(user: User):
                    return user
                """,
            )
        }
        assertEquals(
            "{\"name\":\"string\",\"addresses\":[{\"city\":\"string\",\"zip_code\":\"00000\"}]}",
            compact(template(e)),
        )
        assertEquals(setOf("User", "Address"), body(e)!!.schema.defs.keys)
    }

    fun testListOfModelsAsBody() {
        val e = route(
            """
            from typing import List
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def create(items: List[Item]):
                pass
            """,
        )
        assertEquals("[{\"name\":\"string\"}]", compact(template(e)))
    }

    fun testEmbedWrapsTheModelUnderItsParameterName() {
        val e = route(
            """
            from typing import Annotated
            from fastapi import Body
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def create(item: Annotated[Item, Body(embed=True)]):
                pass
            """,
        )
        assertEquals("{\"item\":{\"name\":\"string\"}}", compact(template(e)))
    }

    fun testSeveralBodyParametersAreKeyedByName() {
        val e = route(
            """
            from typing import Annotated
            from fastapi import Body
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            class User(BaseModel):
                login: str

            def create(item_id: int, item: Item, user: User, importance: Annotated[int, Body(gt=0)]):
                pass
            """,
            path = "/items/{item_id}",
        )
        assertEquals("{\"item\":{\"name\":\"string\"},\"user\":{\"login\":\"string\"},\"importance\":1}", compact(template(e)))
        assertEquals(listOf("item", "user", "importance"), body(e)!!.schema.required)
    }

    fun testOldStyleBodyDefault() {
        val e = route(
            """
            from fastapi import Body

            def create(weight: int = Body(..., ge=5)):
                pass
            """,
        )
        assertEquals("5", template(e))
    }

    fun testQueryAndPathParametersAreNotTheBody() {
        val e = route(
            """
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def create(item_id: int, q: str | None = None, item: Item = None):
                pass
            """,
            path = "/items/{item_id}",
        )
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
    }

    fun testNoBodyMeansNoTemplate() {
        val e = route(
            """
            def create(q: str, limit: int = 10):
                pass
            """,
        )
        assertNull(template(e))
        assertNull(body(e))
    }

    fun testEnumAndLiteralFields() {
        val e = route(
            """
            from enum import Enum
            from typing import Literal
            from pydantic import BaseModel

            class Color(str, Enum):
                RED = "red"
                BLUE = "blue"

            class Level(Enum):
                LOW = 1
                HIGH = 2

            class Item(BaseModel):
                color: Color
                level: Level
                kind: Literal["a", "b"]
                fixed: Literal["x"]

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"color\":\"red\",\"level\":1,\"kind\":\"a\",\"fixed\":\"x\"}", compact(template(e)))
    }

    fun testEnumWithAutoValues() {
        val e = route(
            """
            from enum import Enum, IntEnum, StrEnum, auto
            from pydantic import BaseModel

            class A(IntEnum):
                ONE = auto()
                TWO = auto()

            class B(StrEnum):
                FIRST = auto()

            class Item(BaseModel):
                a: A
                b: B

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"a\":1,\"b\":\"first\"}", compact(template(e)))
    }

    fun testUnionTakesTheFirstBranch() {
        val e = route(
            """
            from typing import Union
            from pydantic import BaseModel

            class Item(BaseModel):
                a: Union[int, str]
                b: str | int | None = None

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"a\":0,\"b\":null}", compact(template(e)))
    }

    fun testRecursiveModelIsCutAtTheDepthLimit() {
        val e = route(
            """
            from typing import List
            from pydantic import BaseModel

            class Node(BaseModel):
                value: int
                children: List["Node"]

            def create(node: Node):
                pass
            """,
        )
        val text = template(e)!!
        assertTrue(text, compact(text)!!.startsWith("{\"value\":0,\"children\":[{\"value\":0,\"children\":[{\"value\":0,\"children\":[]}]}]}"))
        assertEquals(setOf("Node"), body(e)!!.schema.defs.keys)
    }

    fun testRecursiveOptionalChildEndsInNull() {
        val e = route(
            """
            from typing import Optional
            from pydantic import BaseModel

            class Node(BaseModel):
                value: int
                next: Optional["Node"] = None

            def create(node: Node):
                pass
            """,
        )
        assertEquals("{\"value\":0,\"next\":null}", compact(template(e)))
    }

    fun testAliasFromField() {
        val e = route(
            """
            from pydantic import BaseModel, Field

            class User(BaseModel):
                user_id: int = Field(alias="userId")
                name: str = Field(validation_alias="fullName")

            def create(user: User):
                pass
            """,
        )
        assertEquals("{\"userId\":0,\"fullName\":\"string\"}", compact(template(e)))
    }

    fun testAliasGeneratorToCamel() {
        val e = route(
            """
            from pydantic import BaseModel, ConfigDict
            from pydantic.alias_generators import to_camel

            class User(BaseModel):
                model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
                first_name: str
                is_admin_2: bool = False

            def create(user: User):
                pass
            """,
        )
        assertEquals("{\"firstName\":\"string\",\"isAdmin2\":false}", compact(template(e)))
    }

    fun testUnevaluableAliasGeneratorLeavesTheFieldsOut() {
        val e = route(
            """
            from pydantic import BaseModel, ConfigDict, Field

            class User(BaseModel):
                model_config = ConfigDict(alias_generator=lambda s: s.upper())
                first_name: str
                other: str = Field(alias="OTHER")

            def create(user: User):
                pass
            """,
        )
        assertEquals("{\"OTHER\":\"string\"}", compact(template(e)))
        val schema = body(e)!!
        assertEquals(Confidence.MEDIUM, schema.confidence)
        assertTrue(schema.diagnostics.any { it.args.firstOrNull() == "User.first_name" && it.args.last() == "UNRESOLVED_ALIAS" })
    }

    fun testConstraintsAreSatisfiedByTheSample() {
        val e = route(
            """
            from typing import Annotated
            from pydantic import BaseModel, Field, PositiveInt, conint, constr

            class Item(BaseModel):
                qty: int = Field(gt=0, le=100)
                ratio: float = Field(0.5, ge=0, le=1, multiple_of=0.05)
                code: Annotated[str, Field(min_length=2, max_length=5, pattern=r"^[A-Z]+$")]
                a: PositiveInt
                b: conint(ge=18)
                c: constr(min_length=3)

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"qty\":1,\"ratio\":0.5,\"code\":\"AA\",\"a\":1,\"b\":18,\"c\":\"string\"}", compact(template(e)))
    }

    fun testDefaultsAndFactories() {
        val e = route(
            """
            from typing import List
            from pydantic import BaseModel, Field

            class Item(BaseModel):
                name: str = "n"
                tags: List[str] = []
                counts: List[int] = Field(default_factory=list)
                level: int = Field(3)

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"n\",\"tags\":[],\"counts\":[0],\"level\":3}", compact(template(e)))
    }

    fun testRequiredOnlyDropsOptionalFields() {
        val e = route(
            """
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str
                note: str = "x"

            def create(item: Item):
                pass
            """,
        )
        val text = runReadAction { com.routegenie.schema.model.BodyRenderer.render(body(e)!!, includeOptional = false)!!.text }
        assertEquals("{\"name\":\"string\"}", compact(text))
    }

    fun testDataclassBody() {
        val e = route(
            """
            from dataclasses import dataclass, field

            @dataclass
            class Point:
                x: int
                y: int = 0
                tags: list = field(default_factory=list)
                hidden: int = field(default=1, init=False)

            def create(point: Point):
                pass
            """,
        )
        assertEquals("{\"x\":0,\"y\":0,\"tags\":[]}", compact(template(e)))
        assertEquals(listOf("x"), body(e)!!.schema.defs.getValue("Point").required)
    }

    fun testTypedDictNeedsBodyMarker() {
        val code = """
            from typing import TypedDict, NotRequired, Annotated
            from fastapi import Body

            class Movie(TypedDict):
                title: str
                year: NotRequired[int]

            def plain(movie: Movie):
                pass

            def marked(movie: Annotated[Movie, Body()]):
                pass
            """
        add("main.py", code)
        assertNull(template(fastApi("main.plain", "main.py")))
        assertEquals("{\"title\":\"string\",\"year\":0}", compact(template(fastApi("main.marked", "main.py"))))
        assertEquals(listOf("title"), body(fastApi("main.marked", "main.py"))!!.schema.defs.getValue("Movie").required)
    }

    fun testFormFieldsGiveAFormBody() {
        val e = route(
            """
            from typing import Annotated
            from fastapi import Form

            def login(username: Annotated[str, Form()], password: str = Form(...), remember: bool = Form(False)):
                pass
            """,
            handler = "main.login",
        )
        assertEquals("username=string&password=string&remember=false", template(e))
        assertEquals("application/x-www-form-urlencoded", contentType(e))
        assertEquals(BodyEncoding.FORM_URLENCODED, body(e)!!.encoding)
    }

    fun testFormModelFlattensItsFields() {
        val e = route(
            """
            from typing import Annotated
            from fastapi import Form
            from pydantic import BaseModel

            class FormData(BaseModel):
                username: str
                age: int = 18

            def login(data: Annotated[FormData, Form()]):
                pass
            """,
            handler = "main.login",
        )
        assertEquals("username=string&age=18", template(e))
    }

    fun testFileUploadHasNoTemplate() {
        val e = route(
            """
            from fastapi import File, UploadFile

            def upload(file: UploadFile, note: str):
                pass
            """,
        )
        assertNull(template(e))
        assertNull(route("from fastapi import File\ndef up(data: bytes = File()):\n    pass\n", "main.up", "main2.py").let { template(it) })
    }

    fun testDependsIsIgnoredButItsBodyParametersJoin() {
        val e = route(
            """
            from typing import Annotated
            from fastapi import Depends
            from fastapi.security import OAuth2PasswordBearer
            from pydantic import BaseModel

            oauth2 = OAuth2PasswordBearer(tokenUrl="token")

            class Item(BaseModel):
                name: str

            class Meta(BaseModel):
                source: str

            def current_user(token: str = Depends(oauth2)):
                return token

            def meta_dep(meta: Meta):
                return meta

            CurrentUser = Annotated[str, Depends(current_user)]

            def create(user: CurrentUser, item: Item, other=Depends(meta_dep)):
                pass
            """,
        )
        assertEquals("{\"item\":{\"name\":\"string\"},\"meta\":{\"source\":\"string\"}}", compact(template(e)))
    }

    fun testFormDependencyIsNotRepresentable() {
        val e = route(
            """
            from typing import Annotated
            from fastapi import Depends
            from fastapi.security import OAuth2PasswordRequestForm

            def login(form: Annotated[OAuth2PasswordRequestForm, Depends()]):
                pass
            """,
        )
        assertNull(template(e))
    }

    fun testUnknownParameterTypeLeavesTheEndpointWithoutTemplate() {
        val e = route(
            """
            from somewhere import Mystery

            def create(thing: Mystery):
                pass
            """,
        )
        assertNull(template(e))
        assertTrue(extraction(e).absentReason!!.contains("UNRESOLVED_TYPE"))
    }

    fun testInjectedParametersAreIgnored() {
        val e = route(
            """
            from fastapi import BackgroundTasks, Request
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def create(request: Request, tasks: BackgroundTasks, item: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
    }

    fun testInheritanceKeepsBaseFieldsFirst() {
        val e = route(
            """
            from pydantic import BaseModel

            class Base(BaseModel):
                id: int
                name: str

            class Item(Base):
                price: float
                name: str = "overridden"

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"id\":0,\"name\":\"overridden\",\"price\":0}", compact(template(e)))
    }

    fun testMapFieldHasOneSampleEntry() {
        val e = route(
            """
            from typing import Dict
            from pydantic import BaseModel

            class Item(BaseModel):
                scores: Dict[str, int]
                free: dict

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"scores\":{\"key\":0},\"free\":{}}", compact(template(e)))
    }

    fun testExtraForbidBecomesAdditionalPropertiesFalse() {
        val e = route(
            """
            from pydantic import BaseModel, ConfigDict

            class Item(BaseModel):
                model_config = ConfigDict(extra="forbid")
                name: str

            def create(item: Item):
                pass
            """,
        )
        assertEquals(com.routegenie.schema.model.Additional.Forbidden, body(e)!!.schema.defs.getValue("Item").additional)
    }

    fun testFormatTypesGiveRealisticValues() {
        val e = route(
            """
            from datetime import date, datetime
            from decimal import Decimal
            from uuid import UUID
            from pydantic import BaseModel, EmailStr, HttpUrl

            class Item(BaseModel):
                id: UUID
                born: date
                at: datetime
                price: Decimal
                mail: EmailStr
                site: HttpUrl

            def create(item: Item):
                pass
            """,
        )
        assertEquals(
            "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"born\":\"2024-01-01\",\"at\":\"2024-01-01T00:00:00Z\",\"price\":0," +
                "\"mail\":\"user@example.com\",\"site\":\"https://example.com\"}",
            compact(template(e)),
        )
    }

    fun testTuplesAndForwardReferences() {
        val e = route(
            """
            from typing import List, Optional, Tuple
            from pydantic import BaseModel

            class Child(BaseModel):
                n: int

            class Parent(BaseModel):
                pair: Tuple[int, str]
                rest: Tuple[int, ...]
                kids: Optional[List["Child"]] = None

            def create(parent: Parent):
                pass
            """,
        )
        assertEquals("{\"pair\":[0,\"string\"],\"rest\":[0],\"kids\":null}", compact(template(e)))
    }

    fun testCustomValidatorsLowerTheConfidence() {
        val e = route(
            """
            from pydantic import BaseModel, field_validator

            class Item(BaseModel):
                name: str

                @field_validator("name")
                @classmethod
                def check(cls, v):
                    return v

            def create(item: Item):
                pass
            """,
        )
        val schema = body(e)!!
        assertEquals(Confidence.MEDIUM, schema.confidence)
        assertEquals(listOf("Item.check"), schema.validators)
    }

    fun testClassVarsAndPrivateNamesAreNotFields() {
        val e = route(
            """
            from typing import ClassVar
            from pydantic import BaseModel, PrivateAttr

            class Item(BaseModel):
                count: ClassVar[int] = 0
                _secret: str = "x"
                token: str = PrivateAttr()
                name: str

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\"}", compact(template(e)))
    }

    fun testUnresolvableFieldTypeLeavesJustThatFieldOut() {
        val e = route(
            """
            from pydantic import BaseModel
            from elsewhere import Strange

            class Item(BaseModel):
                name: str
                weird: Strange
                age: int

            def create(item: Item):
                pass
            """,
        )
        assertEquals("{\"name\":\"string\",\"age\":0}", compact(template(e)))
        assertTrue(body(e)!!.diagnostics.any { it.args.first() == "Item.weird" })
    }

    fun testOnlyBodyMethodsHaveTemplates() {
        add(
            "main.py",
            """
            from pydantic import BaseModel

            class Item(BaseModel):
                name: str

            def read(item: Item):
                pass
            """,
        )
        assertNull(template(fastApi("main.read", "main.py", method = HttpMethod.GET)))
        assertNotNull(template(fastApi("main.read", "main.py", method = HttpMethod.PUT)))
        assertNotNull(template(fastApi("main.read", "main.py", method = HttpMethod.PATCH)))
    }

    fun testHandlerIsFoundThroughTheRouteFileImports() {
        add("models.py", "from pydantic import BaseModel\n\nclass Item(BaseModel):\n    name: str\n")
        add("handlers.py", "from models import Item\n\ndef create(item: Item):\n    pass\n")
        add("main.py", "from handlers import create\n")
        // The route file names the handler by import only; the qualified name still leads to the module.
        assertEquals("{\"name\":\"string\"}", compact(template(fastApi("handlers.create", "main.py"))))
    }
}
