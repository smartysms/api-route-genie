package com.routegenie

import com.routegenie.model.DiagnosticCode
import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Enforces "one place per fact": identifiers, product names and UI strings are defined once and referenced everywhere else. */
class CentralizationTest {
    private val bundleFile = File(ProjectFiles.mainResources, "messages/RouteGenieBundle.properties")
    private val sources = ProjectFiles.kotlinSources().associateWith { it.readText() }

    @Test
    fun `no identifier from Ids is typed again in another source file`() {
        val offenders = sources.filterKeys { it.name != "Ids.kt" }.flatMap { (file, text) ->
            Ids.ALL.filter { text.contains("\"$it\"") }.map { "${ProjectFiles.relative(file)} repeats \"$it\"" }
        }
        assertTrue("Use the constant from Ids instead:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `no product name is typed in a source file`() {
        val gradle = ProjectFiles.gradleProperties()
        val names = listOf("productName", "productShortName", "productDir", "repoSlug").map { gradle.getProperty(it) }
        val offenders = sources.flatMap { (file, text) ->
            names.filter { text.contains("\"$it\"") }.map { "${ProjectFiles.relative(file)} repeats \"$it\"" }
        }
        assertTrue("Read the name from Branding instead:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `every message key used in code exists in the bundle`() {
        val keys = ProjectFiles.properties(bundleFile).stringPropertyNames()
        val used = sources.flatMap { (file, text) ->
            MESSAGE_CALL.findAll(text).map { it.groupValues[1] to ProjectFiles.relative(file) }.toList()
        }
        val missing = used.filter { it.first !in keys }.map { "${it.second}: ${it.first}" }
        assertTrue("Keys missing in RouteGenieBundle.properties:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun `every key in the bundle is used`() {
        val keys = ProjectFiles.properties(bundleFile).stringPropertyNames()
        val derived = DiagnosticCode.entries.map { it.bundleKey }.toSet()
        val allText = sources.values.joinToString("\n")
        val dead = keys.filter { it !in derived && !allText.contains("\"$it\"") }.sorted()
        assertTrue("Unused keys in RouteGenieBundle.properties:\n${dead.joinToString("\n")}", dead.isEmpty())
    }

    @Test
    fun `every diagnostic code has a message`() {
        val keys = ProjectFiles.properties(bundleFile).stringPropertyNames()
        val missing = DiagnosticCode.entries.map { it.bundleKey }.filter { it !in keys }
        assertTrue("Add these keys to RouteGenieBundle.properties: $missing", missing.isEmpty())
    }

    private companion object {
        val MESSAGE_CALL = Regex("""message\(\s*"([^"]+)"""")
    }
}
