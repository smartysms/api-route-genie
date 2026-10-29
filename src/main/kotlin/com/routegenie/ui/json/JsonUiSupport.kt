package com.routegenie.ui.json

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Condition
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.text.StringUtil
import com.routegenie.RouteGenieBundle
import com.routegenie.model.HttpMethod
import com.routegenie.json.JsonKind
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.color.PaletteSpec
import com.intellij.ui.components.ActionLink
import java.awt.Color
import java.awt.event.ActionListener
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import java.awt.datatransfer.StringSelection
import java.util.concurrent.Future

/**
 * Syntax colors of values. They come from the editor color scheme (the JSON highlighting of the user's theme), so the tree,
 * the table and the text views agree with each other and with any editor theme; neutral text comes from the window palette.
 * Colors are read when they are used, so a theme switch applies to rows painted afterwards.
 */
internal object JsonSyntax {
    /** The scheme's color, unless it cannot be read on the window surface (a light editor scheme under a dark UI theme, or the reverse). */
    private fun foreground(key: TextAttributesKey, fallback: Color): Color {
        val color = EditorColorsManager.getInstance().globalScheme.getAttributes(key)?.foregroundColor
        return if (color != null && ColorUtil.getContrast(color, Palette.surface) >= MIN_CONTRAST) color else fallback
    }

    fun key(): Color = foreground(DefaultLanguageHighlighterColors.INSTANCE_FIELD, Palette.info.text)
    fun string(): Color = foreground(DefaultLanguageHighlighterColors.STRING, Palette.success.text)
    fun number(): Color = foreground(DefaultLanguageHighlighterColors.NUMBER, Palette.warning.text)
    fun keyword(): Color = foreground(DefaultLanguageHighlighterColors.KEYWORD, Palette.method(HttpMethod.PATCH).text)

    /** Large-text contrast of the WCAG guidelines; below it the fallback tones (checked against the surface by the design tests) are used. */
    private const val MIN_CONTRAST = 3.0

    fun of(kind: JsonKind): Color = when (kind) {
        JsonKind.STRING -> string()
        JsonKind.NUMBER -> number()
        JsonKind.TRUE, JsonKind.FALSE, JsonKind.NULL -> keyword()
        else -> Palette.textSecondary
    }
}

internal object JsonUi {
    /** Runs [block] on the UI thread unless [expired] says the owner is gone by then. */
    fun later(expired: () -> Boolean, block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), Condition<Any?> { expired() })
    }

    /**
     * Runs [block] on a pooled thread. A document can be closed while a task still reads it (the viewer moved on); that read then
     * fails with an IOException, which only means the result is no longer wanted.
     */
    fun background(block: () -> Unit): Future<*> = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            block()
        } catch (e: java.io.IOException) {
            // closed under us
        }
    }

    fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    fun size(bytes: Long): String = StringUtil.formatFileSize(bytes)

    /** A link that runs [run]; the explicit listener type avoids the ambiguity between the Runnable and ActionListener constructors. */
    fun link(text: String, run: () -> Unit): ActionLink = ActionLink(text, ActionListener { run() })

    fun keys(n: Int): String = RouteGenieBundle.message("json.count.keys", n)
    fun items(n: Int): String = RouteGenieBundle.message("json.count.items", n)
}

/** A toolbar button whose visibility and enabled state are read from the viewer's current mode each time the toolbar updates. */
internal class JsonAction(
    text: String,
    description: String,
    icon: Icon?,
    private val visible: () -> Boolean = { true },
    private val enabled: () -> Boolean = { true },
    private val run: () -> Unit,
) : DumbAwareAction(text, description, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = visible()
        e.presentation.isEnabled = enabled()
    }

    override fun actionPerformed(e: AnActionEvent) = run()
}

internal class JsonToggle(
    text: String,
    description: String,
    icon: Icon?,
    private val visible: () -> Boolean = { true },
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
) : DumbAwareToggleAction(text, description, icon) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isVisible = visible()
    }

    override fun isSelected(e: AnActionEvent): Boolean = get()
    override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
}

/**
 * Segmented control: a few labels in one rounded outline, one of them selected. Painted with the window palette (the selected
 * segment is the accent color at tint strength), keyboard operable with the arrow keys, and each segment can be disabled.
 */
internal class SegmentedControl(private val labels: List<String>, private val onSelect: (Int) -> Unit) : JComponent() {
    private val enabledSegments = BooleanArray(labels.size) { true }
    private var hovered = -1
    var selected = 0
        set(value) {
            field = value
            repaint()
        }

    init {
        isFocusable = true
        isOpaque = false
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val k = segmentAt(e.x)
                if (k >= 0 && enabledSegments[k]) choose(k)
            }

            override fun mouseMoved(e: MouseEvent) {
                setHover(segmentAt(e.x))
            }

            override fun mouseExited(e: MouseEvent) {
                setHover(-1)
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_LEFT -> step(-1)
                    KeyEvent.VK_RIGHT -> step(1)
                }
            }
        })
    }

    fun setSegmentEnabled(index: Int, value: Boolean) {
        enabledSegments[index] = value
        repaint()
    }

    private fun step(delta: Int) {
        var k = selected + delta
        while (k in labels.indices && !enabledSegments[k]) k += delta
        if (k in labels.indices) choose(k)
    }

    private fun choose(k: Int) {
        if (k == selected) return
        selected = k
        onSelect(k)
    }

    private fun setHover(k: Int) {
        if (k != hovered) {
            hovered = k
            repaint()
        }
    }

    private fun segmentWidth(k: Int): Int = getFontMetrics(font()).stringWidth(labels[k]) + 2 * JBUI.scale(DesignTokens.Space.M)
    private fun font(): Font = DesignTokens.Type.caption()

    private fun segmentAt(x: Int): Int {
        var left = 0
        for (k in labels.indices) {
            val w = segmentWidth(k)
            if (x in left until left + w) return k
            left += w
        }
        return -1
    }

    override fun getPreferredSize(): Dimension =
        Dimension(labels.indices.sumOf { segmentWidth(it) }, JBUI.scale(SEGMENT_HEIGHT))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val arc = JBUI.scale(DesignTokens.Radius.MD) * 2
        val h = height - 1
        val total = preferredSize.width - 1
        g2.color = Palette.surface
        g2.fillRoundRect(0, 0, total, h, arc, arc)
        var left = 0
        g2.font = font()
        val fm = g2.fontMetrics
        for (k in labels.indices) {
            val w = segmentWidth(k)
            if (k == selected) {
                g2.color = ColorUtil.mix(Palette.surface, Palette.accent, PaletteSpec.TINT_ALPHA * 2)
                g2.fillRoundRect(left + 1, 1, w - 2, h - 2, arc, arc)
            } else if (k == hovered && enabledSegments[k]) {
                g2.color = Palette.hover
                g2.fillRoundRect(left + 1, 1, w - 2, h - 2, arc, arc)
            }
            g2.color = when {
                !enabledSegments[k] -> Palette.textDisabled
                k == selected -> Palette.accent
                else -> Palette.textSecondary
            }
            g2.drawString(labels[k], left + (w - fm.stringWidth(labels[k])) / 2, (h - fm.height) / 2 + fm.ascent + 1)
            left += w
        }
        g2.color = if (isFocusOwner) Palette.focus else Palette.border
        g2.drawRoundRect(0, 0, total, h, arc, arc)
    }

    private companion object {
        const val SEGMENT_HEIGHT = 24
    }
}
