package com.routegenie.ui.json

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.json.SearchQuery
import com.routegenie.ui.UiAction
import com.routegenie.ui.UiToggle
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.ToolbarStyle
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal interface SearchBarListener {
    /** The query changed (after the typing pause); null clears the search. */
    fun queryChanged(query: SearchQuery?)
    fun next()
    fun previous()
}

/**
 * One line: the text to find, case and regular expression toggles, what to look at (keys, values or both), previous and next,
 * and the match count. It only collects input; the viewer runs the search off the UI thread and reports back through [setCount].
 */
internal class JsonSearchBar(parent: Disposable, private val listener: SearchBarListener) : JPanel(BorderLayout()), Disposable {
    private val field = SearchTextField(false)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val count = JBLabel().apply {
        font = DesignTokens.Type.caption()
        foreground = Palette.textSecondary
        border = JBUI.Borders.empty(0, DesignTokens.Space.S)
    }
    private var scope = SCOPE_BOTH
    private var matchCase = false
    private var regex = false
    private var silent = false

    init {
        Disposer.register(parent, this)
        field.textEditor.emptyText.text = RouteGenieBundle.message("json.search.placeholder")
        field.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!silent) schedule()
            }
        })
        field.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> listener.previous()
                    e.keyCode == KeyEvent.VK_ENTER -> listener.next()
                    e.keyCode == KeyEvent.VK_ESCAPE -> setText("")
                }
            }
        })
        val scopeGroup = DefaultActionGroup(RouteGenieBundle.message("json.search.scope"), true).apply {
            templatePresentation.icon = AllIcons.General.Filter
            templatePresentation.description = RouteGenieBundle.message("json.search.scope.tooltip")
            for (choice in listOf(SCOPE_BOTH, SCOPE_KEYS, SCOPE_VALUES)) {
                add(JsonToggle(scopeLabel(choice), RouteGenieBundle.message("json.search.scope.tooltip"), null, get = { scope == choice }) {
                    if (it) {
                        scope = choice
                        schedule()
                    }
                })
            }
        }
        val group = DefaultActionGroup(
            UiToggle(RouteGenieBundle.message("json.search.case"), RouteGenieBundle.message("json.search.case.tooltip"), AllIcons.Actions.MatchCase, { matchCase }) {
                matchCase = it
                schedule()
            },
            UiToggle(RouteGenieBundle.message("json.search.regex"), RouteGenieBundle.message("json.search.regex.tooltip"), AllIcons.Actions.Regex, { regex }) {
                regex = it
                schedule()
            },
            scopeGroup,
            UiAction(RouteGenieBundle.message("json.search.previous"), RouteGenieBundle.message("json.search.previous.tooltip"), AllIcons.Actions.PreviousOccurence) { listener.previous() },
            UiAction(RouteGenieBundle.message("json.search.next"), RouteGenieBundle.message("json.search.next.tooltip"), AllIcons.Actions.NextOccurence) { listener.next() },
        )
        val toolbar = ToolbarStyle.create(PLACE, group, this)
        val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(count)
        }
        add(ToolbarStyle.bar(toolbar, field, trailing), BorderLayout.CENTER)
    }

    private fun scopeLabel(value: String?): String = when (value) {
        SCOPE_KEYS -> RouteGenieBundle.message("json.search.scope.keys")
        SCOPE_VALUES -> RouteGenieBundle.message("json.search.scope.values")
        else -> RouteGenieBundle.message("json.search.scope.both")
    }

    private fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest({ fire() }, Limits.JSON_SEARCH_DEBOUNCE_MS)
    }

    fun current(): SearchQuery? {
        val text = field.text
        if (text.isEmpty()) return null
        return SearchQuery(text, regex = regex, matchCase = matchCase, inKeys = scope != SCOPE_VALUES, inValues = scope != SCOPE_KEYS)
    }

    private fun fire() {
        count.text = ""
        listener.queryChanged(current())
    }

    fun setText(text: String) {
        field.text = text
        alarm.cancelAllRequests()
        fire()
    }

    fun focusField() {
        field.textEditor.requestFocusInWindow()
        field.selectText()
    }

    /** Shows "position / total", "searching" or the reason nothing was found. [position] is 1-based, 0 for none. */
    fun setCount(position: Int, total: Int, truncated: Boolean, searching: Boolean, regexError: String?) {
        count.foreground = Palette.textSecondary
        count.toolTipText = null
        count.text = when {
            current() == null -> ""
            regexError != null -> {
                count.foreground = Palette.danger.text
                count.toolTipText = regexError
                RouteGenieBundle.message("json.search.regex_error")
            }
            searching && total == 0 -> RouteGenieBundle.message("json.search.searching")
            total == 0 -> RouteGenieBundle.message("json.search.none")
            truncated -> RouteGenieBundle.message("json.search.count_more", position, total)
            else -> RouteGenieBundle.message("json.search.count", position, total)
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    private companion object {
        const val PLACE = "RouteGenie.JsonSearch"
        const val SCOPE_BOTH = "both"
        const val SCOPE_KEYS = "keys"
        const val SCOPE_VALUES = "values"
    }
}
