package com.routegenie.ui.design

import com.intellij.util.ui.JBUI
import com.routegenie.ui.design.DesignTokens.Space
import java.awt.BorderLayout
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A slim notice that sits above content: the raised background of the chrome, a stripe in a [Tone] along the left edge so the
 * kind of notice reads at a glance, [content] in the middle, and a hairline below. Colors come from the palette, so it follows
 * the theme; it takes no part in the list's selection or hover painting.
 */
class NoticeStrip(private val tone: Tone, content: JComponent) : JPanel(BorderLayout()) {
    init {
        isOpaque = true
        background = Palette.raised
        border = JBUI.Borders.merge(Space.border(Space.S, Space.M + STRIPE_WIDTH, Space.S, Space.M), Divider.bottom(), true)
        add(content, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g.color = tone.fill
        g.fillRect(0, 0, JBUI.scale(STRIPE_WIDTH), height)
    }

    companion object {
        /** Width of the tone stripe in unscaled pixels. */
        const val STRIPE_WIDTH = 3
    }
}
