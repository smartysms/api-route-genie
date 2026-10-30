package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.schema.model.MultipartTemplate
import com.routegenie.schema.model.RequestBodySchema
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.ObjectGraph
import com.routegenie.testing.PerfBudget
import java.lang.ref.WeakReference
import kotlin.random.Random

/** Leak and speed gates of the upload path: the schema and the parts hold no PSI, are collectable, and come fast. */
class MultipartLeakAndSpeedTest : SchemaTestCase() {
    override fun setUp() {
        super.setUp()
        service().clearCache()
    }

    private fun uploadEndpoint(): Endpoint {
        add("app/__init__.py", "")
        add("app/deps.py", "from fastapi import Form\n\ndef meta(title: str = Form(..., min_length=1), tags: list[str] = Form([])):\n    return title\n")
        add(
            "app/routes.py",
            """
            from typing import Annotated, Optional
            from fastapi import APIRouter, Depends, File, Form, UploadFile
            from app.deps import meta

            router = APIRouter()

            @router.post("/upload")
            def upload(
                avatar: Annotated[UploadFile, File(description="Profile picture")],
                m: str = Depends(meta),
                docs: list[UploadFile] = File(...),
                note: Optional[str] = Form(None),
                size: int = Form(..., ge=1, le=100),
                raw: bytes = File(None, max_length=4096),
            ):
                return {}
            """,
        )
        return fastApi("app.routes.upload", "app/routes.py")
    }

    fun testTheUploadEndpointIsRead() {
        val e = uploadEndpoint()
        val parts = runReadAction { service().multipartTemplateNow(e, random = false) }
        assertEquals(listOf("avatar", "title", "docs", "size", "raw"), parts!!.parts.map { it.name })
        assertEquals(1, parts.parts.first { it.name == "raw" }.file!!.let { 1 })
        assertEquals(4096L, parts.parts.first { it.name == "raw" }.file!!.maxBytes)
    }

    fun testSchemaAndPartsHoldNoPsiOrFiles() {
        val e = uploadEndpoint()
        val schema = body(e)!!
        ObjectGraph.assertNoInstanceOf("multipart schema", schema, PsiElement::class.java, VirtualFile::class.java, Project::class.java)
        val parts = runReadAction { service().multipartTemplateNow(e, random = true, seed = Random(1)) }
        ObjectGraph.assertNoInstanceOf("multipart parts", parts, PsiElement::class.java, VirtualFile::class.java, Project::class.java)
        ObjectGraph.assertNoInstanceOf("cached values", service().cachedValues(), PsiElement::class.java, VirtualFile::class.java, Project::class.java)
    }

    fun testCachedMultipartSchemaIsCollectableAfterTheCacheIsCleared() {
        val e = uploadEndpoint()
        val ref = WeakReference<RequestBodySchema>(runReadAction { service().schemaNow(e) })
        val parts = WeakReference<MultipartTemplate>(runReadAction { service().multipartTemplateNow(e, random = false) })
        service().clearCache()
        LeakProbe.assertCollected("cached multipart schema", ref)
        LeakProbe.assertCollected("multipart template", parts)
    }

    fun testRepeatedMultipartRequestsDoNotGrowTheHeapAndShareOneCacheEntry() {
        val e = uploadEndpoint()
        assertLeakFree("multipart template") { runReadAction { service().multipartTemplateNow(e, random = true, seed = Random(2)) } }
        assertEquals(1, service().cacheSize())
    }

    fun testMultipartExtractionIsFastColdAndWarm() {
        val e = uploadEndpoint()
        PerfBudget.assertWithin("multipart-extract-cold", Limits.BUDGET_MULTIPART_EXTRACT_COLD_MS, warmups = 2, runs = 7) {
            runReadAction { RequestSchemaExtractor.extract(project, e) }
        }
        assertNotNull(runReadAction { service().multipartTemplateNow(e, random = false) })
        PerfBudget.assertWithin("multipart-extract-warm", Limits.BUDGET_MULTIPART_EXTRACT_WARM_MS, warmups = 5, runs = 15) {
            runReadAction { service().multipartTemplateNow(e, random = false) }
        }
        PerfBudget.assertWithin("multipart-random-warm", Limits.BUDGET_MULTIPART_EXTRACT_WARM_MS, warmups = 5, runs = 15) {
            runReadAction { service().multipartTemplateNow(e, random = true, seed = Random(3)) }
        }
    }
}
