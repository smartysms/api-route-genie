package com.routegenie.ui.request

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.routegenie.RouteGenieBundle
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.FieldError
import com.routegenie.client.FieldLocation
import com.routegenie.client.SecretKeys
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.ui.design.BadgeState
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.MethodBadge
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.SectionHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentListener as SwingDocumentListener
import javax.swing.table.AbstractTableModel

private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

/** An editable table of [FormRow]s with enable checkbox, name, value (and a "file" flag for multipart parts). */
internal class RowsTable(private val withFile: Boolean, private val onChange: () -> Unit) {
    var rows: MutableList<FormRow> = ArrayList()
        set(value) { field = value; model.fireTableDataChanged() }

    private val model = object : AbstractTableModel() {
        private val names = buildList {
            add(""); add(msg("req.table.name")); add(msg("req.table.value")); if (withFile) add(msg("req.table.file"))
        }
        override fun getRowCount() = rows.size
        override fun getColumnCount() = names.size
        override fun getColumnName(c: Int) = names[c]
        override fun getColumnClass(c: Int): Class<*> = if (c == 0 || c == 3) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(r: Int, c: Int) = true
        override fun getValueAt(r: Int, c: Int): Any = when (c) {
            0 -> rows[r].enabled; 1 -> rows[r].name; 2 -> rows[r].value; else -> rows[r].file
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            rows[r] = when (c) {
                0 -> rows[r].copy(enabled = v as Boolean)
                1 -> rows[r].copy(name = v as String)
                2 -> rows[r].copy(value = v as String)
                else -> rows[r].copy(file = v as Boolean)
            }
            fireTableCellUpdated(r, c)
            onChange()
        }
    }
    val table = JBTable(model).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        columnModel.getColumn(0).apply { maxWidth = JBUI.scale(CHECK_COLUMN_WIDTH); minWidth = maxWidth }
        if (withFile) columnModel.getColumn(3).apply { maxWidth = JBUI.scale(CHECK_COLUMN_WIDTH * 2); minWidth = maxWidth }
        setShowGrid(false)
        visibleRowCount = TABLE_ROWS
    }
    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            rows.add(FormRow())
            model.fireTableRowsInserted(rows.size - 1, rows.size - 1)
            table.editCellAt(rows.size - 1, 1)
            onChange()
        }
        .setRemoveAction {
            val i = table.selectedRow
            if (i in rows.indices) { table.cellEditor?.stopCellEditing(); rows.removeAt(i); model.fireTableRowsDeleted(i, i); onChange() }
        }
        .disableUpDownActions()
        .createPanel()

    private companion object {
        const val CHECK_COLUMN_WIDTH = 28
        const val TABLE_ROWS = 3
    }
}

/**
 * The request form of one endpoint: method, base URL, path parameters, query, headers, auth and body. It is bound to a
 * [RequestForm] (the model) with [bind]; every widget change writes the model back, so switching endpoints and the cURL and
 * history views read one source. It never sends: the Send button belongs to [RequestSection].
 */
class RequestFormPanel(
    private val project: Project,
    parent: Disposable,
    private val bodyTemplates: () -> BodyTemplateSource,
    private val secrets: LayeredSecrets,
    private val onEdited: () -> Unit,
    private val onBaseUrl: (String) -> Unit,
) : JBPanel<RequestFormPanel>(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var form: RequestForm? = null
    private var binding = false

    private val methodCombo = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel(HttpMethod.entries.filter { it.isConcrete }.toTypedArray()))
    /** A fixed endpoint shows its verb as the same badge the list uses, so the URL fields line up whatever the verb. */
    private val methodLabel = JBLabel()
    private val baseUrl = JBTextField().apply { emptyText.text = msg("req.base_url.placeholder") }
    private val pathBox = JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS); isOpaque = false }
    private val pathFields = LinkedHashMap<String, Pair<JBTextField, JBLabel>>()
    private val query: RowsTable = RowsTable(false) { write { f -> f.query = query.rows.toList() }; onEdited() }
    private val headers: RowsTable = RowsTable(false) { write { f -> f.headers = headers.rows.toList() }; onEdited() }
    private val formFields: RowsTable = RowsTable(false) { write { f -> f.formFields = formFields.rows.toList() }; onEdited() }
    private val parts: RowsTable = RowsTable(true) { write { f -> f.parts = parts.rows.toList() }; onEdited() }

    private val authCombo = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel(AuthKind.entries.toTypedArray()))
    private val authName = JBTextField().apply { emptyText.text = msg("req.auth.name") }
    private val authSecret = JBPasswordField().apply { emptyText.text = msg("req.auth.secret"); toolTipText = msg("req.auth.secret.tooltip") }
    private val authNameLabel = JBLabel(msg("req.auth.name"))
    private val authSecretLabel = JBLabel(msg("req.auth.secret.label"))

    private val bodyCombo = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel(BodyType.entries.toTypedArray()))
    private val bodyDocument = EditorFactory.getInstance().createDocument(true)
    internal val bodyEditor = EditorTextField(bodyDocument, project, jsonType(), false, false).apply {
        setPlaceholder(msg("req.body.placeholder"))
        setFontInheritedFromLAF(false)
    }
    private val bodyCards = JPanel(CardLayout())
    private val generate = JButton(msg("req.body.generate")).apply { toolTipText = msg("req.body.generate.tooltip") }
    private val generateNote = JBLabel().apply { foreground = Palette.textSecondary; isVisible = false }
    private val replaceBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), 0)).apply { isVisible = false; isOpaque = false }
    private var pendingTemplate: com.routegenie.client.BodyTemplate? = null

    /** The slot where Phase 4 shows the result of schema validation of the body; empty and hidden until then. */
    internal val bodyValidationSlot = JBLabel().apply { isVisible = false; foreground = Palette.textSecondary }

    private val errors = JBLabel().apply { foreground = JBColorsError.color(); isVisible = false }

    init {
        Disposer.register(parent, this)
        bodyDocument.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                write { it.bodyText = bodyDocument.text }
                onEdited()
            }
        }, this)
        authCombo.renderer = SimpleListCellRenderer.create("") { RequestMessages.auth(it) }
        bodyCombo.renderer = SimpleListCellRenderer.create("") { RequestMessages.body(it) }
        wire()
        add(buildContent(), BorderLayout.CENTER)
    }

    private fun jsonType(): FileType = FileTypeManager.getInstance().getFileTypeByExtension("json").let { if (it.isBinary) PlainTextFileType.INSTANCE else it }

    private fun write(f: (RequestForm) -> Unit) {
        if (!binding) form?.let(f)
    }

    private fun wire() {
        methodCombo.addActionListener { write { it.method = methodCombo.selectedItem as HttpMethod }; onEdited() }
        baseUrl.document.addDocumentListener(object : SwingDocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = changed()
            fun changed() { if (!binding) { onBaseUrl(baseUrl.text.trim()); onEdited() } }
        })
        authCombo.addActionListener {
            write { it.authKind = authCombo.selectedItem as AuthKind }
            updateAuthWidgets(); onEdited()
        }
        authName.document.addDocumentListener(simpleListener { write { it.authName = authName.text } ; onEdited() })
        authSecret.document.addDocumentListener(simpleListener {
            if (!binding) { storeSecret(); onEdited() }
        })
        bodyCombo.addActionListener {
            write { it.bodyKind = bodyCombo.selectedItem as BodyType }
            showBodyCard(bodyCombo.selectedItem as BodyType); onEdited()
        }
        generate.addActionListener { generateBody() }
    }

    private fun simpleListener(f: () -> Unit) = object : SwingDocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) = f()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) = f()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) = f()
    }

    private fun secretKey(kind: AuthKind): String? = when (kind) {
        AuthKind.BEARER -> SecretKeys.TOKEN
        AuthKind.BASIC -> SecretKeys.PASSWORD
        AuthKind.API_KEY -> SecretKeys.API_KEY
        else -> null
    }

    private fun storeSecret() {
        val value = String(authSecret.password)
        secretKey(authCombo.selectedItem as AuthKind)?.let { secrets.put(it, value) }
    }

    /** Says whether a secret is already saved for the current environment, so an empty field is not mistaken for "none". */
    fun refreshSecretHint() {
        val stored = secretKey(authCombo.selectedItem as AuthKind)?.let { secrets.has(it) } == true
        authSecret.emptyText.text = msg(if (stored) "req.auth.secret.saved" else "req.auth.secret")
    }

    private fun buildContent(): JComponent {
        bodyCards.add(JBLabel(msg("req.body.none")).apply { foreground = Palette.textSecondary; border = JBUI.Borders.empty(DesignTokens.Space.S) }, BodyType.NONE.name)
        val editorCard = JPanel(BorderLayout()).apply {
            add(bodyEditor, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(replaceBar, BorderLayout.NORTH); add(bodyValidationSlot, BorderLayout.SOUTH) }, BorderLayout.SOUTH)
        }
        bodyCards.add(editorCard, CARD_EDITOR)
        bodyCards.add(formFields.component, BodyType.FORM.name)
        bodyCards.add(parts.component, BodyType.MULTIPART.name)

        val auth = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(DesignTokens.Space.S)
            add(
                FormBuilder.createFormBuilder()
                    .addLabeledComponent(msg("req.auth.type"), authCombo)
                    .addLabeledComponent(authNameLabel, authName)
                    .addLabeledComponent(authSecretLabel, authSecret)
                    .panel,
                BorderLayout.NORTH,
            )
        }
        val bodyTab = JPanel(BorderLayout()).apply {
            // "Generate body" sits beside the type chooser, not inside the editor card: a new endpoint starts with type None, whose
            // card has no editor, and the button must be reachable there (it switches the type to JSON when it fills the body).
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), JBUI.scale(DesignTokens.Space.XS))).apply {
                isOpaque = false
                add(JBLabel(msg("req.body.type")).apply { foreground = Palette.textSecondary })
                add(bodyCombo)
                add(generate)
                add(generateNote)
            }, BorderLayout.NORTH)
            add(bodyCards, BorderLayout.CENTER)
        }
        val top = JPanel(BorderLayout(JBUI.scale(DesignTokens.Space.S), 0)).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(methodCombo); add(methodLabel) }, BorderLayout.WEST)
            add(baseUrl, BorderLayout.CENTER)
        }
        val head = FormBuilder.createFormBuilder().addComponent(top).addComponent(errors).addComponent(pathBox).panel
        head.border = JBUI.Borders.empty(DesignTokens.Space.S, DesignTokens.Space.S, 0, DesignTokens.Space.S)
        // Tabs keep the body editor (the part people type in) on screen next to the URL instead of below two tables.
        val tabs = JBTabbedPane().apply {
            addTab(msg("req.section.query"), query.component)
            addTab(msg("req.section.headers"), headers.component)
            addTab(msg("req.section.auth"), auth)
            addTab(msg("req.section.body"), bodyTab)
        }
        formTabs = tabs
        return JPanel(BorderLayout()).apply {
            add(head, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
    }

    private var formTabs: JBTabbedPane? = null

    // ---- binding ------------------------------------------------------------------------------------------------

    fun bind(f: RequestForm, baseUrlText: String) {
        form = null
        binding = true
        try {
            methodCombo.isVisible = f.methodIsChoosable
            methodLabel.isVisible = !f.methodIsChoosable
            methodLabel.icon = MethodBadge.of(f.method, BadgeState.NORMAL)
            methodCombo.selectedItem = f.method
            baseUrl.text = baseUrlText
            rebuildPathFields(f)
            query.rows = f.query.toMutableList()
            headers.rows = f.headers.toMutableList()
            formFields.rows = f.formFields.toMutableList()
            parts.rows = f.parts.toMutableList()
            authCombo.selectedItem = f.authKind
            authName.text = f.authName
            authSecret.text = ""
            refreshSecretHint()
            bodyCombo.selectedItem = f.bodyKind
            setBodyText(f.bodyKind, f.bodyText)
            showBodyCard(f.bodyKind)
            updateAuthWidgets()
            generateNote.isVisible = false
            replaceBar.isVisible = false
            pendingTemplate = null
            showErrors(emptyList())
        } finally {
            binding = false
        }
        form = f
    }

    /** Refreshes the widgets from the model after it changed from outside (cURL import, history restore). */
    fun refresh(baseUrlText: String?) {
        val f = form ?: return
        bind(f, baseUrlText ?: baseUrl.text)
    }

    private fun setBodyText(kind: BodyType, text: String) {
        bodyEditor.fileType = if (kind == BodyType.JSON) jsonType() else PlainTextFileType.INSTANCE
        if (bodyDocument.text != text) bodyDocument.setText(text)
    }

    private fun showBodyCard(kind: BodyType) {
        // The button fills a JSON/text body, so it is offered for those and for None (where it is the way in), not for form types.
        generate.isVisible = kind == BodyType.NONE || kind == BodyType.JSON || kind == BodyType.TEXT
        if (kind == BodyType.JSON || kind == BodyType.TEXT) setBodyText(kind, form?.bodyText ?: bodyDocument.text)
        (bodyCards.layout as CardLayout).show(bodyCards, if (kind == BodyType.JSON || kind == BodyType.TEXT) CARD_EDITOR else kind.name)
    }

    private fun updateAuthWidgets() {
        val kind = authCombo.selectedItem as AuthKind
        authName.isVisible = kind == AuthKind.BASIC || kind == AuthKind.API_KEY
        authNameLabel.isVisible = authName.isVisible
        authSecret.isVisible = kind == AuthKind.BEARER || kind == AuthKind.BASIC || kind == AuthKind.API_KEY
        authSecretLabel.isVisible = authSecret.isVisible
        refreshSecretHint()
        authName.emptyText.text = msg(if (kind == AuthKind.BASIC) "req.auth.user" else "req.auth.name")
    }

    private fun rebuildPathFields(f: RequestForm) {
        pathBox.removeAll()
        pathFields.clear()
        for ((name, value) in f.pathValues) {
            val field = JBTextField(value).apply { emptyText.text = f.endpoint.pathParams.firstOrNull { it.name == name }?.type.orEmpty() }
            val error = JBLabel().apply { foreground = JBColorsError.color(); isVisible = false }
            field.document.addDocumentListener(simpleListener { if (!binding) { form?.pathValues?.put(name, field.text); onEdited() } })
            pathFields[name] = field to error
            val row = JPanel(BorderLayout(JBUI.scale(DesignTokens.Space.S), 0)).apply {
                isOpaque = false
                add(JBLabel(name).apply { preferredSize = java.awt.Dimension(JBUI.scale(PATH_LABEL_WIDTH), preferredSize.height) }, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
                add(error, BorderLayout.EAST)
            }
            pathBox.add(row)
        }
        pathBox.isVisible = f.pathValues.isNotEmpty()
    }

    // ---- validation ---------------------------------------------------------------------------------------------

    /** Shows what blocked the send: path problems next to their field, the rest above the form. */
    fun showErrors(list: List<FieldError>) {
        pathFields.values.forEach { it.second.isVisible = false }
        val general = ArrayList<String>()
        for (e in list) {
            val text = RouteGenieBundle.message(e.bundleKey, *e.args.toTypedArray())
            val slot = if (e.location == FieldLocation.PATH) pathFields[e.name] else null
            if (slot != null) { slot.second.text = text; slot.second.isVisible = true } else general += text
        }
        errors.text = if (general.isEmpty()) "" else "<html>" + general.joinToString("<br>") { com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(it) } + "</html>"
        errors.isVisible = general.isNotEmpty()
        revalidate()
    }

    internal fun pathErrorText(name: String): String? = pathFields[name]?.second?.takeIf { it.isVisible }?.text
    internal fun generalErrorText(): String? = errors.takeIf { it.isVisible }?.text

    // ---- body generation ----------------------------------------------------------------------------------------

    fun generateBody() {
        val f = form ?: return
        generate.isEnabled = false
        generateNote.isVisible = false
        scope.launch {
            val result = BodyGenerator.generate(bodyTemplates(), f.endpoint, f.bodyText)
            withContext(Dispatchers.EDT) {
                generate.isEnabled = true
                if (form !== f) return@withContext
                when (result) {
                    is BodyGeneration.Ready -> applyTemplate(f, result.template)
                    is BodyGeneration.NeedsConfirmation -> askReplace(f, result.template)
                    BodyGeneration.Unavailable -> { generateNote.text = msg("req.body.generate.none"); generateNote.isVisible = true }
                }
            }
        }
    }

    private fun applyTemplate(f: RequestForm, template: com.routegenie.client.BodyTemplate) {
        BodyGenerator.apply(f, template)
        replaceBar.isVisible = false
        pendingTemplate = null
        val keep = baseUrl.text
        refresh(keep)
        onEdited()
    }

    private fun askReplace(f: RequestForm, template: com.routegenie.client.BodyTemplate) {
        pendingTemplate = template
        replaceBar.removeAll()
        replaceBar.add(JBLabel(msg("req.body.replace.ask")))
        replaceBar.add(ActionLink(msg("req.body.replace")) { pendingTemplate?.let { applyTemplate(f, it) } })
        replaceBar.add(ActionLink(msg("req.body.replace.cancel")) { replaceBar.isVisible = false; pendingTemplate = null; revalidate() })
        replaceBar.isVisible = true
        revalidate()
    }

    internal fun generateNoteText(): String? = generateNote.takeIf { it.isVisible }?.text
    internal fun replaceBarVisible(): Boolean = replaceBar.isVisible
    internal fun confirmReplaceForTest() { pendingTemplate?.let { t -> form?.let { applyTemplate(it, t) } } }
    internal fun bodyTextForTest(): String = bodyDocument.text
    internal fun generateButtonForTest(): JButton = generate
    internal fun methodComboVisible(): Boolean = methodCombo.isVisible
    internal fun baseUrlTextForTest(): String = baseUrl.text
    internal fun bodyEditorIsOnScreen(): Boolean = generalizedVisible(bodyEditor)
    internal fun generateIsOnScreen(): Boolean = generalizedVisible(generate)
    private fun generalizedVisible(c: java.awt.Component): Boolean {
        var p: java.awt.Component? = c
        while (p != null && p !== this) { if (!p.isVisible) return false; p = p.parent }
        return true
    }
    internal fun secretHintForTest(): String = authSecret.emptyText.text
    internal fun chooseAuthForTest(kind: AuthKind) { authCombo.selectedItem = kind }
    internal fun typeSecretForTest(value: String) { authSecret.text = value }

    override fun dispose() {
        scope.cancel()
    }

    internal fun selectFormTabForTest(title: String) {
        val tabs = formTabs ?: return
        val i = (0 until tabs.tabCount).firstOrNull { tabs.getTitleAt(it) == title } ?: return
        tabs.selectedIndex = i
    }

    private companion object {
        const val PATH_LABEL_WIDTH = 110
        /** JSON and text share one editor card; a card name maps to exactly one component. */
        const val CARD_EDITOR = "editor"
    }
}

/** The error color of the theme for inline messages. */
internal object JBColorsError {
    fun color(): Color = Palette.danger.text
}
