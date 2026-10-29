package com.routegenie.ui.json

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.json.JsonContent
import com.routegenie.json.JsonPath
import com.routegenie.testing.ProjectFiles
import com.routegenie.ui.design.Snapshots
import com.routegenie.ui.design.ThemeSample
import com.routegenie.ui.design.ThemeSamples
import com.routegenie.ui.design.ThemeSwitch
import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Paints the viewer and the diff to PNG files in build/reports/routegenie/json for light, dark and a Dracula-like theme. The
 * files are meant to be looked at; the assertions only prove that every scene painted something and wrote its file.
 */
class JsonGalleryTest : BasePlatformTestCase() {
    private val owner = Disposer.newDisposable("json gallery")
    private val notes = ArrayList<String>()
    private val written = ArrayList<File>()
    private val dir = File(ProjectFiles.root, "build/reports/routegenie/json").also { it.mkdirs() }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            File(dir, "notes.txt").writeText(notes.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)
        } finally {
            super.tearDown()
        }
    }

    private val response = """
        {
          "data": {
            "users": [
              {"id": 1, "name": "Ada Lovelace", "email": "ada@example.com", "active": true, "score": 98.5, "roles": ["admin", "editor"], "address": {"city": "London", "zip": "N1 9GU"}, "lastLogin": null},
              {"id": 2, "name": "Alan Turing", "email": "alan@example.com", "active": true, "score": 97.25, "roles": ["editor"], "address": {"city": "Manchester", "zip": "M1 1AE"}, "lastLogin": "2026-10-29T08:15:00Z"},
              {"id": 3, "name": "Grace Hopper", "email": "grace@example.com", "active": false, "score": 99, "roles": [], "address": {"city": "New York", "zip": "10001"}, "lastLogin": "2026-10-01T17:40:12Z"},
              {"id": 4, "name": "Edsger Dijkstra", "email": "edsger@example.com", "active": true, "score": 91.75, "roles": ["viewer"], "address": {"city": "Eindhoven", "zip": "5611"}, "lastLogin": null}
            ],
            "page": 1, "pageSize": 4, "total": 128
          },
          "links": {"self": "/api/users?page=1", "next": "/api/users?page=2"},
          "meta": {"requestId": "9f2c1c7e-4d1b-4b3a-8a55-0c1f7b6a2e11", "elapsedMs": 12.4, "note": "A longer text value that does not fit on one row, to show how the preview is cut and how the size is reported next to it. It goes on and on and on."}
        }
    """.trimIndent()

    private val previous = """{"id":42,"name":"Order 42","status":"pending","total":99.5,"items":[{"sku":"A-1","qty":1,"price":19.5},{"sku":"B-7","qty":2,"price":40},{"sku":"C-3","qty":1,"price":0}],"customer":{"id":7,"email":"ada@example.com","vip":false},"tags":["new","web"],"updatedAt":"2026-10-29T09:00:00Z"}"""
    private val current = """{"id":42,"name":"Order 42","status":"paid","total":120.0,"items":[{"sku":"A-1","qty":1,"price":19.5},{"sku":"B-7","qty":3,"price":40},{"sku":"D-9","qty":1,"price":0.5}],"customer":{"id":7,"email":"ada@example.com","vip":true,"tier":"gold"},"tags":["new","web","vip"],"paidAt":"2026-10-30T10:30:00Z"}"""

    fun testGalleryPaintsEveryScene() {
        for (theme in listOf(ThemeSamples.LIGHT, ThemeSamples.DARK, ThemeSamples.DRACULA)) {
            ThemeSwitch.with(theme, notes) {
                val parent = Disposer.newDisposable(owner, "gallery ${theme.id}")
                try {
                    scenes(theme, parent)
                } finally {
                    Disposer.dispose(parent)
                }
            }
        }
        assertEquals(3 * SCENES, written.size)
        assertTrue(written.all { it.length() > 1000 })
    }

    private fun scenes(theme: ThemeSample, parent: Disposable) {
        val viewer = JsonViewerPanel(parent)
        shown(viewer, JsonContent.text(response))
        val city = JsonPath.resolve(viewer.currentIndex()!!, JsonPath.parse("$.data.users[2].address.city").segments!!)
        viewer.treeView.expandToLevel(3)
        viewer.treeView.select(city)
        emit("tree", theme, viewer, W, H)

        viewer.search("ada")
        wait("search hits") { viewer.searchHitCount() > 0 }
        UIUtil.dispatchAllInvocationEvents()
        emit("tree-search", theme, viewer, W, H)

        viewer.search("")
        viewer.setMode(JsonViewMode.PRETTY)
        wait("pretty") { viewer.prettyView.hasEditor }
        pump()
        emit("pretty", theme, viewer, W, H)

        viewer.setMode(JsonViewMode.TABLE)
        wait("table") { viewer.cardName() == "table" }
        emit("table", theme, viewer, W, H)
        viewer.clear()

        val broken = JsonViewerPanel(parent)
        shown(broken, JsonContent.text("{\n  \"id\": 7,\n  \"name\": \"Ada\",\n  \"tags\": [\"a\", \"b\",],\n  \"ok\": true\n}"))
        wait("error banner") { broken.hasErrorBanner() && broken.errorBannerText().contains("line") }
        wait("raw") { broken.rawView.hasEditor }
        pump()
        emit("invalid", theme, broken, W, H_SHORT)

        val empty = JsonViewerPanel(parent)
        emit("empty", theme, empty, W, H_SHORT)

        val diff = JsonDiffPanel(parent)
        diff.compare(JsonContent.text(previous), JsonContent.text(current), "Previous response (14:02:11)", "This response (14:05:40)")
        wait("diff") { diff.cardName() == "result" }
        UIUtil.dispatchAllInvocationEvents()
        emit("diff", theme, diff, W, H_DIFF)
    }

    private fun shown(panel: JsonViewerPanel, content: JsonContent) {
        panel.showBody(content)
        wait("indexed") { panel.currentIndex() != null && !panel.isIndexing() }
        pump()
    }

    private fun pump() {
        UIUtil.dispatchAllInvocationEvents()
        Thread.sleep(PUMP_MS)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun wait(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    /** Toolbars fill in on a background update; the snapshot is taken without waiting, so update them all now. */
    private fun refreshToolbars(root: java.awt.Component) {
        if (root is com.intellij.openapi.actionSystem.impl.ActionToolbarImpl) root.updateActionsImmediately()
        if (root is java.awt.Container) root.components.forEach { refreshToolbars(it) }
    }

    private fun emit(scene: String, theme: ThemeSample, component: JComponent, width: Int, height: Int) {
        refreshToolbars(component)
        val image = Snapshots.render(component, width, height, 1.0)
        val file = File(dir, "$scene-${theme.id}.png")
        ImageIO.write(image, "png", file)
        written += file
        assertTrue("$scene-${theme.id} is blank", Snapshots.inkPixels(image) > INK_MIN)
        notes += "$scene-${theme.id}: ${image.width}x${image.height}, corner=${hex(Color(image.getRGB(2, 2)))}"
    }

    private fun hex(color: Color) = "#%06X".format(color.rgb and 0xFFFFFF)

    private companion object {
        const val SCENES = 7
        const val W = 940
        const val H = 560
        const val H_SHORT = 320
        const val H_DIFF = 520
        const val INK_MIN = 300
        const val PUMP_MS = 150L
    }
}
