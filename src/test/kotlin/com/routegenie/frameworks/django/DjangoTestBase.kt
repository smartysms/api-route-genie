package com.routegenie.frameworks.django

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.testing.ProviderPipeline
import java.io.File

/** Shared helpers: put Python files into the light project, run the provider over them and render the result as text. */
abstract class DjangoTestBase : BasePlatformTestCase() {
    protected val provider = DjangoRouteProvider()

    override fun getTestDataPath(): String = TEST_DATA

    /** Adds [files] (relative path to source text), extracts every one and links. */
    protected fun link(vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        return provider.link(ProviderPipeline.collect(project, provider))
    }

    /** Adds the fixture's Python files once (calling this again for the same fixture reuses them) and extracts each. */
    protected fun extractAll(fixture: String): List<FileFacts> {
        val root = File(TEST_DATA, fixture)
        val relatives = root.walkTopDown().filter { it.isFile && it.extension == "py" }
            .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        relatives.forEach { relative ->
            if (myFixture.findFileInTempDir(relative) == null) myFixture.addFileToProject(relative, File(root, relative).readText())
        }
        return ProviderPipeline.collect(project, provider)
    }

    protected fun linkFixture(fixture: String): LinkResult = provider.link(extractAll(fixture))

    /** `GET /shop/item/{pk}`: the shape most tests compare. */
    protected fun brief(result: LinkResult): List<String> = result.endpoints.map { "${it.method} ${it.path}" }

    protected fun find(result: LinkResult, method: String, path: String): Endpoint =
        result.endpoints.single { it.method.name == method && it.path == path }

    /** Diagnostics that point at something to look at; the informational note about a guessed root is not one. */
    protected fun warnings(result: LinkResult) = result.diagnostics.filter { it.code != DiagnosticCode.GUESSED_ROOT }

    /** One line per endpoint with everything the golden files pin down. */
    protected fun render(result: LinkResult): String = result.endpoints.joinToString("\n") { e ->
        val problems = if (e.problems.isEmpty()) "" else " !" + e.problems.joinToString(",") { it.code.name }
        val params = if (e.pathParams.isEmpty()) "" else " params=" + e.pathParams.joinToString(",") { "${it.name}:${it.type ?: "-"}" }
        "${e.method} ${e.path} [${e.framework.name}] name=${e.routeName ?: "-"} handler=${e.handler.qualifiedName} @${e.source}$params$problems"
    }

    protected companion object {
        const val TEST_DATA = "src/test/testData/django"
    }
}
