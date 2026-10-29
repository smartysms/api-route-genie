package com.routegenie.ui.json

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.json.JsonIndex
import com.routegenie.json.JsonKind
import com.routegenie.json.JsonPrinter
import com.routegenie.json.JsonTable
import com.routegenie.ui.UiAction
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.RowPainter
import com.routegenie.ui.design.RowState
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.table.AbstractTableModel

internal interface TableActions {
    /** The row's element is selected; [node] is the element of the array (an object, usually). */
    fun rowSelected(node: Int)
    fun message(text: String)
}

/**
 * An array of objects as a table. Rows and cells are read from the index when painted, so a million rows cost nothing until
 * they scroll into view. Sorting by a column builds an order array in the background (up to the row limit).
 */
internal class JsonTableView(parent: Disposable, private val actions: TableActions) : JPanel(BorderLayout()), Disposable {
    private var core: JsonTable? = null
    private var order: IntArray? = null
    private var sortColumn = -1
    private var ascending = true
    private var sortId = 0
    private var disposed = false

    private val model = object : AbstractTableModel() {
        override fun getRowCount(): Int = core?.rowCount ?: 0
        override fun getColumnCount(): Int = (core?.columns?.size ?: 0) + 1
        override fun getColumnName(column: Int): String {
            if (column == 0) return ROW_NUMBER_HEADER
            val name = core?.columns?.get(column - 1).orEmpty()
            return if (column == sortColumn) name + (if (ascending) ASCENDING else DESCENDING) else name
        }

        override fun getValueAt(row: Int, column: Int): Any {
            val c = core ?: return NONE
            val actual = order?.get(row) ?: row
            return if (column == 0) actual else c.cellNode(actual, column - 1)
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }

    val table = JBTable(model)

    init {
        Disposer.register(parent, this)
        table.autoResizeMode = JTable.AUTO_RESIZE_OFF
        table.rowHeight = JBUI.scale(Limits.JSON_ROW_HEIGHT)
        table.setShowGrid(false)
        table.fillsViewportHeight = true
        table.background = Palette.surface
        table.foreground = Palette.textPrimary
        table.selectionBackground = Palette.selection
        table.selectionForeground = Palette.selectionText
        table.tableHeader.background = Palette.raised
        table.tableHeader.foreground = Palette.textSecondary
        table.setDefaultRenderer(Any::class.java, Cell())
        table.tableHeader.reorderingAllowed = false
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) selectedRowNode().takeIf { it >= 0 }?.let { actions.rowSelected(it) }
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = table.columnAtPoint(e.point)
                if (column >= 1) sortBy(column)
            }
        })
        installCopy()
        add(
            JBScrollPane(table).apply {
                border = JBUI.Borders.empty()
                viewport.background = Palette.surface
                setColumnHeaderView(table.tableHeader)
            },
            BorderLayout.CENTER,
        )
    }

    val hasRows: Boolean get() = (core?.rowCount ?: 0) > 0
    val tableCore: JsonTable? get() = core

    fun bind(table: JsonTable) {
        core = table
        order = null
        sortColumn = -1
        sortId++
        model.fireTableStructureChanged()
        for (c in 0 until this.table.columnModel.columnCount) {
            val col = this.table.columnModel.getColumn(c)
            col.preferredWidth = if (c == 0) JBUI.scale(ROW_NUMBER_WIDTH) else widthFor(table, c - 1)
        }
        if (table.rowCount > 0) this.table.setRowSelectionInterval(0, 0)
    }

    fun clear() {
        core = null
        order = null
        sortId++
        model.fireTableStructureChanged()
    }

    /** Wide enough for the header and the first rows' values (measured, not guessed), within a minimum and a maximum. */
    private fun widthFor(core: JsonTable, column: Int): Int {
        val fm = table.getFontMetrics(table.font)
        var widest = fm.stringWidth(core.columns[column])
        val rows = minOf(core.rowCount, WIDTH_SAMPLE_ROWS)
        for (r in 0 until rows) {
            val node = core.cellNode(r, column)
            if (node < 0 || core.index.isContainer(node)) continue
            val preview = core.index.stringPreview(node, WIDTH_SAMPLE_CHARS)
            widest = maxOf(widest, fm.stringWidth(preview.text))
        }
        return (widest + JBUI.scale(PADDING)).coerceIn(JBUI.scale(MIN_COLUMN_WIDTH), JBUI.scale(MAX_COLUMN_WIDTH))
    }

    /** The array element of the selected row, or -1. */
    fun selectedRowNode(): Int {
        val c = core ?: return -1
        val viewRow = table.selectedRow
        if (viewRow < 0) return -1
        return c.rowNode(order?.get(viewRow) ?: viewRow)
    }

    /** Selects the row that holds [node] (an element of the array or anything below one). */
    fun selectRowOf(node: Int): Boolean {
        val c = core ?: return false
        val idx = c.index
        var n = node
        while (n > 0 && idx.parent(n) != c.arrayNode) n = idx.parent(n)
        if (n <= 0 || idx.parent(n) != c.arrayNode) return false
        val actual = idx.ordinalOf(n)
        val ord = order
        val viewRow = if (ord == null) actual else ord.indexOf(actual)
        if (viewRow < 0) return false
        table.setRowSelectionInterval(viewRow, viewRow)
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true))
        return true
    }

    private fun sortBy(column: Int) {
        val c = core ?: return
        if (c.rowCount > Limits.JSON_TABLE_SORT_MAX_ROWS) {
            actions.message(RouteGenieBundle.message("json.table.sort_limit", Limits.JSON_TABLE_SORT_MAX_ROWS))
            return
        }
        if (sortColumn == column && !ascending) {
            sortColumn = -1
            order = null
            model.fireTableStructureChanged()
            return
        }
        ascending = if (sortColumn == column) false else true
        sortColumn = column
        val id = ++sortId
        val asc = ascending
        JsonUi.background {
            val result = c.sortedRows(column - 1, asc) { id != sortId || disposed }
            JsonUi.later({ disposed || id != sortId }) {
                if (result != null) {
                    order = result
                    refreshKeepingWidths()
                }
            }
        }
    }

    private fun refreshKeepingWidths() {
        val widths = IntArray(table.columnModel.columnCount) { table.columnModel.getColumn(it).preferredWidth }
        model.fireTableStructureChanged()
        for (i in widths.indices) if (i < table.columnModel.columnCount) table.columnModel.getColumn(i).preferredWidth = widths[i]
    }

    private fun installCopy() {
        val copyCell = {
            val c = core
            if (c != null && table.selectedRow >= 0 && table.selectedColumn >= 1) {
                val node = model.getValueAt(table.selectedRow, table.selectedColumn) as Int
                if (node >= 0) JsonUi.copy(cellText(c.index, node))
            }
        }
        table.inputMap.put(KeyStroke.getKeyStroke("ctrl C"), COPY_CELL)
        table.actionMap.put(COPY_CELL, object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = copyCell()
        })
        val group = DefaultActionGroup().apply {
            add(UiAction(RouteGenieBundle.message("json.action.copy_cell"), RouteGenieBundle.message("json.action.copy_cell.tooltip"), null, { table.selectedRow >= 0 }) { copyCell() })
            add(UiAction(RouteGenieBundle.message("json.action.copy_row"), RouteGenieBundle.message("json.action.copy_row.tooltip"), null, { selectedRowNode() >= 0 }) {
                val c = core
                val node = selectedRowNode()
                if (c != null && node >= 0) JsonUi.copy(JsonPrinter.toString(c.index, node, com.routegenie.json.PrintOptions(), Limits.JSON_TEXT_FULL_MAX_CHARS.toLong()))
            })
        }
        PopupHandler.installPopupMenu(table, group, POPUP_PLACE)
    }

    private fun cellText(index: JsonIndex, node: Int): String =
        if (index.isContainer(node)) JsonPrinter.toString(index, node, com.routegenie.json.PrintOptions(), Limits.JSON_TEXT_FULL_MAX_CHARS.toLong()) else index.stringValue(node)

    override fun dispose() {
        disposed = true
        sortId++
        core = null
        order = null
    }

    private inner class Cell : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val state = if (selected) (if (hasFocus) RowState.SELECTED else RowState.SELECTED_INACTIVE) else RowState.NORMAL
            val muted = RowPainter.secondaryForeground(state)
            val primary = RowPainter.foreground(state)
            val number = value as? Int ?: return
            val c = core ?: return
            if (column == 0) {
                append((number + 1).toString(), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, muted))
                return
            }
            if (number < 0) return
            val idx = c.index
            when (val kind = idx.kind(number)) {
                JsonKind.OBJECT -> append(JsonUi.keys(idx.childCount(number)), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, muted))
                JsonKind.ARRAY -> append(JsonUi.items(idx.childCount(number)), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, muted))
                else -> {
                    val p = idx.stringPreview(number, Limits.JSON_TABLE_CELL_PREVIEW_CHARS)
                    val text = (if (p.text.indexOf('\n') >= 0) p.text.replace("\n", "\\n") else p.text) + if (p.truncated) ELLIPSIS else ""
                    append(text, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (selected) primary else JsonSyntax.of(kind)))
                }
            }
        }
    }

    private companion object {
        const val POPUP_PLACE = "RouteGenie.JsonTablePopup"
        const val COPY_CELL = "routegenie.json.copyCell"
        const val ROW_NUMBER_HEADER = "#"
        const val ROW_NUMBER_WIDTH = 56
        const val WIDTH_SAMPLE_ROWS = 50
        const val WIDTH_SAMPLE_CHARS = 48
        const val PADDING = 24
        const val MIN_COLUMN_WIDTH = 96
        const val MAX_COLUMN_WIDTH = 280
        const val ASCENDING = " ▲"
        const val DESCENDING = " ▼"
        const val ELLIPSIS = "…"
        val NONE = Any()
    }
}
