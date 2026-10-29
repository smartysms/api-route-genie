package com.routegenie.ui.design

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.ui.EndpointJTree
import com.routegenie.ui.EndpointTreeModel
import com.routegenie.ui.EndpointTreeRenderer
import com.routegenie.ui.EndpointsPanel
import com.routegenie.ui.RouteGenieWindowContent
import com.routegenie.ui.design.DesignTokens.Space
import com.routegenie.ui.model.Group
import com.routegenie.ui.model.GroupKind
import com.routegenie.ui.model.Leaf
import com.routegenie.ui.model.Node
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import java.io.File

/**
 * Paints sample windows to PNG files in build/reports/routegenie/design, for light, dark and three third-party-style themes, at 1x
 * and 2x. The files are meant to be looked at; the assertions only prove that every scene painted something and that the files
 * exist. Which look and feel was really used for each theme is written to notes.txt.
 */
class DesignGalleryTest : BasePlatformTestCase() {
    private val owner = Disposer.newDisposable("gallery")
    private val notes = ArrayList<String>()
    private val written = ArrayList<File>()

    override fun tearDown() {
        try {
            if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
            File(Snapshots.dir, "notes-${getTestName(true)}.txt").writeText(notes.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)
        } finally {
            super.tearDown()
        }
    }

    // --- Scenes -------------------------------------------------------------------------------------------------------------

    /** One test for all scenes: switching the look and feel is the slow part, so each theme is entered once. */
    fun testGalleryPaintsEveryScene() {
        fixtureProject()
        eachTheme { theme, parent ->
            windowWithEndpointsAndDetails(theme, parent)
            windowStates(theme, parent)
            rowsInEveryStateForEveryMethod(theme)
            emit("components", theme, componentSheet(parent).also { sheet -> sheetHeight = sheet.preferredSize.height }, SHEET_W, sheetHeight)
            stateSheet(theme, parent)
        }
        assertEquals(ThemeSamples.ALL.size * SCENES * 2, written.size)
    }

    private var sheetHeight = 0

    private fun windowWithEndpointsAndDetails(theme: ThemeSample, parent: Disposable) {
        val panel = shownPanel(parent)
        waitForFixtureEndpoints(panel)
        notes += "${theme.id}: rows after load ${panel.tree.rowCount}, ui ${panel.tree.ui.javaClass.simpleName}, largeModel ${panel.tree.isLargeModel}, rowHeight ${panel.tree.rowHeight}"
        panel.expandAll()
        selectFirstLeaf(panel, preferPath = "/users/{user_id}")
        refreshToolbar(panel)
        emit("window", theme, panel, WINDOW_W, WINDOW_H)
    }

    private fun windowStates(theme: ThemeSample, parent: Disposable) {
        val panel = RouteGenieWindowContent(project, parent).panel
        refreshToolbar(panel)
        emit("window-loading", theme, panel, WINDOW_W, WINDOW_H_SHORT)
        panel.setShown(true)
        waitForFixtureEndpoints(panel)
        panel.setQueryForTest("method:post zzz-nothing")
        waitFor("no match") { panel.currentCard() == "nomatch" }
        emit("window-nomatch", theme, panel, WINDOW_W, WINDOW_H_SHORT)
        panel.setQueryForTest("method:get")
        waitFor("filtered") { panel.currentCard() == "tree" && panel.statusText().contains(" of ") }
        emit("window-filtered", theme, panel, WINDOW_W, WINDOW_H_SHORT)
    }

    private fun rowsInEveryStateForEveryMethod(theme: ThemeSample) {
        val tree = EndpointJTree()
        val group = Group("g", "users", GroupKind.PATH, listOf(Leaf(sample(HttpMethod.GET, "/users"))))
        val normal = buildList<Node> {
            HttpMethod.entries.forEach { add(Leaf(sample(it, "/users/{user_id}/items", tags = listOf("users", "admin")))) }
            add(Leaf(sample(HttpMethod.GET, "/legacy/report", tags = listOf("reports", "old", "beta", "extra", "more"), deprecated = true)))
            add(Leaf(sample(HttpMethod.PUT, "/api/v2/organizations/{org_id}/projects/{project_id}/environments/{env}/variables")))
            add(group)
        }
        val selected = buildList<Node> {
            listOf(HttpMethod.GET, HttpMethod.DELETE, HttpMethod.ANY).forEach { add(Leaf(sample(it, "/users/{user_id}", tags = listOf("users")))) }
            add(Leaf(sample(HttpMethod.GET, "/legacy/report", tags = listOf("old"), deprecated = true)))
        }
        val view = RowsView(
            tree, EndpointTreeRenderer(),
            normal.map { RowItem(it, selected = false, focused = true) } +
                selected.map { RowItem(it, selected = true, focused = true) } +
                selected.map { RowItem(it, selected = true, focused = false) },
        )
        emit("rows", theme, view, ROWS_W, view.preferredSize.height)
    }

    private fun stateSheet(theme: ThemeSample, parent: Disposable) {
        val sheet = JPanel(GridLayout(2, 2)).apply {
            add(LoadingState(parent, RouteGenieBundle.message("progress.scanning")))
            add(EmptyState(RouteGenieIcons.EMPTY_ROUTES, RouteGenieBundle.message("window.empty"), RouteGenieBundle.message("state.empty.hint")))
            add(EmptyState(RouteGenieIcons.EMPTY_SEARCH, RouteGenieBundle.message("state.no_match"), RouteGenieBundle.message("state.no_match.hint")))
            add(EmptyState(RouteGenieIcons.EMPTY_PROJECT, RouteGenieBundle.message("state.no_python"), RouteGenieBundle.message("state.no_python.hint")))
        }
        emit("states", theme, sheet, STATES_W, STATES_H)
    }

    // --- Harness ------------------------------------------------------------------------------------------------------------

    private fun eachTheme(scene: (ThemeSample, Disposable) -> Unit) {
        for (theme in ThemeSamples.ALL) {
            ThemeSwitch.with(theme, notes) {
                val parent = Disposer.newDisposable(owner, "gallery ${theme.id}")
                try {
                    notes += "${theme.id}: surface=${hex(Palette.surface)} text=${hex(Palette.textPrimary)} secondary=${hex(Palette.textSecondary)} " +
                        "selection=${hex(Palette.selection)} hover=${hex(Palette.hover)} GET.text=${hex(Palette.method(HttpMethod.GET).text)}"
                    scene(theme, parent)
                } finally {
                    Disposer.dispose(parent)
                }
            }
        }
    }

    private fun hex(color: java.awt.Color) = "#%06X".format(color.rgb and 0xFFFFFF)

    private fun emit(scene: String, theme: ThemeSample, component: JComponent, width: Int, height: Int) {
        for (scale in listOf(1.0, 2.0)) {
            val image = Snapshots.render(component, width, height, scale)
            val name = "$scene-${theme.id}@${scale.toInt()}x"
            written += Snapshots.write(image, name)
            assertTrue("$name is blank", Snapshots.inkPixels(image) > 100)
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    /** The discovery service may still hold a snapshot from an earlier test; wait until the files of this fixture are in the list. */
    private fun waitForFixtureEndpoints(panel: EndpointsPanel) {
        val service = RouteDiscoveryService.getInstance(project)
        service.requestRefresh()
        waitFor("fixture endpoints in the snapshot") { service.snapshot.value.endpoints.any { it.path == "/users/{user_id}" } }
        waitFor("tree shows them") { panel.currentCard() == "tree" && panel.statusText().contains("${service.snapshot.value.endpoints.size} endpoints") }
    }

    private fun shownPanel(parent: Disposable): EndpointsPanel = RouteGenieWindowContent(project, parent).panel.also { it.setShown(true) }

    private fun refreshToolbar(panel: EndpointsPanel) {
        UIUtil.findComponentOfType(panel, ActionToolbarImpl::class.java)?.updateActionsImmediately()
    }

    private fun selectFirstLeaf(panel: EndpointsPanel, preferPath: String) {
        var fallback = -1
        for (row in 0 until panel.tree.rowCount) {
            val node = panel.tree.getPathForRow(row).lastPathComponent as? Leaf ?: continue
            if (fallback < 0) fallback = row
            if (node.endpoint.path == preferPath && node.endpoint.method == HttpMethod.PUT) {
                panel.tree.setSelectionRow(row)
                return
            }
        }
        if (fallback >= 0) panel.tree.setSelectionRow(fallback)
    }

    private fun sample(method: HttpMethod, path: String, tags: List<String> = emptyList(), deprecated: Boolean = false) = Endpoint(
        method, path, if (method == HttpMethod.ANY) Framework.DJANGO else Framework.FASTAPI, Handler("handle_${method.name.lowercase()}", "app.routes.handle"),
        SourceRef("app/routes.py", 10, 1), tags = tags, deprecated = deprecated,
    )

    private fun fixtureProject() {
        myFixture.addFileToProject("main.py", FASTAPI_APP)
        myFixture.addFileToProject("web/urls.py", DJANGO_URLS)
    }

    // --- Sheets -------------------------------------------------------------------------------------------------------------

    private class RowItem(val node: Node, val selected: Boolean, val focused: Boolean)

    /** Draws tree rows through the real renderer, one per item, with the selection fill the tree UI would put behind them. */
    private class RowsView(val tree: EndpointJTree, val renderer: EndpointTreeRenderer, val items: List<RowItem>) : JComponent() {
        private val rowHeight = tree.rowHeight

        override fun getPreferredSize(): Dimension = Dimension(ROWS_W, rowHeight * items.size)

        override fun paintComponent(g: Graphics) {
            g.color = Palette.surface
            g.fillRect(0, 0, width, height)
            items.forEachIndexed { row, item ->
                val state = when {
                    !item.selected -> RowState.NORMAL
                    item.focused -> RowState.SELECTED
                    else -> RowState.SELECTED_INACTIVE
                }
                val y = row * rowHeight
                RowPainter.paint(g as java.awt.Graphics2D, Space.XS, y, width - 2 * Space.XS, rowHeight, state)
                val c = renderer.getTreeCellRendererComponent(tree, item.node, item.selected, false, item.node is Leaf, row, item.focused)
                c.setBounds(Space.XS * 2, 0, width - 4 * Space.XS, rowHeight)
                c.preferredSize
                val rowGraphics = g.create(Space.XS * 2, y, width - 4 * Space.XS, rowHeight)
                try {
                    c.paint(rowGraphics)
                } finally {
                    rowGraphics.dispose()
                }
            }
        }
    }

    private fun labelled(title: String, content: JComponent): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(SectionHeader(title), BorderLayout.NORTH)
        add(content.also { (it as? JPanel)?.border = Space.border(Space.S, Space.M, Space.M, Space.M) }, BorderLayout.CENTER)
    }

    private fun flow(vararg parts: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, Space.S, Space.XS)).apply {
        isOpaque = false
        parts.forEach { add(it) }
    }

    private fun componentSheet(parent: Disposable): JComponent {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = true
            background = Palette.surface
        }
        column.add(labelled("Method badges", flow(*HttpMethod.entries.map { JBLabel(MethodBadge.of(it)) }.toTypedArray())))
        val onSelection = JPanel(FlowLayout(FlowLayout.LEFT, Space.S, Space.XS)).apply {
            isOpaque = true
            background = Palette.selectionBackground(true)
            HttpMethod.entries.forEach { add(JBLabel(MethodBadge.of(it, BadgeState.SELECTED))) }
        }
        column.add(labelled("Badges on a focused selection", onSelection))
        column.add(labelled("Status pills", flow(
            StatusPill(101, "Switching"), StatusPill(200, "OK"), StatusPill(204, "No Content"), StatusPill(301, "Moved"),
            StatusPill(404, "Not Found"), StatusPill(503, "Unavailable"),
        )))
        column.add(labelled("Chips", flow(
            Chip("users"), Chip("admin"), Chip("deprecated", Palette.warning), Chip("beta", Palette.info), Chip("ok", Palette.success), Chip("failed", Palette.danger),
        )))
        val grid = KeyValueGrid()
        (grid.addRow("Handler").value as JBLabel).text = "app.routers.users.get_user"
        (grid.addRow("Framework").value as JBLabel).text = "FastAPI"
        (grid.addRow("Path parameters").value as JBLabel).text = "user_id: int"
        column.add(labelled("Key and value", grid))
        column.add(labelled("Loading", JPanel(BorderLayout()).apply {
            isOpaque = false
            add(LoadingRow(parent, RouteGenieBundle.message("progress.scanning")), BorderLayout.NORTH)
            add(SkeletonRows(), BorderLayout.CENTER)
        }))
        column.add(labelled("Icons", flow(*iconLabels())))
        column.add(labelled("Palette in this look", SwatchStrip()))
        return column
    }

    private fun iconLabels(): Array<JComponent> = listOf(
        RouteGenieIcons.PLUGIN, RouteGenieIcons.GUTTER, RouteGenieIcons.SEND, RouteGenieIcons.STOP, RouteGenieIcons.COPY, RouteGenieIcons.CURL,
        RouteGenieIcons.HISTORY, RouteGenieIcons.STREAM, RouteGenieIcons.CLEAR, RouteGenieIcons.ENVIRONMENT, RouteGenieIcons.AUTH,
        RouteGenieIcons.PANEL_HIDE, RouteGenieIcons.SPLIT_ORIENTATION, RouteGenieIcons.PRETTY_PRINT, RouteGenieIcons.WRAP, RouteGenieIcons.SEARCH,
        RouteGenieIcons.EMPTY_ROUTES, RouteGenieIcons.EMPTY_SEARCH, RouteGenieIcons.EMPTY_PROJECT,
    ).map<javax.swing.Icon, JComponent> { JBLabel(it) }.toTypedArray()

    /** The inherited and tone colors as they resolve in the current look, as small squares with their hex value. */
    private class SwatchStrip : JComponent() {
        private val names = Palette.names()

        override fun getPreferredSize(): Dimension = Dimension(SHEET_W - 24, 20 * ((names.size + COLUMNS - 1) / COLUMNS) + 8)

        override fun paintComponent(g: Graphics) {
            val cell = (width - 8) / COLUMNS
            names.forEachIndexed { i, name ->
                val x = 4 + (i % COLUMNS) * cell
                val y = 4 + (i / COLUMNS) * 20
                val color = Palette.colorOf(name)!!
                g.color = color
                g.fillRoundRect(x, y, 14, 14, 4, 4)
                g.color = Palette.border
                g.drawRoundRect(x, y, 14, 14, 4, 4)
                g.color = Palette.textSecondary
                g.font = DesignTokens.Type.caption()
                g.drawString(name, x + 20, y + 11)
            }
        }

        private companion object {
            const val COLUMNS = 3
        }
    }

    private companion object {
        /** Scenes emitted per theme: window, window-loading, window-nomatch, window-filtered, rows, components, states. */
        const val SCENES = 7
        const val WINDOW_W = 460
        const val WINDOW_H = 680
        const val WINDOW_H_SHORT = 300
        const val ROWS_W = 520
        const val SHEET_W = 720
        const val STATES_W = 900
        const val STATES_H = 520

        const val FASTAPI_APP = """from fastapi import FastAPI
app = FastAPI()

@app.get('/users', tags=['users'])
def list_users():
    return []

@app.post('/users', tags=['users'])
def create_user():
    return {}

@app.get('/users/{user_id}', tags=['users', 'admin'])
def get_user(user_id: int):
    return {}

@app.put('/users/{user_id}', tags=['users'])
def replace_user(user_id: int):
    return {}

@app.patch('/users/{user_id}', tags=['users'])
def update_user(user_id: int):
    return {}

@app.delete('/users/{user_id}', tags=['users'])
def delete_user(user_id: int):
    return {}

@app.head('/health')
def health_head():
    return None

@app.options('/health')
def health_options():
    return None

@app.get('/legacy/report', deprecated=True, tags=['reports', 'old'])
def legacy_report():
    return {}
"""

        const val DJANGO_URLS = """from django.urls import path
from django.http import HttpResponse

def about(request):
    return HttpResponse('x')

urlpatterns = [path('about/', about, name='about')]
"""
    }
}
