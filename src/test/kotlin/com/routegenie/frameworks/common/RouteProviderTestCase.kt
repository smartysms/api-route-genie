package com.routegenie.frameworks.common

import com.intellij.psi.PsiManager
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.testing.PythonPlatformTestCase
import java.io.File

/**
 * Runs the whole provider pipeline (candidates, extraction, linking) over files created in the light project, so the
 * tests see exactly what the engine would see. Library imports do not resolve here; the providers must not need them.
 */
abstract class RouteProviderTestCase(private val provider: RouteProvider) : PythonPlatformTestCase() {
    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    protected fun add(path: String, text: String) {
        pythonFile(path, text.trimIndent() + "\n")
    }

    protected fun discover(vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> add(path, text) }
        return discoverAll()
    }

    protected fun discoverFixture(directory: String): LinkResult {
        myFixture.copyDirectoryToProject(directory, "")
        return discoverAll()
    }

    protected fun discoverAll(): LinkResult {
        val manager = PsiManager.getInstance(project)
        val facts = provider.findCandidates(project)
            .sortedBy { it.path }
            .mapNotNull { manager.findFile(it) }
            .mapNotNull { provider.extract(it) }
        return provider.link(facts)
    }

    /** `GET /path` lines, sorted, for exact list assertions. */
    protected fun routes(result: LinkResult): List<String> =
        result.endpoints.map { "${it.method} ${it.path}" }.sorted()

    protected fun find(result: LinkResult, method: String, path: String): Endpoint =
        result.endpoints.singleOrNull { it.method.name == method && it.path == path }
            ?: error("no unique $method $path in ${routes(result)}")

    protected fun codes(problems: List<Diagnostic>): List<DiagnosticCode> = problems.map { it.code }

    /** One golden line per endpoint: method, path, handler, then tags, deprecation and problem codes when present. */
    protected fun golden(result: LinkResult): List<String> = result.endpoints.map { e ->
        buildString {
            append(e.method).append(' ').append(e.path).append(" -> ").append(e.handler.qualifiedName)
            if (e.routeName != null && e.routeName != e.handler.name) append(" name=").append(e.routeName)
            if (e.tags.isNotEmpty()) append(" tags=").append(e.tags.joinToString(","))
            if (e.deprecated) append(" deprecated")
            if (e.problems.isNotEmpty()) append(" PARTIAL=").append(e.problems.joinToString(",") { it.code.name })
        }
    }.sorted()

    protected fun expectedLines(path: String): List<String> =
        File(testDataPath, path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.sorted()
}
