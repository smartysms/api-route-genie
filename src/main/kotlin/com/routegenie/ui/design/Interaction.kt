package com.routegenie.ui.design

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.util.ui.JBUI
import com.routegenie.ui.design.DesignTokens.Radius
import com.routegenie.ui.design.DesignTokens.Space
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel

/** The look of a row in a custom list; the tree and the platform lists draw their own, with the same theme colors. */
enum class RowState { NORMAL, HOVER, SELECTED, SELECTED_INACTIVE }

/**
 * Paints the background of a row for custom lists (history, environments, headers). Colors come from the IDE theme (hover and
 * selection of the current theme), the corners from [Radius]; there is no state of its own, so one call per row is enough.
 */
object RowPainter {
    /** The background color of [state], or null when nothing is painted for it. */
    fun background(state: RowState): Color? = when (state) {
        RowState.NORMAL -> null
        RowState.HOVER -> Palette.hover
        RowState.SELECTED -> Palette.selectionBackground(true)
        RowState.SELECTED_INACTIVE -> Palette.selectionBackground(false)
    }

    /** The text color that is readable on the row's background. */
    fun foreground(state: RowState): Color = when (state) {
        RowState.SELECTED -> Palette.selectionForeground(true)
        RowState.SELECTED_INACTIVE -> Palette.selectionForeground(false)
        else -> Palette.textPrimary
    }

    fun secondaryForeground(state: RowState): Color = when (state) {
        RowState.SELECTED -> Palette.selectionMuted(true)
        RowState.SELECTED_INACTIVE -> Palette.selectionMuted(false)
        else -> Palette.textSecondary
    }

    fun paint(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, state: RowState, rounded: Boolean = true) {
        val color = background(state) ?: return
        val oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        val oldColor = g.color
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        if (rounded) {
            val arc = JBUI.scale(Radius.MD) * 2
            g.fillRoundRect(x, y, w, h, arc, arc)
        } else {
            g.fillRect(x, y, w, h)
        }
        g.color = oldColor
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa)
    }
}

/** One look for every toolbar of the window: no reserved overflow slot, no border, transparent so the bar's surface shows. */
object ToolbarStyle {
    fun create(place: String, group: ActionGroup, target: JComponent, horizontal: Boolean = true): ActionToolbar {
        val toolbar = ActionManager.getInstance().createActionToolbar(place, group, horizontal)
        toolbar.targetComponent = target
        toolbar.setReservePlaceAutoPopupIcon(false)
        toolbar.component.border = JBUI.Borders.empty()
        toolbar.component.isOpaque = false
        return toolbar
    }

    /**
     * A bar on the raised surface: [toolbar] at the left, [center] (a filter box, a URL field) filling the rest, [trailing] at
     * the right, a hairline below. Padding follows the 4 px grid.
     */
    fun bar(toolbar: ActionToolbar?, center: JComponent? = null, trailing: JComponent? = null): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = Palette.raised
        border = JBUI.Borders.merge(Space.border(Space.XS, Space.S, Space.XS, Space.S), Divider.bottom(), true)
        if (toolbar != null) add(toolbar.component, BorderLayout.WEST)
        if (center != null) add(center, BorderLayout.CENTER)
        if (trailing != null) add(trailing, BorderLayout.EAST)
    }
}
