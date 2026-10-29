package com.routegenie.ui.json

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.json.JsonIndex
import com.routegenie.json.JsonKind
import com.routegenie.json.JsonPath
import com.routegenie.json.JsonTreeCore
import com.routegenie.json.TreeItem
import com.routegenie.ui.UiAction
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.RowState
import com.routegenie.ui.design.RowPainter
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/** What the tree asks the viewer to do; the viewer owns the clipboard and the messages. */
internal interface TreeActions {
    fun selected(node: Int)
    fun copyPath(node: Int)
    fun copyValue(node: Int)
    fun copyKey(node: Int)
}

/** Swing view over a [JsonTreeCore]. Items are created when the tree asks for them; nothing is held per node of the document. */
internal class JsonTreeModel : TreeModel {
    var core: JsonTreeCore? = null
    private val listeners = ArrayList<TreeModelListener>()

    override fun getRoot(): Any = core?.root() ?: EMPTY
    override fun getChild(parent: Any, index: Int): Any = core!!.child(parent as TreeItem, index)
    override fun getChildCount(parent: Any): Int = if (parent is TreeItem) core?.childCount(parent) ?: 0 else 0
    override fun isLeaf(node: Any): Boolean = node !is TreeItem || core?.isLeaf(node) ?: true
    override fun valueForPathChanged(path: TreePath, newValue: Any?) = Unit
    override fun getIndexOfChild(parent: Any?, child: Any?): Int =
        if (parent is TreeItem && child is TreeItem) core?.indexOfChild(parent, child) ?: -1 else -1

    override fun addTreeModelListener(l: TreeModelListener) {
        listeners += l
    }

    override fun removeTreeModelListener(l: TreeModelListener) {
        listeners -= l
    }

    fun rootChanged() {
        val event = TreeModelEvent(this, arrayOf(root))
        for (l in listeners.toList()) l.treeStructureChanged(event)
    }

    private companion object {
        val EMPTY = TreeItem.Value(0, 0)
    }
}

/** Fixed row height and the large-model hint, so the tree never measures rows (the same setup as the endpoint tree). */
internal class JsonTree(model: TreeModel) : Tree(model) {
    init {
        applyFixedRows()
    }

    override fun updateUI() {
        super.updateUI()
        applyFixedRows()
    }

    private fun applyFixedRows() {
        isLargeModel = true
        rowHeight = JBUI.scale(Limits.JSON_ROW_HEIGHT)
    }
}

/** What a row shows, cut once per node and kept in a small bounded cache. */
internal class RowText(val label: String?, val labelIsKey: Boolean, val kind: JsonKind, val value: String, val suffix: String?)

internal class JsonTreeView(parent: Disposable, private val actions: TreeActions) : JPanel(BorderLayout()), Disposable {
    private val model = JsonTreeModel()
    val tree = JsonTree(model)
    private var index: JsonIndex? = null
    private var core: JsonTreeCore? = null
    private val cache = object : LinkedHashMap<Int, RowText>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RowText>?): Boolean = size > Limits.JSON_DISPLAY_CACHE_ENTRIES
    }
    private var suppressSelection = false

    init {
        Disposer.register(parent, this)
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.addTreeSelectionListener {
            if (!suppressSelection) actions.selected(selectedNode())
        }
        installPopup()
        add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun bind(index: JsonIndex) {
        this.index = index
        core = JsonTreeCore(index).also { model.core = it }
        cache.clear()
        model.rootChanged()
        tree.expandRow(0)
    }

    /** Forgets the document (the viewer is cleared or shows another one). */
    fun unbind() {
        index = null
        core = null
        model.core = null
        cache.clear()
        model.rootChanged()
    }

    /** The index grew (or finished): re-reads counts and keeps what the user had opened. */
    fun refresh() {
        val c = core ?: return
        c.invalidate()
        cache.clear()
        val expanded = TreeUtil.collectExpandedPaths(tree)
        val selection = tree.selectionPath
        suppressSelection = true
        try {
            model.rootChanged()
            TreeUtil.restoreExpandedPaths(tree, expanded)
            if (selection != null) tree.selectionPath = selection
        } finally {
            suppressSelection = false
        }
        if (!tree.isExpanded(0) && tree.rowCount > 0) tree.expandRow(0)
    }

    fun selectedNode(): Int = (tree.selectionPath?.lastPathComponent as? TreeItem.Value)?.node ?: -1

    /** Selects [node] and scrolls to it, opening the containers (and ranges) above it. */
    fun select(node: Int): Boolean {
        val c = core ?: return false
        val items = c.pathTo(node) ?: return false
        val path = TreePath(items.toTypedArray())
        tree.scrollPathToVisible(path)
        tree.selectionPath = path
        tree.requestFocusInWindow()
        return true
    }

    fun collapseAll() {
        for (row in tree.rowCount - 1 downTo 1) tree.collapseRow(row)
    }

    /** Opens containers down to [levels] levels of the document, stopping at [Limits.MAX_EXPANDED_ROWS] opened rows. */
    fun expandToLevel(levels: Int) {
        val c = core ?: return
        var budget = Limits.MAX_EXPANDED_ROWS
        fun open(path: TreePath, remaining: Int) {
            if (remaining <= 0 || budget <= 0) return
            val item = path.lastPathComponent as TreeItem
            if (c.isLeaf(item)) return
            tree.expandPath(path)
            budget--
            val n = c.childCount(item)
            var k = 0
            while (k < n && budget > 0) {
                val child = c.child(item, k)
                val next = if (child is TreeItem.Range) remaining else remaining - 1
                // A range opens only its first rows: its children are the same level as the range's siblings.
                if (child is TreeItem.Range && k > 0) break
                open(path.pathByAddingChild(child), next)
                k++
            }
        }
        open(TreePath(c.root()), levels)
    }

    private fun installPopup() {
        val group = DefaultActionGroup().apply {
            add(UiAction(RouteGenieBundle.message("json.action.copy_path"), RouteGenieBundle.message("json.action.copy_path.tooltip"), null, { selectedNode() >= 0 }) {
                actions.copyPath(selectedNode())
            })
            add(UiAction(RouteGenieBundle.message("json.action.copy_value"), RouteGenieBundle.message("json.action.copy_value.tooltip"), null, { selectedNode() >= 0 }) {
                actions.copyValue(selectedNode())
            })
            add(UiAction(RouteGenieBundle.message("json.action.copy_key"), RouteGenieBundle.message("json.action.copy_key.tooltip"), null, { index?.let { i -> selectedNode() >= 0 && i.hasKey(selectedNode()) } == true }) {
                actions.copyKey(selectedNode())
            })
        }
        PopupHandler.installPopupMenu(tree, group, POPUP_PLACE)
        tree.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ctrl C"), COPY_VALUE)
        tree.actionMap.put(COPY_VALUE, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val n = selectedNode()
                if (n >= 0) actions.copyValue(n)
            }
        })
    }

    override fun dispose() {
        model.core = null
        core = null
        index = null
        cache.clear()
    }

    private fun describe(item: TreeItem.Value): RowText {
        val idx = index ?: return RowText(null, false, JsonKind.NULL, "", null)
        val node = item.node
        if (node < 0 || node >= idx.nodeCount) return RowText(null, false, JsonKind.NULL, "", null)
        cache[node]?.let { return it }
        val kind = idx.kind(node)
        val root = idx.root
        val key = idx.keyOf(node)
        val label = when {
            node == root -> JsonPath.format(emptyList())
            key != null -> key.ifEmpty { EMPTY_KEY }.let { if (it.length > KEY_MAX_CHARS) it.substring(0, KEY_MAX_CHARS) + ELLIPSIS else it }
            else -> "[${item.ordinal}]"
        }
        val text = when {
            kind == JsonKind.OBJECT -> RowText(label, key != null || node == root, kind, "", containerSuffix(idx, node, true))
            kind == JsonKind.ARRAY -> RowText(label, key != null || node == root, kind, "", containerSuffix(idx, node, false))
            kind == JsonKind.STRING -> {
                val p = idx.stringPreview(node, Limits.JSON_STRING_PREVIEW_CHARS)
                val shown = visible(p.text) + if (p.truncated) ELLIPSIS else ""
                RowText(label, key != null || node == root, kind, "\"$shown\"", if (p.truncated) JsonUi.size(p.totalBytes.toLong()) else null)
            }
            else -> RowText(label, key != null || node == root, kind, idx.rawValue(node, NUMBER_MAX_CHARS), null)
        }
        // A container that is still being scanned changes under us; it is described again on the next refresh.
        if (!kind.isContainer || idx.isComplete) cache[node] = text
        return text
    }

    private fun containerSuffix(idx: JsonIndex, node: Int, isObject: Boolean): String {
        val n = idx.childCount(node)
        val open = if (idx.subtreeSize(node) == 0) ELLIPSIS else ""
        return (if (isObject) JsonUi.keys(n) else JsonUi.items(n)) + open
    }

    /** Line breaks and tabs inside a string would break the one-line row. */
    private fun visible(s: String): String =
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0 && s.indexOf('\t') < 0) s else s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val state = if (selected) (if (hasFocus) RowState.SELECTED else RowState.SELECTED_INACTIVE) else RowState.NORMAL
            val primary = RowPainter.foreground(state)
            val muted = RowPainter.secondaryForeground(state)
            when (value) {
                is TreeItem.Range -> {
                    val arrayLike = index?.let { it.kind(value.container) != JsonKind.OBJECT } ?: true
                    append("[${value.from}$RANGE_SEPARATOR${value.to - 1}]", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, primary))
                    append("  " + (if (arrayLike) JsonUi.items(value.to - value.from) else JsonUi.keys(value.to - value.from)), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, muted))
                }
                is TreeItem.Value -> {
                    val t = describe(value)
                    if (t.label != null) {
                        val color = if (selected) primary else if (t.labelIsKey) JsonSyntax.key() else muted
                        append(t.label, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
                        append(LABEL_SEPARATOR, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, muted))
                    }
                    if (t.value.isNotEmpty()) {
                        append(t.value, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (selected) primary else JsonSyntax.of(t.kind)))
                    }
                    if (t.suffix != null) {
                        append((if (t.value.isEmpty()) "" else "  ") + t.suffix, SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, muted))
                    }
                }
            }
        }
    }

    private companion object {
        const val POPUP_PLACE = "RouteGenie.JsonTreePopup"
        const val COPY_VALUE = "routegenie.json.copyValue"
        const val ELLIPSIS = "\u2026"
        const val RANGE_SEPARATOR = " \u2026 "
        const val LABEL_SEPARATOR = ": "
        const val EMPTY_KEY = "\"\""
        const val KEY_MAX_CHARS = 120
        const val NUMBER_MAX_CHARS = 80
    }
}
