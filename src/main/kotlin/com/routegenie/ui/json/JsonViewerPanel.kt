package com.routegenie.ui.json

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.json.JsonContent
import com.routegenie.json.JsonErrorCode
import com.routegenie.json.JsonErrors
import com.routegenie.json.JsonIndex
import com.routegenie.json.JsonKind
import com.routegenie.json.JsonPath
import com.routegenie.json.JsonPrinter
import com.routegenie.json.JsonSearch
import com.routegenie.json.JsonTable
import com.routegenie.json.PathSegment
import com.routegenie.json.PrintOptions
import com.routegenie.json.ScanStatus
import com.routegenie.json.SearchHit
import com.routegenie.json.SearchQuery
import com.routegenie.json.SearchResult
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Divider
import com.routegenie.ui.design.EmptyState
import com.routegenie.ui.design.LoadingState
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.RouteGenieIcons
import com.routegenie.ui.design.ToolbarStyle
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

enum class JsonViewMode { TREE, PRETTY, RAW, TABLE }

/**
 * The JSON visualizer: tree, pretty text, raw text and table views of one document, with search, copy of paths and values, and a
 * format menu. A document is given as [JsonContent] (bytes or a spilled file) and indexed on a background thread; the first rows
 * appear while indexing goes on. Large documents stay responsive because every view reads from a compact index (memory follows the
 * number of values, not the text), the tree only creates the rows in view, and long texts are shown a page at a time.
 *
 * [parent] owns the panel; everything it starts (scan, search, page rendering, editors) stops and is released with it.
 */
class JsonViewerPanel(parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private var session: JsonSession? = null
    private var disposed = false

    var mode: JsonViewMode = JsonViewMode.TREE
        private set
    private var modeChosenByUser = false

    private var wrap = false
    private var indent = Limits.JSON_INDENT_DEFAULT
    private var sortKeys = false
    private var minify = false

    private var selectedNode = -1
    private var statusMessage: String? = null
    private var errorLocation: String? = null

    private var prettyBoundTo: JsonIndex? = null
    private var prettyOptions: PrintOptions? = null
    private var rawBoundTo: JsonIndex? = null
    private var tableBoundTo: JsonIndex? = null
    private var tablePending = false
    private var tableNode = -2

    private var hits: IntArray = IntArray(0)
    private var hitCount = 0
    private var hitPosition = -1
    private var hitsTruncated = false
    private var searchId = 0
    private var searching = false
    private var activeQuery: SearchQuery? = null

    // --- views --------------------------------------------------------------------------------------------------------

    private val treeActions = object : TreeActions {
        override fun selected(node: Int) {
            selectedNode = node
            updateBreadcrumb()
            updateFooter()
        }

        override fun copyPath(node: Int) = copyPathOf(node)
        override fun copyValue(node: Int) = copyValueOf(node)
        override fun copyKey(node: Int) {
            val idx = session?.index ?: return
            idx.keyOf(node)?.let { JsonUi.copy(it) }
        }
    }

    private val tableActions = object : TableActions {
        override fun rowSelected(node: Int) {
            selectedNode = node
            updateFooter()
        }

        override fun message(text: String) {
            statusMessage = text
            updateFooter()
        }
    }

    private val textCallbacks = object : TextViewCallbacks {
        override fun textMatches(count: Int, truncated: Boolean, regexError: String?) {
            if (mode != JsonViewMode.PRETTY && mode != JsonViewMode.RAW) return
            hitCount = count
            hitsTruncated = truncated
            hitPosition = if (count > 0) 0 else -1
            searching = false
            searchBar.setCount(hitPosition + 1, count, truncated, false, regexError)
        }
    }

    private val searchListener = object : SearchBarListener {
        override fun queryChanged(query: SearchQuery?) = onQueryChanged(query)
        override fun next() = stepHit(1)
        override fun previous() = stepHit(-1)
    }

    internal val treeView = JsonTreeView(this, treeActions)
    internal val prettyView = JsonTextView(this, TextMode.PRETTY, textCallbacks)
    internal val rawView = JsonTextView(this, TextMode.RAW, textCallbacks)
    internal val tableView = JsonTableView(this, tableActions)
    internal val searchBar = JsonSearchBar(this, searchListener)

    private val cards = JPanel(CardLayout())
    private val statusLabel = JBLabel().apply {
        font = DesignTokens.Type.caption()
        foreground = Palette.textSecondary
    }
    private val breadcrumb = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
    private val errorBanner = JPanel(BorderLayout())
    private val errorTitle = JBLabel().apply { foreground = Palette.danger.text }
    private val showRawLink = JsonUi.link(RouteGenieBundle.message("json.error.show_raw")) {
        modeChosenByUser = true
        setMode(JsonViewMode.RAW)
    }
    private val errorContext = JBLabel().apply { font = DesignTokens.Type.mono() }
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val modeSwitch: SegmentedControl

    private val noticeHost = JPanel(BorderLayout())
    private val loadingHost = JPanel(BorderLayout())
    private var loadingState: JComponent? = null

    init {
        Disposer.register(parent, this)
        modeSwitch = SegmentedControl(
            listOf(
                RouteGenieBundle.message("json.mode.tree"), RouteGenieBundle.message("json.mode.pretty"),
                RouteGenieBundle.message("json.mode.raw"), RouteGenieBundle.message("json.mode.table"),
            ),
        ) { k ->
            modeChosenByUser = true
            setMode(JsonViewMode.entries[k])
        }
        cards.add(EmptyState(RouteGenieIcons.PRETTY_PRINT, RouteGenieBundle.message("json.state.empty"), RouteGenieBundle.message("json.state.empty.hint")), CARD_EMPTY)
        cards.add(loadingHost, CARD_LOADING)
        cards.add(treeView, CARD_TREE)
        cards.add(prettyView, CARD_PRETTY)
        cards.add(rawView, CARD_RAW)
        cards.add(tableView, CARD_TABLE)
        cards.add(EmptyState(RouteGenieIcons.EMPTY_SEARCH, RouteGenieBundle.message("json.table.none"), RouteGenieBundle.message("json.table.none.hint")), CARD_TABLE_NONE)
        cards.add(noticeHost, CARD_NOTICE)

        errorBanner.apply {
            isVisible = false
            background = Palette.raised
            border = JBUI.Borders.merge(DesignTokens.Space.border(DesignTokens.Space.S, DesignTokens.Space.M), Divider.bottom(), false)
            val text = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(errorTitle)
                add(errorContext)
            }
            add(text, BorderLayout.CENTER)
            add(showRawLink, BorderLayout.EAST)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(buildToolbarRow())
            add(searchBar)
            add(errorBanner)
        }
        val footer = JPanel(BorderLayout()).apply {
            background = Palette.raised
            border = JBUI.Borders.merge(DesignTokens.Space.border(DesignTokens.Space.XS, DesignTokens.Space.M), Divider.top(), false)
            add(breadcrumb, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        showCard(CARD_EMPTY)
    }

    // --- toolbar ----------------------------------------------------------------------------------------------------------

    private class ModeGroup(name: String, popup: Boolean, private val visible: () -> Boolean) : DefaultActionGroup(name, popup) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isVisible = visible()
            e.presentation.isPopupGroup = true
        }
    }

    private fun buildToolbarRow(): JComponent {
        val inTree = { mode == JsonViewMode.TREE }
        val inText = { mode == JsonViewMode.PRETTY || mode == JsonViewMode.RAW }
        val expand = ModeGroup(RouteGenieBundle.message("json.action.expand"), true, inTree).apply {
            templatePresentation.icon = AllIcons.Actions.Expandall
            templatePresentation.description = RouteGenieBundle.message("json.action.expand.tooltip")
            for (level in 1..EXPAND_LEVELS_SHOWN) {
                add(JsonAction(RouteGenieBundle.message("json.action.expand_level", level), RouteGenieBundle.message("json.action.expand.tooltip"), null) { treeView.expandToLevel(level) })
            }
            add(JsonAction(RouteGenieBundle.message("json.action.expand_all"), RouteGenieBundle.message("json.action.expand.tooltip"), null) { treeView.expandToLevel(Limits.JSON_EXPAND_MAX_LEVEL) })
        }
        val format = ModeGroup(RouteGenieBundle.message("json.action.format"), true, { mode == JsonViewMode.PRETTY }).apply {
            templatePresentation.icon = RouteGenieIcons.PRETTY_PRINT
            templatePresentation.description = RouteGenieBundle.message("json.action.format.tooltip")
            add(JsonToggle(RouteGenieBundle.message("json.action.sort_keys"), RouteGenieBundle.message("json.action.sort_keys.tooltip"), null, get = { sortKeys }) {
                sortKeys = it
                rebindPretty()
            })
            add(JsonToggle(RouteGenieBundle.message("json.action.minify"), RouteGenieBundle.message("json.action.minify.tooltip"), null, get = { minify }) {
                minify = it
                rebindPretty()
            })
            addSeparator()
            for (width in INDENT_CHOICES) {
                add(JsonToggle(RouteGenieBundle.message("json.action.indent", width), RouteGenieBundle.message("json.action.format.tooltip"), null, get = { indent == width }) {
                    if (it) {
                        indent = width
                        rebindPretty()
                    }
                })
            }
        }
        val group = DefaultActionGroup().apply {
            add(expand)
            add(JsonAction(RouteGenieBundle.message("json.action.collapse"), RouteGenieBundle.message("json.action.collapse.tooltip"), AllIcons.Actions.Collapseall, inTree) { treeView.collapseAll() })
            add(JsonToggle(RouteGenieBundle.message("json.action.wrap"), RouteGenieBundle.message("json.action.wrap.tooltip"), RouteGenieIcons.WRAP, inText, { wrap }) {
                wrap = it
                prettyView.setWrap(it)
                rawView.setWrap(it)
            })
            add(format)
            addSeparator()
            add(JsonAction(RouteGenieBundle.message("json.action.copy"), RouteGenieBundle.message("json.action.copy.tooltip"), RouteGenieIcons.COPY, enabled = { session?.index != null }) { copyCurrent() })
            add(JsonAction(RouteGenieBundle.message("json.action.save"), RouteGenieBundle.message("json.action.save.tooltip"), AllIcons.Actions.MenuSaveall, enabled = { session != null }) { saveToFile() })
            add(JsonAction(RouteGenieBundle.message("json.action.open_file"), RouteGenieBundle.message("json.action.open_file.tooltip"), AllIcons.Actions.OpenNewTab, enabled = { session != null }) { openAsFile() })
        }
        val toolbar = ToolbarStyle.create(TOOLBAR_PLACE, group, this)
        // The toolbar takes all the width that is left, so it can show every action and only overflows when it must.
        val center = JPanel(BorderLayout(JBUI.scale(DesignTokens.Space.S), 0)).apply {
            isOpaque = false
            add(modeSwitch, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.CENTER)
        }
        return ToolbarStyle.bar(null, center)
    }

    // --- public API -------------------------------------------------------------------------------------------------------

    /** Shows [content]; whatever was shown before is released. May be called from the UI thread only. */
    fun showBody(content: JsonContent) {
        closeSession()
        resetViews()
        if (content.sizeBytes == 0L) {
            showCard(CARD_EMPTY)
            updateFooter()
            return
        }
        val s = JsonSession(content)
        session = s
        mode = JsonViewMode.TREE
        modeChosenByUser = false
        modeSwitch.selected = 0
        showLoading(RouteGenieBundle.message("json.state.loading"))
        updateModeAvailability()
        s.start(object : JsonSession.Callbacks {
            override fun onStarted(index: JsonIndex) {
                JsonUi.later({ disposed || session !== s }) { attach(s, index) }
            }

            override fun onProgress(index: JsonIndex) = Unit

            override fun onFinished(index: JsonIndex?, error: java.io.IOException?) {
                JsonUi.later({ disposed || session !== s }) { finish(s, index, error) }
            }
        })
    }

    fun clear() {
        closeSession()
        resetViews()
        showCard(CARD_EMPTY)
        updateFooter()
    }

    fun setMode(newMode: JsonViewMode) {
        mode = newMode
        modeSwitch.selected = newMode.ordinal
        showMode()
        rerunSearchForMode()
    }

    /** The JSON path of the selected row, or null. */
    val selectedPath: String?
        get() {
            val idx = session?.index ?: return null
            return if (selectedNode in 0 until idx.nodeCount) JsonPath.format(idx, selectedNode) else null
        }

    /** Types [text] into the search field and starts the search. */
    fun search(text: String) = searchBar.setText(text)

    fun focusSearch() = searchBar.focusField()

    override fun dispose() {
        disposed = true
        refreshAlarm.cancelAllRequests()
        closeSession()
    }

    // --- session lifecycle ------------------------------------------------------------------------------------------------

    private fun closeSession() {
        searchId++
        refreshAlarm.cancelAllRequests()
        session?.close()
        session = null
    }

    private fun resetViews() {
        treeView.unbind()
        prettyView.release()
        rawView.release()
        tableView.clear()
        prettyBoundTo = null
        rawBoundTo = null
        tableBoundTo = null
        tablePending = false
        tableNode = -2
        selectedNode = -1
        hits = IntArray(0)
        hitCount = 0
        hitPosition = -1
        searching = false
        statusMessage = null
        errorLocation = null
        errorBanner.isVisible = false
        breadcrumb.removeAll()
        modeSwitch.setSegmentEnabled(JsonViewMode.PRETTY.ordinal, true)
        modeSwitch.setSegmentEnabled(JsonViewMode.TABLE.ordinal, true)
        searchBar.setCount(0, 0, false, false, null)
    }

    private fun attach(s: JsonSession, index: JsonIndex) {
        treeView.bind(index)
        if (mode == JsonViewMode.TREE) showCard(CARD_TREE)
        scheduleRefresh(s)
        updateFooter()
        if (mode == JsonViewMode.RAW) showMode()
    }

    private fun scheduleRefresh(s: JsonSession) {
        refreshAlarm.addRequest({
            if (disposed || session !== s) return@addRequest
            if (s.done) return@addRequest
            treeView.refresh()
            updateFooter()
            scheduleRefresh(s)
        }, REFRESH_MS)
    }

    private fun finish(s: JsonSession, index: JsonIndex?, error: java.io.IOException?) {
        refreshAlarm.cancelAllRequests()
        if (index == null) {
            showMessageCard(RouteGenieBundle.message("json.error.read_failed", error?.javaClass?.simpleName.orEmpty()), error?.message.orEmpty())
            updateFooter()
            return
        }
        treeView.refresh()
        val invalid = index.error != null
        modeSwitch.setSegmentEnabled(JsonViewMode.PRETTY.ordinal, !invalid)
        modeSwitch.setSegmentEnabled(JsonViewMode.TABLE.ordinal, !invalid)
        if (invalid) {
            describeError(s, index)
            if (!modeChosenByUser || mode == JsonViewMode.PRETTY || mode == JsonViewMode.TABLE) {
                mode = JsonViewMode.RAW
                modeSwitch.selected = JsonViewMode.RAW.ordinal
            }
        }
        showMode()
        if (activeQuery != null) rerunSearchForMode()
        updateFooter()
    }

    /** Shows the banner at once with the offset; line, column and context follow once read (off the UI thread). */
    private fun describeError(s: JsonSession, index: JsonIndex) {
        val e = index.error ?: return
        val reason = if (e.code == JsonErrorCode.UNEXPECTED_END && s.content.isCut) {
            RouteGenieBundle.message("json.error.cut", JsonUi.size(s.content.sizeBytes), JsonUi.size(s.content.fullSizeBytes))
        } else {
            RouteGenieBundle.message(e.code.bundleKey, e.detail ?: "")
        }
        pendingErrorOffset = e.offset
        errorTitle.text = RouteGenieBundle.message("json.error.banner", RouteGenieBundle.message("json.error.offset", e.offset), reason)
        errorContext.text = ""
        errorBanner.isVisible = true
        JsonUi.background {
            val where = JsonErrors.locate(index.source, e.offset)
            JsonUi.later({ disposed || session !== s }) {
                errorLocation = RouteGenieBundle.message("json.error.position", where.line, where.column)
                errorTitle.text = RouteGenieBundle.message("json.error.banner", errorLocation!!, reason)
                val pointer = " ".repeat(where.caretColumn) + "^"
                errorContext.text = "<html><pre>" + escape(where.lineText) + LINE_BREAK + pointer + "</pre></html>"
            }
        }
    }

    private var pendingErrorOffset = -1L

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // --- modes -------------------------------------------------------------------------------------------------------------

    private val textBlocked: Boolean get() = (session?.content?.sizeBytes ?: 0L) > Limits.JSON_TEXT_HARD_CAP_BYTES

    private fun updateModeAvailability() {
        modeSwitch.setSegmentEnabled(JsonViewMode.PRETTY.ordinal, true)
        modeSwitch.setSegmentEnabled(JsonViewMode.TABLE.ordinal, true)
    }

    private fun rebindPretty() {
        prettyBoundTo = null
        if (mode == JsonViewMode.PRETTY) showMode()
    }

    private fun showMode() {
        showRawLink.isVisible = mode != JsonViewMode.RAW
        val s = session
        if (s == null) {
            showCard(CARD_EMPTY)
            return
        }
        val index = s.index
        when (mode) {
            JsonViewMode.TREE -> if (index == null) showLoadingCard() else showCard(CARD_TREE)
            JsonViewMode.RAW -> when {
                textBlocked -> showLargeNotice()
                index == null -> showLoadingCard()
                else -> {
                    if (rawBoundTo !== index) {
                        rawBoundTo = index
                        rawView.setWrap(wrap)
                        if (pendingErrorOffset >= 0 && s.done) {
                            rawView.bind(index)
                            rawView.revealByte(pendingErrorOffset)
                        } else {
                            rawView.bind(index, reveal = if (selectedNode > 0) selectedNode else -1)
                        }
                    }
                    showCard(CARD_RAW)
                }
            }
            JsonViewMode.PRETTY -> when {
                textBlocked -> showLargeNotice()
                index == null || !index.isComplete -> showLoadingCard()
                index.error != null -> showMessageCard(RouteGenieBundle.message("json.pretty.invalid"), RouteGenieBundle.message("json.pretty.invalid.hint"))
                else -> {
                    val options = PrintOptions(indent = indent, sortKeys = sortKeys, minify = minify)
                    if (prettyBoundTo !== index || prettyOptions?.let { sameOptions(it, options) } != true) {
                        prettyBoundTo = index
                        prettyOptions = options
                        prettyView.setWrap(wrap)
                        prettyView.bind(index, options, if (selectedNode > 0) selectedNode else -1)
                    }
                    showCard(CARD_PRETTY)
                }
            }
            JsonViewMode.TABLE -> showTable(s, index)
        }
        updateFooter()
    }

    private fun sameOptions(a: PrintOptions, b: PrintOptions): Boolean = a.indent == b.indent && a.sortKeys == b.sortKeys && a.minify == b.minify

    private fun showTable(s: JsonSession, index: JsonIndex?) {
        if (index == null || !index.isComplete) {
            showLoadingCard()
            return
        }
        if (index.error != null) {
            showMessageCard(RouteGenieBundle.message("json.table.none"), RouteGenieBundle.message("json.pretty.invalid.hint"))
            return
        }
        if (tableBoundTo === index) {
            showCard(if (tableNode >= 0) CARD_TABLE else CARD_TABLE_NONE)
            return
        }
        if (tablePending) {
            showLoadingCard()
            return
        }
        tablePending = true
        showLoadingCard()
        JsonUi.background {
            val node = JsonTable.detect(index)
            val table = if (node >= 0) JsonTable.create(index, node) { disposed || session !== s } else null
            JsonUi.later({ disposed || session !== s }) {
                tablePending = false
                tableBoundTo = index
                tableNode = node
                if (table != null) tableView.bind(table)
                if (mode == JsonViewMode.TABLE) showCard(if (table != null) CARD_TABLE else CARD_TABLE_NONE)
                updateFooter()
            }
        }
    }

    // --- cards --------------------------------------------------------------------------------------------------------------

    private var currentCard = CARD_EMPTY

    private fun showCard(name: String) {
        currentCard = name
        (cards.layout as CardLayout).show(cards, name)
    }

    internal fun cardName(): String = currentCard

    private fun showLoading(text: String) {
        loadingState?.let { loadingHost.remove(it) }
        val state = LoadingState(this, text)
        loadingState = state
        loadingHost.add(state, BorderLayout.CENTER)
        loadingHost.revalidate()
        showCard(CARD_LOADING)
    }

    private fun showLoadingCard() {
        if (loadingState == null) showLoading(RouteGenieBundle.message("json.state.loading")) else showCard(CARD_LOADING)
    }

    private fun showMessageCard(title: String, hint: String) {
        noticeHost.removeAll()
        noticeHost.add(EmptyState(RouteGenieIcons.EMPTY_SEARCH, title, hint.ifEmpty { null }), BorderLayout.CENTER)
        noticeHost.revalidate()
        showCard(CARD_NOTICE)
    }

    private fun showLargeNotice() {
        val size = JsonUi.size(session?.content?.sizeBytes ?: 0L)
        val actions = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(DesignTokens.Space.M), 0)).apply {
            isOpaque = false
            add(JsonUi.link(RouteGenieBundle.message("json.action.save")) { saveToFile() })
            add(JsonUi.link(RouteGenieBundle.message("json.action.open_file")) { openAsFile() })
        }
        noticeHost.removeAll()
        noticeHost.add(
            EmptyState(RouteGenieIcons.PRETTY_PRINT, RouteGenieBundle.message("json.notice.large.title", size), RouteGenieBundle.message("json.notice.large.hint"), actions),
            BorderLayout.CENTER,
        )
        noticeHost.revalidate()
        showCard(CARD_NOTICE)
    }

    // --- status line and breadcrumb ----------------------------------------------------------------------------------------------

    private fun updateFooter() {
        val s = session
        val index = s?.index
        statusLabel.text = when {
            s == null -> ""
            statusMessage != null -> statusMessage
            index == null -> RouteGenieBundle.message("json.state.loading")
            !s.done -> {
                val total = index.source.length.coerceAtLeast(1)
                RouteGenieBundle.message("json.status.indexing", (index.scannedBytes * 100 / total).toInt(), index.nodeCount)
            }
            else -> summary(s, index)
        }
        updateBreadcrumb()
    }

    private fun summary(s: JsonSession, index: JsonIndex): String {
        val ms = (s.finishedNanos - s.startedNanos) / 1_000_000
        val parts = ArrayList<String>()
        parts += JsonUi.size(s.content.sizeBytes)
        parts += RouteGenieBundle.message("json.status.nodes", index.nodeCount - 1)
        if (index.lines) parts += RouteGenieBundle.message("json.status.documents", index.documentCount)
        parts += RouteGenieBundle.message("json.status.depth", index.maxDepth)
        parts += RouteGenieBundle.message("json.status.time", ms)
        if (s.content.isCut) parts += RouteGenieBundle.message("json.status.cut", JsonUi.size(s.content.sizeBytes), JsonUi.size(s.content.fullSizeBytes))
        if (index.status == ScanStatus.LIMITED) parts += RouteGenieBundle.message("json.status.limited")
        return parts.joinToString(STATUS_SEPARATOR)
    }

    private fun updateBreadcrumb() {
        breadcrumb.removeAll()
        val index = session?.index
        if (index != null && selectedNode in 0 until index.nodeCount && mode != JsonViewMode.RAW && mode != JsonViewMode.PRETTY) {
            val chain = ArrayList<Int>()
            var n = selectedNode
            val root = index.root
            while (true) {
                chain += n
                if (n <= root || index.parent(n) < 0) break
                n = index.parent(n)
            }
            chain.reverse()
            val skip = (chain.size - BREADCRUMB_MAX).coerceAtLeast(0)
            if (skip > 0) addCrumbSeparator(ELLIPSIS)
            for ((k, node) in chain.withIndex()) {
                if (k < skip) continue
                if (k > skip || skip > 0) addCrumbSeparator(CRUMB_SEPARATOR)
                val label = when {
                    node == root -> JsonPath.format(emptyList<PathSegment>())
                    index.hasKey(node) -> index.keyOf(node).orEmpty().ifEmpty { EMPTY_KEY }
                    else -> "[${index.ordinalOf(node)}]"
                }
                val last = k == chain.size - 1
                breadcrumb.add(
                    if (last) JBLabel(label).apply { font = DesignTokens.Type.captionStrong() } else JsonUi.link(label) { treeView.select(node) }.apply { font = DesignTokens.Type.caption() },
                )
            }
        }
        breadcrumb.revalidate()
        breadcrumb.repaint()
    }

    private fun addCrumbSeparator(text: String) {
        breadcrumb.add(JBLabel(text).apply {
            font = DesignTokens.Type.caption()
            foreground = Palette.textSecondary
            border = JBUI.Borders.empty(0, DesignTokens.Space.XS)
        })
    }

    // --- search ---------------------------------------------------------------------------------------------------------------

    private fun onQueryChanged(query: SearchQuery?) {
        activeQuery = query
        searchId++
        hits = IntArray(0)
        hitCount = 0
        hitPosition = -1
        if (mode == JsonViewMode.PRETTY || mode == JsonViewMode.RAW) {
            val view = if (mode == JsonViewMode.PRETTY) prettyView else rawView
            searching = query != null
            view.setQuery(query)
            if (query == null) searchBar.setCount(0, 0, false, false, null)
            return
        }
        val index = session?.index
        if (query == null || index == null) {
            searchBar.setCount(0, 0, false, false, null)
            return
        }
        val id = searchId
        searching = true
        searchBar.setCount(0, 0, false, true, null)
        JsonUi.background {
            val result = JsonSearch.search(index, query, cancelled = { id != searchId || disposed })
            JsonUi.later({ disposed || id != searchId }) { applyStructural(result) }
        }
    }

    private fun applyStructural(result: SearchResult) {
        searching = false
        hits = result.hits
        hitCount = result.count
        hitsTruncated = result.truncated
        hitPosition = if (result.count > 0) 0 else -1
        searchBar.setCount(hitPosition + 1, result.count, result.truncated || result.timedOut, false, result.regexError)
        if (hitCount > 0) revealHit()
    }

    private fun rerunSearchForMode() {
        val q = searchBar.current()
        if (q != null) onQueryChanged(q) else if (activeQuery != null) onQueryChanged(null)
    }

    private fun stepHit(delta: Int) {
        if (mode == JsonViewMode.PRETTY || mode == JsonViewMode.RAW) {
            val view = if (mode == JsonViewMode.PRETTY) prettyView else rawView
            val n = if (delta > 0) view.nextMatch() else view.previousMatch()
            if (n >= 0) {
                hitPosition = n
                searchBar.setCount(n + 1, view.matchCount, hitsTruncated, false, null)
            }
            return
        }
        if (hitCount == 0) return
        hitPosition = ((hitPosition + delta) % hitCount + hitCount) % hitCount
        searchBar.setCount(hitPosition + 1, hitCount, hitsTruncated, false, null)
        revealHit()
    }

    private fun revealHit() {
        if (hitPosition < 0 || hitPosition >= hitCount) return
        val node = SearchHit.node(hits[hitPosition])
        when (mode) {
            JsonViewMode.TREE -> treeView.select(node)
            JsonViewMode.TABLE -> tableView.selectRowOf(node)
            else -> Unit
        }
    }

    // --- copy, save, open -----------------------------------------------------------------------------------------------------------

    private fun copyPathOf(node: Int) {
        val idx = session?.index ?: return
        JsonUi.copy(JsonPath.format(idx, node))
    }

    private fun copyValueOf(node: Int) {
        val idx = session?.index ?: return
        if (!idx.isContainer(node)) {
            JsonUi.copy(idx.stringValue(node))
            return
        }
        val bytes = idx.end(node).toLong() - idx.start(node)
        if (bytes > Limits.JSON_TEXT_FULL_MAX_CHARS) {
            statusMessage = RouteGenieBundle.message("json.copy.too_large", JsonUi.size(bytes))
            updateFooter()
            return
        }
        JsonUi.copy(JsonPrinter.toString(idx, node, PrintOptions(indent = indent)))
    }

    private fun copyCurrent() {
        val idx = session?.index ?: return
        when (mode) {
            JsonViewMode.PRETTY -> JsonUi.copy(prettyView.text)
            JsonViewMode.RAW -> JsonUi.copy(rawView.text)
            else -> if (selectedNode >= 0) copyValueOf(selectedNode) else copyValueOf(idx.root)
        }
    }

    private fun saveToFile() {
        val s = session ?: return
        val descriptor = FileSaverDescriptor(RouteGenieBundle.message("json.save.title"), RouteGenieBundle.message("json.save.description"), SAVE_EXTENSION)
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, this).save(null as java.nio.file.Path?, SAVE_NAME) ?: return
        val target = wrapper.file.toPath()
        JsonUi.background {
            val result = runCatching {
                when (val c = s.content) {
                    is JsonContent.Bytes -> Files.write(target, c.bytes)
                    is JsonContent.File -> Files.copy(c.path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            JsonUi.later({ disposed }) {
                statusMessage = result.fold(
                    { RouteGenieBundle.message("json.save.done", target.fileName.toString()) },
                    { RouteGenieBundle.message("json.save.failed", it.message.orEmpty()) },
                )
                updateFooter()
            }
        }
    }

    private fun projectOrNull(): Project? =
        CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(this)) ?: ProjectManager.getInstance().openProjects.firstOrNull()

    private fun openAsFile() {
        val s = session ?: return
        val project = projectOrNull() ?: return
        JsonUi.background {
            val path = runCatching {
                when (val c = s.content) {
                    is JsonContent.File -> c.path
                    is JsonContent.Bytes -> Files.createTempFile(TEMP_PREFIX, SAVE_SUFFIX).also { Files.write(it, c.bytes); it.toFile().deleteOnExit() }
                }
            }.getOrNull()
            JsonUi.later({ disposed }) {
                val file = path?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
                if (file != null) FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    }

    // --- test hooks -------------------------------------------------------------------------------------------------------------

    internal fun statusText(): String = statusLabel.text.orEmpty()
    internal fun hasErrorBanner(): Boolean = errorBanner.isVisible
    internal fun errorBannerText(): String = errorTitle.text.orEmpty()
    internal fun searchHitCount(): Int = hitCount
    internal fun currentIndex(): JsonIndex? = session?.index
    internal fun isIndexing(): Boolean = session?.done == false

    private companion object {
        const val LINE_BREAK = "<br>"
        const val CARD_EMPTY = "empty"
        const val CARD_LOADING = "loading"
        const val CARD_TREE = "tree"
        const val CARD_PRETTY = "pretty"
        const val CARD_RAW = "raw"
        const val CARD_TABLE = "table"
        const val CARD_TABLE_NONE = "tableNone"
        const val CARD_NOTICE = "notice"
        const val TOOLBAR_PLACE = "RouteGenie.JsonViewer"
        const val REFRESH_MS = 150L
        const val EXPAND_LEVELS_SHOWN = 4
        val INDENT_CHOICES = intArrayOf(2, 4)
        const val BREADCRUMB_MAX = 8
        const val STATUS_SEPARATOR = "  ·  "
        const val CRUMB_SEPARATOR = "›"
        const val ELLIPSIS = "…"
        const val EMPTY_KEY = "\"\""
        const val SAVE_NAME = "response.json"
        const val SAVE_EXTENSION = "json"
        const val SAVE_SUFFIX = ".json"
        const val TEMP_PREFIX = "routegenie-body-"
    }
}
