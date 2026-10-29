package com.routegenie.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.hover.TreeHoverListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.routegenie.RouteGenieBundle
import com.routegenie.model.Endpoint
import com.routegenie.ui.design.BadgeState
import com.routegenie.ui.design.ChipPainter
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.MethodBadge
import com.routegenie.ui.design.Palette
import com.routegenie.ui.model.Group
import com.routegenie.ui.model.GroupKind
import com.routegenie.ui.model.Leaf
import com.routegenie.ui.model.Node
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JTree
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/** Swing view over an immutable [com.routegenie.ui.model.EndpointTree]: no node objects are created for the UI. */
class EndpointTreeModel(private val rootGroup: Group) : TreeModel {
    override fun getRoot(): Any = rootGroup
    override fun getChild(parent: Any, index: Int): Any = (parent as Group).children[index]
    override fun getChildCount(parent: Any): Int = (parent as? Group)?.children?.size ?: 0
    override fun isLeaf(node: Any): Boolean = node is Leaf
    override fun getIndexOfChild(parent: Any?, child: Any?): Int = (child as? Node)?.index ?: -1
    override fun valueForPathChanged(path: TreePath, newValue: Any?) = Unit
    override fun addTreeModelListener(l: TreeModelListener) = Unit
    override fun removeTreeModelListener(l: TreeModelListener) = Unit

    companion object {
        fun pathOf(node: Node): TreePath {
            val nodes = ArrayList<Node>()
            var n: Node? = node
            while (n != null) {
                nodes += n
                n = n.parent
            }
            nodes.reverse()
            return TreePath(nodes.toTypedArray())
        }
    }
}

/** Fixed row height and the large-model hint (research 06 Q5); re-applied when the look and feel changes. */
class EndpointJTree : Tree() {
    init {
        applyFixedRows()
        TreeHoverListener.DEFAULT.addTo(this)
    }

    override fun updateUI() {
        super.updateUI()
        applyFixedRows()
    }

    /**
     * The row height must be set first: Swing's BasicTreeUI turns the large-model flag off again while the height is still
     * zero, and the tree then keeps a variable-height layout cache that asks the renderer for every row of a model swap
     * (about 400 ms on the UI thread for 50,000 flat rows).
     */
    private fun applyFixedRows() {
        rowHeight = JBUI.scale(DesignTokens.Rows.TREE)
        isLargeModel = true
    }
}

/**
 * Draws one row. A leaf is method badge, path, handler, framework and then chips for deprecation and tags; a group is its label
 * and a count chip. The badge and the chips are painted by the design system, text and selection by the platform's tree, so
 * selection, focus, speed search and the theme's colors all behave as in any other IDE tree. The renderer is reused for every
 * row and allocates nothing per row beyond what the platform's text component does.
 */
class EndpointTreeRenderer : ColoredTreeCellRenderer() {
    private var endpoint: Endpoint? = null
    private var group: Group? = null
    private var selectedRow = false
    private var focusedRow = false
    private var textEnd = UNKNOWN
    private var chipsWidth = 0

    private val deprecatedLabel: String by lazy { RouteGenieBundle.message("endpoint.deprecated") }

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        selectedRow = selected
        focusedRow = hasFocus || tree.hasFocus()
        textEnd = UNKNOWN
        endpoint = null
        group = null
        when (value) {
            is Leaf -> {
                val e = value.endpoint
                endpoint = e
                val state = if (!selected) BadgeState.NORMAL else if (focusedRow) BadgeState.SELECTED else BadgeState.SELECTED_INACTIVE
                icon = MethodBadge.of(e.method, state)
                iconTextGap = JBUI.scale(Space.S)
                append(e.path, if (e.deprecated) deprecatedPath(selected) else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(GAP, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(e.handler.name, secondary(selected))
                if (!underFrameworkGroup(value)) {
                    append(GAP, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(e.framework.displayName, secondarySmall(selected))
                }
                chipsWidth = measureChips(e)
            }
            is Group -> {
                group = value
                icon = when (value.kind) {
                    GroupKind.FILE -> AllIcons.FileTypes.Text
                    GroupKind.FRAMEWORK -> AllIcons.Nodes.Package
                    else -> AllIcons.Nodes.Folder
                }
                append(value.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                chipsWidth = 0
            }
        }
    }

    /** The framework is named by its group in the default grouping; repeating it on every row would only add noise. */
    private fun underFrameworkGroup(leaf: Leaf): Boolean {
        var node: Group? = leaf.parent
        while (node != null) {
            if (node.kind == GroupKind.FRAMEWORK) return true
            node = node.parent
        }
        return false
    }

    private fun secondary(selected: Boolean) = if (selected) SELECTED_SECONDARY[focusIndex()] else SECONDARY
    private fun secondarySmall(selected: Boolean) = if (selected) SELECTED_SECONDARY_SMALL[focusIndex()] else SECONDARY_SMALL
    private fun deprecatedPath(selected: Boolean) = if (selected) SELECTED_DEPRECATED[focusIndex()] else DEPRECATED
    private fun focusIndex() = if (focusedRow) 0 else 1

    // --- Chips: drawn after the text, so the row's preferred width includes them ---------------------------------------------

    private fun measureChips(e: Endpoint): Int {
        var total = 0
        val gap = JBUI.scale(Space.XS)
        if (e.deprecated) total += gap + ChipPainter.width(this, deprecatedLabel)
        val shown = minOf(e.tags.size, DesignTokens.Rows.MAX_TAG_CHIPS)
        for (i in 0 until shown) total += gap + ChipPainter.width(this, e.tags[i])
        if (e.tags.size > shown) total += gap + ChipPainter.width(this, overflow(e.tags.size - shown))
        return total
    }

    private fun overflow(extra: Int): String = "+$extra"

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (textEnd == UNKNOWN) textEnd = base.width
        if (endpoint == null && group == null) return base
        val extra = if (group != null) groupChipWidth() else chipsWidth
        return Dimension(base.width + extra, base.height)
    }

    private fun groupChipWidth(): Int = JBUI.scale(Space.XS) + ChipPainter.width(this, groupCountText(group))

    private fun groupCountText(g: Group?): String = g?.count?.toString().orEmpty()

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        if (textEnd == UNKNOWN) textEnd = super.getPreferredSize().width
        val chipHeight = ChipPainter.height()
        val y = (height - chipHeight) / 2
        var x = textEnd
        val gap = JBUI.scale(Space.XS)
        val selectedColor = if (selectedRow) Palette.selectionForeground(focusedRow) else null
        val e = endpoint
        if (e != null) {
            if (e.deprecated) x += gap + ChipPainter.paint(g2, x + gap, y, deprecatedLabel, Palette.warning, selectedColor)
            val shown = minOf(e.tags.size, DesignTokens.Rows.MAX_TAG_CHIPS)
            for (i in 0 until shown) x += gap + ChipPainter.paint(g2, x + gap, y, e.tags[i], null, selectedColor)
            if (e.tags.size > shown) ChipPainter.paint(g2, x + gap, y, overflow(e.tags.size - shown), null, selectedColor)
        } else if (group != null) {
            ChipPainter.paint(g2, x + gap, y, groupCountText(group), null, selectedColor)
        }
    }

    private companion object {
        const val UNKNOWN = -1
        const val GAP = "  "

        // Colors are JBColors resolved when drawn, so these cached attributes follow theme switches.
        val SECONDARY = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.textSecondary)
        val SECONDARY_SMALL = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Palette.textSecondary)
        val DEPRECATED = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, Palette.textSecondary)
        val SELECTED_SECONDARY = arrayOf(
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.selectionMuted(true)),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.selectionMuted(false)),
        )
        val SELECTED_SECONDARY_SMALL = arrayOf(
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Palette.selectionMuted(true)),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Palette.selectionMuted(false)),
        )
        val SELECTED_DEPRECATED = arrayOf(
            SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, Palette.selectionMuted(true)),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, Palette.selectionMuted(false)),
        )
    }
}
