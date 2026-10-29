package com.routegenie.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.CopyProvider
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.AsyncProcessIcon
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.Endpoint
import com.routegenie.model.RouteSnapshot
import com.routegenie.ui.gutter.RouteGutterIndex
import com.routegenie.ui.model.EndpointIndex
import com.routegenie.ui.model.EndpointQuery
import com.routegenie.ui.model.EndpointTree
import com.routegenie.ui.model.Group
import com.routegenie.ui.model.GroupMode
import com.routegenie.ui.model.Leaf
import com.routegenie.ui.model.QueryKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.tree.TreeSelectionModel

/**
 * The endpoint list: toolbar, query box, tree (or a state page), details, status line. Snapshots are collected only while the
 * window is showing; the sorted index, the filtering and the grouped tree are computed off the UI thread and swapped in on it.
 * Everything it starts is tied to [parent] (scope cancelled in [dispose]).
 */
class EndpointsPanel(private val project: Project, parent: Disposable) :
    JBPanel<EndpointsPanel>(BorderLayout()), UiDataProvider, Disposable {

    private data class ViewParams(val query: String, val mode: GroupMode, val includeDeprecated: Boolean)

    private class View(
        val snapshot: RouteSnapshot,
        val params: ViewParams,
        val total: Int,
        val visible: Int,
        val tree: EndpointTree,
        val hasPython: Boolean,
        val index: EndpointIndex,
    )

    private val props = PropertiesComponent.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service by lazy { RouteDiscoveryService.getInstance(project) }
    private val params = MutableStateFlow(
        ViewParams("", GroupMode.parse(props.getValue(UiKeys.PROP_GROUP_MODE)), props.getBoolean(UiKeys.PROP_SHOW_DEPRECATED, true)),
    )

    @Volatile
    private var selectedEndpoint: Endpoint? = null
    private var shown = false
    private var disposed = false
    private var applying = false
    private var updating = false
    private var lastView: View? = null
    private var currentTree: EndpointTree = EndpointTree.EMPTY
    private var pendingKey: String? = null
    private var collector: Job? = null
    private var hintJob: Job? = null
    private var card = ""

    // Caches used by the background computation only; dropped while hidden.
    @Volatile private var cachedIndex: Pair<RouteSnapshot, EndpointIndex>? = null
    @Volatile private var cachedFull: Pair<Triple<RouteSnapshot, GroupMode, Boolean>, EndpointTree>? = null

    internal val tree = EndpointJTree().apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = EndpointTreeRenderer()
        model = EndpointTreeModel(EndpointTree.EMPTY.root)
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    internal val searchField = SearchTextField(false)
    internal val details = EndpointDetailsPanel(project)
    private val status = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(UiMetrics.GAP))
    }
    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    private val copyProvider = object : CopyProvider {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun performCopy(dataContext: DataContext) = copyPath()
        override fun isCopyEnabled(dataContext: DataContext): Boolean = selectedEndpoint != null
        override fun isCopyVisible(dataContext: DataContext): Boolean = true
    }

    init {
        Disposer.register(parent, this)
        buildCards()
        add(buildTop(), BorderLayout.NORTH)
        val splitter = OnePixelSplitter(true, UiKeys.SPLITTER, Limits.DETAILS_SPLIT_PROPORTION).apply {
            firstComponent = cards
            secondComponent = details
        }
        add(splitter, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        wireTree()
        showCard(CARD_LOADING)

        // The window being on screen is the signal that somebody looks (content hidden = tool window hidden or another tab).
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) setShown(isShowing)
        }
        scope.launch {
            EndpointNavigator.getInstance(project).requested.collect { key ->
                if (key != null) withContext(Dispatchers.EDT) {
                    pendingKey = key
                    trySelectPending()
                }
            }
        }
    }

    // ---- construction -------------------------------------------------------------------------------------------

    private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

    private fun buildCards() {
        cards.add(JBScrollPane(tree), CARD_TREE)
        val loading = JPanel(GridBagLayout()).apply {
            val icon = AsyncProcessIcon("loading")
            Disposer.register(this@EndpointsPanel, icon)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.CENTER, JBUI.scale(UiMetrics.GAP), 0)).apply {
                add(icon)
                add(JBLabel(msg("progress.scanning")))
            })
        }
        cards.add(loading, CARD_LOADING)
        cards.add(JBPanelWithEmptyText().withEmptyText(msg("window.empty")).also { it.emptyText.appendLine(msg("state.empty.hint")) }, CARD_EMPTY)
        cards.add(JBPanelWithEmptyText().withEmptyText(msg("state.no_match")).also { it.emptyText.appendLine(msg("state.no_match.hint")) }, CARD_NO_MATCH)
        cards.add(JBPanelWithEmptyText().withEmptyText(msg("state.no_python")).also { it.emptyText.appendLine(msg("state.no_python.hint")) }, CARD_NO_PYTHON)
    }

    private fun buildTop(): JPanel {
        val group = DefaultActionGroup().apply {
            add(UiAction(msg("toolbar.refresh"), msg("toolbar.refresh.tooltip"), AllIcons.Actions.Refresh) { service.requestRefresh() })
            addSeparator()
            add(UiAction(msg("toolbar.expand_all"), msg("toolbar.expand_all.tooltip"), AllIcons.Actions.Expandall) { expandAll() })
            add(UiAction(msg("toolbar.collapse_all"), msg("toolbar.collapse_all.tooltip"), AllIcons.Actions.Collapseall) { collapseAll() })
            val groupBy = DefaultActionGroup(msg("toolbar.group_by"), true)
            groupBy.templatePresentation.icon = AllIcons.Actions.GroupBy
            groupBy.templatePresentation.description = msg("toolbar.group_by.tooltip")
            for (mode in GroupMode.entries) {
                groupBy.add(UiToggle(msg(mode.bundleKey), msg("toolbar.group_by.tooltip"), null, { params.value.mode == mode }, { setMode(mode) }))
            }
            add(groupBy)
            add(UiToggle(msg("toolbar.show_deprecated"), msg("toolbar.show_deprecated.tooltip"), AllIcons.General.Filter,
                { params.value.includeDeprecated }, { setIncludeDeprecated(it) }))
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(UiKeys.TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = this

        searchField.textEditor.toolTipText = msg("query.tooltip", QueryKeys.ALL.joinToString(", ") { "$it:" })
        (searchField.textEditor as? JBTextField)?.emptyText?.text = msg("query.placeholder")
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = searchField.text
                params.update { it.copy(query = text) }
            }
        })
        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(searchField, BorderLayout.CENTER)
            border = JBUI.Borders.empty(JBUI.scale(2))
        }
    }

    private fun wireTree() {
        // Speed search over visible rows only: a "can expand" search would walk all 50k nodes on every key.
        TreeSpeedSearch.installOn(tree, false) { path ->
            when (val node = path.lastPathComponent) {
                is Leaf -> "${node.endpoint.method} ${node.endpoint.path} ${node.endpoint.handler.name}"
                is Group -> node.label
                else -> ""
            }
        }
        tree.addTreeSelectionListener { if (!applying) onSelectionChanged() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                if (tree.getPathForLocation(e.x, e.y)?.lastPathComponent is Leaf) navigateSelected()
            }
        })
        UiAction("", "", null) { navigateSelected() }.registerCustomShortcutSet(CommonShortcuts.ENTER, tree)
        val popup = DefaultActionGroup().apply {
            add(UiAction(msg("action.navigate"), msg("action.navigate.tooltip"), AllIcons.Actions.EditSource, { selectedEndpoint != null }) { navigateSelected() })
            add(UiAction(msg("action.copy_path"), msg("action.copy_path.tooltip"), AllIcons.Actions.Copy, { selectedEndpoint != null }) { copyPath() })
        }
        // Own handler instead of PopupHandler.installPopupMenu: that one creates a popup menu registered with the application
        // up front, which kept every closed window's tree alive. A menu is created per click and owned by the platform.
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                tree.getClosestPathForLocation(x, y)?.takeIf { tree.getPathBounds(it)?.contains(x, y) == true }
                    ?.let { if (!tree.isPathSelected(it)) tree.selectionPath = it }
                ActionManager.getInstance().createActionPopupMenu(UiKeys.POPUP_PLACE, popup).component.show(comp, x, y)
            }
        })
        tree.emptyText.text = ""
    }

    // ---- state changes from the user ------------------------------------------------------------------------------

    private fun setMode(mode: GroupMode) {
        props.setValue(UiKeys.PROP_GROUP_MODE, mode.name)
        params.update { it.copy(mode = mode) }
    }

    private fun setIncludeDeprecated(value: Boolean) {
        props.setValue(UiKeys.PROP_SHOW_DEPRECATED, value, true)
        params.update { it.copy(includeDeprecated = value) }
    }

    private fun navigateSelected() {
        selectedEndpoint?.let { EndpointSourceNavigation.navigate(project, it.source) }
    }

    private fun copyPath() {
        selectedEndpoint?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.path)) }
    }

    private fun onSelectionChanged() {
        selectedEndpoint = (tree.lastSelectedPathComponent as? Leaf)?.endpoint
        details.show(selectedEndpoint)
    }

    internal fun expandAll() = expandGroups(currentTree) { true }

    internal fun collapseAll() {
        for (i in tree.rowCount - 1 downTo 0) tree.collapseRow(i)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val endpoint = selectedEndpoint ?: return
        sink[EndpointDataKeys.SELECTED_ENDPOINT] = endpoint
        sink[CommonDataKeys.NAVIGATABLE] = EndpointNavigatable(project, endpoint)
        sink[PlatformDataKeys.COPY_PROVIDER] = copyProvider
    }

    // ---- shown / hidden ------------------------------------------------------------------------------------------

    /** Called from the hierarchy listener; public so tests can drive it without a real frame. */
    fun setShown(value: Boolean) {
        if (disposed || value == shown) return
        shown = value
        if (value || !gutterNeedsDiscovery()) service.setActive(value)
        if (value) startCollecting() else stopCollecting()
    }

    /** Gutter icons keep discovery on once they used it; hiding this window must not freeze them. */
    private fun gutterNeedsDiscovery(): Boolean = RouteGutterIndex.getInstance(project).isActivated

    private fun startCollecting() {
        if (service.isStale) {
            updating = true
            refreshStatus()
            hintJob = scope.launch {
                delay(Limits.UPDATING_HINT_MS)
                withContext(Dispatchers.EDT) {
                    updating = false
                    refreshStatus()
                }
            }
        }
        collector = scope.launch {
            combine(service.snapshot, params) { snapshot, p -> snapshot to p }.collectLatest { (snapshot, p) ->
                val view = compute(snapshot, p)
                withContext(Dispatchers.EDT) { if (shown) apply(view) }
            }
        }
    }

    private fun stopCollecting() {
        collector?.cancel()
        collector = null
        hintJob?.cancel()
        cachedIndex = null
        cachedFull = null
    }

    // ---- background computation ----------------------------------------------------------------------------------

    private suspend fun compute(snapshot: RouteSnapshot, p: ViewParams): View {
        val index = cachedIndex?.takeIf { it.first === snapshot }?.second
            ?: EndpointIndex.build(snapshot.endpoints).also { cachedIndex = snapshot to it }
        val query = EndpointQuery.parse(p.query)
        val visible = index.filter(query, p.includeDeprecated)
        val fullKey = Triple(snapshot, p.mode, p.includeDeprecated)
        val tree = if (query.isEmpty) {
            cachedFull?.takeIf { it.first == fullKey }?.second
                ?: EndpointTree.build(visible, p.mode).also { cachedFull = fullKey to it }
        } else {
            EndpointTree.build(visible, p.mode)
        }
        val hasPython = snapshot.generation == 0L || index.size > 0 || hasPythonFiles()
        return View(snapshot, p, index.size, visible.size, tree, hasPython, index)
    }

    private suspend fun hasPythonFiles(): Boolean = try {
        readAction {
            FileTypeIndex.containsFileOfType(FileTypeManager.getInstance().getFileTypeByExtension(PYTHON_EXTENSION), GlobalSearchScope.projectScope(project))
        }
    } catch (e: IndexNotReadyException) {
        true
    }

    // ---- applying on the UI thread -------------------------------------------------------------------------------

    private fun apply(view: View) {
        updating = false
        val previous = lastView
        val keepKey = selectedEndpoint?.key
        applying = true
        try {
            if (view.tree !== currentTree) {
                val expanded = if (previous == null) emptySet() else expandedKeys()
                currentTree = view.tree
                tree.model = EndpointTreeModel(view.tree.root)
                val reset = previous == null || previous.params != view.params
                if (reset && view.tree.count <= Limits.AUTO_EXPAND_MAX_ENDPOINTS) expandGroups(view.tree) { true }
                else expandGroups(view.tree) { it.key in expanded }
                keepKey?.let { view.tree.findLeaf(it) }?.let { select(it) }
            }
            lastView = view
        } finally {
            applying = false
        }
        showCard(
            when {
                view.snapshot.generation == 0L -> CARD_LOADING
                view.total == 0 -> if (view.hasPython) CARD_EMPTY else CARD_NO_PYTHON
                view.visible == 0 -> CARD_NO_MATCH
                else -> CARD_TREE
            },
        )
        onSelectionChanged()
        refreshStatus()
        trySelectPending()
    }

    private fun showCard(name: String) {
        card = name
        cardLayout.show(cards, name)
    }

    internal fun currentCard(): String = card

    internal fun statusText(): String = status.text

    private fun refreshStatus() {
        val view = lastView
        val base = when {
            view == null || view.snapshot.generation == 0L -> ""
            view.visible == view.total -> msg("status.count", view.total)
            else -> msg("status.filtered", view.visible, view.total)
        }
        status.text = if (updating) listOf(base, msg("status.updating")).filter { it.isNotEmpty() }.joinToString("  ") else base
    }

    private fun expandedKeys(): Set<String> {
        val root = tree.model.root as? Group ?: return emptySet()
        val open = tree.getExpandedDescendants(EndpointTreeModel.pathOf(root)) ?: return emptySet()
        val keys = HashSet<String>()
        while (open.hasMoreElements()) (open.nextElement().lastPathComponent as? Group)?.let { keys += it.key }
        return keys
    }

    /** Bounded: stops once the open groups would show more than [Limits.MAX_EXPANDED_ROWS] rows. */
    private fun expandGroups(model: EndpointTree, wanted: (Group) -> Boolean) {
        var rows = 0
        for (group in model.groups()) {
            if (group === model.root || !wanted(group)) continue
            rows += group.children.size
            if (rows > Limits.MAX_EXPANDED_ROWS) break
            tree.expandPath(EndpointTreeModel.pathOf(group))
        }
    }

    private fun select(leaf: Leaf) {
        val path = EndpointTreeModel.pathOf(leaf)
        tree.makeVisible(path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /**
     * Handles [EndpointNavigator.requested]: selects the endpoint when it is in the current tree; when a filter hides it the
     * filter is cleared; when it is no longer in the snapshot the request is dropped. Waits while nothing is loaded yet.
     */
    private fun trySelectPending() {
        val key = pendingKey ?: return
        val view = lastView ?: return
        if (view.snapshot.generation == 0L) return
        val leaf = currentTree.findLeaf(key)
        if (leaf != null) {
            applying = true
            try {
                select(leaf)
            } finally {
                applying = false
            }
            onSelectionChanged()
            done(key)
        } else if (view.index.contains(key) && (view.params.query.isNotEmpty() || !view.params.includeDeprecated)) {
            searchField.text = ""
            params.update { it.copy(query = "", includeDeprecated = true) }
        } else {
            done(key)
        }
    }

    private fun done(key: String) {
        pendingKey = null
        EndpointNavigator.getInstance(project).consume(key)
    }

    // ---- test hooks ----------------------------------------------------------------------------------------------

    internal fun selectedKey(): String? = selectedEndpoint?.key

    internal fun setQueryForTest(text: String) {
        searchField.text = text
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        if (shown && !project.isDisposed && !gutterNeedsDiscovery()) service.setActive(false)
        shown = false
    }

    private companion object {
        const val CARD_TREE = "tree"
        const val CARD_LOADING = "loading"
        const val CARD_EMPTY = "empty"
        const val CARD_NO_MATCH = "nomatch"
        const val CARD_NO_PYTHON = "nopython"
        const val PYTHON_EXTENSION = "py"
    }
}
