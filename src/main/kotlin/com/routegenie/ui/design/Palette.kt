package com.routegenie.ui.design

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.routegenie.model.HttpMethod
import com.routegenie.ui.design.color.PaletteSpec
import com.routegenie.ui.design.color.Swatch
import com.routegenie.ui.design.color.ToneSpec
import java.awt.Color

/** HTTP status classes of the response viewer; [of] maps a code, anything outside 100..599 is [UNKNOWN]. */
enum class StatusClass {
    INFORMATIONAL, SUCCESS, REDIRECT, CLIENT_ERROR, SERVER_ERROR, UNKNOWN;

    companion object {
        fun of(code: Int): StatusClass = when (code) {
            in 100..199 -> INFORMATIONAL
            in 200..299 -> SUCCESS
            in 300..399 -> REDIRECT
            in 400..499 -> CLIENT_ERROR
            in 500..599 -> SERVER_ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * A hue for a badge, pill, dot or outline. [fill] is the vivid color (dots, outlines, the tinted background) and [text] the
 * variant that stays readable on the surface and on the tint of [fill]. Both are JBColors, so they follow the theme at paint time.
 */
class Tone internal constructor(val name: String, val fill: JBColor, val text: JBColor)

/**
 * Every color of the window as a JBColor. The numbers live in [PaletteSpec]; this object only binds them:
 *  - neutrals are read from the running IDE theme each time they are used (Dracula, Material, New or Classic UI all apply);
 *  - tones (method, status, semantic hues) are our own light/dark pairs that a theme may override with `RouteGenie.<name>`.
 * Nothing in the window types a color anywhere else; CentralizationTest enforces that.
 */
object Palette {
    /** Theme key prefix of the overridable tones; documented in docs/DESIGN.md. */
    const val THEME_KEY_PREFIX = "RouteGenie."

    private val registry = LinkedHashMap<String, JBColor>()

    private fun inherit(swatch: Swatch, source: () -> Color): JBColor =
        JBColor.lazy { source() }.also { registry[swatch.name] = it }

    private fun own(swatch: Swatch): JBColor =
        JBColor.namedColor(THEME_KEY_PREFIX + swatch.name, JBColor(swatch.light, swatch.dark)).also { registry[swatch.name] = it }

    private fun tone(spec: ToneSpec) = Tone(spec.name, own(spec.fill), own(spec.text))

    // --- Neutrals, inherited from the IDE theme -----------------------------------------------------------------------
    /** Background of lists and trees: where rows are drawn. */
    val surface: JBColor = inherit(PaletteSpec.SURFACE) { UIUtil.getTreeBackground() }

    /** Background of chrome around the content: toolbar, status line, headers. */
    val raised: JBColor = inherit(PaletteSpec.RAISED) { UIUtil.getPanelBackground() }
    val hover: JBColor = inherit(PaletteSpec.HOVER) { JBUI.CurrentTheme.List.Hover.background(true) }
    val selection: JBColor = inherit(PaletteSpec.SELECTION) { UIUtil.getTreeSelectionBackground(true) }
    val selectionText: JBColor = inherit(PaletteSpec.SELECTION_TEXT) { UIUtil.getTreeSelectionForeground(true) }
    val border: JBColor = inherit(PaletteSpec.BORDER) { JBColor.border() }
    val textPrimary: JBColor = inherit(PaletteSpec.TEXT) { UIUtil.getLabelForeground() }
    val textSecondary: JBColor = inherit(PaletteSpec.TEXT_SECONDARY) { UIUtil.getContextHelpForeground() }
    val textDisabled: JBColor = inherit(PaletteSpec.TEXT_DISABLED) { UIUtil.getLabelDisabledForeground() }

    /** Links and the emphasis of interactive text. */
    val accent: JBColor = inherit(PaletteSpec.ACCENT) { JBUI.CurrentTheme.Link.Foreground.ENABLED }
    val focus: JBColor = inherit(PaletteSpec.FOCUS) { JBUI.CurrentTheme.Focus.focusColor() }

    // --- Tones, defined once; methods, statuses and semantics point at them -------------------------------------------------
    private val green = tone(PaletteSpec.GREEN)
    private val blue = tone(PaletteSpec.BLUE)
    private val orange = tone(PaletteSpec.ORANGE)
    private val purple = tone(PaletteSpec.PURPLE)
    private val red = tone(PaletteSpec.RED)
    private val slate = tone(PaletteSpec.SLATE)
    private val teal = tone(PaletteSpec.TEAL)
    private val indigo = tone(PaletteSpec.INDIGO)
    private val cyan = tone(PaletteSpec.CYAN)

    /** Tags and other plain labels: secondary text tinted by itself. */
    val neutral: Tone = Tone("neutral", textSecondary, textSecondary)

    val success: Tone = green
    val warning: Tone = orange
    val danger: Tone = red
    val info: Tone = blue

    /** Hue of an HTTP verb; ANY is the one painted as an outline (see MethodBadge) because it means "every method". */
    fun method(method: HttpMethod): Tone = when (method) {
        HttpMethod.GET -> green
        HttpMethod.POST -> blue
        HttpMethod.PUT -> orange
        HttpMethod.PATCH -> purple
        HttpMethod.DELETE -> red
        HttpMethod.HEAD -> teal
        HttpMethod.OPTIONS -> indigo
        HttpMethod.TRACE -> slate
        HttpMethod.ANY -> cyan
    }

    fun status(statusClass: StatusClass): Tone = when (statusClass) {
        StatusClass.INFORMATIONAL -> slate
        StatusClass.SUCCESS -> green
        StatusClass.REDIRECT -> cyan
        StatusClass.CLIENT_ERROR -> orange
        StatusClass.SERVER_ERROR -> red
        StatusClass.UNKNOWN -> slate
    }

    fun status(code: Int): Tone = status(StatusClass.of(code))

    /** The color registered under a [PaletteSpec] swatch name, for tests and the gallery legend. */
    fun colorOf(swatchName: String): JBColor? = registry[swatchName]

    /** Names of all registered colors, in declaration order. */
    fun names(): List<String> = registry.keys.toList()

    // --- State colors that depend on whether the list has focus --------------------------------------------------------------
    fun selectionBackground(focused: Boolean): Color = UIUtil.getTreeSelectionBackground(focused)
    fun selectionForeground(focused: Boolean): Color = UIUtil.getTreeSelectionForeground(focused)

    private val mutedFocused: JBColor = JBColor.lazy { mixSelection(true) }
    private val mutedInactive: JBColor = JBColor.lazy { mixSelection(false) }

    private fun mixSelection(focused: Boolean): Color =
        ColorUtil.mix(selectionForeground(focused), selectionBackground(focused), DesignTokens.State.SELECTED_MUTED_MIX)

    /**
     * Secondary text on a selected row: the selection foreground pulled towards the selection background, so it stays readable
     * whatever color the theme gives a selection (a strong blue in Classic UI, a pale one in New UI). Lazy, so cached text
     * attributes built from it still follow a theme switch.
     */
    fun selectionMuted(focused: Boolean): JBColor = if (focused) mutedFocused else mutedInactive
}
