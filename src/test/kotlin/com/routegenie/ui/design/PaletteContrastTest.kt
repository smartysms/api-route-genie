package com.routegenie.ui.design

import com.routegenie.ui.design.color.ColorMath
import com.routegenie.ui.design.color.PaletteSpec
import com.routegenie.ui.design.color.ReferenceSurface
import com.routegenie.ui.design.color.Swatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.routegenie.testing.ProjectFiles
import java.io.File
import java.util.Locale

/**
 * The WCAG contract of the palette, on the numbers alone (no IDE, no Swing). If a pair fails, change the palette in
 * [PaletteSpec]; the thresholds are not negotiable. Each run also writes the measured worst ratios to
 * build/reports/routegenie/design/contrast.txt so the design review can quote them.
 */
class PaletteContrastTest {
    private val failures = ArrayList<String>()

    private fun ratio(a: Int, b: Int) = ColorMath.contrast(a, b)

    private fun format(value: Double) = String.format(Locale.ROOT, "%.2f", value)

    private fun check(what: String, fg: Int, bg: Int, minimum: Double, surface: String) {
        val ratio = ratio(fg, bg)
        if (ratio < minimum) {
            failures += "$what ${ColorMath.toHex(fg)} on $surface ${ColorMath.toHex(bg)} is ${format(ratio)}:1, needs $minimum:1"
        }
    }

    private fun pick(swatch: Swatch, surface: ReferenceSurface) = swatch.of(surface.dark)

    private fun assertNoFailures() {
        assertTrue("Contrast failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `every swatch has a light and a dark value and the two differ`() {
        val bad = PaletteSpec.ALL_SWATCHES.filter { it.light !in 0..0xFFFFFF || it.dark !in 0..0xFFFFFF || it.light == it.dark }
        assertTrue("Swatches without two distinct values: ${bad.map { it.name }}", bad.isEmpty())
        val names = PaletteSpec.ALL_SWATCHES.map { it.name }
        assertEquals("duplicate swatch names", names.size, names.toSet().size)
    }

    @Test
    fun `text colors reach 4,5 to 1 on every reference surface`() {
        val texts = listOf(PaletteSpec.TEXT, PaletteSpec.TEXT_SECONDARY, PaletteSpec.ACCENT) + PaletteSpec.TONES.map { it.text }
        for (surface in PaletteSpec.REFERENCES) {
            for (text in texts) check(text.name, pick(text, surface), surface.rgb, PaletteSpec.MIN_TEXT_CONTRAST, surface.name)
        }
        assertNoFailures()
    }

    @Test
    fun `fills and focus reach 3 to 1 on every reference surface`() {
        val fills = listOf(PaletteSpec.FOCUS) + PaletteSpec.TONES.map { it.fill }
        for (surface in PaletteSpec.REFERENCES) {
            for (fill in fills) check(fill.name, pick(fill, surface), surface.rgb, PaletteSpec.MIN_FILL_CONTRAST, surface.name)
        }
        assertNoFailures()
    }

    @Test
    fun `tone text stays readable on its own tint over every reference surface`() {
        for (surface in PaletteSpec.REFERENCES) {
            for (tone in PaletteSpec.TONES) {
                val tint = ColorMath.blend(pick(tone.fill, surface), surface.rgb, PaletteSpec.TINT_ALPHA)
                check("${tone.name}.text on its tint", pick(tone.text, surface), tint, PaletteSpec.MIN_TEXT_CONTRAST, surface.name)
            }
        }
        assertNoFailures()
    }

    @Test
    fun `text on the palette's own raised, hover and selection surfaces`() {
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            val raised = PaletteSpec.RAISED.of(dark)
            val hover = PaletteSpec.HOVER.of(dark)
            val selection = PaletteSpec.SELECTION.of(dark)
            for ((name, surface) in listOf("raised" to raised, "hover" to hover)) {
                check("text on $name ($mode)", PaletteSpec.TEXT.of(dark), surface, PaletteSpec.MIN_TEXT_CONTRAST, name)
                check("secondary on $name ($mode)", PaletteSpec.TEXT_SECONDARY.of(dark), surface, PaletteSpec.MIN_TEXT_CONTRAST, name)
                check("accent on $name ($mode)", PaletteSpec.ACCENT.of(dark), surface, PaletteSpec.MIN_TEXT_CONTRAST, name)
                for (tone in PaletteSpec.TONES) {
                    val tint = ColorMath.blend(tone.fill.of(dark), surface, PaletteSpec.TINT_ALPHA)
                    check("${tone.name} on tint over $name ($mode)", tone.text.of(dark), tint, PaletteSpec.MIN_TEXT_CONTRAST, name)
                }
            }
            check("selection text ($mode)", PaletteSpec.SELECTION_TEXT.of(dark), selection, PaletteSpec.MIN_TEXT_CONTRAST, "selection")
            check("text on selection ($mode)", PaletteSpec.TEXT.of(dark), selection, PaletteSpec.MIN_TEXT_CONTRAST, "selection")
        }
        assertNoFailures()
    }

    @Test
    fun `only decorative colors are exempt and they are named`() {
        assertEquals(setOf("textDisabled", "border"), PaletteSpec.EXEMPT_FROM_CONTRAST)
        val known = PaletteSpec.ALL_SWATCHES.map { it.name }.toSet()
        assertTrue(known.containsAll(PaletteSpec.EXEMPT_FROM_CONTRAST))
    }

    @Test
    fun `the reference list covers light, New UI dark, Dracula, One Dark and Material Oceanic`() {
        val rgbs = PaletteSpec.REFERENCES.map { it.rgb }.toSet()
        listOf(0xFFFFFF, 0xF7F8FA, 0x2B2D30, 0x1E1F22, 0x282A36, 0x282C34, 0x263238).forEach { assertTrue(ColorMath.toHex(it), it in rgbs) }
        assertTrue(PaletteSpec.REFERENCES.any { !it.dark } && PaletteSpec.REFERENCES.any { it.dark })
    }

    @Test
    fun `a method badge on a selected row keeps its hue and its lettering stays readable on every sample selection`() {
        val selections = ThemeSamples.ALL.mapNotNull { t ->
            val bg = t.overrides["Tree.selectionBackground"] ?: return@mapNotNull null
            val fg = t.overrides["Tree.selectionForeground"] ?: return@mapNotNull null
            Triple(t.id, bg, fg)
        }
        assertTrue(selections.size >= 4)
        for ((id, bg, fg) in selections) {
            for (tone in PaletteSpec.TONES) {
                val dark = ratio(bg, 0xFFFFFF) < ratio(bg, 0x000000)
                val tinted = ColorMath.blend(tone.fill.of(dark), bg, PaletteSpec.SELECTED_TINT_ALPHA)
                check("selected badge lettering over ${tone.name}", fg, tinted, PaletteSpec.MIN_TEXT_CONTRAST, "selection of $id")
                assertTrue("the tint must be visible against the plain selection", ColorMath.blend(tone.fill.of(dark), bg, PaletteSpec.SELECTED_TINT_ALPHA) != bg)
            }
        }
        assertNoFailures()
    }

    /** Writes the measured worst ratios (per swatch and mode) so the review can quote them; nothing is asserted here. */
    @Test
    fun `measured worst ratios are written to the report`() {
        val lines = ArrayList<String>()
        for (swatch in PaletteSpec.ALL_SWATCHES.filter { it.name !in PaletteSpec.EXEMPT_FROM_CONTRAST }) {
            lines += "${swatch.name}: worst on light ${format(worst(swatch, false))}:1, worst on dark ${format(worst(swatch, true))}:1"
        }
        for (tone in PaletteSpec.TONES) {
            val tintWorst = { dark: Boolean ->
                PaletteSpec.REFERENCES.filter { it.dark == dark }.minOf {
                    ratio(tone.text.of(dark), ColorMath.blend(tone.fill.of(dark), it.rgb, PaletteSpec.TINT_ALPHA))
                }
            }
            lines += "${tone.name}.text on its tint: worst on light ${format(tintWorst(false))}:1, worst on dark ${format(tintWorst(true))}:1"
        }
        val dir = File(ProjectFiles.root, "build/reports/routegenie/design")
        dir.mkdirs()
        File(dir, "contrast.txt").writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()), Charsets.US_ASCII)
        assertTrue(lines.isNotEmpty())
    }

    private fun worst(swatch: Swatch, dark: Boolean): Double =
        PaletteSpec.REFERENCES.filter { it.dark == dark }.minOf { ratio(swatch.of(dark), it.rgb) }
}
