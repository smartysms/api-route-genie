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
import com.routegenie.pinside.PinsService
import com.routegenie.ui.pins.PinsDialog
import com.routegenie.ui.design.BadgeState
import com.routegenie.ui.design.Chip
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.design.EmptyState
import com.routegenie.ui.design.KeyValueGrid
import com.routegenie.ui.design.KeyValueRow
import com.routegenie.ui.design.MethodBadge
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.RouteGenieIcons
import com.routegenie.ui.design.SectionHeader
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
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
    private var tabs: JBTabbedPane? = null

    init {
        addSection(OverviewSection(project))
    }

    /** Brings [section] to the front when the area shows tabs. */
    fun showSection(section: EndpointDetailsSection) {
        val i = sections.indexOf(section)
        if (i >= 0) tabs?.selectedIndex = i
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
            this.tabs = tabs
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

    private var current: Endpoint? = null

    private val heading = JBLabel().apply {
        font = DesignTokens.Type.title()
        foreground = Palette.textPrimary
        iconTextGap = JBUI.scale(Space.S)
    }
    private val deprecatedChip = Chip(RouteGenieBundle.message("endpoint.deprecated"), Palette.warning)
    // HorizontalLayout puts the gap only between chips, so the first chip lines up with the other values.
    private val tagChips = JPanel(com.intellij.ui.components.panels.HorizontalLayout(JBUI.scale(Space.XS))).apply { isOpaque = false }

    private val grid = KeyValueGrid()
    private val handler = grid.addRow(RouteGenieBundle.message("details.handler"), KeyValueGrid.valueLabel(mono = true))
    private val framework = grid.addRow(RouteGenieBundle.message("details.framework"))
    private val params = grid.addRow(RouteGenieBundle.message("details.parameters"), KeyValueGrid.valueLabel(mono = true))
    private val tags = grid.addRow(RouteGenieBundle.message("details.tags"), tagChips)
    private val routeName = grid.addRow(RouteGenieBundle.message("details.route_name"), KeyValueGrid.valueLabel(mono = true))
    private val sourceLink = ActionLink("") { navigate() }
    private val source = grid.addRow(RouteGenieBundle.message("details.source"), sourceLink)

    /** One line per pin this path was built with: "Prefix taken from your pin settings.X = /api/v1" and a link to change it. */
    private val pinLines = JPanel().apply {
        isOpaque = false
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    }
    private val pinRow = grid.addRow(RouteGenieBundle.message("pins.details.label"), pinLines)

    private val navigateLink = ActionLink(RouteGenieBundle.message("action.navigate")) { navigate() }.apply {
        icon = AllIcons.Actions.EditSource
        toolTipText = RouteGenieBundle.message("action.navigate.tooltip")
    }
    /** Icon only, at the end of the title row: copying the path is the one action tied to the title itself. */
    private val copyLink = ActionLink("") {
        current?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.path)) }
    }.apply {
        icon = RouteGenieIcons.COPY
        toolTipText = RouteGenieBundle.message("action.copy_path.tooltip")
    }

    private val cards = CardLayout()
    private val root = JPanel(cards).apply {
        isOpaque = true
        background = Palette.surface
    }

    override val component: JComponent = root

    init {
        root.add(EmptyState(RouteGenieIcons.EMPTY_ROUTES, RouteGenieBundle.message("details.none")), CARD_NONE)
        root.add(JBScrollPane(buildContent()).apply {
            border = JBUI.Borders.empty()
            viewport.isOpaque = true
            viewport.background = Palette.surface
        }, CARD_DETAILS)
        cards.show(root, CARD_NONE)
    }

    /**
     * Title row (verb badge, path, copy), then a captioned block of facts, then the one navigation action. Horizontal insets
     * are [Space.M] everywhere so the section header, the facts and the actions share one left edge; vertical gaps step
     * M (title to header), S (inside the facts) and L (facts to action).
     */
    private fun buildContent(): JComponent {
        val trailing = JPanel(com.intellij.ui.components.panels.HorizontalLayout(JBUI.scale(Space.S))).apply {
            isOpaque = false
            add(deprecatedChip.also { it.isVisible = false })
            add(copyLink)
        }
        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(heading, BorderLayout.CENTER)
            add(trailing, BorderLayout.EAST)
            border = Space.border(Space.M, Space.M, Space.M, Space.M)
        }
        val facts = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = Space.border(Space.S, Space.M, 0, Space.M)
            add(grid, BorderLayout.CENTER)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(navigateLink)
            border = Space.border(Space.L, Space.M, Space.L, Space.M)
        }
        val column = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(head, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(SectionHeader(RouteGenieBundle.message("details.section")), BorderLayout.NORTH)
                add(facts, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = Palette.surface
            add(column, BorderLayout.NORTH)
        }
    }

    private fun navigate() {
        current?.let { EndpointSourceNavigation.navigate(project, it.source) }
    }

    override fun show(endpoint: Endpoint?) {
        current = endpoint
        if (endpoint == null) {
            cards.show(root, CARD_NONE)
            return
        }
        heading.icon = MethodBadge.of(endpoint.method, BadgeState.NORMAL)
        heading.text = endpoint.path
        heading.toolTipText = endpoint.path
        deprecatedChip.isVisible = endpoint.deprecated

        handler.valueLabel().text = endpoint.handler.qualifiedName
        framework.valueLabel().text = endpoint.framework.displayName
        val paramText = endpoint.pathParams.joinToString(", ") { p -> if (p.type == null) p.name else "${p.name}: ${p.type}" }
        params.valueLabel().text = paramText
        params.isVisible = paramText.isNotEmpty()

        tagChips.removeAll()
        endpoint.tags.forEach { tagChips.add(Chip(it)) }
        tags.isVisible = endpoint.tags.isNotEmpty()
        tagChips.invalidate()
        root.revalidate()
        root.repaint()

        val name = endpoint.routeName.orEmpty()
        routeName.valueLabel().text = name
        routeName.isVisible = name.isNotEmpty()
        sourceLink.text = endpoint.source.toString()
        source.isVisible = true
        showPins(endpoint)
        cards.show(root, CARD_DETAILS)
    }

    private fun showPins(endpoint: Endpoint) {
        pinLines.removeAll()
        val values = PinsService.getInstance(project).snapshot.value.byExpression
        for (expression in endpoint.pins) {
            val text = RouteGenieBundle.message("pins.details.text", expression, values[expression]?.value ?: "")
            pinLines.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(Space.M), 0)).apply {
                isOpaque = false
                alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(JBLabel(text).apply { foreground = Palette.textPrimary })
                add(ActionLink(RouteGenieBundle.message("pins.details.change")) { PinsDialog(project).show() })
            })
        }
        pinRow.isVisible = endpoint.pins.isNotEmpty()
        pinLines.revalidate()
    }

    private fun KeyValueRow.valueLabel(): JBLabel = value as JBLabel

    private companion object {
        const val CARD_NONE = "none"
        const val CARD_DETAILS = "details"
    }
}
