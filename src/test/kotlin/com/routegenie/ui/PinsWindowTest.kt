package com.routegenie.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.pinside.PinsService
import com.routegenie.pins.Pin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The whole path in the real window: strip, one click, file, refresh, endpoints; and the "taken from your pin" line in the details. */
class PinsWindowTest : BasePlatformTestCase() {
    private val owner = Disposer.newDisposable("pins window test")
    private lateinit var dir: Path
    private lateinit var service: PinsService

    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("pins-window")
        service = PinsService.getInstance(project)
        service.baseDirOverride = dir
        service.reload()
    }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            service.baseDirOverride = null
            service.dismissed.value.toList().forEach { service.undismiss(it) }
            service.reload()
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 90)

    fun testTheTemplateShowsAStripUntilTheUserClicksUseAndThenListsItsEndpoints() {
        myFixture.copyDirectoryToProject("pins/fastapi_template", "")
        val panel = RouteGenieWindowContent(project, owner).panel
        panel.setShown(true)
        val bar = panel.pinsBarForTest()
        waitFor("a strip for the template's prefix") { bar.strips().any { it.setting.expression == "settings.API_V1_STR" } }
        assertTrue(bar.isVisible)
        val strip = bar.strips().first { it.setting.expression == "settings.API_V1_STR" }
        assertTrue(strip.message.text, strip.message.text.contains("11 routes are hidden"))
        assertEquals("Use /api/v1", strip.useLink.text)
        assertTrue("nothing is pinned before the click", service.env.values.isEmpty())
        assertTrue(RouteDiscoveryService.getInstance(project).snapshot.value.endpoints.none { it.path.startsWith("/api/v1/") })

        strip.useLink.doClick()

        waitFor("endpoints behind the pin") {
            RouteDiscoveryService.getInstance(project).snapshot.value.endpoints.count { it.path.startsWith("/api/v1/") } == 11
        }
        waitFor("strip gone") { bar.strips().none { it.setting.expression == "settings.API_V1_STR" } }
        assertEquals(mapOf("settings.API_V1_STR" to "/api/v1"), service.env.values)
        assertTrue(Files.isRegularFile(service.filePath()!!))
    }

    fun testRemovingThePinHidesTheRoutesAgainAndTheStripReturns() {
        myFixture.copyDirectoryToProject("pins/fastapi_template", "")
        service.set(Pin("settings.API_V1_STR", "/api/v1"), true)
        val panel = RouteGenieWindowContent(project, owner).panel
        panel.setShown(true)
        waitFor("pinned endpoints") {
            RouteDiscoveryService.getInstance(project).snapshot.value.endpoints.count { it.path.startsWith("/api/v1/") } == 11
        }
        service.remove("settings.API_V1_STR")
        waitFor("routes hidden again") {
            RouteDiscoveryService.getInstance(project).snapshot.value.endpoints.none { it.path.startsWith("/api/v1/") }
        }
        waitFor("strip back") { panel.pinsBarForTest().strips().any { it.setting.expression == "settings.API_V1_STR" } }
    }

    private fun endpointWithPin() = Endpoint(
        HttpMethod.GET, "/api/v1/users/", Framework.FASTAPI, Handler("read_users", "app.users.read_users"),
        SourceRef("app/users.py", 0, 1), pins = listOf("settings.API_V1_STR"),
    )

    fun testTheDetailsSayWhichPinThePathWasBuiltFromAndOfferToChangeIt() {
        service.set(Pin("settings.API_V1_STR", "/api/v1"), true)
        val section = OverviewSection(project)
        section.show(endpointWithPin())
        val labels = UIUtil.findComponentsOfType(section.component, JBLabel::class.java)
        assertTrue(labels.any { it.text == "Prefix taken from your pin settings.API_V1_STR = /api/v1" })
        val links = UIUtil.findComponentsOfType(section.component, ActionLink::class.java)
        assertTrue(links.any { it.text == "Change" })
    }

    fun testAnEndpointWithoutPinsHasNoPinLine() {
        val section = OverviewSection(project)
        section.show(endpointWithPin().copy(pins = emptyList()))
        val labels = UIUtil.findComponentsOfType(section.component, JBLabel::class.java)
        assertTrue(labels.none { it.text.orEmpty().startsWith("Prefix taken from your pin") })
        assertTrue(UIUtil.findComponentsOfType(section.component, ActionLink::class.java).none { it.text == "Change" })
    }

    fun testALongerPinListShowsOneLinePerPin() {
        service.set(Pin("settings.ROOT", "/r"), true)
        service.set(Pin("settings.VERSION", "/v1"), true)
        val section = OverviewSection(project)
        section.show(endpointWithPin().copy(pins = listOf("settings.ROOT", "settings.VERSION")))
        val texts = UIUtil.findComponentsOfType(section.component, JBLabel::class.java).map { it.text.orEmpty() }
        assertTrue(texts.contains("Prefix taken from your pin settings.ROOT = /r"))
        assertTrue(texts.contains("Prefix taken from your pin settings.VERSION = /v1"))
    }
}
