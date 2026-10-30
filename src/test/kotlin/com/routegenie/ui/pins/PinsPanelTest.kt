package com.routegenie.ui.pins

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.RouteGenieBundle
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SourceRef
import com.routegenie.pinside.PinsService
import com.routegenie.pins.Pin
import com.routegenie.pins.PinProblem
import com.routegenie.pins.PinsCodec
import java.nio.file.Files
import java.nio.file.Path

/** The pins view: the list with used/unused status, editing a value in place with the strip's validation, deleting, file messages. */
class PinsPanelTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var service: PinsService
    private lateinit var owner: com.intellij.openapi.Disposable
    private var snapshot = RouteSnapshot.EMPTY

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("pins-panel")
        service = PinsService.getInstance(project)
        service.baseDirOverride = dir
        service.reload()
        owner = Disposer.newDisposable("pins panel test")
        snapshot = RouteSnapshot.EMPTY
    }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            service.baseDirOverride = null
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val file: Path get() = service.filePath()!!

    private fun panel() = PinsPanel(project, owner, service, background = { it.run() }, snapshotOf = { snapshot })

    private fun endpointUsing(vararg pins: String, framework: Framework = Framework.FASTAPI) = Endpoint(
        HttpMethod.GET, "/api/v1/x", framework, Handler("x", "m.x"), SourceRef("m.py", 0, 1), pins = pins.toList(),
    )

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 30)

    private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

    fun testAnEmptyViewSaysHowPinsAppear() {
        val panel = panel()
        assertEquals(0, panel.model.rowCount)
        assertEquals(msg("pins.view.empty"), panel.table.emptyText.text)
    }

    fun testEveryPinIsListedWithItsValueNoteAndUsage() {
        service.set(Pin("settings.A", "/a", "default in c.py:3"), true)
        service.set(Pin("settings.B", "/b"), true)
        snapshot = RouteSnapshot(1, listOf(endpointUsing("settings.A"), endpointUsing("settings.A")))
        val panel = panel()
        assertEquals(2, panel.model.rowCount)
        assertEquals(listOf("settings.A", "settings.B"), (0 until 2).map { panel.model.getValueAt(it, 0) })
        assertEquals("/a", panel.model.getValueAt(0, 1))
        assertEquals("Used by 2 routes", panel.model.getValueAt(0, 2))
        assertEquals("default in c.py:3", panel.model.getValueAt(0, 3))
        assertEquals(msg("pins.view.status.unused"), panel.model.getValueAt(1, 2))
    }

    fun testOnlyTheValueColumnCanBeEdited() {
        service.set(Pin("settings.A", "/a"), true)
        val panel = panel()
        assertEquals(listOf(false, true, false, false), (0..3).map { panel.model.isCellEditable(0, it) })
    }

    fun testEditingAValueSavesItAndRefreshesTheRow() {
        service.set(Pin("settings.A", "/a"), true)
        snapshot = RouteSnapshot(1, listOf(endpointUsing("settings.A")))
        val panel = panel()
        panel.model.setValueAt("/changed", 0, 1)
        UIUtil.dispatchAllInvocationEvents()
        waitFor("value saved") { service.env.values["settings.A"] == "/changed" }
        waitFor("row refreshed") { panel.model.getValueAt(0, 1) == "/changed" }
        assertFalse(panel.error.isVisible)
    }

    fun testAnInvalidEditIsRefusedWithTheStripsMessageAndTheOldValueStays() {
        service.set(Pin("settings.A", "/a"), true)
        snapshot = RouteSnapshot(1, listOf(endpointUsing("settings.A")))
        val panel = panel()
        panel.model.setValueAt("no-slash", 0, 1)
        assertTrue(panel.error.isVisible)
        assertEquals(msg(PinProblem.VALUE_SLASH_START.bundleKey), panel.error.text)
        assertEquals("/a", service.env.values["settings.A"])
        panel.model.setValueAt("/a b", 0, 1)
        assertEquals(msg(PinProblem.VALUE_BLANK_CHARS.bundleKey), panel.error.text)
    }

    fun testADjangoPinMayEndWithASlashAndNotStartWithOne() {
        service.set(Pin("settings.API_PREFIX", "api/"), requireLeadingSlash = false)
        snapshot = RouteSnapshot(1, listOf(endpointUsing("settings.API_PREFIX", framework = Framework.DJANGO)))
        val panel = panel()
        panel.model.setValueAt("v2/", 0, 1)
        waitFor("value saved") { service.env.values["settings.API_PREFIX"] == "v2/" }
        assertFalse(panel.error.isVisible)
    }

    fun testDeletingARemovesThePinFromTheFile() {
        service.set(Pin("settings.A", "/a"), true)
        service.set(Pin("settings.B", "/b"), true)
        val panel = panel()
        assertFalse(panel.deleteButton.isEnabled)
        panel.table.setRowSelectionInterval(0, 0)
        assertTrue(panel.deleteButton.isEnabled)
        panel.deleteButton.doClick()
        waitFor("deleted") { service.env.values.keys == setOf("settings.B") }
        assertEquals(listOf("settings.B"), PinsCodec.read(String(Files.readAllBytes(file), Charsets.UTF_8)).pins.map { it.expression })
        waitFor("row gone") { panel.model.rowCount == 1 }
    }

    fun testAPinTheCodeNoLongerUsesIsShownAsUnusedAndCanBeDeleted() {
        service.set(Pin("settings.GONE", "/gone"), true)
        snapshot = RouteSnapshot(1, listOf(endpointUsing("settings.OTHER")))
        val panel = panel()
        assertEquals(msg("pins.view.status.unused"), panel.model.getValueAt(0, 2))
        panel.table.setRowSelectionInterval(0, 0)
        panel.deleteButton.doClick()
        waitFor("deleted") { service.env.values.isEmpty() }
    }

    fun testABrokenFileIsExplainedAtTheTopAndDoesNotCrashTheView() {
        Files.createDirectories(file.parent)
        Files.write(file, "{ nope".toByteArray())
        service.reload()
        val panel = panel()
        assertEquals(0, panel.model.rowCount)
        val labels = UIUtil.findComponentsOfType(panel, com.intellij.ui.components.JBLabel::class.java)
        assertTrue(labels.any { it.isVisible && it.text.contains("could not be read") })
    }

    fun testTheViewFollowsExternalChangesToThePins() {
        val panel = panel()
        assertEquals(0, panel.model.rowCount)
        service.set(Pin("settings.A", "/a"), true)
        waitFor("row appears") { panel.model.rowCount == 1 }
    }

    fun testDisposingTheViewStopsItsUpdates() {
        val panel = panel()
        Disposer.dispose(owner)
        owner = Disposer.newDisposable("again")
        service.set(Pin("settings.A", "/a"), true)
        UIUtil.dispatchAllInvocationEvents()
        Thread.sleep(100)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(0, panel.model.rowCount)
    }
}
