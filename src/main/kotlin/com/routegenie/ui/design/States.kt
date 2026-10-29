package com.routegenie.ui.design

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.routegenie.ui.design.DesignTokens.Radius
import com.routegenie.ui.design.DesignTokens.Rows
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.DesignTokens.State
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

private fun stackedColumn(): JPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
}

private fun centerX(c: JComponent): JComponent = c.also { it.alignmentX = Component.CENTER_ALIGNMENT }

/**
 * A full-page state with nothing to show: an icon, a title, a hint and optionally one action. All text comes from the caller.
 * It paints on the surface color, so it looks right on any theme and never fights the list it replaces.
 */
class EmptyState(icon: Icon?, title: String, hint: String? = null, action: JComponent? = null) : JPanel(GridBagLayout()) {
    init {
        isOpaque = true
        background = Palette.surface
        val column = stackedColumn()
        if (icon != null) {
            column.add(centerX(JBLabel(icon)))
            column.add(Box.createVerticalStrut(JBUI.scale(Space.M)))
        }
        column.add(centerX(JBLabel(title, SwingConstants.CENTER).apply {
            font = DesignTokens.Type.bodyStrong()
            foreground = Palette.textPrimary
        }))
        if (!hint.isNullOrEmpty()) {
            column.add(Box.createVerticalStrut(JBUI.scale(Space.XS)))
            column.add(centerX(JBLabel(wrapped(hint)).apply { foreground = Palette.textSecondary }))
        }
        if (action != null) {
            column.add(Box.createVerticalStrut(JBUI.scale(Space.M)))
            column.add(centerX(action))
        }
        add(column, GridBagConstraints())
    }

    private fun wrapped(text: String): String =
        "<html><div style='text-align:center;width:${JBUI.scale(Rows.EMPTY_STATE_TEXT_WIDTH)}px'>${StringUtil.escapeXmlEntities(text)}</div></html>"
}

/** A spinner and a line of text; [parent] owns the spinner, which stops animating when it is disposed. */
class LoadingRow(parent: Disposable, text: String) : JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(Space.S), 0)) {
    private val icon = AsyncProcessIcon(ICON_NAME)

    init {
        isOpaque = false
        Disposer.register(parent, icon)
        add(icon)
        add(JBLabel(text).apply { foreground = Palette.textSecondary })
    }

    private companion object {
        const val ICON_NAME = "RouteGenie.loading"
    }
}

/**
 * Static placeholder bars in the shape of list rows. Nothing animates here, so there is no timer to leak; the spinner row above
 * it shows that work is going on.
 */
class SkeletonRows(private val rows: Int = Rows.SKELETON_ROWS) : JComponent() {
    init {
        isOpaque = false
    }

    private fun barHeight(): Int = JBUI.scale(Rows.SKELETON_BAR_HEIGHT)
    private fun rowPitch(): Int = barHeight() + JBUI.scale(Space.M)

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(Rows.EMPTY_STATE_TEXT_WIDTH), rows * rowPitch())
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        val oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldComposite = g2.composite
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.composite = State.SKELETON
        g2.color = Palette.textSecondary
        val arc = JBUI.scale(Radius.SM)
        val badgeWidth = JBUI.scale(DesignTokens.Badge.WIDTH)
        val gap = JBUI.scale(Space.S)
        for (row in 0 until rows) {
            val y = row * rowPitch() + (rowPitch() - barHeight()) / 2
            g2.fillRoundRect(0, y, badgeWidth, barHeight(), arc, arc)
            val rest = width - badgeWidth - gap
            g2.fillRoundRect(badgeWidth + gap, y, rest * WIDTH_PERCENT[row % WIDTH_PERCENT.size] / PERCENT, barHeight(), arc, arc)
        }
        g2.composite = oldComposite
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa)
    }

    private companion object {
        const val PERCENT = 100
        val WIDTH_PERCENT = intArrayOf(82, 64, 74, 50, 70)
    }
}

/** The page shown while the first scan runs: spinner row and skeleton rows, centered. */
class LoadingState(parent: Disposable, text: String) : JPanel(GridBagLayout()) {
    init {
        isOpaque = true
        background = Palette.surface
        val column = stackedColumn()
        column.add(centerX(LoadingRow(parent, text)))
        column.add(Box.createVerticalStrut(JBUI.scale(Space.L)))
        column.add(centerX(SkeletonRows()))
        add(column, GridBagConstraints())
    }
}
