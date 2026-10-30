package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonText
import com.routegenie.schema.model.RandomSampleGenerator
import com.routegenie.testing.SchemaChecker
import kotlin.random.Random

/** From Python source to a random body: the declared nested models, through the real extraction, always give valid documents. */
class RandomBodyTest : SchemaTestCase() {
    private val order = """
        from datetime import date, datetime
        from enum import Enum
        from typing import Literal, Optional
        from uuid import UUID
        from fastapi import FastAPI
        from pydantic import BaseModel, Field

        app = FastAPI()

        class Color(str, Enum):
            RED = "red"
            GREEN = "green"
            BLUE = "blue"

        class Address(BaseModel):
            street: str = Field(min_length=3, max_length=20)
            city: str
            zip_code: str = Field(pattern=r"^[0-9]{5}$")

        class Item(BaseModel):
            sku: str = Field(pattern=r"^[A-Z]{3}-[0-9]{4}$")
            quantity: int = Field(ge=1, le=10)
            price: float = Field(gt=0, lt=1000)
            color: Color
            tags: list[str] = Field(min_length=1, max_length=3)

        class Cat(BaseModel):
            kind: Literal["cat"]
            lives: int = Field(ge=1, le=9)

        class Dog(BaseModel):
            kind: Literal["dog"]
            good: bool

        class Order(BaseModel):
            id: UUID
            placed: datetime
            due: Optional[date] = None
            customer: Address
            items: list[Item] = Field(min_length=1)
            pet: Cat | Dog
            attributes: dict[str, int]
            note: Optional[str] = Field(default=None, max_length=40)
            mode: Literal["a", "b"]

        @app.post("/orders")
        def create(order: Order):
            return order
    """

    private fun orderEndpoint() = fastApi("main.create", "main.py", "/orders").also { add("main.py", order) }

    fun testEveryRandomDocumentOfANestedOrderIsValidAndComplete() {
        val e = orderEndpoint()
        val schema = body(e)!!.schema
        for (seed in 0 until 150) {
            val result = RandomSampleGenerator.generate(schema, Random(seed))
            val value = result.value as Json.Obj
            val violations = SchemaChecker.violations(schema, value)
            assertTrue("seed $seed: $violations in ${JsonText.compact(value)}", violations.isEmpty())
            assertTrue("seed $seed: gaps ${result.gaps}", result.gaps.isEmpty())
            for (required in listOf("id", "placed", "customer", "items", "pet", "attributes", "mode")) {
                assertTrue("seed $seed: '$required' missing", required in value.fields)
            }
        }
    }

    fun testTheButtonTextIsDifferentEachTimeAndTheSameForTheSameSeed() {
        val e = orderEndpoint()
        val texts = (0 until 20).map { seed -> runReadAction { service().randomTemplateNow(e, Random(seed))!!.text } }
        assertTrue("20 seeds, ${texts.toSet().size} documents", texts.toSet().size > 18)
        assertEquals(texts[3], runReadAction { service().randomTemplateNow(e, Random(3))!!.text })
        assertEquals("application/json", runReadAction { service().randomTemplateNow(e, Random(0))!!.contentType })
        // The unseeded call, the one the button makes, gives yet another document.
        val live = runReadAction { service().randomTemplateNow(e)!!.text }
        assertFalse(live in texts)
    }

    fun testTheFixedSampleIsStillTheFixedSample() {
        val e = orderEndpoint()
        assertEquals(template(e), template(e))
        assertTrue(template(e)!!.contains("\"city\": \"string\""))
    }

    fun testAnEndpointWithoutADeclaredBodyHasNoRandomBodyEither() {
        val e = fastApi("main.ping", "main.py", "/ping").also {
            add("main.py", "from fastapi import FastAPI\napp = FastAPI()\n\n@app.post(\"/ping\")\ndef ping(name: str):\n    return name\n")
        }
        assertNull(runReadAction { service().randomTemplateNow(e) })
    }
}
