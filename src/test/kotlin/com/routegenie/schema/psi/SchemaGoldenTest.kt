package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.django.DjangoRouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.frameworks.flask.FlaskRouteProvider
import com.routegenie.model.HttpMethod
import com.routegenie.schema.BodyTemplateService
import com.routegenie.schema.model.Confidence
import com.routegenie.testing.ProviderPipeline
import com.routegenie.testing.PythonPlatformTestCase
import java.io.File

/**
 * Whole-project golden tests: endpoints come from the real route providers (so handler names and positions are what the
 * window will hold), and the generated body of every endpoint is compared with expected.txt exactly.
 */
class SchemaGoldenTest : PythonPlatformTestCase() {
    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    private fun bodies(provider: RouteProvider, fixture: String): List<String> {
        myFixture.copyDirectoryToProject(fixture, "")
        val endpoints = provider.link(ProviderPipeline.collect(project, provider)).endpoints
        assertTrue("the fixture must have endpoints", endpoints.isNotEmpty())
        val service = BodyTemplateService.getInstance(project)
        return endpoints.map { e ->
            val template = runReadAction { service.templateNow(e) }
            val head = "${e.method} ${e.path} | "
            when {
                template == null -> head + "ABSENT"
                template.contentType == "application/json" -> head + template.contentType + " | " + compactJson(template.text)
                else -> head + template.contentType + " | " + template.text
            }
        }.sorted()
    }

    private fun expected(path: String): List<String> =
        File(testDataPath, path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.sorted()

    private fun compactJson(text: String) = Regex("\\s*\\n\\s*").replace(text, "").replace("\": ", "\":")

    fun testFastApiProject() {
        assertEquals(expected("schema/fastapi_app/expected.txt"), bodies(FastApiRouteProvider(), "schema/fastapi_app"))
    }

    fun testDrfProject() {
        assertEquals(expected("schema/drf_app/expected.txt"), bodies(DjangoRouteProvider(), "schema/drf_app"))
    }

    fun testDjangoFormsProject() {
        assertEquals(expected("schema/django_forms/expected.txt"), bodies(DjangoRouteProvider(), "schema/django_forms"))
    }

    fun testFlaskProjectDeclaresNothingExceptFlaskPydantic() {
        assertEquals(expected("schema/flask_app/expected.txt"), bodies(FlaskRouteProvider(), "schema/flask_app"))
    }

    fun testFastApiSchemasKeepTheDeclaredModelsAndRecordNothingMissing() {
        myFixture.copyDirectoryToProject("schema/fastapi_app", "")
        val provider = FastApiRouteProvider()
        val endpoints = provider.link(ProviderPipeline.collect(project, provider)).endpoints
        val create = endpoints.single { it.method == HttpMethod.POST && it.path == "/items/" }
        val schema = runReadAction { BodyTemplateService.getInstance(project).schemaNow(create) }!!
        assertEquals(setOf("Item", "Dimensions"), schema.schema.defs.keys)
        assertEquals(Confidence.HIGH, schema.confidence)
        assertTrue(schema.diagnostics.isEmpty())
    }
}
