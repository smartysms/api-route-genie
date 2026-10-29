package com.routegenie.ui.design

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.DesignTokens.Stroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Insets
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border

/** A hairline in the theme's border color, as a component ([horizontal], [vertical]) or as a border ([top], [bottom]). */
class Divider private constructor(private val vertical: Boolean) : JComponent() {
    init {
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val thickness = JBUI.scale(Stroke.HAIRLINE)
        return if (vertical) Dimension(thickness, 0) else Dimension(0, thickness)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension =
        if (vertical) Dimension(preferredSize.width, Int.MAX_VALUE) else Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        g.color = Palette.border
        g.fillRect(0, 0, width, height)
    }

    companion object {
        fun horizontal(): Divider = Divider(false)
        fun vertical(): Divider = Divider(true)

        /** A line along the bottom edge; combine with spacing through [JBUI.Borders.merge]. */
        fun bottom(): Border = JBUI.Borders.customLine(Palette.border, 0, 0, Stroke.HAIRLINE, 0)
        fun top(): Border = JBUI.Borders.customLine(Palette.border, Stroke.HAIRLINE, 0, 0, 0)
    }
}

/**
 * The title of a block of content: small, bold, secondary-colored, with an optional component at the right (a count, a toggle,
 * an action) and a hairline below. The caller passes the text from the bundle.
 */
class SectionHeader(title: String, trailing: JComponent? = null, divider: Boolean = true) : JPanel(BorderLayout()) {
    private var titleLabel: JBLabel? = null

    init {
        isOpaque = false
        val spacing = Space.border(Space.S, Space.M, Space.S, Space.M)
        border = if (divider) JBUI.Borders.merge(spacing, Divider.bottom(), true) else spacing
        val label = JBLabel(title).apply {
            font = DesignTokens.Type.captionStrong()
            foreground = Palette.textSecondary
        }
        titleLabel = label
        add(label, BorderLayout.CENTER)
        if (trailing != null) add(trailing, BorderLayout.EAST)
    }

    var title: String
        get() = titleLabel?.text.orEmpty()
        set(value) {
            titleLabel?.text = value
        }

    override fun updateUI() {
        super.updateUI()
        titleLabel?.font = DesignTokens.Type.captionStrong()
    }
}

/** A label with its value; [value] is any component so a row can hold text, a link or a chip. */
class KeyValueRow(labelText: String, val value: JComponent) {
    val label: JBLabel = JBLabel(labelText).apply {
        font = DesignTokens.Type.caption()
        foreground = Palette.textSecondary
    }

    /** Hides or shows label and value together; the grid skips hidden rows, so there is no gap. */
    var isVisible: Boolean
        get() = value.isVisible
        set(visible) {
            label.isVisible = visible
            value.isVisible = visible
        }
}

/**
 * Facts as aligned label/value rows. The label column is as wide as the widest label; values take the rest and align on the
 * text baseline of their label. Add rows with [addRow]; keep the returned [KeyValueRow] to change or hide it later.
 */
class KeyValueGrid : JPanel(GridBagLayout()) {
    private var next = 0

    init {
        isOpaque = false
    }

    fun addRow(labelText: String, value: JComponent = valueLabel()): KeyValueRow = add(KeyValueRow(labelText, value))

    fun add(row: KeyValueRow): KeyValueRow {
        val top = if (next == 0) 0 else JBUI.scale(Space.S)
        add(row.label, GridBagConstraints().apply {
            gridx = 0; gridy = next; anchor = GridBagConstraints.BASELINE_LEADING
            insets = Insets(top, 0, 0, JBUI.scale(Space.M))
        })
        add(row.value, GridBagConstraints().apply {
            gridx = 1; gridy = next; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.BASELINE_LEADING
            insets = Insets(top, 0, 0, 0)
        })
        next++
        return row
    }

    companion object {
        /** A selectable single-line text value; [mono] uses the editor font for things the user copies, such as paths. */
        fun valueLabel(copyable: Boolean = true, mono: Boolean = false): JBLabel = JBLabel().apply {
            foreground = Palette.textPrimary
            if (mono) font = DesignTokens.Type.mono()
            if (copyable) setCopyable(true)
        }
    }
}
