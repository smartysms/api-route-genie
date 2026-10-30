package com.routegenie.ui.tests

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.client.HostGuard
import com.routegenie.client.HttpEngine
import com.routegenie.client.PrepareResult
import com.routegenie.client.RequestSpec
import com.routegenie.client.BodyTemplateSource
import com.routegenie.files.GeneratedFileStore
import com.routegenie.model.Endpoint
import com.routegenie.settings.BodyFillMode
import com.routegenie.testmap.TestCall
import com.routegenie.testmap.TestCommands
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.Palette
import com.routegenie.ui.model.TestVerdict
import com.routegenie.ui.model.Verdict
import com.routegenie.ui.request.EnvironmentProvider
import com.routegenie.ui.request.HistoryLog
import com.routegenie.ui.request.LayeredSecrets
import com.routegenie.ui.request.Phase
import com.routegenie.ui.request.RequestForm
import com.routegenie.ui.request.RequestFormPanel
import com.routegenie.ui.request.ResponsePanel
import com.routegenie.ui.request.SendController
import com.routegenie.ui.request.SendResult
import com.routegenie.ui.request.UiHooks
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel

private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

/**
 * What the execution page borrows from the Request page: the environment (base URL), the secrets, the engine, the shared
 * one-time confirmation of non-local hosts and the UI hooks. Nothing is duplicated: the page only builds its own controller and
 * form around these, so a quick test is sent by exactly the machinery of a normal request.
 */
interface QuickTestHost {
    val secrets: LayeredSecrets
    val environment: EnvironmentProvider
    val bodyTemplates: () -> BodyTemplateSource
    val fileStore: GeneratedFileStore
    val engine: () -> HttpEngine
    val guard: HostGuard
    val ui: UiHooks

    /** Moves an edited request into the form of the Request page. */
    fun openInRequest(form: RequestForm)
}

/**
 * The execution page of the Tests tab. Selecting a test call fills an editable request from the literal arguments of the call;
 * the user may change anything (in memory only: neither the test file nor the project is touched), press Run quick test, and
 * read the answer below with the verdict against the status the test asserts. Sending is always an explicit click.
 */
class TestExecutionPanel(
    private val project: Project,
    parent: Disposable,
    private val host: QuickTestHost,
) : JBPanel<TestExecutionPanel>(BorderLayout()), Disposable {
    private val history = HistoryLog()
    private val controller = SendController(host.engine, host.guard, host.secrets, host.ui, history)
    private val formPanel = RequestFormPanel(
        project, this, host.bodyTemplates, host.secrets, onEdited = { response.refreshCurl() }, onBaseUrl = { host.environment.updateBaseUrl(it) },
        fillMode = { BodyFillMode.OFF }, fileStore = host.fileStore,
    )
    private val response: ResponsePanel = ResponsePanel(project, this, controller, history, ::specForCurl, onRestore = {}, onImport = {})
    private val title = JBLabel().apply { font = DesignTokens.Type.bodyStrong() }
    private val note = JBLabel().apply { foreground = Palette.textSecondary }
    private val verdict = JBLabel().apply { border = JBUI.Borders.empty(Space.XS, Space.M) }
    private val run = JButton(msg("tests.exec.run"), AllIcons.Actions.Execute).apply { toolTipText = msg("tests.exec.run.tooltip") }
    private val openRequest = JButton(msg("tests.exec.open_request")).apply { toolTipText = msg("tests.exec.open_request.tooltip") }
    private val ideRun = JButton(msg("tests.exec.ide_run"), AllIcons.RunConfigurations.TestState.Run).apply { toolTipText = msg("tests.exec.ide_run.tooltip") }
    private val copyCommand = JButton(msg("tests.exec.copy_command"), AllIcons.Actions.Copy).apply { toolTipText = msg("tests.exec.copy_command.tooltip") }

    private val forms = object : LinkedHashMap<String, RequestForm>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RequestForm>) = size > Limits.TEST_QUICK_INPUTS_MAX
    }
    private val notes = HashMap<String, List<NotDetermined>>()
    private var endpoint: Endpoint? = null
    private var call: TestCall? = null
    private var form: RequestForm? = null
    private var disposed = false

    /** Set while the user's last click on "Run test in the IDE" had no run configuration; shown instead of the verdict. */
    private var ideMessage: String? = null

    init {
        Disposer.register(parent, this)
        controller.observer = ::updateButtons
        run.addActionListener { runQuickTest() }
        openRequest.addActionListener { form?.let { host.openInRequest(it.copy()) } }
        ideRun.addActionListener { runInIde() }
        copyCommand.addActionListener { call?.let { CopyPasteManager.getInstance().setContents(StringSelection(TestCommands.of(it.test))) } }
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.S), JBUI.scale(Space.XS))).apply {
            isOpaque = false
            add(run)
            add(openRequest)
            add(ideRun)
            add(copyCommand)
        }
        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(Space.XS, Space.M, 0, Space.M)
            add(title, BorderLayout.NORTH)
            add(note, BorderLayout.CENTER)
            add(bar, BorderLayout.SOUTH)
        }
        val editor = JPanel(BorderLayout()).apply {
            add(head, BorderLayout.NORTH)
            add(formPanel, BorderLayout.CENTER)
        }
        val answer = JPanel(BorderLayout()).apply {
            add(verdict, BorderLayout.NORTH)
            add(response, BorderLayout.CENTER)
        }
        add(OnePixelSplitter(true, SPLITTER_KEY, Limits.WORKSPACE_REQUEST_PROPORTION).apply {
            firstComponent = editor
            secondComponent = answer
        }, BorderLayout.CENTER)
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) { if (run.isEnabled) runQuickTest() }
        }.registerCustomShortcutSet(CommonShortcuts.CTRL_ENTER, this, this)
        showTest(null, null)
    }

    // ---- what is shown -----------------------------------------------------------------------------------------------

    /** The selected test call of [endpoint], or nothing. Fills the editor with that call's request, or with what the user edited before. */
    fun showTest(endpoint: Endpoint?, call: TestCall?) {
        if (disposed) return
        this.endpoint = endpoint
        this.call = call
        ideMessage = null
        if (endpoint == null || call == null) {
            form = null
            title.text = msg("tests.exec.none")
            note.text = ""
            verdict.text = ""
            updateButtons()
            return
        }
        val key = call.test.id + "@" + call.source.offset + "#" + endpoint.key
        val form = forms[key] ?: QuickTestForm.build(endpoint, call).also { built ->
            forms[key] = built.form
            notes[key] = built.notDetermined
        }.form
        this.form = form
        host.environment.ensureFor(endpoint.framework)
        formPanel.bind(form, host.environment.current().baseUrl)
        title.text = msg("tests.exec.title", call.test.displayName)
        note.text = noteText(notes[key].orEmpty(), call)
        verdict.text = ""
        response.refreshCurl()
        updateButtons()
    }

    private fun noteText(missing: List<NotDetermined>, call: TestCall): String {
        val parts = ArrayList<String>()
        parts += if (call.expectedStatus != null) msg("tests.exec.expects", call.expectedStatus) else msg("tests.exec.expects_none")
        if (missing.isNotEmpty()) {
            parts += msg("tests.exec.not_determined", missing.joinToString(", ") { msg(it.bundleKey) })
        }
        parts += msg("tests.exec.hint")
        return "<html>" + parts.joinToString("<br>") { com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it) } + "</html>"
    }

    // ---- running -----------------------------------------------------------------------------------------------------

    /** Sends the edited request. Only an explicit click (or Ctrl+Enter in this page) gets here. */
    fun runQuickTest(): SendResult? {
        val endpoint = endpoint ?: return null
        val form = form ?: return null
        val result = controller.send(endpoint, form, host.environment.current())
        formPanel.showErrors((result as? SendResult.Invalid)?.errors.orEmpty())
        if (result is SendResult.Started) {
            verdict.text = ""
            response.selectTab(msg("req.tab.body"))
        }
        updateButtons()
        return result
    }

    private fun updateButtons() {
        if (disposed) return
        val running = controller.state.running
        run.isEnabled = form != null && !running
        openRequest.isEnabled = form != null
        ideRun.isEnabled = call != null
        copyCommand.isEnabled = call != null
        if (!running && controller.state.phase != Phase.IDLE) showVerdict()
    }

    private fun showVerdict() {
        val state = controller.state
        val status = state.response?.status ?: state.head?.status
        val expected = call?.expectedStatus
        verdict.text = when (TestVerdict.of(expected, status)) {
            Verdict.MATCHES -> msg("tests.exec.verdict.match", expected!!, status!!)
            Verdict.DIFFERS -> msg("tests.exec.verdict.differs", expected!!, status!!)
            Verdict.UNASSERTED -> msg("tests.exec.verdict.none", status!!)
            Verdict.NO_ANSWER -> msg("tests.exec.verdict.failed")
        }
        verdict.foreground = when (TestVerdict.of(expected, status)) {
            Verdict.MATCHES -> Palette.success.text
            Verdict.DIFFERS, Verdict.NO_ANSWER -> Palette.danger.text
            Verdict.UNASSERTED -> Palette.textSecondary
        }
    }

    private fun runInIde() {
        val test = call?.test ?: return
        TestLauncher.run(project, test) { started ->
            if (!disposed) {
                ideMessage = if (started) null else msg("tests.exec.ide_unavailable")
                if (!started) {
                    verdict.foreground = Palette.warning.text
                    verdict.text = ideMessage.orEmpty()
                }
            }
        }
    }

    /** The request as cURL needs it (credentials hidden); null while the form is invalid. */
    private fun specForCurl(withSecrets: Boolean): RequestSpec? {
        val endpoint = endpoint ?: return null
        val form = form ?: return null
        val prepared = (controller.prepare(endpoint, form, host.environment.current()) as? PrepareResult.Ready)?.request ?: return null
        return if (withSecrets) prepared.spec else RequestSpec(prepared.spec.method, prepared.displayUrl, prepared.redactedHeaders, prepared.spec.body)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        controller.observer = null
        controller.close()
        history.releaseBodies()
        forms.clear()
        notes.clear()
    }

    // ---- test hooks --------------------------------------------------------------------------------------------------

    internal fun formForTest(): RequestForm? = form
    internal fun formPanelForTest() = formPanel
    internal fun responseForTest() = response
    internal fun controllerForTest() = controller
    internal fun runButton() = run
    internal fun openRequestButton() = openRequest
    internal fun ideRunButton() = ideRun
    internal fun copyCommandButton() = copyCommand
    internal fun verdictText(): String = verdict.text
    internal fun noteTextForTest(): String = note.text
    internal fun titleText(): String = title.text
    internal fun rememberedForms(): Int = forms.size

    private companion object {
        const val SPLITTER_KEY = "RouteGenie.TestsExecutionSplitter"
    }
}
