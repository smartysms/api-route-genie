package com.routegenie.ui.tests

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.model.Endpoint
import com.routegenie.model.SourceRef
import com.routegenie.testmap.CallOrigin
import com.routegenie.testmap.TestCall
import com.routegenie.testmap.TestIndexSource
import com.routegenie.testmap.Reach
import com.routegenie.testmap.TestMark
import com.routegenie.testmap.UnattributedCall
import com.routegenie.ui.EndpointSourceNavigation
import com.routegenie.ui.design.BadgeState
import com.routegenie.ui.design.Chip
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.EmptyState
import com.routegenie.ui.design.LoadingState
import com.routegenie.ui.design.MethodBadge
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.RouteGenieIcons
import com.routegenie.ui.design.RowPainter
import com.routegenie.ui.design.RowState
import com.routegenie.ui.design.ToolbarStyle
import com.routegenie.ui.model.TestsCard
import com.routegenie.ui.model.TestsView
import com.routegenie.ui.model.TestsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

/**
 * The Tests tab: the project's own tests that call the selected endpoint, found by static analysis (nothing is run or sent
 * here). A summary line says how many tests reach the endpoint, the list shows each test call (verb, test, file and line,
 * direct or indirect, skipped or expected to fail), and a double click or Enter opens the call in the editor. The page below
 * the list shows the request the selected test makes and runs it on request ([execution]).
 *
 * It owns no analysis: it renders the immutable index of [source] and tells the source when it is on screen ([setShown]) so
 * the work stays lazy. Host independent, so it can sit in the response area or become a page of its own.
 */
class TestsPanel(
    private val project: Project,
    parent: Disposable,
    private val source: () -> TestIndexSource,
    /** The title badge: called with the number of tests that reach the selected endpoint (0 when none or unknown). */
    private val onBadge: (Int) -> Unit = {},
    private val navigate: (SourceRef) -> Unit = { EndpointSourceNavigation.navigate(project, it) },
    /** Builds the page that shows and runs the selected test; null for a list-only panel. */
    private val executionFactory: ((Disposable) -> TestExecutionPanel)? = null,
) : JBPanel<TestsPanel>(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collector: Job? = null
    private var disposed = false
    private var shown = false
    private var endpoint: Endpoint? = null
    private var view: TestsView = TestsView.NONE

    private val model = DefaultListModel<TestCall>()
    private val list = JBList(model)
    private val renderer = TestRowRenderer()
    private val summary = JBLabel().apply { foreground = Palette.textSecondary; border = JBUI.Borders.empty(Space.XS, Space.M) }
    private val untestedChip = Chip("", Palette.warning)
    private val doubtNote = JBLabel().apply { foreground = Palette.textSecondary; border = JBUI.Borders.empty(0, Space.M, Space.XS, Space.M) }
    private val cards = JPanel(CardLayout())
    private val loading = LoadingState(this, msg("tests.indexing"))
    private var execution: TestExecutionPanel? = null
    private val splitter = OnePixelSplitter(true, SPLITTER_KEY, Limits.TESTS_LIST_PROPORTION)
    private var currentCard = ""
    private var rebuilding = false

    init {
        Disposer.register(parent, this)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = renderer
        list.emptyText.text = ""
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) goToSelected()
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isControlDown) {
                    goToSelected()
                    e.consume()
                }
            }
        })
        list.addListSelectionListener { if (!it.valueIsAdjusting) selectionChanged() }

        cards.add(EmptyState(RouteGenieIcons.EMPTY_ROUTES, msg("tests.select.title"), msg("tests.select.hint")), CARD_SELECT)
        cards.add(loading, CARD_INDEXING)
        cards.add(EmptyState(RouteGenieIcons.EMPTY_PROJECT, msg("tests.no_files.title"), msg("tests.no_files.hint")), CARD_NO_FILES)
        cards.add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, CARD_LIST)

        val toolbar = ToolbarStyle.create(TOOLBAR_PLACE, DefaultActionGroup().apply {
            add(object : DumbAwareAction(msg("tests.action.refresh"), msg("tests.action.refresh.tooltip"), AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = source().requestRefresh()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : DumbAwareAction(msg("tests.action.unattributed"), msg("tests.action.unattributed.tooltip"), AllIcons.General.Information) {
                override fun actionPerformed(e: AnActionEvent) = showUnattributed(e)
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = shown && source().index.value.unattributed.isNotEmpty() }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(object : DumbAwareAction(msg("tests.action.go"), msg("tests.action.go.tooltip"), AllIcons.Actions.EditSource) {
                override fun actionPerformed(e: AnActionEvent) = goToSelected()
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = list.selectedValue != null }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }, this)
        val strip = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(summary, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(Space.S), JBUI.scale(Space.XS))).apply { isOpaque = false; add(untestedChip) }, BorderLayout.EAST)
            add(doubtNote, BorderLayout.SOUTH)
        }
        add(ToolbarStyle.bar(toolbar, strip), BorderLayout.NORTH)
        splitter.firstComponent = cards
        add(splitter, BorderLayout.CENTER)
        untestedChip.isVisible = false
        doubtNote.isVisible = false
        showCard(CARD_SELECT)
    }

    // ---- what is on screen ------------------------------------------------------------------------------------------

    /** Called on the UI thread with the selected endpoint, or null when a group or nothing is selected. */
    fun show(endpoint: Endpoint?) {
        if (disposed) return
        this.endpoint = endpoint
        execution?.showTest(null, null)
        refresh()
    }

    /** The tab's host tells whether anybody looks; only then the index is read and kept up to date. */
    fun setShown(value: Boolean) {
        if (disposed || value == shown) return
        shown = value
        if (value) {
            val s = source()
            s.setActive(true)
            collector = scope.launch {
                s.index.collect { withContext(Dispatchers.EDT) { if (!disposed) refresh() } }
            }
            refresh()
        } else {
            collector?.cancel()
            collector = null
            if (!project.isDisposed) source().setActive(false)
        }
    }

    private fun refresh() {
        val index = if (shown) source().index.value else com.routegenie.testmap.TestIndex.EMPTY
        val next = TestsViewModel.build(index, endpoint)
        val keep = list.selectedValue
        view = next
        // Rebuilding the list must not reset the execution page: it is told only when the selected call is gone or changed.
        rebuilding = true
        try {
            model.clear()
            next.calls.forEach { model.addElement(it) }
            val kept = keep?.let { k -> next.calls.indexOfFirst { it.test.id == k.test.id && it.source.offset == k.source.offset } } ?: -1
            if (kept >= 0) list.selectedIndex = kept
        } finally {
            rebuilding = false
        }
        if (list.selectedValue != keep) execution?.showTest(endpoint, list.selectedValue)
        summary.text = summaryText(next)
        untestedChip.isVisible = next.card == TestsCard.NONE_FOUND && next.untested
        untestedChip.text = msg("tests.untested")
        val doubtText = doubtText(next)
        doubtNote.text = doubtText.orEmpty()
        doubtNote.isVisible = !doubtText.isNullOrEmpty()
        showCard(
            when (next.card) {
                TestsCard.SELECT_ENDPOINT -> CARD_SELECT
                TestsCard.INDEXING -> CARD_INDEXING
                TestsCard.NO_TEST_FILES -> CARD_NO_FILES
                TestsCard.NONE_FOUND -> CARD_NONE
                TestsCard.TESTS -> CARD_LIST
            },
        )
        onBadge(next.badge)
        revalidate()
        repaint()
    }

    private fun showCard(name: String) {
        if (name == CARD_NONE) {
            // The "no test found" page carries the numbers, so it is rebuilt with them.
            val old = cards.components.firstOrNull { it.name == CARD_NONE }
            if (old != null) cards.remove(old)
            val hint = if (view.doubt > 0) msg("tests.none.hint_doubt", view.doubt) else msg("tests.none.hint")
            cards.add(EmptyState(RouteGenieIcons.EMPTY_ROUTES, msg("tests.none.title"), hint).apply { this.name = CARD_NONE }, CARD_NONE)
        }
        currentCard = name
        (cards.layout as CardLayout).show(cards, name)
        val hasList = name == CARD_LIST
        execution?.isVisible = hasList
        if (!hasList) splitter.secondComponent = null else ensureExecution()
    }

    private fun ensureExecution() {
        val factory = executionFactory ?: return
        val page = execution ?: factory(this).also { execution = it }
        if (splitter.secondComponent !== page) splitter.secondComponent = page
        page.isVisible = true
    }

    private fun summaryText(v: TestsView): String = when (v.card) {
        TestsCard.SELECT_ENDPOINT -> ""
        TestsCard.INDEXING -> msg("tests.indexing")
        TestsCard.NO_TEST_FILES -> ""
        TestsCard.NONE_FOUND -> msg("tests.summary.none")
        TestsCard.TESTS -> buildString {
            append(msg("tests.summary.reached", v.reached))
            if (v.reached > 0) append(" (").append(msg("tests.summary.split", v.direct, v.indirect)).append(')')
            if (v.off > 0) append("  -  ").append(msg("tests.summary.off", v.off))
            if (v.hidden > 0) append("  -  ").append(msg("tests.summary.hidden", v.hidden))
        }
    }

    private fun doubtText(v: TestsView): String? = when {
        v.card == TestsCard.TESTS && v.doubt > 0 -> msg("tests.doubt.some", v.doubt)
        v.card == TestsCard.NONE_FOUND && v.unattributed > 0 -> msg("tests.doubt.project", v.unattributed)
        else -> null
    }

    // ---- selection and navigation -----------------------------------------------------------------------------------

    private fun selectionChanged() {
        if (rebuilding) return
        val call = list.selectedValue
        execution?.showTest(endpoint, call)
    }

    /**
     * The test calls that could not be attributed, with the reason of each: those that might be about the selected endpoint
     * first, then the rest. Choosing one opens it in the editor.
     */
    private fun showUnattributed(e: AnActionEvent) {
        val index = source().index.value
        val key = endpoint?.key
        fun relevant(c: UnattributedCall) = (c.reach as? Reach.Endpoints)?.keys?.contains(key) == true || c.reach == Reach.Anywhere
        val items = (index.unattributed.filter(::relevant) + index.unattributed.filterNot(::relevant)).take(Limits.TESTS_SHOWN_PER_ENDPOINT_MAX)
        if (items.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(msg("tests.unattributed.title"))
            .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): Component =
                    super.getListCellRendererComponent(list, unattributedText(value as UnattributedCall), index, sel, focus)
            })
            .setItemChosenCallback { navigate(it.source) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    private fun goToSelected() {
        val call = list.selectedValue ?: return
        navigate(call.source)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        collector?.cancel()
        scope.cancel()
    }

    // ---- test hooks -------------------------------------------------------------------------------------------------

    internal fun cardName(): String = currentCard
    internal fun summaryForTest(): String = summary.text
    internal fun rowsForTest(): List<TestCall> = (0 until model.size).map { model.getElementAt(it) }
    internal fun selectRowForTest(index: Int) { list.selectedIndex = index }
    internal fun goToSelectedForTest() = goToSelected()
    internal fun untestedVisible(): Boolean = untestedChip.isVisible
    internal fun doubtTextForTest(): String = doubtNote.text.takeIf { doubtNote.isVisible }.orEmpty()
    internal fun executionForTest(): TestExecutionPanel? = execution
    internal fun rendererTextForTest(index: Int): String = renderer.textOf(model.getElementAt(index))
    internal fun listForTest(): JBList<TestCall> = list
    internal fun unattributedTextForTest(call: UnattributedCall): String = unattributedText(call)

    companion object {
        private const val CARD_SELECT = "select"
        private const val CARD_INDEXING = "indexing"
        private const val CARD_NO_FILES = "nofiles"
        private const val CARD_NONE = "none"
        private const val CARD_LIST = "list"
        private const val TOOLBAR_PLACE = "RouteGenie.Tests"
        private const val SPLITTER_KEY = "RouteGenie.TestsSplitter"
    }
}

/** `tests/test_x.py:12  GET  <reason>` for the list of calls that are not attributed. */
internal fun unattributedText(call: UnattributedCall): String =
    "${call.source.path}:${call.source.line}  ${call.verb?.name.orEmpty()}  ${msg(call.reason.bundleKey, call.detail.orEmpty())}"

/** One row: verb badge, test, path as called, file and line, direct or indirect, marks. Painted from cached labels, nothing allocated per row beyond text. */
internal class TestRowRenderer : JPanel(), ListCellRenderer<TestCall> {
    private val badge = JBLabel()
    private val name = JBLabel().apply { font = DesignTokens.Type.bodyStrong() }
    private val path = JBLabel().apply { font = DesignTokens.Type.mono() }
    private val where = JBLabel().apply { font = DesignTokens.Type.caption() }
    private val marks = JBLabel().apply { font = DesignTokens.Type.caption() }
    private var state = RowState.NORMAL

    init {
        layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.S), JBUI.scale(Space.XS))
        isOpaque = false
        add(badge)
        add(name)
        add(path)
        add(where)
        add(marks)
    }

    fun textOf(call: TestCall): String = buildString {
        append(call.verb.name).append(' ').append(call.test.displayName).append(' ').append(call.path).append(' ')
        append(call.source.path).append(':').append(call.source.line)
        marksOf(call).takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
    }

    private fun marksOf(call: TestCall): String {
        val parts = ArrayList<String>()
        parts += msg(if (call.origin == CallOrigin.DIRECT) "tests.row.direct" else "tests.row.indirect")
        when (call.test.mark) {
            TestMark.SKIPPED -> parts += msg("tests.mark.skipped")
            TestMark.XFAIL -> parts += msg("tests.mark.xfail")
            TestMark.CONDITIONAL_SKIP -> parts += msg("tests.mark.conditional")
            TestMark.NONE -> Unit
        }
        if (call.viaRedirect) parts += msg("tests.row.redirect")
        call.expectedStatus?.let { parts += msg("tests.row.expects", it) }
        return parts.joinToString("  -  ")
    }

    override fun getListCellRendererComponent(list: JList<out TestCall>, value: TestCall, index: Int, selected: Boolean, focus: Boolean): Component {
        state = when {
            selected && list.hasFocus() -> RowState.SELECTED
            selected -> RowState.SELECTED_INACTIVE
            else -> RowState.NORMAL
        }
        val badgeState = when (state) {
            RowState.SELECTED -> BadgeState.SELECTED
            RowState.SELECTED_INACTIVE -> BadgeState.SELECTED_INACTIVE
            else -> BadgeState.NORMAL
        }
        badge.icon = MethodBadge.of(value.verb, badgeState)
        name.text = value.test.displayName
        path.text = value.path
        where.text = "${value.source.path}:${value.source.line}"
        marks.text = marksOf(value)
        val off = value.test.mark.isOff
        val primary = RowPainter.foreground(state)
        val secondary = RowPainter.secondaryForeground(state)
        name.foreground = if (off) secondary else primary
        path.foreground = if (off) secondary else primary
        where.foreground = secondary
        marks.foreground = if (state == RowState.NORMAL && value.test.mark != TestMark.NONE) Palette.warning.text else secondary
        toolTipText = textOf(value)
        return this
    }

    override fun paintComponent(g: Graphics) {
        RowPainter.paint(g as Graphics2D, 0, 0, width, height, state)
        super.paintComponent(g)
    }
}
