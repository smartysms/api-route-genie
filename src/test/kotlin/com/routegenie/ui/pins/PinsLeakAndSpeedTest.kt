package com.routegenie.ui.pins

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.routegenie.Limits
import com.routegenie.model.Framework
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SettingSuggestion
import com.routegenie.model.SourceRef
import com.routegenie.model.SuggestionKind
import com.routegenie.model.UnresolvedSetting
import com.routegenie.pinside.PinsService
import com.routegenie.pins.Pin
import com.routegenie.pins.PinsCodec
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.PlatformLeaks
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path

/** Pins UI and file code: created and disposed over and over without leaks, and within the speed budgets of Limits. */
class PinsLeakAndSpeedTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var service: PinsService

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("pins-leak")
        service = PinsService.getInstance(project)
        service.baseDirOverride = dir
        service.reload()
    }

    override fun tearDown() {
        try {
            service.baseDirOverride = null
            service.dismissed.value.toList().forEach { service.undismiss(it) }
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun settle() = UIUtil.dispatchAllInvocationEvents()

    private fun setting(i: Int) = UnresolvedSetting(
        "settings.S$i", SourceRef("app/main.py", 10, 2), 100 - i,
        SettingSuggestion("/s$i", SuggestionKind.CLASS_DEFAULT, SourceRef("app/config.py", 5, 1)), Framework.FASTAPI,
    )

    /** Builds a bar and a view, uses both, disposes the owner; returns weak references only (see docs/ARCHITECTURE.md, "Leak tests"). */
    private fun scenario(): List<Pair<String, WeakReference<*>>> {
        val owner = Disposer.newDisposable("pins leak")
        val bar = PinsBar(project, owner, service, background = { it.run() }, openPins = {})
        bar.update(RouteSnapshot(1, emptyList(), emptyList(), (1..5).map(::setting)))
        bar.strips().first().otherLink.doClick()
        bar.strips().last().laterLink.doClick()
        settle()
        val panel = PinsPanel(project, owner, service, background = { it.run() }, snapshotOf = { RouteSnapshot.EMPTY })
        val refs = listOf("PinsBar" to WeakReference(bar), "PinsPanel" to WeakReference(panel), "pins table" to WeakReference(panel.table))
        Disposer.dispose(owner)
        service.dismissed.value.toList().forEach { service.undismiss(it) }
        settle()
        return refs
    }

    fun testABarAndAViewAreCollectedAfterTheirOwnerIsDisposed() {
        scenario().forEach { (name, ref) -> LeakProbe.assertCollected("$name after dispose", ref, timeoutMs = LEAK_WAIT_MS) }
    }

    fun testCreatingAndDisposingManyTimesLeavesNothingBehind() {
        PlatformLeaks.assertScenarioClean("pins bar and view") { repeat(6) { scenario() } }
    }

    fun testHeapDoesNotGrowOverManyCycles() {
        LeakProbe.assertNoHeapGrowth("pins cycles", maxGrowthBytes = HEAP_GROWTH_LIMIT, warmups = 8, iterations = 40) { scenario() }
    }

    private val kept = ArrayList<Any>()

    fun testTheCollectabilityCheckCatchesABarThatIsKept() {
        val owner: Disposable = Disposer.newDisposable("kept")
        val bar = PinsBar(project, owner, service, background = { it.run() }, openPins = {})
        kept += bar
        val ref = WeakReference(bar)
        Disposer.dispose(owner)
        settle()
        try {
            LeakProbe.assertCollected("kept bar", ref, timeoutMs = 1_500)
            fail("expected the kept bar to be reported as a leak")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("kept bar"))
        }
        kept.clear()
    }

    fun testReadingAFileOfFiveHundredPinsIsWithinTheBudget() {
        val pins = (0 until Limits.MAX_PINS).map { Pin("settings.PREFIX_$it", "/prefix/$it", "default written in app/config.py:$it") }
        val text = PinsCodec.write(pins)
        assertTrue(text.length < Limits.PINS_FILE_MAX_BYTES)
        PerfBudget.assertWithin("pins-parse-500", Limits.BUDGET_PINS_PARSE_MS) {
            assertEquals(Limits.MAX_PINS, PinsCodec.read(text).pins.size)
        }
    }

    fun testShowingTheStripsOfFiftySettingsIsAUiStepWithinTheBudget() {
        val owner = Disposer.newDisposable("pins speed")
        try {
            val bar = PinsBar(project, owner, service, background = { it.run() }, openPins = {})
            val first = RouteSnapshot(1, emptyList(), emptyList(), (1..Limits.MAX_UNRESOLVED_SETTINGS).map(::setting))
            val second = RouteSnapshot(2, emptyList(), emptyList(), (1..Limits.MAX_UNRESOLVED_SETTINGS).map { setting(it + 1) })
            var flip = false
            PerfBudget.assertWithin("pins-bar-update", Limits.BUDGET_UI_STEP_MS, warmups = 5, runs = 15) {
                flip = !flip
                bar.update(if (flip) first else second)
            }
        } finally {
            Disposer.dispose(owner)
        }
    }

    private companion object {
        const val LEAK_WAIT_MS = 15_000L
        const val HEAP_GROWTH_LIMIT = 3L * 1024 * 1024
    }
}
