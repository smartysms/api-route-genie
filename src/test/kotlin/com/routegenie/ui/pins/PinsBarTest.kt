package com.routegenie.ui.pins

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.model.Framework
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SettingSuggestion
import com.routegenie.model.SourceRef
import com.routegenie.model.SuggestionKind
import com.routegenie.model.UnresolvedSetting
import com.routegenie.pinside.PinsService
import com.routegenie.pins.Pin
import com.routegenie.pins.PinProblem
import com.routegenie.pins.PinsCodec
import java.nio.file.Files
import java.nio.file.Path

/** The strip that asks once: use the default, enter another value, not now, validation, and the collapsed line for the rest. */
class PinsBarTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var service: PinsService
    private lateinit var owner: com.intellij.openapi.Disposable
    private var opened = 0

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("pins-bar")
        service = PinsService.getInstance(project)
        service.baseDirOverride = dir
        service.reload()
        owner = Disposer.newDisposable("pins bar test")
        opened = 0
    }

    override fun tearDown() {
        try {
            Disposer.dispose(owner)
            service.baseDirOverride = null
            service.dismissed.value.toList().forEach { service.undismiss(it) }
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val file: Path get() = service.filePath()!!

    private fun bar() = PinsBar(project, owner, service, background = { it.run() }, openPins = { opened++ })

    private fun setting(
        expression: String = "settings.API_V1_STR",
        routes: Int = 12,
        suggestion: SettingSuggestion? = SettingSuggestion("/api/v1", SuggestionKind.CLASS_DEFAULT, SourceRef("app/core/config.py", 120, 7)),
        framework: Framework = Framework.FASTAPI,
    ) = UnresolvedSetting(expression, SourceRef("app/main.py", 300, 30), routes, suggestion, framework)

    private fun show(bar: PinsBar, vararg settings: UnresolvedSetting) {
        bar.update(RouteSnapshot(1, emptyList(), emptyList(), settings.toList()))
        UIUtil.dispatchAllInvocationEvents()
    }

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 30)

    private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

    // --- when it appears -------------------------------------------------------------------------------------------

    fun testNothingIsShownWhenNoSettingHidesRoutes() {
        val bar = bar()
        show(bar)
        assertFalse(bar.isVisible)
        assertTrue(bar.strips().isEmpty())
        assertNull(bar.hidden())
    }

    fun testOneStripSaysHowManyRoutesAreHiddenAndByWhat() {
        val bar = bar()
        show(bar, setting())
        assertTrue(bar.isVisible)
        val strip = bar.strips().single()
        assertTrue(strip.message.text, strip.message.text.contains("12 routes are hidden because their URL prefix comes from"))
        assertTrue(strip.message.text.contains("<code>settings.API_V1_STR</code>"))
    }

    fun testASingleRouteUsesTheSingularText() {
        val bar = bar()
        show(bar, setting(routes = 1))
        assertTrue(bar.strips().single().message.text.contains("1 route is hidden because its URL prefix comes from"))
    }

    fun testTheDefaultIsOfferedWithWhereItIsWritten() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        assertTrue(strip.useLink.isVisible)
        assertEquals("Use /api/v1", strip.useLink.text)
        assertTrue(strip.origin.text, strip.origin.text.contains("the default written in app/core/config.py:7"))
        assertEquals("Enter another value", strip.otherLink.text)
        assertEquals("Not now", strip.laterLink.text)
    }

    fun testWithoutADefaultThereIsNoUseLink() {
        val bar = bar()
        show(bar, setting(suggestion = null))
        val strip = bar.strips().single()
        assertFalse(strip.useLink.isVisible)
        assertFalse(strip.origin.isVisible)
    }

    fun testAnEmptyDefaultIsShownAsEmptyNotAsNothing() {
        val bar = bar()
        show(bar, setting(suggestion = SettingSuggestion("", SuggestionKind.CLASS_DEFAULT, SourceRef("c.py", 0, 1))))
        assertEquals("Use " + msg("pins.value.empty"), bar.strips().single().useLink.text)
    }

    fun testAnEnvironmentDefaultNamesItsKind() {
        val bar = bar()
        show(bar, setting("os.environ[\"P\"]", suggestion = SettingSuggestion("/p", SuggestionKind.ENV_DEFAULT, SourceRef("m.py", 5, 2))))
        assertTrue(bar.strips().single().origin.text.contains("os.environ.get"))
    }

    fun testASettingWithNoRoutesBelowIsNotShown() {
        val bar = bar()
        show(bar, setting(routes = 0))
        assertFalse(bar.isVisible)
    }

    fun testNothingIsAppliedWithoutAClick() {
        val bar = bar()
        show(bar, setting())
        UIUtil.dispatchAllInvocationEvents()
        assertFalse(Files.exists(file))
        assertTrue(service.env.values.isEmpty())
    }

    // --- Use ----------------------------------------------------------------------------------------------------

    fun testUseWritesThePinInOneClickAndTheStripGoesAway() {
        val bar = bar()
        show(bar, setting())
        bar.strips().single().useLink.doClick()
        waitFor("strip removed") { bar.strips().isEmpty() }
        assertFalse(bar.isVisible)
        val pin = PinsCodec.read(String(Files.readAllBytes(file), Charsets.UTF_8)).pins.single()
        assertEquals("settings.API_V1_STR", pin.expression)
        assertEquals("/api/v1", pin.value)
        assertTrue("the file says where the value came from: ${pin.note}", pin.note.contains("app/core/config.py:7"))
        assertEquals(mapOf("settings.API_V1_STR" to "/api/v1"), service.env.values)
    }

    fun testUseSavesAnEmptyDefaultAsAnEmptyPin() {
        val bar = bar()
        show(bar, setting("settings.BASE_PATH", suggestion = SettingSuggestion("", SuggestionKind.CLASS_DEFAULT, SourceRef("c.py", 0, 1))))
        bar.strips().single().useLink.doClick()
        waitFor("saved") { service.env.values.containsKey("settings.BASE_PATH") }
        assertEquals("", service.env.values["settings.BASE_PATH"])
    }

    fun testAnInvalidDefaultOpensTheEditorInsteadOfSavingIt() {
        val bar = bar()
        show(bar, setting(suggestion = SettingSuggestion("/api/", SuggestionKind.CLASS_DEFAULT, SourceRef("c.py", 0, 1))))
        val strip = bar.strips().single()
        strip.useLink.doClick()
        assertFalse(Files.exists(file))
        assertTrue(strip.error.isVisible)
        assertEquals(msg(PinProblem.VALUE_SLASH_END.bundleKey), strip.error.text)
        assertEquals("/api/", strip.field.text)
    }

    fun testADjangoDefaultWithoutALeadingSlashIsFine() {
        val bar = bar()
        show(bar, setting("settings.API_PREFIX", framework = Framework.DJANGO, suggestion = SettingSuggestion("api/", SuggestionKind.CLASS_DEFAULT, SourceRef("c.py", 0, 1))))
        bar.strips().single().useLink.doClick()
        waitFor("saved") { service.env.values["settings.API_PREFIX"] == "api/" }
    }

    // --- Enter another value --------------------------------------------------------------------------------------------

    fun testEnterAnotherValueShowsAFieldWithTheDefaultInIt() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        assertEquals("/api/v1", strip.field.text)
        assertTrue(strip.saveButton.isShowing || strip.saveButton.parent.isVisible)
    }

    fun testAnotherValueIsSavedWhenItIsValid() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        strip.field.text = "/internal/api"
        strip.saveButton.doClick()
        waitFor("saved") { service.env.values["settings.API_V1_STR"] == "/internal/api" }
        waitFor("strip removed") { bar.strips().isEmpty() }
        val pin = service.snapshot.value.pins.single()
        assertEquals("a value other than the default has no origin note", "", pin.note)
    }

    fun testPressingEnterInTheFieldSaves() {
        val bar = bar()
        show(bar, setting(suggestion = null))
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        strip.field.text = "/enter"
        strip.field.postActionEvent()
        waitFor("saved") { service.env.values["settings.API_V1_STR"] == "/enter" }
    }

    fun testValidationErrorsAreShownInlineAndNothingIsSaved() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        val cases = mapOf(
            "api" to PinProblem.VALUE_SLASH_START,
            "/api/" to PinProblem.VALUE_SLASH_END,
            "/a b" to PinProblem.VALUE_BLANK_CHARS,
            "/a/../b" to PinProblem.VALUE_DOTS,
            "/a?x" to PinProblem.VALUE_CHARS,
            "/" + "a".repeat(Limits.PIN_MAX_VALUE_CHARS) to PinProblem.VALUE_TOO_LONG,
        )
        for ((text, problem) in cases) {
            strip.field.text = text
            strip.saveButton.doClick()
            assertTrue("$text must show an error", strip.error.isVisible)
            assertEquals(text, msg(problem.bundleKey), strip.error.text)
        }
        assertFalse(Files.exists(file))
        assertEquals(1, bar.strips().size)
    }

    fun testAFixedValueClearsTheError() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        strip.field.text = "bad"
        strip.saveButton.doClick()
        assertTrue(strip.error.isVisible)
        strip.field.text = "/good"
        strip.saveButton.doClick()
        waitFor("saved") { service.env.values["settings.API_V1_STR"] == "/good" }
        assertFalse(strip.error.isVisible)
    }

    fun testCancelHidesTheEditorAndKeepsTheStrip() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        strip.field.text = "bad"
        strip.saveButton.doClick()
        strip.cancelLink.doClick()
        assertFalse(strip.error.isVisible)
        assertEquals(1, bar.strips().size)
        assertFalse(Files.exists(file))
    }

    fun testAnEmptyValueTypedByHandIsAValidRootPrefix() {
        val bar = bar()
        show(bar, setting())
        val strip = bar.strips().single()
        strip.otherLink.doClick()
        strip.field.text = ""
        strip.saveButton.doClick()
        waitFor("saved") { service.env.values.containsKey("settings.API_V1_STR") }
        assertEquals("", service.env.values["settings.API_V1_STR"])
    }

    // --- Not now ------------------------------------------------------------------------------------------------------

    fun testNotNowHidesTheStripForThisSessionOnlyAndWritesNothing() {
        val bar = bar()
        show(bar, setting())
        bar.strips().single().laterLink.doClick()
        waitFor("strip hidden") { bar.strips().isEmpty() }
        assertFalse(Files.exists(file))
        assertEquals(setOf("settings.API_V1_STR"), service.dismissed.value)
    }

    fun testTheOmittedRoutesStayListedInACollapsedLine() {
        val bar = bar()
        show(bar, setting())
        bar.strips().single().laterLink.doClick()
        waitFor("hidden line") { bar.hidden() != null }
        val line = bar.hidden()!!
        assertEquals("12 routes are hidden by unresolved URL prefixes", line.summary.text)
        assertTrue("collapsed: no detail lines yet", line.lines.isEmpty())
        assertEquals(msg("pins.hidden.show"), line.toggle.text)
        assertTrue(bar.isVisible)
    }

    fun testExpandingTheLineListsEachSettingWithItsRoutesAndPlace() {
        val bar = bar()
        service.dismiss("settings.API_V1_STR")
        show(bar, setting(), setting("settings.OTHER", routes = 3))
        bar.setHiddenExpandedForTest(true)
        val line = bar.hidden()!!
        assertEquals(1, line.lines.size)
        assertEquals("settings.API_V1_STR: 12 routes, used at app/main.py:30", line.lines.single().text)
    }

    fun testAnotherSessionStartsAskingAgain() {
        val bar = bar()
        service.dismiss("settings.API_V1_STR")
        show(bar, setting())
        assertTrue(bar.strips().isEmpty())
        service.undismiss("settings.API_V1_STR")
        waitFor("strip back") { bar.strips().size == 1 }
    }

    // --- several settings -----------------------------------------------------------------------------------------------

    fun testOneStripPerSettingMostRoutesFirstAndBounded() {
        val bar = bar()
        val all = (1..Limits.PINS_MAX_STRIPS + 2).map { setting("settings.S$it", routes = it) }.sortedByDescending { it.routeCount }
        show(bar, *all.toTypedArray())
        assertEquals(Limits.PINS_MAX_STRIPS, bar.strips().size)
        assertEquals(all.take(Limits.PINS_MAX_STRIPS).map { it.expression }, bar.strips().map { it.setting.expression })
        val rest = bar.hidden()!!
        assertEquals(all.drop(Limits.PINS_MAX_STRIPS).sumOf { it.routeCount }, rest.total)
    }

    fun testTwoSettingsOfOnePrefixAreBothShown() {
        val bar = bar()
        show(bar, setting("settings.ROOT", routes = 4), setting("settings.VERSION", routes = 4))
        assertEquals(listOf("settings.ROOT", "settings.VERSION"), bar.strips().map { it.setting.expression })
    }

    fun testPinningTheFirstLeavesTheSecondStrip() {
        val bar = bar()
        show(bar, setting("settings.ROOT", routes = 4), setting("settings.VERSION", routes = 4, suggestion = null))
        bar.strips().first { it.setting.expression == "settings.ROOT" }.useLink.doClick()
        waitFor("one strip left") { bar.strips().map { it.setting.expression } == listOf("settings.VERSION") }
    }

    fun testASettingThatIsAlreadyPinnedShowsNoStrip() {
        service.set(Pin("settings.API_V1_STR", "/api/v1"), true)
        val bar = bar()
        show(bar, setting())
        assertTrue(bar.strips().isEmpty())
        assertFalse(bar.isVisible)
    }

    // --- secrets and file problems -------------------------------------------------------------------------------------

    fun testASecretLookingSettingGetsNoStripAndIsExplainedInTheLine() {
        val bar = bar()
        show(bar, setting("settings.SECRET_PREFIX", routes = 2))
        assertTrue(bar.strips().isEmpty())
        bar.setHiddenExpandedForTest(true)
        assertTrue(bar.hidden()!!.lines.single().text.contains("looks like a secret"))
    }

    fun testABrokenPinsFileIsOneClearMessageAndUseDoesNotOverwriteIt() {
        Files.createDirectories(file.parent)
        Files.write(file, "{ nope".toByteArray())
        service.reload()
        val bar = bar()
        show(bar, setting())
        assertTrue(bar.isVisible)
        assertEquals("the message strip and the setting strip", 2, bar.componentCount)
        val strip = bar.strips().single()
        strip.useLink.doClick()
        waitFor("error shown") { strip.error.isVisible }
        assertTrue(strip.error.text, strip.error.text.contains("could not be read"))
        assertEquals("{ nope", String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    fun testTheProblemMessageShowsEvenWithoutAnyHiddenRoutes() {
        Files.createDirectories(file.parent)
        Files.write(file, "{ nope".toByteArray())
        service.reload()
        val bar = bar()
        show(bar)
        assertTrue(bar.isVisible)
    }

    fun testIgnoredEntriesAreMentioned() {
        Files.createDirectories(file.parent)
        Files.write(file, """{"pins": {"settings.A": "/a", "settings.SECRET_KEY": "/x"}}""".toByteArray())
        service.reload()
        val bar = bar()
        show(bar)
        assertTrue(bar.isVisible)
    }

    // --- life cycle ----------------------------------------------------------------------------------------------------

    fun testDisposedBarsIgnoreLateUpdates() {
        val bar = bar()
        Disposer.dispose(owner)
        owner = Disposer.newDisposable("again")
        show(bar, setting())
        assertTrue(bar.strips().isEmpty())
    }
}
