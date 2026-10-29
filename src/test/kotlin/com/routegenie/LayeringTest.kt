package com.routegenie

import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pure layers must stay free of the IDE and Swing, so their tests run in milliseconds without a platform. */
class LayeringTest {
    private val pureLayers = listOf("model", "client", "openapi", "ui/model", "schema/model", "json")
    private val forbidden = listOf("com.intellij", "com.jetbrains", "java.awt", "javax.swing")

    @Test
    fun `pure layers import nothing from the IDE or Swing`() {
        val offenders = pureLayers.map { File(ProjectFiles.mainKotlin, "com/routegenie/$it") }.filter { it.isDirectory }
            .flatMap { ProjectFiles.kotlinSources(it) }
            .flatMap { file ->
                file.readLines().filter { it.startsWith("import ") && forbidden.any { prefix -> it.removePrefix("import ").startsWith(prefix) } }
                    .map { "${ProjectFiles.relative(file)}: $it" }
            }
        assertTrue("Pure layers must not import the IDE:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    /** The linker side of discovery: facts, expression evaluation, module resolution and the three linkers. */
    private val pureLinkerFiles = listOf(
        "discovery/core/Expr.kt", "discovery/core/ExprEvaluator.kt", "discovery/core/ModuleModel.kt", "discovery/core/ModuleWalk.kt",
        "discovery/core/FactsCache.kt",
        "frameworks/common/PyFacts.kt", "frameworks/common/PyResolver.kt", "frameworks/common/PyModuleIndex.kt",
        "frameworks/common/LinkSupport.kt", "frameworks/common/Primitives.kt", "frameworks/common/PyExtractionSpec.kt",
        "frameworks/django/DjangoFacts.kt", "frameworks/django/DjangoFile.kt", "frameworks/django/DjangoLinker.kt",
        "frameworks/django/RouteText.kt", "frameworks/django/DjangoCatalog.kt",
        "frameworks/fastapi/FastApiLinker.kt", "frameworks/fastapi/FastApiCatalog.kt",
        "frameworks/flask/FlaskLinker.kt", "frameworks/flask/FlaskCatalog.kt",
    )

    @Test
    fun `the pure linker imports nothing from the IDE or Swing`() {
        val missing = pureLinkerFiles.filter { !File(ProjectFiles.mainKotlin, "com/routegenie/$it").isFile }
        assertTrue("These files moved: $missing (update the list)", missing.isEmpty())
        val offenders = pureLinkerFiles.map { File(ProjectFiles.mainKotlin, "com/routegenie/$it") }.flatMap { file ->
            file.readLines().filter { it.startsWith("import ") && forbidden.any { prefix -> it.removePrefix("import ").startsWith(prefix) } }
                .map { "${ProjectFiles.relative(file)}: $it" }
        }
        assertTrue("The pure linker must not import the IDE:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `the model layer exists`() {
        assertTrue(File(ProjectFiles.mainKotlin, "com/routegenie/model").isDirectory)
    }
}
