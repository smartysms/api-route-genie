package com.routegenie.ui.request

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowType
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.routegenie.Ids
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.Environment
import com.routegenie.client.ResponseSlot
import com.routegenie.client.SecretKeys
import com.routegenie.client.HttpEngine
import com.routegenie.client.PrepareResult
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.client.curl.ParsedCurl
import com.routegenie.clientide.RouteGenieClientService
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.clientide.SecretStore
import com.routegenie.clientide.passwordSafeFor
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.schema.BodyTemplateService
import com.routegenie.testmap.TestIndexSource
import com.routegenie.testmap.psi.TestMapService
import com.routegenie.ui.tests.QuickTestHost
import com.routegenie.ui.tests.TestExecutionPanel
import com.routegenie.ui.tests.TestsPanel
import com.routegenie.ui.EndpointDetailsSection
import com.routegenie.ui.EndpointNavigator
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.EmptyState
import com.routegenie.ui.design.ToolbarStyle
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

enum class WorkspaceLayout { AUTO, STACKED, SIDE_BY_SIDE }

/** UI thread plumbing for [SendController] on the real toolkit; every delayed task hangs on an [Alarm] owned by [parent]. */
class SwingUiHooks(private val project: Project, parent: Disposable) : UiHooks {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    override fun post(r: Runnable) = ApplicationManager.getApplication().invokeLater(r, ModalityState.any())

    override fun postDelayed(ms: Long, r: Runnable): () -> Unit {
        if (alarm.isDisposed) return {}
        alarm.addRequest(r, ms, ModalityState.any())
        return { if (!alarm.isDisposed) alarm.cancelRequest(r) }
    }

    override fun confirmHost(host: String): Boolean =
        Messages.showYesNoDialog(project, msg("req.confirm.text", host), msg("req.confirm.title"), msg("req.confirm.send"), msg("req.confirm.cancel"), Messages.getWarningIcon()) == Messages.YES
}

/**
 * The "Request" page of the details area: the whole calling workflow inside the window. Request form on top (or at the left),
 * response below (or at the right), chosen by the window's shape unless the user fixed it; a toggle hides the endpoint list
 * so this page gets the full window. Nothing is sent except when the user presses Send (or Ctrl+Enter).
 */
class RequestSection(
    private val project: Project,
    parent: Disposable,
    private val setListVisible: (Boolean) -> Unit = {},
    engine: (() -> HttpEngine)? = null,
    private val bodyTemplates: () -> BodyTemplateSource = { BodyTemplateService.getInstance(project) },
    ui: UiHooks? = null,
    /** What the project remembers: environments, last inputs, history lines, confirmed hosts (never secrets). */
    private val state: RouteGenieClientState = RouteGenieClientState.getInstance(project),
    /** Where secret values live; the IDE credential store in the window, a map in tests. */
    secretStore: SecretStore = passwordSafeFor(project),
    secretExecutor: (Runnable) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    /** Owner of the response on screen, so replaced responses lose their spill file. */
    responses: ResponseSlot = project.getService(RouteGenieClientService::class.java).responses,
    /** Where random upload files are written; the IDE's temporary folder in the window, a throw-away folder in tests. */
    private val fileStore: com.routegenie.files.GeneratedFileStore = defaultFileStore(),
    /** The test mapping the Tests tab reads: the project's service in the window, a fake in tests. Created only when the tab is shown. */
    private val tests: () -> TestIndexSource = { TestMapService.getInstance(project) },
) : EndpointDetailsSection, Disposable {
    override val title: String = msg("req.title")

    private val props = PropertiesComponent.getInstance(project)
    private val hooks = ui ?: SwingUiHooks(project, this)
    private val secrets = LayeredSecrets(secretStore, secretExecutor)
    private val history = HistoryLog(persisted = state.history)
    private val env: EnvironmentProvider = PersistentEnvironments(state, msg("req.env.default"))
    /** The project's shared engine (IDE trust store, proxy, spill folder), closed by its service with the project. */
    private val engineFactory: () -> HttpEngine = engine ?: { project.getService(RouteGenieClientService::class.java).engine }
    val controller = SendController(engineFactory, state.hostGuard, secrets, hooks, history, slot = responses)

    private val forms = object : LinkedHashMap<String, RequestForm>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestForm>) = size > Limits.LAST_INPUT_MAX_ENDPOINTS
    }
    private var current: RequestForm? = null
    private var disposed = false

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val formPanel = RequestFormPanel(project, this, bodyTemplates, secrets, ::onEdited, env::updateBaseUrl, fileStore = fileStore)
    private val envCombo = ComboBox<String>()
    private var envEditor: EnvironmentEditorPanel? = null
    private val requestCards = JPanel(CardLayout())
    private var refreshingEnvCombo = false
    private val response = ResponsePanel(project, this, controller, history, ::specForCurl, ::restore, ::import, testsTab = ::buildTestsTab)
    private val send = JButton(msg("req.send"), AllIcons.Actions.Execute).apply { toolTipText = msg("req.send.tooltip") }
    private val cancel = JButton(msg("req.cancel"), AllIcons.Actions.Suspend).apply { isVisible = false }
    private val splitter = OnePixelSplitter(true, UiKeys2.SPLITTER, Limits.WORKSPACE_REQUEST_PROPORTION)
    private val cards = JPanel(CardLayout())
    private var layoutChoice = WorkspaceLayout.entries.firstOrNull { it.name == props.getValue(PROP_LAYOUT) } ?: WorkspaceLayout.AUTO
    private var listVisible = true
    private var focusResponse = false
    var revealSelf: (() -> Unit)? = null

    override val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout())

    init {
        Disposer.register(parent, this)
        controller.observer = ::updateButtons
        send.addActionListener { send() }
        cancel.addActionListener { controller.cancel() }
        val sendBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), JBUI.scale(DesignTokens.Space.XS))).apply { add(send); add(cancel) }
        val envBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), JBUI.scale(DesignTokens.Space.XS))).apply {
            add(JBLabel(msg("req.env.label")))
            add(envCombo)
            add(ActionLink(msg("req.env.manage")) { showEnvironmentEditor(true) })
        }
        envCombo.addActionListener {
            if (!refreshingEnvCombo) (envCombo.selectedItem as? String)?.let { chooseEnvironment(it) }
        }
        val requestSide = JPanel(BorderLayout()).apply { add(envBar, BorderLayout.NORTH); add(formPanel, BorderLayout.CENTER); add(sendBar, BorderLayout.SOUTH) }
        requestCards.add(requestSide, CARD_REQUEST)
        splitter.firstComponent = requestCards
        secrets.onLoaded = { ApplicationManager.getApplication().invokeLater({ if (!disposed) formPanel.refreshSecretHint() }, ModalityState.any()) }
        refreshEnvironmentCombo()
        splitter.secondComponent = response
        cards.add(EmptyState(null, msg("req.none.title"), msg("req.none.hint")), CARD_NONE)
        cards.add(splitter, CARD_FORM)
        component.add(ToolbarStyle.bar(ToolbarStyle.create(TOOLBAR_PLACE, toolbarGroup(), component)), BorderLayout.NORTH)
        component.add(cards, BorderLayout.CENTER)
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) { if (send.isEnabled) send() }
        }.registerCustomShortcutSet(CommonShortcuts.CTRL_ENTER, component, this)
        component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = applyOrientation()
        })
        applyOrientation()
        (cards.layout as CardLayout).show(cards, CARD_NONE)
        RequestWorkspace.getInstance(project).register(this)
    }

    private fun toolbarGroup() = DefaultActionGroup().apply {
        add(object : ToggleAction(msg("req.hide_list"), msg("req.hide_list.tooltip"), AllIcons.Actions.SplitVertically) {
            override fun isSelected(e: AnActionEvent) = !listVisible
            override fun setSelected(e: AnActionEvent, state: Boolean) { listVisible = !state; applyListVisibility() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : ToggleAction(msg("req.focus_response"), msg("req.focus_response.tooltip"), AllIcons.General.ExpandComponent) {
            override fun isSelected(e: AnActionEvent) = focusResponse
            override fun setSelected(e: AnActionEvent, state: Boolean) = focusOnResponse(state)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : ToggleAction(msg("req.separate_window"), msg("req.separate_window.tooltip"), AllIcons.Actions.MoveToWindow) {
            override fun isSelected(e: AnActionEvent) = inSeparateWindow()
            override fun setSelected(e: AnActionEvent, state: Boolean) = showInSeparateWindow(state)
            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isEnabled = toolWindow() != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        val layout = DefaultActionGroup(msg("req.layout"), true).apply { templatePresentation.icon = AllIcons.Actions.SplitHorizontally }
        for (l in WorkspaceLayout.entries) {
            layout.add(object : ToggleAction(RequestMessages.layout(l)) {
                override fun isSelected(e: AnActionEvent) = layoutChoice == l
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    layoutChoice = l; props.setValue(PROP_LAYOUT, l.name); applyOrientation()
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        add(layout)
    }

    /** The endpoint list is shown only when the user did not hide it and the response is not in focus. */
    private fun applyListVisibility() = setListVisible(listVisible && !focusResponse)

    /**
     * Gives the whole page to the response: the request form and the endpoint list go away, and come back as they were when
     * this is switched off. Ctrl+Enter still sends, so the call can be repeated while reading the answer.
     */
    internal fun focusOnResponse(on: Boolean) {
        if (focusResponse == on) return
        focusResponse = on
        splitter.firstComponent = if (on) null else requestCards
        splitter.revalidate()
        applyListVisibility()
    }

    private fun toolWindow() = ToolWindowManager.getInstance(project).getToolWindow(Ids.TOOL_WINDOW)

    private fun inSeparateWindow() = toolWindow()?.type == ToolWindowType.WINDOWED

    /** Moves the whole window out of the IDE frame into its own resizable window (or docks it again). */
    private fun showInSeparateWindow(on: Boolean) {
        toolWindow()?.setType(if (on) ToolWindowType.WINDOWED else ToolWindowType.DOCKED, null)
    }

    /** Wide window: request at the left, response at the right. Narrow: stacked. A fixed choice wins. */
    internal fun applyOrientation() {
        val wide = component.width >= JBUI.scale(Limits.WORKSPACE_SIDE_BY_SIDE_MIN_WIDTH)
        val sideBySide = when (layoutChoice) {
            WorkspaceLayout.AUTO -> wide
            WorkspaceLayout.STACKED -> false
            WorkspaceLayout.SIDE_BY_SIDE -> true
        }
        splitter.orientation = !sideBySide
    }

    // ---- endpoint selection ------------------------------------------------------------------------------------

    override fun show(endpoint: Endpoint?) {
        if (disposed) return
        response.showTests(endpoint)
        if (endpoint == null) {
            current = null
            (cards.layout as CardLayout).show(cards, CARD_NONE)
            return
        }
        if (current?.endpoint?.key == endpoint.key) {
            (cards.layout as CardLayout).show(cards, CARD_FORM)
            return
        }
        rememberCurrent()
        env.ensureFor(endpoint.framework)
        useEnvironmentSecrets()
        refreshEnvironmentCombo()
        val form = forms.getOrPut(endpoint.key) {
            RequestForm(endpoint).also { f -> state.lastInputs.get(endpoint.key)?.let(f::restore) }
        }
        current = form
        formPanel.bind(form, env.current().baseUrl)
        formPanel.autoFillBody()
        (cards.layout as CardLayout).show(cards, CARD_FORM)
        response.refreshCurl()
        updateButtons()
    }

    // ---- the Tests tab -------------------------------------------------------------------------------------------------

    /** What the execution page of the Tests tab borrows from this page; built when the first test is selected. */
    private val quickHost: QuickTestHost by lazy {
        object : QuickTestHost {
            override val secrets get() = this@RequestSection.secrets
            override val environment get() = env
            override val bodyTemplates get() = this@RequestSection.bodyTemplates
            override val fileStore get() = this@RequestSection.fileStore
            override val engine get() = engineFactory
            override val guard get() = state.hostGuard
            override val ui get() = hooks
            override fun openInRequest(form: RequestForm) = adoptForm(form)
        }
    }

    private fun buildTestsTab(parent: Disposable, onBadge: (Int) -> Unit) =
        TestsPanel(project, parent, tests, onBadge, executionFactory = { owner -> TestExecutionPanel(project, owner, quickHost) })

    /** "Open in Request tab": the edited request of a quick test becomes the form of this page (in memory; nothing is sent). */
    internal fun adoptForm(form: RequestForm) {
        if (disposed) return
        forms[form.endpoint.key] = form
        current = form
        formPanel.bind(form, env.current().baseUrl)
        response.refreshCurl()
        response.selectTab(msg("req.tab.body"))
        updateButtons()
        revealSelf?.invoke()
    }

    // ---- sending -----------------------------------------------------------------------------------------------------

    fun send(): SendResult? {
        val form = current ?: return null
        val result = controller.send(form.endpoint, form, env.current())
        formPanel.showErrors((result as? SendResult.Invalid)?.errors.orEmpty())
        if (result is SendResult.Started) response.selectTab(msg("req.tab.body"))
        updateButtons()
        return result
    }

    private fun updateButtons() {
        val running = controller.state.running
        send.isEnabled = current != null && !running
        cancel.isVisible = running
    }

    private fun onEdited() {
        current ?: return
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ rememberCurrent(); response.refreshCurl() }, Limits.REFRESH_DEBOUNCE_MS, ModalityState.any())
    }

    /** Keeps the inputs of the open endpoint (method, parameters, body; credentials blanked by the store) for the next session. */
    private fun rememberCurrent() {
        val form = current ?: return
        state.lastInputs.put(form.endpoint.key, form.toInput())
    }

    // ---- environments --------------------------------------------------------------------------------------------

    private fun refreshEnvironmentCombo() {
        refreshingEnvCombo = true
        try {
            val names = env.all().map { it.name }.ifEmpty { listOf(env.current().name) }
            envCombo.model = javax.swing.DefaultComboBoxModel(names.toTypedArray())
            envCombo.selectedItem = env.current().name
        } finally {
            refreshingEnvCombo = false
        }
    }

    /** The user picked another environment: base URL, secrets and cURL follow it. */
    private fun chooseEnvironment(name: String) {
        if (env.current().name == name) return
        env.select(name)
        environmentChanged()
    }

    private fun environmentChanged() {
        useEnvironmentSecrets()
        formPanel.refresh(env.current().baseUrl)
        response.refreshCurl()
    }

    /** Loads the secrets of the current environment from the credential store (background) into the memory layer. */
    private fun useEnvironmentSecrets() {
        val e = env.current()
        val keys = listOf(SecretKeys.TOKEN, SecretKeys.PASSWORD, SecretKeys.API_KEY) + e.secretVariables.map { SecretKeys.variable(it) }
        secrets.useScope(e.name, keys)
    }

    fun showEnvironmentEditor(show: Boolean) {
        if (disposed) return
        if (show) {
            val editor = envEditor ?: EnvironmentEditorPanel(
                this, env, { name, key -> name == secrets.currentScope && secrets.has(key) }, ::environmentSaved, { showEnvironmentEditor(false) },
            ).also { envEditor = it; requestCards.add(it, CARD_ENV) }
            editor.reload(env.current().name)
            (requestCards.layout as CardLayout).show(requestCards, CARD_ENV)
        } else {
            (requestCards.layout as CardLayout).show(requestCards, CARD_REQUEST)
        }
    }

    private fun environmentSaved(saved: Environment, secretValues: Map<String, String>) {
        secretValues.forEach { (key, value) -> secrets.putFor(saved.name, key, value) }
        refreshEnvironmentCombo()
        environmentChanged()
    }

    /** The request as cURL needs it: from the form, with credentials hidden unless [withSecrets]. Null while the form is invalid. */
    private fun specForCurl(withSecrets: Boolean): RequestSpec? {
        val form = current ?: return null
        val prepared = (controller.prepare(form.endpoint, form, env.current()) as? PrepareResult.Ready)?.request ?: return null
        return if (withSecrets) prepared.spec else RequestSpec(prepared.spec.method, prepared.displayUrl, prepared.redactedHeaders, prepared.spec.body)
    }

    /** The current form's request for [endpoint] (credentials hidden), or null when another endpoint is open or the form is invalid. */
    internal fun specFor(endpoint: Endpoint): RequestSpec? = if (current?.endpoint?.key == endpoint.key) specForCurl(false) else null

    private fun restore(entry: HistoryEntry) {
        if (entry.endpointKey.isNotEmpty() && entry.endpointKey != current?.endpoint?.key) {
            EndpointNavigator.getInstance(project).reveal(entry.endpointKey)
            return
        }
        val copy = entry.form?.copy() ?: return
        forms[entry.endpointKey] = copy
        current = copy
        formPanel.bind(copy, env.current().baseUrl)
        response.refreshCurl()
    }

    private fun import(parsed: ParsedCurl) {
        val form = current ?: return
        val result = form.apply(parsed.spec)
        result.baseUrl?.let { env.updateBaseUrl(it) }
        formPanel.refresh(env.current().baseUrl)
        response.refreshCurl()
    }

    /** Called by the "Send Request..." action: show this page, focus the Send button. Does not send. */
    fun focusSend() {
        revealSelf?.invoke()
        send.requestFocusInWindow()
    }

    // ---- test hooks ------------------------------------------------------------------------------------------------

    internal fun formForTest() = formPanel
    internal fun responseForTest() = response
    internal fun sendButton() = send
    internal fun cancelButton() = cancel
    internal fun currentForm() = current
    internal fun splitterVertical() = splitter.orientation
    internal fun requestSideShown() = splitter.firstComponent != null
    internal fun isFocusOnResponse() = focusResponse
    internal fun environmentEditorForTest(): EnvironmentEditorPanel? = envEditor
    internal fun environmentNames(): List<String> = (0 until envCombo.itemCount).map { envCombo.getItemAt(it) }
    internal fun chooseEnvironmentForTest(name: String) { envCombo.selectedItem = name }
    internal fun secretsForTest() = secrets
    internal fun historyForTest() = history
    internal fun rememberNowForTest() = rememberCurrent()

    override fun dispose() {
        if (disposed) return
        disposed = true
        RequestWorkspace.getInstance(project).unregister(this)
        rememberCurrent()
        controller.close()
        history.releaseBodies()
        forms.clear()
        secrets.onLoaded = null
        secrets.clear()
    }

    companion object {
        private const val CARD_NONE = "none"
        private const val CARD_FORM = "form"
        private const val CARD_REQUEST = "request"
        private const val CARD_ENV = "environment"
        private const val TOOLBAR_PLACE = "RouteGenie.Request"
        private const val PROP_LAYOUT = "routegenie.ui.workspaceLayout"
    }
}

internal object UiKeys2 {
    const val SPLITTER = "RouteGenie.RequestResponseSplitter"
}
