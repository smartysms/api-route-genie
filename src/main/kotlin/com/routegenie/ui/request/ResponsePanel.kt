package com.routegenie.ui.request

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.routegenie.RouteGenieBundle
import com.routegenie.client.ErrorKind
import com.routegenie.client.Header
import com.routegenie.client.ModeOverride
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.client.ResponseMode
import com.routegenie.client.curl.CurlEmitter
import com.routegenie.client.curl.CurlOptions
import com.routegenie.client.curl.CurlParseResult
import com.routegenie.client.curl.CurlParser
import com.routegenie.client.curl.CurlShell
import com.routegenie.client.curl.CurlUnsupportedException
import com.routegenie.client.curl.ParsedCurl
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.EmptyState
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.SectionHeader
import com.routegenie.ui.design.StatusPill
import com.routegenie.ui.design.ToolbarStyle
import com.routegenie.ui.json.JsonDiffPanel
import com.routegenie.ui.json.JsonViewerPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

/**
 * Everything about the answer, in the window: status line, notices, Body (plain viewer; the JSON visualizer takes over for JSON
 * when it is available), Events (SSE), Headers, the exact Request, cURL and History. It reads [SendController.state] and
 * never touches the network. Formatting of finished bodies runs on a pooled thread; the UI thread only swaps documents.
 */
class ResponsePanel(
    private val project: Project,
    parent: Disposable,
    private val controller: SendController,
    private val history: HistoryLog,
    private val currentSpec: (Boolean) -> RequestSpec?,
    private val onRestore: (HistoryEntry) -> Unit,
    private val onImport: (ParsedCurl) -> Unit,
) : JBPanel<ResponsePanel>(BorderLayout()), Disposable, SendController.Listener {
    private val props = PropertiesComponent.getInstance()
    private var disposed = false
    private var formatGen = 0

    private val pill = StatusPill()
    private val meta = JBLabel().apply { foreground = Palette.textSecondary }
    private val notice = JBLabel().apply { foreground = Palette.warning.text; isVisible = false }
    private val viewer = BodyViewer(project, this)
    private val json = JsonViewerPanel(this)
    private val diff = JsonDiffPanel(this)
    private val events = EventsView()
    private val bodyCards = JPanel(CardLayout())
    private val headersModel = DefaultTableModel(arrayOf(msg("req.table.name"), msg("req.table.value")), 0)
    private val requestText = JBTextArea().apply { isEditable = false; font = DesignTokens.Type.mono(); margin = JBUI.insets(DesignTokens.Space.S) }
    private val curlText = JBTextArea().apply { isEditable = false; font = DesignTokens.Type.mono(); lineWrap = true; margin = JBUI.insets(DesignTokens.Space.S) }
    private val curlNote = JBLabel().apply { foreground = Palette.textSecondary }
    private val curlImport = JBTextArea(CURL_IMPORT_ROWS, 0).apply { lineWrap = true; font = DesignTokens.Type.mono() }
    private val curlImportResult = JBLabel().apply { foreground = Palette.textSecondary }
    private val historyModel = DefaultListModel<HistoryEntry>()
    private val historyList = JBList(historyModel)
    private val tabs = JBTabbedPane()
    private val eventsTabTitle = msg("req.tab.events")
    private var eventsTabShown = false

    private var shell = CurlShell.entries.firstOrNull { it.name == props.getValue(PROP_SHELL) } ?: defaultShell()
    private var includeSecrets = false
    private var prettyBody = props.getBoolean(PROP_PRETTY, true)
    private var lastResponseKey: Any? = null
    private var diffShown = false
    private var jsonShown = false
    private var errorShown = false
    private var currentEntry: HistoryEntry? = null

    private val shellBoxes = CurlShell.entries.associateWith { s ->
        javax.swing.JRadioButton(RequestMessages.shell(s), s == shell).apply {
            addActionListener { shell = s; props.setValue(PROP_SHELL, s.name); refreshCurl() }
        }
    }

    private var toolbarRefresh: (() -> Unit)? = null

    private val stopAction = object : DumbAwareAction(msg("req.stop"), msg("req.stop.tooltip"), AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) = controller.cancel()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = controller.state.running }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    init {
        Disposer.register(parent, this)
        controller.listener = this
        bodyCards.add(EmptyState(null, msg("req.idle.title"), msg("req.idle.hint")), CARD_IDLE)
        bodyCards.add(viewer, CARD_VIEWER)
        bodyCards.add(json, CARD_JSON)
        bodyCards.add(JPanel(), CARD_ERROR)
        bodyCards.add(diff, CARD_DIFF)
        bodyCards.add(EmptyState(AllIcons.FileTypes.Any_type, msg("req.binary.title"), msg("req.binary.hint")), CARD_BINARY)
        tabs.addTab(msg("req.tab.body"), bodyCards)
        tabs.addTab(msg("req.tab.headers"), JBScrollPane(JBTable(headersModel).apply { setShowGrid(false); isEnabled = true }))
        tabs.addTab(msg("req.tab.request"), JBScrollPane(requestText))
        tabs.addTab(msg("req.tab.curl"), buildCurlTab())
        tabs.addTab(msg("req.tab.history"), buildHistoryTab())
        add(buildHeader(), BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        (bodyCards.layout as CardLayout).show(bodyCards, CARD_IDLE)
        viewer.onAutoScrollChanged = { _ -> toolbarRefresh?.invoke() }
        refreshHistory()
    }

    // ---- header: status, counters, toolbar -----------------------------------------------------------------------

    private fun buildHeader(): JComponent {
        val group = DefaultActionGroup().apply {
            add(stopAction)
            add(object : DumbAwareAction(msg("req.clear"), msg("req.clear.tooltip"), AllIcons.Actions.GC) {
                override fun actionPerformed(e: AnActionEvent) = clearAll()
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(toggle(msg("req.autoscroll"), msg("req.autoscroll.tooltip"), AllIcons.RunConfigurations.Scroll_down, { viewer.autoScroll }) { viewer.autoScroll = it })
            add(toggle(msg("req.pretty"), msg("req.pretty.tooltip"), AllIcons.Actions.PrettyPrint, { prettyBody }) { setPretty(it) })
            add(object : DumbAwareAction(msg("req.copy_body"), msg("req.copy_body.tooltip"), AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) = copy(bodyTextToCopy())
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            addSeparator()
            add(compareGroup())
            add(toggle(msg("req.diff.show"), msg("req.diff.show.tooltip"), AllIcons.Actions.Diff, { diffShown }) { showDiffView(it) })
            addSeparator()
            val mode = DefaultActionGroup(msg("req.mode"), true).apply { templatePresentation.icon = AllIcons.Actions.Execute }
            for (o in ModeOverride.entries) {
                mode.add(toggle(RequestMessages.override(o), msg("req.mode.tooltip"), null, { controller.override == o }) { if (it) controller.override = o })
            }
            add(mode)
        }
        val toolbar = ToolbarStyle.create(TOOLBAR_PLACE, group, this)
        toolbarRefresh = { toolbar.updateActionsAsync() }
        // Title, status and counters, toolbar. The status text sits in the CENTER so it takes what the toolbar leaves and is cut
        // with an ellipsis; as a trailing component of a header it would be given its full width and run out of the window.
        val line = JPanel(BorderLayout(JBUI.scale(DesignTokens.Space.S), 0)).apply { isOpaque = false; add(pill, BorderLayout.WEST); add(meta, BorderLayout.CENTER) }
        val title = JBLabel(msg("req.section.response")).apply {
            font = DesignTokens.Type.captionStrong()
            foreground = Palette.textSecondary
        }
        val north = JPanel(BorderLayout(JBUI.scale(DesignTokens.Space.M), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(DesignTokens.Space.XS, DesignTokens.Space.M, 0, DesignTokens.Space.S)
            add(title, BorderLayout.WEST)
            add(line, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(north, BorderLayout.NORTH)
            add(notice, BorderLayout.SOUTH)
            border = JBUI.Borders.empty()
        }
    }

    private fun compareGroup() = DefaultActionGroup(msg("req.compare"), true).apply {
        templatePresentation.icon = AllIcons.Actions.Diff
        templatePresentation.description = msg("req.compare.tooltip")
        add(object : DumbAwareAction(msg("req.compare.previous"), msg("req.compare.previous.tooltip"), null) {
            override fun actionPerformed(e: AnActionEvent) { comparePrevious() }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = canCompare() && history.previousWithBody(currentEntry) != null }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction(msg("req.compare.with"), msg("req.compare.with.tooltip"), null) {
            override fun actionPerformed(e: AnActionEvent) = chooseAndCompare(e)
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = canCompare() && comparable().isNotEmpty() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
    }

    /** Only a finished JSON response can be the right side of a comparison. */
    private fun canCompare(): Boolean {
        val s = controller.state
        return !s.running && s.response?.let { ResponseJson.isJson(it) } == true
    }

    private fun comparable(): List<HistoryEntry> = history.withBodies().filter { it !== currentEntry }

    private fun comparePrevious(): Boolean {
        val entry = history.previousWithBody(currentEntry)
        if (entry == null) {
            showNotice(msg("req.compare.none"))
            return false
        }
        return compareWith(entry)
    }

    private fun chooseAndCompare(e: AnActionEvent) {
        val choices = comparable()
        if (choices.isEmpty()) { showNotice(msg("req.compare.none")); return }
        JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle(msg("req.compare.with"))
            .setRenderer(object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): java.awt.Component =
                    super.getListCellRendererComponent(list, entryTitle(value as HistoryEntry), index, sel, focus)
            })
            .setItemChosenCallback { compareWith(it) }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    /** Verb, path and time: the host adds nothing the user does not know and would not fit the title of a diff column. */
    private fun entryTitle(e: HistoryEntry): String {
        val path = runCatching { java.net.URI(e.displayUrl).let { u -> u.rawPath.orEmpty() + u.rawQuery?.let { q -> "?$q" }.orEmpty() } }.getOrNull()?.ifEmpty { null } ?: e.displayUrl
        return msg("req.compare.entry", e.method.name, path, java.text.DateFormat.getTimeInstance().format(java.util.Date(e.atMillis)))
    }

    /** Shows the structural difference between the body kept with [entry] (left) and the current response (right), inside the window. */
    internal fun compareWith(entry: HistoryEntry): Boolean {
        val current = controller.state.response
        val kept = entry.body
        if (disposed || current == null || kept == null || !ResponseJson.isJson(current)) {
            showNotice(msg("req.compare.none"))
            return false
        }
        diff.compare(kept.content(), ResponseJson.content(current), entryTitle(entry), msg("req.compare.current"))
        showDiffView(true)
        return true
    }

    private fun showDiffView(on: Boolean) {
        diffShown = on && diff.cardName() != DIFF_EMPTY_CARD
        showBodyCard()
        toolbarRefresh?.invoke()
    }

    private fun showBodyCard() {
        (bodyCards.layout as CardLayout).show(bodyCards, if (diffShown) CARD_DIFF else if (jsonShown) CARD_JSON else if (errorShown) CARD_ERROR else CARD_VIEWER)
    }

    /** A call that produced no answer shows what went wrong where the body would be, instead of an empty editor. */
    private fun showError(kind: ErrorKind) {
        errorShown = true
        val old = bodyCards.components.firstOrNull { it.name == CARD_ERROR }
        if (old != null) bodyCards.remove(old)
        val card = EmptyState(AllIcons.General.Error, msg("req.error.title"), RouteGenieBundle.message(kind.bundleKey)).apply { name = CARD_ERROR }
        bodyCards.add(card, CARD_ERROR)
        showBodyCard()
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.isVisible = true
    }

    private fun bodyTextToCopy(): String {
        if (!jsonShown) return viewer.text
        return controller.state.response?.takeIf { it.bodyFile == null && !it.truncated }?.bodyText().orEmpty()
    }

    /** The response on screen goes away: viewers must let go of its file before it is deleted. */
    override fun releasing() {
        if (disposed) return
        errorShown = false
        json.clear()
        diff.clear()
        diffShown = false
        jsonShown = false
        currentEntry = null
    }

    private fun toggle(text: String, description: String, icon: javax.swing.Icon?, selected: () -> Boolean, set: (Boolean) -> Unit) =
        object : ToggleAction(text, description, icon) {
            override fun isSelected(e: AnActionEvent) = selected()
            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    // ---- tabs ------------------------------------------------------------------------------------------------------

    private fun buildCurlTab(): JComponent {
        val shells = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), 0)).apply {
            isOpaque = false
            val g = javax.swing.ButtonGroup()
            shellBoxes.values.forEach { g.add(it); add(it) }
            add(JCheckBox(msg("req.curl.secrets")).apply { toolTipText = msg("req.curl.secrets.tooltip"); addActionListener { includeSecrets = isSelected; refreshCurl() } })
            add(JButton(msg("req.curl.copy"), AllIcons.Actions.Copy).apply { addActionListener { copy(curlText.text) } })
        }
        val importBar = JPanel(BorderLayout(JBUI.scale(DesignTokens.Space.S), 0)).apply {
            add(JButton(msg("req.curl.import")).apply { addActionListener { importCurl() } }, BorderLayout.WEST)
            add(curlImportResult, BorderLayout.CENTER)
        }
        val top = JPanel(BorderLayout()).apply { add(shells, BorderLayout.NORTH); add(curlNote, BorderLayout.SOUTH) }
        val bottom = JPanel(BorderLayout()).apply {
            add(SectionHeader(msg("req.curl.import.title")), BorderLayout.NORTH)
            add(JBScrollPane(curlImport), BorderLayout.CENTER)
            add(importBar, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(curlText), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun buildHistoryTab(): JComponent {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean): java.awt.Component {
                return super.getListCellRendererComponent(list, historyText(value as HistoryEntry), index, sel, focus)
            }
        }
        historyList.emptyText.text = msg("req.history.empty")
        historyList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) historyList.selectedValue?.let(onRestore)
            }
        })
        return JPanel(BorderLayout()).apply {
            add(JBLabel(msg("req.history.hint")).apply { foreground = Palette.textSecondary; border = JBUI.Borders.empty(DesignTokens.Space.XS, DesignTokens.Space.S) }, BorderLayout.NORTH)
            add(JBScrollPane(historyList), BorderLayout.CENTER)
        }
    }

    private fun historyText(e: HistoryEntry): String {
        val status = if (e.status > 0) e.status.toString() else e.error?.let { msg("req.history.failed") }.orEmpty()
        val kept = if (e.body == null && e.status > 0) "  -  " + msg("req.history.body_not_kept") else ""
        return "${e.method.name}  ${e.displayUrl}  -  $status  ${ByteSize.millis(e.durationMs)}$kept"
    }

    fun refreshHistory() {
        historyModel.clear()
        history.all().forEach { historyModel.addElement(it) }
    }

    /** Recomputes the cURL view from the form (before a send) or the last request (after one). */
    fun refreshCurl() {
        val spec = currentSpec(includeSecrets)
        if (spec == null) {
            curlText.text = ""
            curlNote.text = msg("req.curl.unavailable")
            return
        }
        try {
            val cmd = CurlEmitter.emit(spec, CurlOptions(shell, maskSecrets = !includeSecrets))
            curlText.text = cmd.text
            curlNote.text = when {
                cmd.sideFiles.isNotEmpty() -> msg("req.curl.side_file", cmd.sideFiles.joinToString { it.name })
                !includeSecrets -> msg("req.curl.masked")
                else -> ""
            }
        } catch (e: CurlUnsupportedException) {
            curlText.text = ""
            curlNote.text = RequestMessages.problem(e.problem, e.shell)
        }
        curlText.caretPosition = 0
    }

    private fun importCurl() {
        when (val r = CurlParser.parse(curlImport.text, null)) {
            is CurlParseResult.Rejected -> curlImportResult.text = RequestMessages.rejection(r.reason)
            is CurlParseResult.Parsed -> {
                val ignored = r.request.ignored.joinToString { it.flag }
                curlImportResult.text = if (ignored.isEmpty()) msg("req.curl.imported") else msg("req.curl.imported_ignored", ignored)
                onImport(r.request)
            }
        }
    }

    // ---- state changes ---------------------------------------------------------------------------------------------

    override fun changed(state: ResponseState, drain: LiveDrain?) {
        if (disposed) return
        val started = state.phase == Phase.SENDING && state.head == null && drain == null && state.response == null
        if (started) begin(state)
        drain?.let { applyDrain(state, it) }
        if (!state.running) {
            val failed = state.error?.takeIf { state.phase == Phase.FAILED && (state.response?.status ?: state.head?.status ?: 0) == 0 }
            if (failed != null) showError(failed.kind)
        }
        updateStatus(state)
        if (!state.running) {
            refreshHistory()
            refreshCurl()
            currentEntry = if (state.response != null && state.phase == Phase.DONE) history.all().firstOrNull()?.takeIf { it.body != null } else null
        }
        toolbarRefresh?.invoke()
    }

    private fun begin(state: ResponseState) {
        formatGen++
        viewer.live = false
        viewer.clear()
        events.clear()
        setEventsTab(false)
        headersModel.rowCount = 0
        notice.isVisible = false
        errorShown = false
        showBodyCard()
        requestText.text = state.sent?.let { describe(it.spec, it.body) }.orEmpty()
        refreshCurl()
    }

    private fun applyDrain(state: ResponseState, d: LiveDrain) {
        d.headers?.let { h ->
            fillHeaders(h.headers)
            val live = h.mode != ResponseMode.BUFFERED
            setEventsTab(h.mode == ResponseMode.SSE)
            if (live) {
                viewer.live = true
                viewer.clear()
                viewer.live = true
                viewer.autoScroll = true
                (bodyCards.layout as CardLayout).show(bodyCards, CARD_VIEWER)
            }
        }
        if (d.text.isNotEmpty()) viewer.append(d.text)
        if (d.rows.isNotEmpty()) events.add(d.rows)
        d.buffered?.let { showBuffered(it) }
        if (state.droppedChars > 0 || state.droppedRows > 0 || viewer.trimmedChars > 0) {
            notice.text = msg("req.notice.dropped", state.droppedChars + viewer.trimmedChars, state.droppedRows + events.droppedRows)
            notice.isVisible = true
        }
        if (!state.running) viewer.live = false
    }

    private fun showBuffered(r: com.routegenie.client.ResponseData) {
        fillHeaders(r.headers)
        if (ResponseJson.isJson(r)) {
            showJson(r)
            return
        }
        jsonShown = false
        json.clear()
        val key = Any()
        lastResponseKey = key
        val gen = ++formatGen
        val type = r.header("content-type")
        val pretty = prettyBody
        ApplicationManager.getApplication().executeOnPooledThread {
            val f = BodyFormatter.format(r.body, type, r.bodySize, prettyJson = pretty)
            val doc = if (f.kind == ContentKind.BINARY) null else viewer.newDocument(f.text)
            ApplicationManager.getApplication().invokeLater({
                if (disposed || gen != formatGen) return@invokeLater
                if (doc == null) {
                    (bodyCards.layout as CardLayout).show(bodyCards, CARD_BINARY)
                } else {
                    viewer.show(doc, if (f.kind == ContentKind.JSON) "json" else if (f.kind == ContentKind.XML) "xml" else if (f.kind == ContentKind.HTML) "html" else null, f.highlight, f.softWrap)
                    (bodyCards.layout as CardLayout).show(bodyCards, CARD_VIEWER)
                }
                val notes = f.notices.mapNotNull { (n, v) -> noticeText(n, v) }
                notice.text = notes.joinToString("  ")
                notice.isVisible = notes.isNotEmpty()
            }, ModalityState.any())
        }
    }

    /** JSON goes to the visualizer; a spilled body is passed as its file and never loaded into an editor or a String. */
    private fun showJson(r: com.routegenie.client.ResponseData) {
        formatGen++
        json.showBody(ResponseJson.content(r))
        jsonShown = true
        showBodyCard()
        val cut = r.truncated && r.bodyFile == null
        notice.text = if (cut) msg("req.notice.engine_cut", ByteSize.format(r.bodySize)) else ""
        notice.isVisible = cut
    }

    private fun noticeText(n: BodyNotice, value: Long): String? = when (n) {
        BodyNotice.ENGINE_CUT -> msg("req.notice.engine_cut", ByteSize.format(value))
        BodyNotice.VIEW_CUT -> msg("req.notice.view_cut", value)
        BodyNotice.NOT_FORMATTED -> msg("req.notice.not_formatted")
        BodyNotice.INVALID_JSON -> msg("req.notice.invalid_json", value)
        BodyNotice.BINARY -> null
    }

    private fun fillHeaders(h: List<Pair<String, String>>) {
        headersModel.rowCount = 0
        h.forEach { headersModel.addRow(arrayOf(it.first, it.second)) }
    }

    private fun updateStatus(s: ResponseState) {
        val code = s.response?.status ?: s.head?.status ?: 0
        val reason = s.response?.reason ?: s.head?.reason.orEmpty()
        pill.setStatus(code, reason)
        pill.isVisible = code > 0
        val parts = ArrayList<String>()
        when {
            s.phase == Phase.IDLE -> Unit
            s.error != null && s.phase == Phase.FAILED && errorShown -> parts += msg("req.state.failed")
            s.error != null -> parts += RouteGenieBundle.message(s.error!!.kind.bundleKey)
            s.phase == Phase.SENDING -> parts += msg("req.state.sending")
        }
        if (s.phase != Phase.IDLE) {
            parts += ByteSize.millis(s.elapsedMs)
            parts += ByteSize.format(s.response?.bodySize ?: s.bytes)
            (s.response?.httpVersion ?: s.head?.protocol)?.takeIf { it.isNotEmpty() }?.let { parts += it }
            s.mode?.takeIf { it != ResponseMode.BUFFERED }?.let { parts += RequestMessages.modeLabel(it) }
            if (s.events > 0) parts += msg("req.state.events", s.events)
            if (s.phase == Phase.CANCELLED) parts += msg("req.state.cancelled")
            if (s.phase == Phase.STREAMING) parts += msg("req.state.live")
        }
        meta.text = parts.joinToString("  -  ")
    }

    private fun setEventsTab(show: Boolean) {
        if (show == eventsTabShown) return
        eventsTabShown = show
        if (show) tabs.insertTab(eventsTabTitle, null, events, null, 1) else tabs.remove(events)
    }

    private fun setPretty(value: Boolean) {
        prettyBody = value
        props.setValue(PROP_PRETTY, value, true)
        controller.state.response?.takeIf { !jsonShown }?.let { showBuffered(it) }
    }

    private fun describe(spec: RequestSpec, body: String?): String = buildString {
        append(spec.method.name).append(' ').append(spec.url).append('\n')
        for (h in spec.headers.filter { it.enabled }) append(h.name).append(": ").append(h.value).append('\n')
        if (!body.isNullOrEmpty()) append('\n').append(body)
        if (spec.body is RequestBody.None && body == null) append("")
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun clearAll() {
        controller.clear()
        jsonShown = false
        errorShown = false
        viewer.clear()
        events.clear()
        setEventsTab(false)
        headersModel.rowCount = 0
        notice.isVisible = false
        requestText.text = ""
        (bodyCards.layout as CardLayout).show(bodyCards, CARD_IDLE)
        diffShown = false
        updateStatus(controller.state)
    }

    fun selectTab(title: String) {
        val i = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title } ?: return
        tabs.selectedIndex = i
    }

    // ---- test hooks ------------------------------------------------------------------------------------------------

    internal fun viewerForTest() = viewer
    internal fun eventsForTest() = events
    internal fun metaText() = meta.text
    internal fun noticeText() = notice.takeIf { it.isVisible }?.text
    internal fun tabTitles() = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }
    internal fun curlTextForTest() = curlText.text
    internal fun setShellForTest(s: CurlShell) { shell = s; refreshCurl() }
    internal fun importForTest(text: String) { curlImport.text = text; importCurl() }
    internal fun importResultText() = curlImportResult.text
    internal fun historyCount() = historyModel.size()
    internal fun jsonForTest() = json
    internal fun diffForTest() = diff
    internal fun jsonIsShown() = jsonShown
    internal fun diffIsShown() = diffShown
    internal fun comparePreviousForTest() = comparePrevious()
    internal fun historyTexts(): List<String> = (0 until historyModel.size()).map { historyText(historyModel.get(it)) }

    override fun dispose() {
        disposed = true
        formatGen++
        if (controller.listener === this) controller.listener = null
        toolbarRefresh = null
    }

    companion object {
        private const val CARD_IDLE = "idle"
        private const val CARD_VIEWER = "viewer"
        private const val CARD_BINARY = "binary"
        private const val CARD_ERROR = "error"
        private const val CARD_JSON = "json"
        private const val CARD_DIFF = "diff"
        private const val DIFF_EMPTY_CARD = "empty"
        private const val TOOLBAR_PLACE = "RouteGenie.Response"
        internal const val PROP_SHELL = "routegenie.ui.curlShell"
        private const val PROP_PRETTY = "routegenie.ui.prettyBody"
        private const val CURL_IMPORT_ROWS = 3

        fun defaultShell(): CurlShell = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) CurlShell.CMD else CurlShell.BASH
    }
}
