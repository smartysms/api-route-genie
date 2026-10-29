package com.routegenie.ui.design

import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "No hardcoded colors outside the tokens": the window's code takes every color from [Palette] and every size from
 * [DesignTokens]. This scans the UI sources for color constructors, named AWT colors and 6 digit hex literals.
 */
class DesignCentralizationTest {
    private val uiRoot = File(ProjectFiles.mainKotlin, "com/routegenie/ui")

    /** Nothing is exempt any more: the request panel and the JSON viewer use the palette too. */
    private val colorConstructor = Regex("""(^|[^A-Za-z0-9_])(java\.awt\.)?(JB)?Color\s*\(""")
    private val namedColor = Regex("""(^|[^A-Za-z0-9_])(java\.awt\.)?(JB)?Color\.(?!getHSBColor|decode)[A-Za-z_]+\b""")
    private val grayHelper = Regex("""(^|[^A-Za-z0-9_])Gray\.[A-Za-z_0-9]+""")
    private val hexColor = Regex("""\b0x[0-9a-fA-F]{6}\b""")

    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private fun scanned(): List<File> = ProjectFiles.kotlinSources(uiRoot).filter { file ->
        val relative = ProjectFiles.relative(file).removePrefix("src/main/kotlin/com/routegenie/")
        !relative.startsWith("ui/design/")
    }

    @Test
    fun `no color literal or named color outside ui design`() {
        val offenders = scanned().flatMap { file ->
            stripComments(file.readText()).lines().withIndex().filter { (_, line) ->
                colorConstructor.containsMatchIn(line) || namedColor.containsMatchIn(line) || grayHelper.containsMatchIn(line) || hexColor.containsMatchIn(line)
            }.map { (i, line) -> "${ProjectFiles.relative(file)}:${i + 1}: ${line.trim()}" }
        }
        assertTrue("Colors belong in ui/design (Palette, PaletteSpec):\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    @Test
    fun `the scan sees the window files and the request panel`() {
        val names = scanned().map { it.name }
        assertTrue(names.containsAll(listOf("EndpointTreeView.kt", "EndpointsPanel.kt", "EndpointDetailsPanel.kt", "UiSupport.kt")))
        assertTrue("ui/request must be scanned", scanned().any { ProjectFiles.relative(it).contains("/ui/request/") })
    }

    @Test
    fun `the scan itself catches what it is meant to catch`() {
        assertTrue(colorConstructor.containsMatchIn("val c = Color(0x112233)"))
        assertTrue(colorConstructor.containsMatchIn("JBColor(0x112233, 0x445566)"))
        assertTrue(colorConstructor.containsMatchIn("java.awt.Color(1, 2, 3)"))
        assertTrue(namedColor.containsMatchIn("g.color = Color.WHITE"))
        assertTrue(grayHelper.containsMatchIn("Gray._80"))
        assertTrue(hexColor.containsMatchIn("val x = 0xFF00FF"))
        assertTrue(!colorConstructor.containsMatchIn("val c = Palette.surface"))
        assertTrue(!hexColor.containsMatchIn("val mask = 0xFF"))
    }

    @Test
    fun `the pure color package imports nothing from Swing or the IDE`() {
        val pure = File(ProjectFiles.mainKotlin, "com/routegenie/ui/design/color")
        val forbidden = listOf("com.intellij", "com.jetbrains", "java.awt", "javax.swing")
        val offenders = ProjectFiles.kotlinSources(pure).flatMap { file ->
            file.readLines().filter { it.startsWith("import ") && forbidden.any { prefix -> it.removePrefix("import ").startsWith(prefix) } }
                .map { "${ProjectFiles.relative(file)}: $it" }
        }
        assertTrue("The color math must stay testable without Swing:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }
}
