package com.routegenie.schema.psi

import com.intellij.openapi.application.runReadAction
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.schema.BodyTemplateService
import com.routegenie.schema.model.RequestBodySchema
import com.routegenie.testing.PythonPlatformTestCase
import java.io.File

/** Base of the extraction tests: files are created in the light project and endpoints are built by hand like a linker would. */
abstract class SchemaTestCase : PythonPlatformTestCase() {
    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    protected fun add(path: String, text: String) = pythonFile(path, text.trimIndent() + "\n")

    /** An endpoint of [framework]; [handler] is the qualified name, [name] the part after the module (defaults to the last segment). */
    protected fun endpoint(
        framework: Framework,
        method: HttpMethod,
        path: String,
        handler: String,
        file: String,
        name: String = handler.substringAfterLast('.'),
    ) = Endpoint(method, path, framework, Handler(name, handler), SourceRef(file, 0, 1))

    protected fun fastApi(handler: String, file: String, path: String = "/items", method: HttpMethod = HttpMethod.POST) =
        endpoint(Framework.FASTAPI, method, path, handler, file)

    protected fun service() = BodyTemplateService.getInstance(project)

    protected fun extraction(endpoint: Endpoint): Extraction = runReadAction { RequestSchemaExtractor.extract(project, endpoint) }

    protected fun body(endpoint: Endpoint): RequestBodySchema? = extraction(endpoint).body

    /** The text the "Generate body" button would put in the editor, or null. */
    protected fun template(endpoint: Endpoint): String? = runReadAction { service().templateNow(endpoint)?.text }

    protected fun contentType(endpoint: Endpoint): String? = runReadAction { service().templateNow(endpoint)?.contentType }

    /** A JSON text compacted for short assertions. */
    protected fun compact(text: String?): String? = text?.let { Regex("\\s*\\n\\s*").replace(it, "").replace(": ", ":") }
}
