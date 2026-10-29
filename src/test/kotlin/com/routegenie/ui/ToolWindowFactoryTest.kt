package com.routegenie.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.routegenie.Limits
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PlatformLeaks
import com.routegenie.testing.PlatformPerf
import java.lang.reflect.Proxy

/**
 * ToolWindowHeadlessManagerImpl registers a mock window and never runs a factory, and a real ToolWindowImpl needs a
 * frame. So the factory is called directly with a ToolWindow stand-in that carries a real ContentManager and a real
 * disposable and rejects every other call: the factory is held to exactly the API it may use.
 * What this cannot show (real stripe button, lazy first-show call by the platform, painting) needs a runIde check.
 */
class ToolWindowFactoryTest : BasePlatformTestCase() {
    private class Window(val disposable: Disposable, val manager: ContentManager, val proxy: ToolWindow)

    private fun newWindow(): Window {
        val disposable = Disposer.newDisposable("test tool window")
        val manager = ContentFactory.getInstance().createContentManager(false, project)
        Disposer.register(disposable, manager)
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ToolWindow::class.java)) { _, method, _ ->
            when (method.name) {
                "getDisposable" -> disposable
                "getContentManager" -> manager
                "getProject" -> project
                "isDisposed" -> Disposer.isDisposed(disposable)
                else -> throw UnsupportedOperationException("factory used ToolWindow.${method.name}")
            }
        } as ToolWindow
        return Window(disposable, manager, proxy)
    }

    private fun open(): Window = newWindow().also { RouteGenieToolWindowFactory().createToolWindowContent(project, it.proxy) }

    fun testCreatesOneContentWithTheEndpointPanel() {
        val window = open()
        try {
            val content = window.manager.getContent(0)!!
            assertTrue(content.component is EndpointsPanel)
            assertEquals(1, window.manager.contentCount)
            assertNotNull("content needs its disposer so closing it releases the owner", content.disposer)
        } finally {
            Disposer.dispose(window.disposable)
        }
    }

    fun testContentOwnerIsDisposedWithTheToolWindow() {
        val window = open()
        val owner = window.manager.getContent(0)!!.disposer!!
        assertFalse(Disposer.isDisposed(owner))
        Disposer.dispose(window.disposable)
        assertTrue("owner must hang below toolWindow.disposable", Disposer.isDisposed(owner))
        assertTrue(window.manager.isDisposed)
    }

    fun testFactoryIsLazyAndDoesNothingUntilCalled() {
        val before = Disposer.newDisposable("probe")
        val factory = RouteGenieToolWindowFactory()
        // Constructing the factory must not touch the tool window, project or any service.
        assertNotNull(factory)
        Disposer.dispose(before)
    }

    fun testContentCreationStaysWithinBudget() {
        val result = PlatformPerf.assertWithinOnEdt("tool-window-open", Limits.BUDGET_WINDOW_OPEN_MS) {
            Disposer.dispose(open().disposable)
        }
        assertTrue(result.medianMs <= Limits.BUDGET_WINDOW_OPEN_MS)
    }

    fun testNothingIsReachableAfterCycles() {
        repeat(20) { Disposer.dispose(open().disposable) }
        PlatformLeaks.assertNoneReachable("window content owners", RouteGenieWindowContent::class.java)
        PlatformLeaks.assertNoneReachable("window panels", EndpointsPanel::class.java)
    }

    fun testLeakCheckCatchesAWindowThatWasKept() {
        val kept = ArrayList<Any>()
        val window = open()
        kept.add(window.manager.getContent(0)!!.disposer!!)
        Disposer.dispose(window.disposable)
        try {
            PlatformLeaks.assertNoneReachableFrom("kept owner", kept, RouteGenieWindowContent::class.java) { Disposer.isDisposed(it) }
            fail("expected the kept owner to be reported")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("kept owner"))
        }
    }
}
