package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.RequestBodySchema
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.ObjectGraph
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PlatformLeaks
import java.lang.ref.WeakReference

/** Leak and speed gates of body extraction: caches are bounded and PSI-free, results are fast cold and warm. */
class SchemaLeakAndSpeedTest : SchemaTestCase() {
    override fun setUp() {
        super.setUp()
        // The project service outlives a test; a clean cache makes the counts below mean what they say.
        service().clearCache()
    }

    private fun nestedProject(): Endpoint {
        add("app/__init__.py", "")
        add("app/schemas/__init__.py", "")
        add("app/schemas/dimensions.py", "from typing import Literal\nfrom pydantic import BaseModel\n\nclass Dimensions(BaseModel):\n    width: float\n    height: float\n    unit: Literal['cm', 'in'] = 'cm'\n")
        add(
            "app/schemas/item.py",
            """
            from enum import Enum
            from typing import List, Optional
            from pydantic import BaseModel, Field
            from .dimensions import Dimensions

            class Category(str, Enum):
                BOOK = "book"
                TOY = "toy"

            class Item(BaseModel):
                name: str = Field(min_length=1, max_length=50)
                price: float = Field(gt=0)
                category: Category
                tags: List[str] = []
                dimensions: Dimensions
                note: Optional[str] = None
            """,
        )
        add(
            "app/routes.py",
            """
            from fastapi import APIRouter
            from app.schemas.item import Item

            router = APIRouter()

            @router.post("/items")
            def create_item(item: Item):
                return item
            """,
        )
        return fastApi("app.routes.create_item", "app/routes.py")
    }

    fun testSchemaAndTemplateHoldNoPsiOrFiles() {
        val e = nestedProject()
        val schema = body(e)!!
        ObjectGraph.assertNoInstanceOf("request schema", schema, PsiElement::class.java, VirtualFile::class.java, Project::class.java)
        template(e)
        ObjectGraph.assertNoInstanceOf("cached values", service().cachedValues(), PsiElement::class.java, VirtualFile::class.java, Project::class.java)
    }

    fun testCachedSchemasAreCollectableAfterTheCacheIsCleared() {
        val e = nestedProject()
        val ref = WeakReference<RequestBodySchema>(runReadAction { service().schemaNow(e) })
        service().clearCache()
        LeakProbe.assertCollected("cached schema", ref)
    }

    fun testCacheIsBounded() {
        add("m.py", "from pydantic import BaseModel\n\nclass A(BaseModel):\n    a: int\n\ndef h(a: A):\n    pass\n")
        val total = Limits.SCHEMA_CACHE_MAX_ENTRIES + 40
        for (i in 0 until total) assertNotNull(template(fastApi("m.h", "m.py", path = "/p$i")))
        assertTrue(service().cacheSize() <= Limits.SCHEMA_CACHE_MAX_ENTRIES)
        assertEquals(Limits.SCHEMA_CACHE_MAX_ENTRIES, service().cacheSize())
    }

    fun testNoBodyAnswersAreCachedToo() {
        add("m.py", "def h(q: int):\n    pass\n")
        val e = fastApi("m.h", "m.py")
        assertNull(template(e))
        assertEquals(1, service().cacheSize())
        assertNull(template(e))
        assertEquals(1, service().cacheSize())
    }

    fun testAnEditInvalidatesTheCache() {
        add("m.py", "from pydantic import BaseModel\n\nclass A(BaseModel):\n    a: int\n\ndef h(a: A):\n    pass\n")
        val e = fastApi("m.h", "m.py")
        assertEquals("{\"a\":0}", compact(template(e)))
        val file = myFixture.findFileInTempDir("m.py")
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("from pydantic import BaseModel\n\nclass A(BaseModel):\n    a: int\n    b: str\n\ndef h(a: A):\n    pass\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertEquals("{\"a\":0,\"b\":\"string\"}", compact(template(e)))
    }

    fun testExtractionOfATypicalEndpointIsFast() {
        val e = nestedProject()
        PerfBudget.assertWithin("schema-extract-cold", Limits.BUDGET_SCHEMA_EXTRACT_COLD_MS, warmups = 2, runs = 7) {
            runReadAction { RequestSchemaExtractor.extract(project, e) }
        }
        val first = runReadAction { service().templateNow(e) }
        assertNotNull(first)
        PerfBudget.assertWithin("schema-extract-warm", Limits.BUDGET_SCHEMA_EXTRACT_WARM_MS, warmups = 5, runs = 15) {
            runReadAction { service().templateNow(e) }
        }
    }

    fun testFirstExtractionRightAfterTheFilesWereCreated() {
        // Nothing of this project was read before: the first call also pays for loading the syntax trees it touches.
        val e = nestedProject()
        val result = PerfBudget.measure("schema-extract-first-call", Limits.BUDGET_SCHEMA_EXTRACT_COLD_MS, warmups = 0, runs = 1) {
            runReadAction { RequestSchemaExtractor.extract(project, e) }
        }
        assertTrue("first call took ${result.medianMs} ms", result.medianMs <= Limits.BUDGET_SCHEMA_EXTRACT_COLD_MS)
    }

    fun testNoDisposedProjectOrEditorStaysReachable() {
        val e = nestedProject()
        assertLeakFree("body template") { template(e) }
    }

    fun testOnlyBodyMethodsAreAnalysed() {
        val e = nestedProject()
        assertNull(template(fastApi("app.routes.create_item", "app/routes.py", method = HttpMethod.GET)))
        assertEquals(0, service().cacheSize())
    }
}
