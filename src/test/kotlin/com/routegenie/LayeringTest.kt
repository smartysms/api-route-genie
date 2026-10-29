package com.routegenie

import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pure layers must stay free of the IDE and Swing, so their tests run in milliseconds without a platform. */
class LayeringTest {
    private val pureLayers = listOf("model", "client", "openapi")
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

    @Test
    fun `the model layer exists`() {
        assertTrue(File(ProjectFiles.mainKotlin, "com/routegenie/model").isDirectory)
    }
}
