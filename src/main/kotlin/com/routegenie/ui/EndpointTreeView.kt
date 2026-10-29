package com.routegenie.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.icons.AllIcons
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.model.HttpMethod
import com.routegenie.ui.model.Group
import com.routegenie.ui.model.GroupKind
import com.routegenie.ui.model.Leaf
import com.routegenie.ui.model.Node
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.RenderingHints
import javax.swing.Icon
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
    }

    override fun updateUI() {
        super.updateUI()
        applyFixedRows()
    }

    private fun applyFixedRows() {
        isLargeModel = true
        rowHeight = JBUI.scale(Limits.TREE_ROW_HEIGHT)
    }
}

/** Colors of the method badges, one place, theme aware. */
object MethodColors {
    private fun c(light: Int, dark: Int) = JBColor(Color(light), Color(dark))

    private val GET = c(0x2E7D32, 0x3C8F40)
    private val POST = c(0x1565C0, 0x2979C8)
    private val PUT = c(0xE65100, 0xEF6C00)
    private val PATCH = c(0x6A1B9A, 0x8E3FB0)
    private val DELETE = c(0xC62828, 0xD13A3A)
    private val OTHER = c(0x546E7A, 0x6B8290)
    private val ANY = c(0x00796B, 0x14907F)

    fun of(method: HttpMethod): JBColor = when (method) {
        HttpMethod.GET -> GET
        HttpMethod.POST -> POST
        HttpMethod.PUT -> PUT
        HttpMethod.PATCH -> PATCH
        HttpMethod.DELETE -> DELETE
        HttpMethod.ANY -> ANY
        else -> OTHER
    }
}

/** A small rounded label with the verb; ANY is shown as an outlined ANY badge so it reads as "every method". */
class MethodBadge private constructor(private val method: HttpMethod) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(UiMetrics.BADGE_WIDTH)
    override fun getIconHeight(): Int = JBUI.scale(UiMetrics.BADGE_HEIGHT)

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val color = MethodColors.of(method)
            val arc = JBUI.scale(UiMetrics.BADGE_ARC)
            val w = iconWidth
            val h = iconHeight
            val outlined = method == HttpMethod.ANY
            g2.color = color
            if (outlined) g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc) else g2.fillRoundRect(x, y, w, h, arc, arc)
            g2.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            val fm = g2.fontMetrics
            val text = method.name
            g2.color = if (outlined) color else Color.WHITE
            g2.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (h - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private val ALL = HttpMethod.entries.associateWith { MethodBadge(it) }
        fun of(method: HttpMethod): Icon = ALL.getValue(method)
    }
}

/** Draws one row: a leaf is badge, path, handler, framework, tags; a group is its label and endpoint count. */
class EndpointTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        when (value) {
            is Leaf -> {
                val e = value.endpoint
                icon = MethodBadge.of(e.method)
                iconTextGap = JBUI.scale(UiMetrics.GAP)
                append(e.path, if (e.deprecated) DEPRECATED else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  " + e.handler.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  " + e.framework.displayName, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (e.deprecated) append("  " + RouteGenieBundle.message("endpoint.deprecated"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (e.tags.isNotEmpty()) append("  " + e.tags.joinToString(" ") { "#$it" }, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
            is Group -> {
                icon = when (value.kind) {
                    GroupKind.FILE -> AllIcons.FileTypes.Text
                    GroupKind.FRAMEWORK -> AllIcons.Nodes.Package
                    else -> AllIcons.Nodes.Folder
                }
                append(value.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  " + value.count, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }

    private companion object {
        val DEPRECATED = SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
    }
}
