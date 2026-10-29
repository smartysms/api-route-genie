package com.routegenie.ui.design

import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.routegenie.model.HttpMethod
import com.routegenie.ui.design.DesignTokens.Badge
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.DesignTokens.State
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JComponent

/** How a badge is drawn: a tinted fill with text, or only an outline (used for ANY, "every method"). */
internal enum class BadgeLook { TINTED, OUTLINED }

/**
 * The one routine that draws a rounded label: method badges, status pills and chips all go through it, so their geometry,
 * tint and text placement cannot drift apart. It restores every Graphics setting it touches and allocates nothing, because the
 * tree calls it for each visible row on every repaint.
 */
internal object BadgePainter {
    /** [fill] drives the tint or outline, [text] the lettering; both are resolved by the caller so selected rows can recolor. */
    fun paint(
        g: Graphics2D, x: Int, y: Int, w: Int, h: Int, arc: Int, label: String, font: Font, fill: Color, text: Color, look: BadgeLook,
        tint: java.awt.AlphaComposite = State.TINT,
    ) {
        val oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldStrokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL)
        val oldFont = g.font
        val oldColor = g.color
        val oldComposite = g.composite
        val oldStroke = g.stroke
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = fill
            when (look) {
                BadgeLook.TINTED -> {
                    g.composite = tint
                    g.fillRoundRect(x, y, w, h, arc, arc)
                }
                BadgeLook.OUTLINED -> {
                    // A line is centered on its path: shifting by half a pixel puts it inside the box instead of half outside it,
                    // which a clipped component would cut off on its top and left edges.
                    g.composite = State.OUTLINE
                    g.stroke = DesignTokens.Stroke.hairline()
                    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                    g.translate(HALF_PIXEL, HALF_PIXEL)
                    g.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
                    g.translate(-HALF_PIXEL, -HALF_PIXEL)
                }
            }
            g.composite = oldComposite
            g.font = font
            g.color = text
            val fm = g.fontMetrics
            g.drawString(label, x + (w - fm.stringWidth(label)) / 2, baseline(y, h, fm.ascent, fm.descent))
        } finally {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, oldStrokeControl)
            g.font = oldFont
            g.color = oldColor
            g.composite = oldComposite
            g.stroke = oldStroke
        }
    }

    /** Baseline that centers capital letters (cap height is ascent minus descent in the UI fonts) inside a box of height [h]. */
    fun baseline(y: Int, h: Int, ascent: Int, descent: Int): Int = y + (h + (ascent - descent)) / 2

    fun applyTextHints(g: Graphics) {
        GraphicsUtil.setupAntialiasing(g)
    }

    private const val HALF_PIXEL = 0.5
}

/** The state of the row a badge sits on; selected rows recolor the badge from the selection colors the theme chose. */
enum class BadgeState { NORMAL, SELECTED, SELECTED_INACTIVE }

/**
 * The verb of an endpoint as a small fixed-width label: a tinted fill with the verb in the tone's text color, ANY as an outline.
 * All instances are cached (one per verb and state), so a row only picks one; they are safe to share between components.
 */
class MethodBadge private constructor(private val method: HttpMethod, private val state: BadgeState) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(Badge.WIDTH)
    override fun getIconHeight(): Int = JBUI.scale(Badge.HEIGHT)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val tone = Palette.method(method)
        val outlined = method == HttpMethod.ANY
        val fill: Color
        val text: Color
        when (state) {
            BadgeState.NORMAL -> {
                fill = tone.fill
                text = tone.text
            }
            // The lettering is the theme's own selection foreground (readable on whatever selection color the theme chose); the
            // verb keeps its hue as a stronger tint behind it. An outline has no fill to tint, so it takes the foreground.
            BadgeState.SELECTED -> {
                text = Palette.selectionForeground(true)
                fill = if (outlined) text else tone.fill
            }
            BadgeState.SELECTED_INACTIVE -> {
                text = Palette.selectionForeground(false)
                fill = if (outlined) text else tone.fill
            }
        }
        val look = if (outlined) BadgeLook.OUTLINED else BadgeLook.TINTED
        val tint = if (state == BadgeState.NORMAL) State.TINT else State.TINT_SELECTED
        BadgePainter.paint(g as Graphics2D, x, y, iconWidth, iconHeight, JBUI.scale(Badge.ARC), method.name, DesignTokens.Type.badge(), fill, text, look, tint)
    }

    companion object {
        private val CACHE: Array<Array<MethodBadge>> =
            Array(HttpMethod.entries.size) { m -> Array(BadgeState.entries.size) { s -> MethodBadge(HttpMethod.entries[m], BadgeState.entries[s]) } }

        fun of(method: HttpMethod, state: BadgeState = BadgeState.NORMAL): Icon = CACHE[method.ordinal][state.ordinal]
    }
}

/** Measures and draws chips; shared by the [Chip] component and by tree rows, which cannot host components. */
object ChipPainter {
    private fun padding(): Int = JBUI.scale(Badge.PADDING_X)

    fun height(): Int = JBUI.scale(Badge.CHIP_HEIGHT)

    fun width(g: Graphics2D, text: String): Int = g.getFontMetrics(DesignTokens.Type.badge()).stringWidth(text) + 2 * padding()

    fun width(c: Component, text: String): Int = c.getFontMetrics(DesignTokens.Type.badge()).stringWidth(text) + 2 * padding()

    /** Draws a chip with its left edge at [x] and its top at [y]; [tone] null is the neutral chip. Returns the width drawn. */
    fun paint(g: Graphics2D, x: Int, y: Int, text: String, tone: Tone?, selectedColor: Color? = null, fixedWidth: Int = -1): Int {
        // A component paints into the width its layout gave it; measuring again with the (possibly scaled) Graphics can differ by
        // a pixel and would push the outline over the component's edge, where it is clipped.
        val w = if (fixedWidth > 0) fixedWidth else width(g, text)
        val resolved = tone ?: Palette.neutral
        val fill: Color = selectedColor ?: resolved.fill
        val textColor: Color = selectedColor ?: resolved.text
        // A tag with no hue of its own is an outline: secondary text on the surface is already contrast-checked, a tint of grey on grey is not.
        val look = if (tone == null) BadgeLook.OUTLINED else BadgeLook.TINTED
        BadgePainter.paint(g, x, y, w, height(), JBUI.scale(Badge.ARC), text, DesignTokens.Type.badge(), fill, textColor, look)
        return w
    }
}

/** A short label such as a tag; an outline by default, tinted when given a [Tone]. Painted, so it is cheap in a list. */
class Chip(text: String, private val tone: Tone? = null) : JComponent() {
    var text: String = text
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        font = DesignTokens.Type.badge()
    }

    override fun getPreferredSize(): Dimension = Dimension(ChipPainter.width(this, text), ChipPainter.height())
    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun getBaseline(width: Int, height: Int): Int {
        val fm = getFontMetrics(DesignTokens.Type.badge())
        return BadgePainter.baseline((height - ChipPainter.height()) / 2, ChipPainter.height(), fm.ascent, fm.descent)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        BadgePainter.applyTextHints(g2)
        ChipPainter.paint(g2, 0, (height - ChipPainter.height()) / 2, text, tone, fixedWidth = width)
    }
}

/**
 * The status of a response: the code in bold and a short reason, on a pill tinted by status class (1xx grey, 2xx green, 3xx cyan,
 * 4xx orange, 5xx red). [label] comes from the caller (bundle text such as "OK"); the pill adds no words of its own.
 */
class StatusPill(code: Int = 0, label: String = "") : JComponent() {
    var code: Int = code
        private set
    var label: String = label
        private set

    init {
        isOpaque = false
        font = DesignTokens.Type.badge()
    }

    fun setStatus(code: Int, label: String) {
        this.code = code
        this.label = label
        revalidate()
        repaint()
    }

    private fun codeText(): String = if (code > 0) code.toString() else ""
    private fun gap(): Int = if (label.isNotEmpty() && code > 0) JBUI.scale(Space.XS) else 0
    private fun height0(): Int = JBUI.scale(Badge.PILL_HEIGHT)

    override fun getPreferredSize(): Dimension {
        val strong = getFontMetrics(DesignTokens.Type.captionStrong())
        val plain = getFontMetrics(DesignTokens.Type.caption())
        val textWidth = strong.stringWidth(codeText()) + gap() + plain.stringWidth(label)
        return Dimension(textWidth + 2 * JBUI.scale(Badge.PADDING_X + Space.HALF), height0())
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        BadgePainter.applyTextHints(g2)
        val tone = Palette.status(code)
        val h = height0()
        val y = (height - h) / 2
        // The pill background and, on top, the two text runs; the background pass draws an empty label.
        BadgePainter.paint(g2, 0, y, width, h, h, "", DesignTokens.Type.captionStrong(), tone.fill, tone.text, BadgeLook.TINTED)
        val strong = g2.getFontMetrics(DesignTokens.Type.captionStrong())
        val plain = g2.getFontMetrics(DesignTokens.Type.caption())
        val total = strong.stringWidth(codeText()) + gap() + plain.stringWidth(label)
        var x = (width - total) / 2
        val base = BadgePainter.baseline(y, h, strong.ascent, strong.descent)
        val oldFont = g2.font
        val oldColor = g2.color
        g2.color = tone.text
        g2.font = DesignTokens.Type.captionStrong()
        g2.drawString(codeText(), x, base)
        x += strong.stringWidth(codeText()) + gap()
        g2.font = DesignTokens.Type.caption()
        g2.drawString(label, x, base)
        g2.font = oldFont
        g2.color = oldColor
    }
}
