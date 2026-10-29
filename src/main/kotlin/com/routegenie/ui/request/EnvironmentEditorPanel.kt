package com.routegenie.ui.request

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.client.ApiKeyLocation
import com.routegenie.client.AuthConfig
import com.routegenie.client.Environment
import com.routegenie.client.SecretKeys
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.SectionHeader
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

enum class EnvAuthKind(val bundleKey: String) {
    NONE("req.env.auth.none"), BEARER("req.env.auth.bearer"), BASIC("req.env.auth.basic"), API_KEY("req.env.auth.apikey");

    override fun toString(): String = msg(bundleKey)
}

/** One variable of an environment. A secret variable keeps only its name in the project; its value goes to the password safe. */
data class VarRow(val name: String = "", val value: String = "", val secret: Boolean = false)

/**
 * What the editor holds before it is saved. Pure (no Swing) so the rules are tested directly: variables are bounded, a secret
 * variable never keeps its value in [toEnvironment], and [secretWrites] lists the typed secrets for the credential store.
 */
class EnvironmentDraft(
    var name: String,
    var baseUrl: String,
    var authKind: EnvAuthKind = EnvAuthKind.NONE,
    var authName: String = "",
    var authSecret: String = "",
    var vars: MutableList<VarRow> = ArrayList(),
    private val source: Environment? = null,
) {
    fun toEnvironment(): Environment {
        val named = vars.filter { it.name.isNotBlank() }.take(Limits.ENVIRONMENT_VARIABLES_MAX)
        val auth = when (authKind) {
            EnvAuthKind.NONE -> AuthConfig.None
            EnvAuthKind.BEARER -> AuthConfig.Bearer
            EnvAuthKind.BASIC -> AuthConfig.Basic(authName)
            EnvAuthKind.API_KEY -> AuthConfig.ApiKey(authName.ifBlank { DEFAULT_API_KEY_NAME }, ApiKeyLocation.HEADER)
        }
        return (source ?: Environment(name, baseUrl)).copy(
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            variables = named.filter { !it.secret }.associate { it.name.trim() to it.value },
            secretVariables = named.filter { it.secret }.map { it.name.trim() }.toSet(),
            auth = auth,
        )
    }

    /** Credential store entries to write: only values the user typed; an empty secret field means "keep what is stored". */
    fun secretWrites(): Map<String, String> = buildMap {
        if (authSecret.isNotEmpty()) {
            when (authKind) {
                EnvAuthKind.BEARER -> put(SecretKeys.TOKEN, authSecret)
                EnvAuthKind.BASIC -> put(SecretKeys.PASSWORD, authSecret)
                EnvAuthKind.API_KEY -> put(SecretKeys.API_KEY, authSecret)
                EnvAuthKind.NONE -> Unit
            }
        }
        vars.filter { it.secret && it.name.isNotBlank() && it.value.isNotEmpty() }.forEach { put(SecretKeys.variable(it.name.trim()), it.value) }
    }

    fun validationError(existingNames: Collection<String>, isNew: Boolean): String? = when {
        name.isBlank() -> msg("req.env.error.name")
        isNew && existingNames.any { it.equals(name.trim(), ignoreCase = true) } -> msg("req.env.error.duplicate", name.trim())
        vars.map { it.name.trim() }.filter { it.isNotEmpty() }.let { it.size != it.toSet().size } -> msg("req.env.error.variables")
        else -> null
    }

    companion object {
        private const val DEFAULT_API_KEY_NAME = "X-API-Key"

        fun of(env: Environment): EnvironmentDraft {
            val (kind, authName) = when (val a = env.auth) {
                AuthConfig.None -> EnvAuthKind.NONE to ""
                AuthConfig.Bearer -> EnvAuthKind.BEARER to ""
                is AuthConfig.Basic -> EnvAuthKind.BASIC to a.username
                is AuthConfig.ApiKey -> EnvAuthKind.API_KEY to a.name
            }
            val rows = env.variables.map { VarRow(it.key, it.value, false) } + env.secretVariables.map { VarRow(it, "", true) }
            return EnvironmentDraft(env.name, env.baseUrl, kind, authName, "", rows.toMutableList(), env)
        }
    }
}

/**
 * The environment editor, shown inside the window in place of the request form (no dialog): pick or create an environment,
 * name it, give it a base URL, variables and authentication. Secrets are typed into password fields and go straight to
 * [onSave]'s receiver, which writes them to the credential store; they are never displayed again and never written to XML.
 */
class EnvironmentEditorPanel(
    parent: Disposable,
    private val environments: EnvironmentProvider,
    private val hasSecret: (envName: String, key: String) -> Boolean,
    private val onSave: (Environment, Map<String, String>) -> Unit,
    private val onClose: () -> Unit,
) : JBPanel<EnvironmentEditorPanel>(BorderLayout()), Disposable {
    private var draft = EnvironmentDraft("", "")
    private var isNew = false
    private var loading = false

    private val picker = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel<String>())
    private val name = JBTextField().apply { emptyText.text = msg("req.env.name.placeholder") }
    private val baseUrl = JBTextField().apply { emptyText.text = msg("req.base_url.placeholder") }
    private val authKind = com.intellij.openapi.ui.ComboBox(DefaultComboBoxModel(EnvAuthKind.entries.toTypedArray()))
    private val authName = JBTextField()
    private val authSecret = JBPasswordField()
    private val authNameLabel = JBLabel(msg("req.env.auth.name"))
    private val authSecretLabel = JBLabel(msg("req.env.auth.secret"))
    private val error = JBLabel().apply { foreground = JBColorsError.color(); isVisible = false }
    private val saved = JBLabel().apply { foreground = Palette.textSecondary; isVisible = false }

    private val varsModel = object : AbstractTableModel() {
        private val columns = listOf(msg("req.table.name"), msg("req.table.value"), msg("req.env.var.secret"))
        override fun getRowCount() = draft.vars.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(c: Int) = columns[c]
        override fun getColumnClass(c: Int): Class<*> = if (c == 2) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(r: Int, c: Int) = true
        override fun getValueAt(r: Int, c: Int): Any = draft.vars[r].let { if (c == 0) it.name else if (c == 1) it.value else it.secret }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            val row = draft.vars[r]
            draft.vars[r] = when (c) {
                0 -> row.copy(name = v as String)
                1 -> row.copy(value = v as String)
                else -> row.copy(secret = v as Boolean)
            }
            fireTableCellUpdated(r, c)
        }
    }
    private val varsTable = JBTable(varsModel).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        setShowGrid(false)
        visibleRowCount = TABLE_ROWS
        columnModel.getColumn(2).apply { maxWidth = JBUI.scale(SECRET_COLUMN_WIDTH); minWidth = maxWidth }
    }

    init {
        Disposer.register(parent, this)
        picker.addActionListener { if (!loading) (picker.selectedItem as? String)?.let { load(environments.all().firstOrNull { e -> e.name == it }) } }
        authKind.addActionListener { updateAuthWidgets() }
        add(buildContent(), BorderLayout.CENTER)
        reload(environments.current().name)
    }

    private fun buildContent(): JComponent {
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), 0)).apply {
            isOpaque = false
            add(picker)
            add(JButton(msg("req.env.new")).apply { addActionListener { startNew() } })
            add(JButton(msg("req.env.delete")).apply { addActionListener { deleteCurrent() } })
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.S), 0)).apply {
            isOpaque = false
            add(JButton(msg("req.env.save")).apply { addActionListener { save() } })
            add(ActionLink(msg("req.env.close")) { onClose() })
            add(saved)
        }
        val form = FormBuilder.createFormBuilder()
            .addComponent(SectionHeader(msg("req.env.title")))
            .addComponent(tools)
            .addLabeledComponent(msg("req.env.name"), name)
            .addLabeledComponent(msg("req.env.base_url"), baseUrl)
            .addComponent(SectionHeader(msg("req.env.section.auth")))
            .addLabeledComponent(msg("req.env.auth"), authKind)
            .addLabeledComponent(authNameLabel, authName)
            .addLabeledComponent(authSecretLabel, authSecret)
            .addComponent(SectionHeader(msg("req.env.section.variables")))
            .addComponent(
                ToolbarDecorator.createDecorator(varsTable)
                    .setAddAction {
                        draft.vars.add(VarRow())
                        varsModel.fireTableRowsInserted(draft.vars.size - 1, draft.vars.size - 1)
                        varsTable.editCellAt(draft.vars.size - 1, 0)
                    }
                    .setRemoveAction {
                        val i = varsTable.selectedRow
                        if (i in draft.vars.indices) { varsTable.cellEditor?.stopCellEditing(); draft.vars.removeAt(i); varsModel.fireTableRowsDeleted(i, i) }
                    }
                    .disableUpDownActions()
                    .createPanel(),
            )
            .addComponent(error)
            .addComponent(buttons)
            .panel
        form.border = JBUI.Borders.empty(DesignTokens.Space.S)
        return JBScrollPane(form).apply { border = JBUI.Borders.empty() }
    }

    /** Rebuilds the picker from the stored environments and shows [select]. */
    fun reload(select: String?) {
        loading = true
        try {
            val all = environments.all()
            picker.model = DefaultComboBoxModel(all.map { it.name }.toTypedArray())
            val env = all.firstOrNull { it.name == select } ?: all.firstOrNull()
            picker.selectedItem = env?.name
            load(env)
        } finally {
            loading = false
        }
    }

    private fun load(env: Environment?) {
        isNew = env == null
        draft = env?.let { EnvironmentDraft.of(it) } ?: EnvironmentDraft("", "")
        name.text = draft.name
        name.isEditable = isNew
        baseUrl.text = draft.baseUrl
        authKind.selectedItem = draft.authKind
        authName.text = draft.authName
        authSecret.text = ""
        varsModel.fireTableDataChanged()
        updateAuthWidgets()
        error.isVisible = false
        saved.isVisible = false
    }

    private fun startNew() {
        loading = true
        picker.selectedItem = null
        loading = false
        load(null)
        baseUrl.text = environments.current().baseUrl
        name.requestFocusInWindow()
    }

    private fun deleteCurrent() {
        val current = draft.takeIf { !isNew }?.name ?: return
        if (environments.all().size <= 1) {
            show(msg("req.env.error.last"))
            return
        }
        environments.remove(current)
        reload(environments.current().name)
        onSave(environments.current(), emptyMap())
    }

    private fun updateAuthWidgets() {
        val kind = authKind.selectedItem as? EnvAuthKind ?: EnvAuthKind.NONE
        authName.isVisible = kind == EnvAuthKind.BASIC || kind == EnvAuthKind.API_KEY
        authNameLabel.isVisible = authName.isVisible
        authName.emptyText.text = msg(if (kind == EnvAuthKind.BASIC) "req.auth.user" else "req.auth.name")
        authSecret.isVisible = kind != EnvAuthKind.NONE
        authSecretLabel.isVisible = authSecret.isVisible
        val key = when (kind) {
            EnvAuthKind.BEARER -> SecretKeys.TOKEN
            EnvAuthKind.BASIC -> SecretKeys.PASSWORD
            EnvAuthKind.API_KEY -> SecretKeys.API_KEY
            EnvAuthKind.NONE -> null
        }
        val stored = key != null && !isNew && hasSecret(draft.name, key)
        authSecret.emptyText.text = msg(if (stored) "req.env.secret.stored" else "req.env.secret.empty")
    }

    private fun show(text: String) {
        error.text = text
        error.isVisible = true
        saved.isVisible = false
    }

    /** Applies the fields to the draft and saves it. Returns false (with the problem shown) when it cannot be saved. */
    fun save(): Boolean {
        varsTable.cellEditor?.stopCellEditing()
        draft.name = name.text
        draft.baseUrl = baseUrl.text
        draft.authKind = authKind.selectedItem as? EnvAuthKind ?: EnvAuthKind.NONE
        draft.authName = authName.text
        draft.authSecret = String(authSecret.password)
        val problem = draft.validationError(environments.all().map { it.name }, isNew)
        if (problem != null) {
            show(problem)
            return false
        }
        val env = draft.toEnvironment()
        environments.save(env)
        environments.select(env.name)
        onSave(env, draft.secretWrites())
        error.isVisible = false
        saved.text = msg("req.env.saved")
        saved.isVisible = true
        reload(env.name)
        return true
    }

    // ---- test hooks ---------------------------------------------------------------------------------------------

    internal fun draftForTest() = draft
    internal fun setFieldsForTest(newName: String, url: String, kind: EnvAuthKind, authUser: String, secret: String) {
        name.text = newName; baseUrl.text = url; authKind.selectedItem = kind; authName.text = authUser; authSecret.text = secret
    }
    internal fun startNewForTest() = startNew()
    internal fun addVariableForTest(row: VarRow) { draft.vars.add(row); varsModel.fireTableDataChanged() }
    internal fun errorText(): String? = error.takeIf { it.isVisible }?.text
    internal fun pickerNames(): List<String> = (0 until picker.itemCount).map { picker.getItemAt(it) }

    override fun dispose() = Unit

    private companion object {
        const val TABLE_ROWS = 4
        const val SECRET_COLUMN_WIDTH = 64
    }
}
