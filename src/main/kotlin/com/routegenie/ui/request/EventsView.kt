package com.routegenie.ui.request

import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.ui.design.DesignTokens
import javax.swing.JComponent
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel

/** Rows of an SSE stream (time, event, id, data) with the pretty-printed data of the selected row below. Bounded. */
class EventsView : OnePixelSplitter(true, EVENTS_SPLITTER_PROPORTION) {
    private val rows = ArrayDeque<SseRow>()
    private val model = object : AbstractTableModel() {
        private val names = listOf("req.events.time", "req.events.event", "req.events.id", "req.events.data").map { RouteGenieBundle.message(it) }
        override fun getRowCount() = rows.size
        override fun getColumnCount() = names.size
        override fun getColumnName(c: Int) = names[c]
        override fun getValueAt(r: Int, c: Int): Any? {
            val row = rows[r]
            return when (c) {
                0 -> ByteSize.millis(row.atMs)
                1 -> row.event.orEmpty()
                2 -> row.id.orEmpty()
                else -> row.data.replace('\n', ' ').take(Limits.LIVE_CELL_MAX_CHARS)
            }
        }
    }
    private val table = JBTable(model).apply {
        setShowGrid(false)
        selectionModel.addListSelectionListener { e: ListSelectionEvent -> if (!e.valueIsAdjusting) showSelected() }
    }
    private val detail = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        font = DesignTokens.Type.mono()
        margin = JBUI.insets(DesignTokens.Space.S)
    }

    var droppedRows = 0L
        private set

    val rowCount: Int get() = rows.size

    init {
        firstComponent = JBScrollPane(table)
        secondComponent = JBScrollPane(detail)
    }

    val component: JComponent get() = this

    fun add(batch: List<SseRow>) {
        if (batch.isEmpty()) return
        val firstNew = rows.size
        rows.addAll(batch)
        var removed = 0
        while (rows.size > Limits.LIVE_EVENT_ROWS_MAX) { rows.removeFirst(); removed++ }
        droppedRows += removed
        if (removed > 0) model.fireTableDataChanged() else model.fireTableRowsInserted(firstNew, rows.size - 1)
    }

    fun clear() {
        rows.clear()
        droppedRows = 0
        model.fireTableDataChanged()
        detail.text = ""
    }

    private fun showSelected() {
        val i = table.selectedRow
        if (i < 0 || i >= rows.size) { detail.text = ""; return }
        val data = rows[i].data
        // Same bounded re-indenter as the body viewer: data above the limit is shown as it came.
        val pretty = if (data.length <= Limits.VIEWER_PRETTY_MAX_CHARS) JsonPretty.format(data.trim()).text else null
        detail.text = pretty ?: data
        detail.caretPosition = 0
    }

    private companion object {
        const val EVENTS_SPLITTER_PROPORTION = 0.6f
    }
}
