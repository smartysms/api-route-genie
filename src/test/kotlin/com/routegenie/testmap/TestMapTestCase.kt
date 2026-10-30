package com.routegenie.testmap

import com.routegenie.discovery.ProjectPaths
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.SourceRef
import com.routegenie.testing.PythonPlatformTestCase
import com.routegenie.testmap.psi.PyTestFactsExtractor

/**
 * Runs the real extractor over Python files created in the light project and the pure linker over hand-made endpoints.
 * Library imports do not resolve in the light project, so the tests prove that recognition goes by import text only.
 */
abstract class TestMapTestCase : PythonPlatformTestCase() {
    protected fun ep(
        method: HttpMethod,
        path: String,
        framework: Framework = Framework.FASTAPI,
        name: String? = null,
        types: Map<String, String> = emptyMap(),
        ordinal: Int = 0,
    ): Endpoint {
        val params = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""").findAll(path).map { PathParam(it.groupValues[1], types[it.groupValues[1]]) }.toList()
        return Endpoint(
            method, path, framework, Handler(name ?: "handler", "app.${name ?: "handler"}"), SourceRef("app/main.py", 0, 1),
            routeName = name, pathParams = params, ordinal = ordinal,
        )
    }

    protected fun facts(vararg files: Pair<String, String>): List<LocatedTestFacts> = files.mapNotNull { (path, text) ->
        val py = pythonFile(path, text.trimIndent() + "\n")
        val extracted = PyTestFactsExtractor.extract(py) ?: return@mapNotNull null
        val relative = ProjectPaths.relative(project, py.virtualFile)
        LocatedTestFacts(relative, extracted, ProjectPaths.sourceRoot(project, py.virtualFile, relative))
    }

    protected fun link(files: List<Pair<String, String>>, endpoints: List<Endpoint>): TestIndex =
        TestLinker.link(facts(*files.toTypedArray()), endpoints)

    protected fun link(path: String, text: String, vararg endpoints: Endpoint): TestIndex = link(listOf(path to text), endpoints.toList())

    protected fun TestIndex.mappedLines(): List<String> = byEndpoint.entries.flatMap { (key, calls) ->
        calls.map { c -> "${c.verb} ${key.split('|')[2]} <- ${c.test.displayName}${if (c.viaRedirect) " (redirect)" else ""}" }
    }.sorted()

    protected fun TestIndex.reasons(): List<String> =
        unattributed.map { "${it.reason.name.removePrefix("TEST_")}${it.detail?.let { d -> ":$d" } ?: ""}" }.sorted()

    protected fun TestIndex.single(): TestCall = byEndpoint.values.flatten().single()
}
