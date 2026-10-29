package com.routegenie.ui.request

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.client.EngineError
import com.routegenie.client.ErrorKind
import com.routegenie.client.ResponseMode
import com.routegenie.client.ResponseSlot
import com.routegenie.client.StreamEvent
import com.routegenie.clientide.RouteGenieClientState
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.ui.design.Snapshots
import com.routegenie.ui.design.ThemeSample
import com.routegenie.ui.design.ThemeSamples
import com.routegenie.ui.design.ThemeSwitch
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

/**
 * Paints the request and response workspace (form, JSON tree, diff, stream, error and empty states, environment editor) to
 * build/reports/routegenie/design/workspace-*.png in light, dark and Dracula. The files are meant to be looked at; the
 * assertions only prove that every scene painted something.
 */
class WorkspaceGalleryTest : BasePlatformTestCase() {
    private val owner = Disposer.newDisposable("workspace gallery")
    private val notes = ArrayList<String>()
    private var written = 0

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            java.io.File(Snapshots.dir, "notes-workspace.txt").writeText(notes.joinToString("\n", postfix = "\n"), Charsets.US_ASCII)
        } finally {
            super.tearDown()
        }
    }

    private val users = """
        {"data":{"users":[
          {"id":1,"name":"Ada Lovelace","email":"ada@example.com","active":true,"score":98.5,"roles":["admin","editor"],"address":{"city":"London","zip":"N1 9GU"},"lastLogin":null},
          {"id":2,"name":"Alan Turing","email":"alan@example.com","active":true,"score":97.25,"roles":["editor"],"address":{"city":"Manchester","zip":"M1 1AE"},"lastLogin":"2026-10-29T08:15:00Z"},
          {"id":3,"name":"Grace Hopper","email":"grace@example.com","active":false,"score":99,"roles":[],"address":{"city":"New York","zip":"10001"},"lastLogin":"2026-10-01T17:40:12Z"}
        ],"page":1,"pageSize":3,"total":128},
        "links":{"self":"/api/users?page=1","next":"/api/users?page=2"},"meta":{"requestId":"9f2c1c7e-4d1b-4b3a-8a55","elapsedMs":12.4}}
    """.trimIndent()

    private val previous = """{"id":42,"name":"Order 42","status":"pending","total":99.5,"items":[{"sku":"A-1","qty":1},{"sku":"B-7","qty":2}],"customer":{"id":7,"vip":false},"tags":["new","web"]}"""
    private val current = """{"id":42,"name":"Order 42","status":"paid","total":120.0,"items":[{"sku":"A-1","qty":1},{"sku":"B-7","qty":3},{"sku":"D-9","qty":1}],"customer":{"id":7,"vip":true,"tier":"gold"},"tags":["new","web","vip"]}"""

    fun testGalleryPaintsEveryScene() {
        for (theme in listOf(ThemeSamples.LIGHT, ThemeSamples.DARK, ThemeSamples.DRACULA)) {
            ThemeSwitch.with(theme, notes) {
                val parent = Disposer.newDisposable(owner, "workspace ${theme.id}")
                try {
                    scenes(theme, parent)
                } finally {
                    Disposer.dispose(parent)
                }
            }
        }
        assertEquals(3 * SCENES, written)
    }

    private class Rig(val section: RequestSection, val engine: FakeEngine, val ui: ManualUi)

    private fun rig(parent: Disposable): Rig {
        val engine = FakeEngine()
        val ui = ManualUi()
        val section = RequestSection(
            project, parent, engine = { engine }, bodyTemplates = { com.routegenie.client.BodyTemplateSource { null } }, ui = ui,
            state = RouteGenieClientState(), secretStore = MapSecretStore(), secretExecutor = directExecutor, responses = ResponseSlot(),
        )
        return Rig(section, engine, ui)
    }

    private fun scenes(theme: ThemeSample, parent: Disposable) {
        // 1. The form of a POST endpoint filled in, no response yet.
        val form = rig(parent)
        val post = endpoint(HttpMethod.POST, "/users/{user_id}/items", listOf(PathParam("user_id", "int")))
        form.section.show(post)
        form.section.currentForm()!!.apply {
            pathValues["user_id"] = "42"
            query = listOf(FormRow("page", "1"), FormRow("verbose", "true", enabled = false))
            headers = listOf(FormRow("X-Trace", "abc-123"))
            authKind = AuthKind.BEARER
            bodyKind = BodyType.JSON
            bodyText = "{\n  \"name\": \"Notebook\",\n  \"price\": 12.5,\n  \"tags\": [\"office\", \"paper\"]\n}"
        }
        form.section.formForTest().refresh(null)
        emitSection("request", theme, form.section, W, H)
        form.section.formForTest().selectFormTabForTest("Authentication")
        emitSection("request-auth", theme, form.section, W, H)
        form.section.formForTest().selectFormTabForTest("Body")
        withEditor(form.section)
        emitSection("request-body", theme, form.section, W, H)
        form.section.formForTest().selectFormTabForTest("Query parameters")
        emitSection("request-narrow", theme, form.section, W_NARROW, H_NARROW)
        runCatching { form.section.formForTest().bodyEditor.removeNotify() }

        // 2. A JSON response in the visualizer.
        val json = rig(parent)
        json.section.show(endpoint(HttpMethod.GET, "/users", emptyList()))
        answer(json, jsonResponse(users))
        wait("json indexed") { json.section.responseForTest().jsonForTest().let { it.currentIndex() != null && !it.isIndexing() } }
        pump()
        emitSection("response-json", theme, json.section, W, H)

        // 3. The comparison of two responses.
        val diff = rig(parent)
        diff.section.show(endpoint(HttpMethod.GET, "/orders/42", emptyList()))
        answer(diff, jsonResponse(previous))
        answer(diff, jsonResponse(current))
        diff.section.responseForTest().comparePreviousForTest()
        wait("diff computed") { diff.section.responseForTest().diffForTest().cardName() == "result" }
        pump()
        emitSection("diff", theme, diff.section, W, H)

        // 4. A live event stream.
        val sse = rig(parent)
        sse.section.show(endpoint(HttpMethod.GET, "/events", emptyList()))
        sse.section.send()
        sse.engine.emit(
            StreamEvent.HeadersReceived(200, "OK", listOf("content-type" to "text/event-stream"), "HTTP/1.1", ResponseMode.SSE, "http://127.0.0.1:8000/events"),
            *Array(8) { StreamEvent.SseEvent("${it + 1}", "tick", "{\"n\":${it + 1},\"load\":0.${it + 3}}", null) },
        )
        sse.ui.runPending()
        pump()
        emitSection("stream", theme, sse.section, W, H)

        // 5. A failed call.
        val failed = rig(parent)
        failed.section.show(endpoint(HttpMethod.GET, "/users", emptyList()))
        failed.section.send()
        failed.engine.emit(StreamEvent.Failed(EngineError(ErrorKind.CONNECTION_REFUSED, "127.0.0.1:8000")))
        failed.ui.runPending()
        emitSection("error", theme, failed.section, W, H_SHORT)

        // 6. Nothing selected.
        val empty = rig(parent)
        emitSection("empty", theme, empty.section, W, H_SHORT)

        // 7. The environment editor.
        val env = rig(parent)
        env.section.show(endpoint(HttpMethod.GET, "/users", emptyList()))
        env.section.showEnvironmentEditor(true)
        env.section.environmentEditorForTest()!!.apply {
            startNewForTest()
            setFieldsForTest("Staging", "https://staging.example.com", EnvAuthKind.BEARER, "", "")
            addVariableForTest(VarRow("tenant", "acme", false))
            addVariableForTest(VarRow("apiKey", "", true))
        }
        emitSection("environment", theme, env.section, W, H)
    }

    private fun answer(rig: Rig, r: com.routegenie.client.ResponseData) {
        rig.section.send()
        rig.engine.emit(*bufferedEvents(r))
        rig.ui.runPending()
    }

    /** The body editor is created when its component becomes displayable; in a snapshot nothing does, so ask for it. */
    private fun withEditor(section: RequestSection) {
        val editor = section.formForTest().bodyEditor
        runCatching { editor.addNotify() }
        editor.editor?.let { it.component.setSize(600, 200) }
    }

    private fun pump() {
        UIUtil.dispatchAllInvocationEvents()
        Thread.sleep(PUMP_MS)
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun wait(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 60)

    private fun refreshToolbars(root: Component) {
        if (root is ActionToolbarImpl) root.updateActionsImmediately()
        if (root is Container) root.components.forEach { refreshToolbars(it) }
    }

    private fun emitSection(scene: String, theme: ThemeSample, section: RequestSection, width: Int, height: Int) {
        section.component.setSize(width, height)
        section.applyOrientation()
        emit(scene, theme, section.component, width, height)
    }

    private fun emit(scene: String, theme: ThemeSample, component: JComponent, width: Int, height: Int) {
        refreshToolbars(component)
        val image = Snapshots.render(component, width, height, SCALE)
        Snapshots.write(image, "workspace-$scene-${theme.id}")
        written++
        assertTrue("$scene-${theme.id} is blank", Snapshots.inkPixels(image) > INK_MIN)
        notes += "$scene-${theme.id}: ${image.width}x${image.height}"
    }

    private companion object {
        const val SCENES = 10
        const val W = 1000
        const val H = 640
        const val H_SHORT = 360
        const val W_NARROW = 520
        const val H_NARROW = 900
        const val SCALE = 1.5
        const val INK_MIN = 300
        const val PUMP_MS = 150L
    }
}
