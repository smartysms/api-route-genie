package com.routegenie.ui.design.color

/** One color in its light and its dark value (0xRRGGBB). The pair is what [com.routegenie.ui.design.Palette] turns into a JBColor. */
class Swatch(val name: String, val light: Int, val dark: Int) {
    fun of(dark: Boolean): Int = if (dark) this.dark else light
}

/**
 * A hue used for badges, pills, dots and outlines. [fill] is the vivid color (outline, dot, tinted background) and must be
 * visible on every surface (3:1); [text] is the readable variant for text on the surface and on the tinted fill (4.5:1).
 */
class ToneSpec(val name: String, val fill: Swatch, val text: Swatch)

/** A surface a theme can show behind the window; the palette is checked against all of them. */
class ReferenceSurface(val name: String, val rgb: Int, val dark: Boolean)

/**
 * The numbers of the palette, and nothing else: no Swing, so tests can check them without an IDE. This file is the one place
 * a color value is typed; [com.routegenie.ui.design.Palette] binds each entry to a JBColor and nothing outside ui/design may
 * type a color.
 *
 * Two kinds of entries:
 *  - INHERITED neutrals come from the running IDE theme at paint time (so Dracula, Material and any installed theme apply).
 *    The pair stored here is the platform's default for IntelliJ Light and New UI Dark and serves as the stand-in in tests.
 *  - OWN tones exist because the platform has no color with a guaranteed contrast on a tinted fill. They can be overridden by a
 *    theme through the key `RouteGenie.<name>` (see docs/DESIGN.md).
 */
object PaletteSpec {
    /** How strongly a tone's fill is mixed into the background for a tinted badge or pill (SrcOver alpha). */
    const val TINT_ALPHA = 0.16

    /** Tint of a method badge on a selected row: stronger, so the verb keeps its hue on the theme's selection color. */
    const val SELECTED_TINT_ALPHA = 0.34

    /** WCAG AA for normal text, and for non-text graphics such as dots, outlines and icons. */
    const val MIN_TEXT_CONTRAST = 4.5
    const val MIN_FILL_CONTRAST = 3.0

    // --- Inherited neutrals -----------------------------------------------------------------------------------------
    val SURFACE = Swatch("surface", 0xFFFFFF, 0x2B2D30)
    val RAISED = Swatch("raised", 0xF7F8FA, 0x34363B)
    val HOVER = Swatch("hover", 0xF2F3F7, 0x3A3D42)
    val SELECTION = Swatch("selection", 0xD4E2FF, 0x2E436E)
    val SELECTION_TEXT = Swatch("selectionText", 0x1E1F22, 0xDFE1E5)
    val BORDER = Swatch("border", 0xEBECF0, 0x43454A)
    val TEXT = Swatch("text", 0x1E1F22, 0xDFE1E5)
    val TEXT_SECONDARY = Swatch("textSecondary", 0x5F6368, 0xA9ADB5)
    val TEXT_DISABLED = Swatch("textDisabled", 0x9C9FA6, 0x6F737A)
    val ACCENT = Swatch("accent", 0x2A58C8, 0x86AEFF)
    val FOCUS = Swatch("focus", 0x3574F0, 0x548AF7)

    val INHERITED: List<Swatch> =
        listOf(SURFACE, RAISED, HOVER, SELECTION, SELECTION_TEXT, BORDER, TEXT, TEXT_SECONDARY, TEXT_DISABLED, ACCENT, FOCUS)

    // --- Own tones: one hue each, defined once; methods, statuses and semantics only point at them -------------------------
    private fun tone(name: String, fillLight: Int, fillDark: Int, textLight: Int, textDark: Int) =
        ToneSpec(name, Swatch("$name.fill", fillLight, fillDark), Swatch("$name.text", textLight, textDark))

    val GREEN = tone("green", 0x2C964F, 0x30A456, 0x206F3A, 0x61D186)
    val BLUE = tone("blue", 0x3D85E3, 0x5192E6, 0x1B60BB, 0x9BC0F1)
    val ORANGE = tone("orange", 0xCD6911, 0xE07212, 0x974D0C, 0xF3AB6D)
    val PURPLE = tone("purple", 0x9D61D1, 0xAD7BD9, 0x8439C6, 0xCFB2E9)
    val RED = tone("red", 0xE0525B, 0xE56972, 0xBA212B, 0xF0AAAF)
    val SLATE = tone("slate", 0x758697, 0x8393A2, 0x566371, 0xB5BEC7)
    val TEAL = tone("teal", 0x1F9384, 0x22A291, 0x176D62, 0x2DD2BC)
    val INDIGO = tone("indigo", 0x716BC7, 0x8E89D2, 0x5851BD, 0xBCB9E4)
    val CYAN = tone("cyan", 0x1891AA, 0x1A9DB7, 0x12697B, 0x48CBE5)

    val TONES: List<ToneSpec> = listOf(GREEN, BLUE, ORANGE, PURPLE, RED, SLATE, TEAL, INDIGO, CYAN)

    /** Every swatch of the palette, for "has a light and a dark value" checks and the gallery legend. */
    val ALL_SWATCHES: List<Swatch> = INHERITED + TONES.flatMap { listOf(it.fill, it.text) }

    // --- Reference surfaces ---------------------------------------------------------------------------------------------
    val REFERENCES: List<ReferenceSurface> = listOf(
        ReferenceSurface("Light white", 0xFFFFFF, dark = false),
        ReferenceSurface("Light New UI panel", 0xF7F8FA, dark = false),
        ReferenceSurface("Light classic panel", 0xF2F2F2, dark = false),
        ReferenceSurface("New UI dark tool window", 0x2B2D30, dark = true),
        ReferenceSurface("New UI dark editor", 0x1E1F22, dark = true),
        ReferenceSurface("Darcula editor", 0x2B2B2B, dark = true),
        ReferenceSurface("Darcula classic panel", 0x3C3F41, dark = true),
        ReferenceSurface("Dracula", 0x282A36, dark = true),
        ReferenceSurface("One Dark", 0x282C34, dark = true),
        ReferenceSurface("Material Oceanic", 0x263238, dark = true),
    )

    /**
     * Pairs that are not checked against every reference because they do not carry meaning: disabled text (WCAG exempts it)
     * and hairline borders (decorative, the content is readable without them).
     */
    val EXEMPT_FROM_CONTRAST: Set<String> = setOf(TEXT_DISABLED.name, BORDER.name)
}
