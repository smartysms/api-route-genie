package com.routegenie.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.routegenie.RouteGenieBundle
import com.routegenie.model.Endpoint
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A page of the details area. Phase 3 adds Request, Response and cURL pages by calling [EndpointDetailsPanel.addSection];
 * with a single page the area shows it directly, with more it shows tabs. Nothing else in the window needs to change.
 */
interface EndpointDetailsSection {
    val title: String
    val component: JComponent

    /** Called on the UI thread with the selected endpoint, or null when a group or nothing is selected. */
    fun show(endpoint: Endpoint?)
}

class EndpointDetailsPanel(project: Project) : JBPanel<EndpointDetailsPanel>(BorderLayout()) {
    private val sections = ArrayList<EndpointDetailsSection>()
    private var current: Endpoint? = null

    init {
        addSection(OverviewSection(project))
    }

    fun addSection(section: EndpointDetailsSection) {
        sections += section
        section.show(current)
        removeAll()
        if (sections.size == 1) {
            add(sections[0].component, BorderLayout.CENTER)
        } else {
            val tabs = JBTabbedPane()
            sections.forEach { tabs.addTab(it.title, it.component) }
            add(tabs, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    fun show(endpoint: Endpoint?) {
        current = endpoint
        sections.forEach { it.show(endpoint) }
    }
}

/** Facts about the selected endpoint, with the two actions that need no network: go to the source and copy the path. */
class OverviewSection(private val project: Project) : EndpointDetailsSection {
    override val title: String = RouteGenieBundle.message("details.overview")

    private val header = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val badge = JBLabel()
    private val handler = field()
    private val framework = field()
    private val params = field()
    private val tags = field()
    private val routeName = field()
    private val source = ActionLink("") { current?.let { EndpointSourceNavigation.navigate(project, it.source) } }
    private val rows = listOf(
        row("details.handler", handler), row("details.framework", framework), row("details.parameters", params),
        row("details.tags", tags), row("details.route_name", routeName), row("details.source", source),
    )
    private val navigate = JButton(RouteGenieBundle.message("action.navigate"), AllIcons.Actions.EditSource).apply {
        toolTipText = RouteGenieBundle.message("action.navigate.tooltip")
        addActionListener { current?.let { EndpointSourceNavigation.navigate(project, it.source) } }
    }
    private val copy = JButton(RouteGenieBundle.message("action.copy_path"), AllIcons.Actions.Copy).apply {
        toolTipText = RouteGenieBundle.message("action.copy_path.tooltip")
        addActionListener { current?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.path)) } }
    }
    private val hint = JBLabel(RouteGenieBundle.message("details.none")).apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() }
    private var current: Endpoint? = null

    override val component: JComponent = JBScrollPane(buildContent()).apply { border = JBUI.Borders.empty() }

    private fun field() = JBLabel().apply { setCopyable(true) }

    private class Row(val label: JBLabel, val value: JComponent)

    private fun row(key: String, value: JComponent) = Row(JBLabel(RouteGenieBundle.message(key)).apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }, value)

    private fun buildContent(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(UiMetrics.GAP, UiMetrics.GAP * 2)
        val gap = JBUI.scale(UiMetrics.GAP)
        var y = 0
        fun add(c: JComponent, x: Int, width: Int, weightX: Double, top: Int = 0) {
            panel.add(c, GridBagConstraints().apply {
                gridx = x; gridy = y; gridwidth = width; weightx = weightX; fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(top, 0, 0, if (x == 0 && width == 1) gap * 2 else 0)
            })
        }
        add(hint, 0, 2, 1.0)
        y++
        val head = JPanel(BorderLayout(gap, 0)).apply { isOpaque = false; add(badge, BorderLayout.WEST); add(header, BorderLayout.CENTER) }
        add(head, 0, 2, 1.0)
        y++
        for (r in rows) {
            add(r.label, 0, 1, 0.0, gap)
            add(r.value, 1, 1, 1.0, gap)
            y++
        }
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(navigate)
            add(javax.swing.Box.createHorizontalStrut(gap))
            add(copy)
        }
        add(buttons, 0, 2, 1.0, gap * 2)
        y++
        panel.add(JPanel().apply { isOpaque = false }, GridBagConstraints().apply { gridx = 0; gridy = y; weighty = 1.0; fill = GridBagConstraints.VERTICAL })
        showEndpoint(null, panel)
        return panel
    }

    override fun show(endpoint: Endpoint?) {
        showEndpoint(endpoint, null)
    }

    private fun showEndpoint(endpoint: Endpoint?, panel: JPanel?) {
        current = endpoint
        val has = endpoint != null
        hint.isVisible = !has
        header.isVisible = has
        badge.isVisible = has
        navigate.isEnabled = has
        copy.isEnabled = has
        if (endpoint != null) {
            badge.icon = MethodBadge.of(endpoint.method)
            header.text = endpoint.path
            header.toolTipText = endpoint.path
            handler.text = endpoint.handler.qualifiedName
            framework.text = endpoint.framework.displayName
            params.text = endpoint.pathParams.joinToString(", ") { p -> if (p.type == null) p.name else "${p.name}: ${p.type}" }
            tags.text = endpoint.tags.joinToString(", ")
            routeName.text = endpoint.routeName.orEmpty()
            source.text = endpoint.source.toString()
        }
        for (r in rows) {
            val visible = has && when (r.value) {
                params -> params.text.isNotEmpty()
                tags -> tags.text.isNotEmpty()
                routeName -> routeName.text.isNotEmpty()
                else -> true
            }
            r.label.isVisible = visible
            r.value.isVisible = visible
        }
        panel?.revalidate()
    }
}
