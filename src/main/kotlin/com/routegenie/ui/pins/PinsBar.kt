package com.routegenie.ui.pins

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SettingSuggestion
import com.routegenie.model.SuggestionKind
import com.routegenie.model.UnresolvedSetting
import com.routegenie.pinside.PinResult
import com.routegenie.pinside.PinsService
import com.routegenie.pins.Pin
import com.routegenie.pins.PinRules
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.NoticeStrip
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
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The place where the window asks the user, once, to confirm a value it cannot know: a slim strip per setting that hides
 * routes ("12 routes are hidden because their URL prefix comes from settings.API_V1_STR"), with one click to use the default
 * the code states, an inline field for another value, and "Not now". It is never modal and never applies a suggestion by itself.
 *
 * The bar shows at most [Limits.PINS_MAX_STRIPS] strips, most routes first. Everything else that hides routes (answered "Not now",
 * beyond the limit, or too secret-looking to pin) is summarized in one collapsed line, so the user always knows routes are missing.
 *
 * Threading: UI on the event thread; the file write goes through [background] and its result comes back with `invokeLater`.
 */
class PinsBar(
    private val project: Project,
    parent: Disposable,
    private val service: PinsService = PinsService.getInstance(project),
    private val background: (Runnable) -> Unit = { AppExecutorUtil.getAppExecutorService().execute(it) },
    private val openPins: () -> Unit = { PinsDialog(project).show() },
) : JPanel(), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settings: List<UnresolvedSetting> = emptyList()
    private val rows = LinkedHashMap<String, StripRow>()
    private var hiddenLine: HiddenLine? = null
    private var expandedHidden = false
    private var disposed = false

    init {
        Disposer.register(parent, this)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        render()
        scope.launch {
            combine(service.snapshot, service.dismissed) { pins, dismissed -> pins to dismissed }.collect {
                withContext(Dispatchers.EDT) { if (!disposed) render() }
            }
        }
    }

    /** Called on the event thread with every snapshot the window applies. */
    fun update(snapshot: RouteSnapshot) {
        if (disposed || snapshot.settings == settings) return
        settings = snapshot.settings
        render()
    }

    private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

    private fun render() {
        val pins = service.snapshot.value
        val pinned = pins.byExpression.keys
        val dismissed = service.dismissed.value
        val open = settings.filter { it.routeCount > 0 && it.expression !in pinned }.sortedByDescending { it.routeCount }
        val stripSettings = open.filter { !PinRules.looksSecret(it.expression) && it.expression !in dismissed }.take(Limits.PINS_MAX_STRIPS)
        val inLine = open.filter { s -> stripSettings.none { it.expression == s.expression } }

        removeAll()
        pins.problem?.let { problem ->
            val text = msg(problem.bundleKey, pins.where?.let { ", $it" }.orEmpty())
            add(NoticeStrip(Palette.danger, messageRow(text, ActionLink(msg("pins.hidden.edit")) { openPins() })))
        }
        if (pins.problem == null && pins.skippedCount > 0) {
            add(NoticeStrip(Palette.warning, messageRow(msg("pins.file.skipped", pins.skippedCount), ActionLink(msg("pins.hidden.edit")) { openPins() })))
        }
        val kept = LinkedHashMap<String, StripRow>()
        for (setting in stripSettings) {
            val row = rows[setting.expression]?.also { it.setting = setting; it.refresh() } ?: StripRow(setting)
            kept[setting.expression] = row
            add(row.component)
        }
        rows.clear()
        rows.putAll(kept)
        hiddenLine = if (inLine.isEmpty()) null else HiddenLine(inLine, dismissed).also { add(it.component) }
        isVisible = componentCount > 0
        revalidate()
        repaint()
    }

    private fun messageRow(text: String, vararg actions: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JBLabel("<html>${StringUtil.escapeXmlEntities(text)}</html>").apply { font = DesignTokens.Type.body() }, BorderLayout.CENTER)
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(Space.S), 0)).apply { isOpaque = false }
        actions.forEach { right.add(it) }
        add(right, BorderLayout.EAST)
    }

    // ---- one strip per unresolved setting ----------------------------------------------------------------------------

    internal inner class StripRow(var setting: UnresolvedSetting) {
        val message = JBLabel().apply { font = DesignTokens.Type.body() }
        val useLink = ActionLink("") { saveValue(setting.suggestion?.value.orEmpty()) }
        val origin = JBLabel().apply {
            font = DesignTokens.Type.caption()
            foreground = Palette.textSecondary
        }
        val otherLink = ActionLink(msg("pins.strip.other")) { showEditor(true) }
        val laterLink = ActionLink(msg("pins.strip.later")) { service.dismiss(setting.expression) }
        val field = JBTextField(FIELD_COLUMNS)
        val saveButton = JButton(msg("pins.strip.save")).apply { addActionListener { saveValue(field.text) } }
        val cancelLink = ActionLink(msg("pins.strip.cancel")) { showEditor(false) }
        val error = JBLabel().apply {
            font = DesignTokens.Type.caption()
            foreground = Palette.danger.text
            isVisible = false
        }
        private val editor = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.S), 0)).apply {
            isOpaque = false
            isVisible = false
            add(field)
            add(saveButton)
            add(cancelLink)
        }
        private val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.M), 0)).apply {
            isOpaque = false
            add(useLink)
            add(origin)
            add(otherLink)
            add(laterLink)
        }
        val component: JComponent = NoticeStrip(Palette.warning, JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(message, actions, editor, error).forEach {
                (it as JComponent).alignmentX = LEFT_ALIGNMENT
                add(it)
            }
        })

        init {
            field.toolTipText = msg("pins.strip.field.tooltip", setting.expression)
            field.addActionListener { saveValue(field.text) }
            refresh()
        }

        fun refresh() {
            val code = "<code>${StringUtil.escapeXmlEntities(setting.expression)}</code>"
            message.text = "<html>" + if (setting.routeCount == 1) msg("pins.strip.message.one", PLACEHOLDER) else msg("pins.strip.message.many", PLACEHOLDER, setting.routeCount) + "</html>"
            message.text = message.text.replace(PLACEHOLDER, code)
            val suggestion = setting.suggestion
            useLink.isVisible = suggestion != null
            origin.isVisible = suggestion != null
            if (suggestion != null) {
                useLink.text = msg("pins.strip.use", shown(suggestion.value))
                useLink.toolTipText = msg("pins.strip.use.tooltip", shown(suggestion.value), setting.expression)
                origin.text = "(" + originText(suggestion) + ")"
            }
            laterLink.toolTipText = msg("pins.strip.later.tooltip")
        }

        fun showEditor(visible: Boolean) {
            editor.isVisible = visible
            if (visible) {
                if (field.text.isEmpty()) field.text = setting.suggestion?.value.orEmpty()
                field.requestFocusInWindow()
            } else {
                setError(null)
            }
            component.revalidate()
        }

        private fun setError(text: String?) {
            error.text = text.orEmpty()
            error.isVisible = !text.isNullOrEmpty()
            component.revalidate()
        }

        /** Validates [value]; on a mistake the editor opens with the message, otherwise the pin is written off the event thread. */
        fun saveValue(value: String) {
            val pin = Pin(
                setting.expression, value,
                note = setting.suggestion?.takeIf { it.value == value }?.let { originText(it) }.orEmpty(),
            )
            val problem = PinRules.check(pin, setting.needsLeadingSlash)
            if (problem != null) {
                field.text = value
                editor.isVisible = true
                setError(msg(problem.bundleKey))
                return
            }
            setError(null)
            setBusy(true)
            background(Runnable {
                val result = service.set(pin, setting.needsLeadingSlash)
                ApplicationManager.getApplication().invokeLater({ finished(result) }, ModalityState.any())
            })
        }

        private fun finished(result: PinResult) {
            if (disposed) return
            setBusy(false)
            when (result) {
                PinResult.Saved -> showEditor(false)
                is PinResult.Refused -> { editor.isVisible = true; setError(msg(result.problem.bundleKey)) }
                is PinResult.Failed -> {
                    editor.isVisible = true
                    setError(msg("pins.strip.save_failed", result.file?.let { msg(it.bundleKey, "") } ?: result.message ?: msg("pins.strip.save_failed.unknown")))
                }
            }
        }

        private fun setBusy(busy: Boolean) {
            listOf<JComponent>(useLink, otherLink, laterLink, saveButton, field, cancelLink).forEach { it.isEnabled = !busy }
        }
    }

    // ---- the collapsed line for everything that stays hidden -----------------------------------------------------------

    internal inner class HiddenLine(val items: List<UnresolvedSetting>, dismissed: Set<String>) {
        val total = items.sumOf { it.routeCount }
        val summary = JBLabel(
            if (total == 1) msg("pins.hidden.summary.one") else msg("pins.hidden.summary.many", total),
        ).apply { font = DesignTokens.Type.caption(); foreground = Palette.textSecondary }
        val toggle = ActionLink(msg(if (expandedHidden) "pins.hidden.hide" else "pins.hidden.show")) {
            expandedHidden = !expandedHidden
            render()
        }
        val lines = ArrayList<JBLabel>()
        val component: JComponent

        init {
            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.M), 0)).apply {
                isOpaque = false
                add(summary)
                add(toggle)
                add(ActionLink(msg("pins.hidden.edit")) { openPins() })
            }
            val column = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                head.alignmentX = LEFT_ALIGNMENT
                add(head)
                if (expandedHidden) {
                    for (item in items.take(Limits.PINS_MAX_HIDDEN_LINES)) {
                        val secret = PinRules.looksSecret(item.expression)
                        val key = if (secret) "pins.hidden.line.secret" else "pins.hidden.line"
                        val label = JBLabel(msg(key, item.expression, item.routeCount, item.use)).apply {
                            font = DesignTokens.Type.caption()
                            foreground = Palette.textSecondary
                        }
                        lines += label
                        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.M), 0)).apply {
                            isOpaque = false
                            alignmentX = LEFT_ALIGNMENT
                            add(label)
                            if (!secret && item.expression in dismissed) add(ActionLink(msg("pins.hidden.pin")) { service.undismiss(item.expression) })
                        }
                        add(row)
                    }
                    if (items.size > Limits.PINS_MAX_HIDDEN_LINES) {
                        add(JBLabel(msg("pins.hidden.more", items.size - Limits.PINS_MAX_HIDDEN_LINES)).apply {
                            font = DesignTokens.Type.caption(); foreground = Palette.textSecondary; alignmentX = LEFT_ALIGNMENT
                        })
                    }
                }
            }
            component = NoticeStrip(Palette.info, column)
        }
    }

    // ---- text ----------------------------------------------------------------------------------------------------------

    private fun shown(value: String): String = if (value.isEmpty()) msg("pins.value.empty") else value

    private fun originText(suggestion: SettingSuggestion): String {
        val where = suggestion.origin?.toString().orEmpty()
        return when (suggestion.kind) {
            SuggestionKind.CLASS_DEFAULT -> msg("pins.strip.origin.class", where)
            SuggestionKind.ENV_DEFAULT -> msg("pins.strip.origin.env", where)
            SuggestionKind.CONFIG_DEFAULT -> msg("pins.strip.origin.config", where)
        }
    }

    // ---- test hooks -----------------------------------------------------------------------------------------------------

    internal fun strips(): List<StripRow> = rows.values.toList()

    internal fun hidden(): HiddenLine? = hiddenLine

    internal fun setHiddenExpandedForTest(value: Boolean) {
        expandedHidden = value
        render()
    }

    internal fun renderNow() = render()

    override fun dispose() {
        disposed = true
        scope.cancel()
    }

    private companion object {
        const val FIELD_COLUMNS = 24

        /** Stands for the expression while the message is formatted, so the HTML escaping applies to the rest of the text only. */
        const val PLACEHOLDER = "\u0001EXPR\u0001"
    }
}
