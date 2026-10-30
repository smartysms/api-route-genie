package com.routegenie.ui.pins

import com.intellij.CommonBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.Framework
import com.routegenie.model.RouteSnapshot
import com.routegenie.pinside.PinResult
import com.routegenie.pinside.PinsService
import com.routegenie.pinside.PinsState
import com.routegenie.pins.Pin
import com.routegenie.pins.PinRules
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * The small pins view: every confirmed value with the setting it belongs to, whether any route uses it now, editing of the
 * value in place (same validation as the strip) and deletion. A pin whose setting the code no longer uses is shown as unused
 * and stays harmless. File problems are shown once at the top.
 */
class PinsPanel(
    private val project: Project,
    parent: Disposable,
    private val service: PinsService = PinsService.getInstance(project),
    private val background: (Runnable) -> Unit = { AppExecutorUtil.getAppExecutorService().execute(it) },
    private val snapshotOf: () -> RouteSnapshot = { RouteDiscoveryService.getInstance(project).snapshot.value },
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var disposed = false

    internal inner class Model : AbstractTableModel() {
        var pins: List<Pin> = emptyList()
        var uses: Map<String, Int> = emptyMap()

        override fun getRowCount() = pins.size
        override fun getColumnCount() = COLUMNS.size
        override fun getColumnName(column: Int) = RouteGenieBundle.message(COLUMNS[column])
        override fun isCellEditable(row: Int, column: Int) = column == COL_VALUE

        override fun getValueAt(row: Int, column: Int): Any {
            val pin = pins[row]
            return when (column) {
                COL_EXPRESSION -> pin.expression
                COL_VALUE -> pin.value
                COL_STATUS -> uses[pin.expression]?.takeIf { it > 0 }
                    ?.let { RouteGenieBundle.message("pins.view.status.used", it) } ?: RouteGenieBundle.message("pins.view.status.unused")
                else -> pin.note
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column != COL_VALUE) return
            changeValue(pins.getOrNull(row) ?: return, value?.toString().orEmpty())
        }
    }

    internal val model = Model()
    internal val table = JBTable(model).apply {
        setShowGrid(false)
        emptyText.text = RouteGenieBundle.message("pins.view.empty")
        rowHeight = JBUI.scale(DesignTokens.Rows.TREE)
    }
    internal val error = JBLabel().apply {
        foreground = Palette.danger.text
        font = DesignTokens.Type.caption()
        isVisible = false
    }
    private val fileLabel = JBLabel().apply {
        font = DesignTokens.Type.caption()
        foreground = Palette.textSecondary
    }
    private val problemLabel = JBLabel().apply {
        font = DesignTokens.Type.caption()
        foreground = Palette.danger.text
        isVisible = false
    }
    internal val deleteButton = JButton(RouteGenieBundle.message("pins.view.delete")).apply {
        isEnabled = false
        addActionListener { deleteSelected() }
    }
    internal val openButton = JButton(RouteGenieBundle.message("pins.view.open_file")).apply { addActionListener { openFile() } }

    init {
        Disposer.register(parent, this)
        border = Space.border(Space.M)
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(fileLabel, BorderLayout.NORTH)
            add(problemLabel, BorderLayout.CENTER)
            add(JBLabel(RouteGenieBundle.message("pins.view.hint")).apply {
                font = DesignTokens.Type.caption()
                foreground = Palette.textSecondary
            }, BorderLayout.SOUTH)
            border = Space.border(0, 0, Space.S, 0)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = Space.border(Space.S, 0, 0, 0)
            add(error, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.S), 0)).apply {
                isOpaque = false
                add(deleteButton)
                add(openButton)
            }, BorderLayout.CENTER)
        }
        add(bottom, BorderLayout.SOUTH)
        table.selectionModel.addListSelectionListener { deleteButton.isEnabled = table.selectedRow >= 0 }
        preferredSize = JBUI.size(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        refresh(service.snapshot.value)
        scope.launch {
            combine(service.snapshot, RouteDiscoveryService.getInstance(project).snapshot) { pins, _ -> pins }.collect { pins ->
                withContext(Dispatchers.EDT) { if (!disposed) refresh(pins) }
            }
        }
    }

    private fun refresh(state: PinsState) {
        val selected = table.selectedRow.takeIf { it >= 0 }?.let { model.pins.getOrNull(it)?.expression }
        val endpoints = snapshotOf().endpoints
        model.uses = state.pins.associate { pin -> pin.expression to endpoints.count { pin.expression in it.pins } }
        model.pins = state.pins
        model.fireTableDataChanged()
        selected?.let { key -> model.pins.indexOfFirst { it.expression == key }.takeIf { it >= 0 }?.let { table.setRowSelectionInterval(it, it) } }
        fileLabel.text = RouteGenieBundle.message("pins.view.file", service.filePath()?.toString().orEmpty())
        val problem = state.problem
        problemLabel.isVisible = problem != null
        problemLabel.text = problem?.let { RouteGenieBundle.message(it.bundleKey, state.where?.let { w -> ", $w" }.orEmpty()) }.orEmpty()
    }

    private fun slashFor(expression: String): Boolean {
        val framework = snapshotOf().endpoints.firstOrNull { expression in it.pins }?.framework ?: return false
        return framework != Framework.DJANGO && framework != Framework.DRF
    }

    private fun showError(text: String?) {
        error.text = text.orEmpty()
        error.isVisible = !text.isNullOrEmpty()
    }

    /** Edits the value in place: the same rules as the strip; a refused value leaves the old one and says why. */
    internal fun changeValue(pin: Pin, value: String) {
        if (value == pin.value) return
        val next = pin.copy(value = value, at = "")
        val slash = slashFor(pin.expression)
        PinRules.check(next, slash)?.let { showError(RouteGenieBundle.message(it.bundleKey)); return }
        showError(null)
        run { service.set(next, slash) }
    }

    internal fun deleteSelected() {
        val pin = model.pins.getOrNull(table.selectedRow) ?: return
        showError(null)
        run { service.remove(pin.expression) }
    }

    private fun run(change: () -> PinResult) {
        background(Runnable {
            val result = change()
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                when (result) {
                    PinResult.Saved -> refresh(service.snapshot.value)
                    is PinResult.Refused -> showError(RouteGenieBundle.message(result.problem.bundleKey))
                    is PinResult.Failed -> showError(RouteGenieBundle.message("pins.view.edit_failed", result.file?.let { RouteGenieBundle.message(it.bundleKey, "") } ?: result.message.orEmpty()))
                }
            }, ModalityState.any())
        })
    }

    private fun openFile() {
        val path = service.filePath() ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    private companion object {
        val COLUMNS = listOf("pins.view.column.expression", "pins.view.column.value", "pins.view.column.status", "pins.view.column.note")
        const val COL_EXPRESSION = 0
        const val COL_VALUE = 1
        const val COL_STATUS = 2
        const val PREFERRED_WIDTH = 640
        const val PREFERRED_HEIGHT = 280
    }
}

/** The pins view in a dialog the user opens on purpose (menu, link in the strip or in the details); asking happens in the window. */
class PinsDialog(private val project: Project) : DialogWrapper(project, true) {
    init {
        title = RouteGenieBundle.message("pins.view.title")
        setOKButtonText(CommonBundle.getCloseButtonText())
        init()
    }

    override fun createCenterPanel(): JComponent = PinsPanel(project, disposable)

    override fun createActions() = arrayOf(okAction)
}

/** Tools | Edit Pins...: opens the pins view. */
class EditPinsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        PinsDialog(e.project ?: return).show()
    }
}
