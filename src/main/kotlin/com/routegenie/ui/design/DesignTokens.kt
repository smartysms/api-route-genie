package com.routegenie.ui.design

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.routegenie.Limits
import com.routegenie.ui.design.color.PaletteSpec
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Font
import javax.swing.border.Border

/**
 * Every measure of the window, in unscaled pixels, in one place. Callers pass tokens to JBUI (`JBUI.Borders.empty(Space.S)`)
 * or to [Space.px]; nothing outside this file types a pixel size for spacing, radius, stroke or icon size.
 *
 * Spacing follows a 4 px grid like the platform's New UI; [Space.HALF] is the single exception for hairline optical fixes.
 */
object DesignTokens {
    object Space {
        const val HALF = 2
        const val XS = 4
        const val S = 8
        const val M = 12
        const val L = 16
        const val XL = 24
        const val XXL = 32

        fun px(unscaled: Int): Int = JBUI.scale(unscaled)
        fun insets(top: Int, left: Int, bottom: Int, right: Int): JBInsets = JBUI.insets(top, left, bottom, right)
        fun insets(vertical: Int, horizontal: Int): JBInsets = JBUI.insets(vertical, horizontal)
        fun border(top: Int, left: Int, bottom: Int, right: Int): Border = JBUI.Borders.empty(top, left, bottom, right)
        fun border(vertical: Int, horizontal: Int): Border = JBUI.Borders.empty(vertical, horizontal)
        fun border(all: Int): Border = JBUI.Borders.empty(all)
    }

    object Radius {
        const val SM = 4
        const val MD = 6
        const val LG = 8
    }

    object Stroke {
        /** One physical line; scaled with the user's UI scale so it stays one pixel wide on a 100 percent screen. */
        const val HAIRLINE = 1
        const val FOCUS_RING = 2

        private var cachedWidth = -1f
        private var cached: BasicStroke = BasicStroke(1f)

        /** The stroke of [HAIRLINE] times the UI scale; one instance is reused while the scale does not change. */
        fun hairline(): BasicStroke {
            val width = JBUI.scale(HAIRLINE.toFloat())
            if (width != cachedWidth) {
                cachedWidth = width
                cached = BasicStroke(width)
            }
            return cached
        }
    }

    object IconSize {
        /** Toolbar, tree node and tab icons. */
        const val ACTION = 16
        const val DOT = 8
        const val EMPTY_STATE = 32
    }

    object Badge {
        /** Wide enough for OPTIONS in the bold small font; every method badge has this width so paths line up in a column. */
        const val WIDTH = 54
        const val HEIGHT = 16
        const val ARC = Radius.SM

        /** Status pills and chips are a little taller than a method badge to carry two text runs. */
        const val PILL_HEIGHT = 18
        const val CHIP_HEIGHT = 16
        const val PADDING_X = 6
    }

    /** Hover, selection and focus: alphas and widths shared by every custom painted control. */
    object State {
        val TINT: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, PaletteSpec.TINT_ALPHA.toFloat())
        val TINT_SELECTED: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, PaletteSpec.SELECTED_TINT_ALPHA.toFloat())
        val SKELETON: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f)
        val OUTLINE: AlphaComposite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f)
        const val FOCUS_RING_WIDTH = Stroke.FOCUS_RING

        /** Share of the selection background mixed into the selection foreground for secondary text on a selected row. */
        const val SELECTED_MUTED_MIX = 0.30
    }

    object Rows {
        /** Fixed row height of the endpoint tree; lives in Limits because the large-model hint depends on it. */
        const val TREE = Limits.TREE_ROW_HEIGHT

        /** Most tag chips drawn after a row's text; the rest is summarized as +N. */
        const val MAX_TAG_CHIPS = Limits.TREE_ROW_MAX_TAG_CHIPS
        const val SKELETON_ROWS = 5
        const val SKELETON_BAR_HEIGHT = 10
        const val EMPTY_STATE_TEXT_WIDTH = 320
    }

    /**
     * The type scale. It is derived from the IDE's own label font (so the user's font size and the New UI scale apply) and
     * cached by font identity: paint code may call these on every row without allocating.
     */
    object Type {
        private var base: Font? = null
        private var cachedBadge: JBFont = JBFont.small()
        private var cachedCaption: JBFont = JBFont.small()
        private var cachedCaptionStrong: JBFont = JBFont.small()

        private fun refresh() {
            val label = UIUtil.getLabelFont()
            if (label === base) return
            base = label
            cachedCaption = JBFont.small()
            cachedCaptionStrong = JBFont.small().asBold()
            cachedBadge = cachedCaptionStrong
        }

        fun body(): JBFont = JBFont.label()
        fun bodyStrong(): JBFont = JBFont.label().asBold()
        fun title(): JBFont = JBFont.label().biggerOn(TITLE_STEP).asBold()

        fun caption(): JBFont {
            refresh()
            return cachedCaption
        }

        fun captionStrong(): JBFont {
            refresh()
            return cachedCaptionStrong
        }

        /** Method badges, status pills and chips. */
        fun badge(): JBFont {
            refresh()
            return cachedBadge
        }

        /** The editor's font at the UI font size, for values the user copies (paths, handlers); the editor scheme keeps themes in charge. */
        fun mono(): JBFont {
            val scheme = EditorColorsManager.getInstance().globalScheme
            return JBFont.create(scheme.getFont(EditorFontType.PLAIN).deriveFont(UIUtil.getLabelFont().size2D - MONO_STEP))
        }

        private const val TITLE_STEP = 2f

        /** Monospaced fonts look larger than proportional ones at the same point size. */
        private const val MONO_STEP = 1f
    }
}
