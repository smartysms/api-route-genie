package com.routegenie.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The platform helpers must be able to fail: each has a deliberately leaking or slow scenario next to the passing one. */
class PlatformHarnessTest : BasePlatformTestCase() {
    private class Leaky : Disposable {
        override fun dispose() = Unit
    }

    private class Holder {
        val items = ArrayList<Any>()
    }

    private inline fun expectFailure(fragment: String, body: () -> Unit) {
        try {
            body()
        } catch (expected: AssertionError) {
            assertTrue("unexpected message: ${expected.message}", expected.message!!.contains(fragment))
            return
        }
        fail("expected an AssertionError containing '$fragment'")
    }

    fun testEmptyHolderPasses() {
        PlatformLeaks.assertNoneReachableFrom("clean", Holder(), Leaky::class.java)
    }

    fun testObjectKeptByHolderIsReported() {
        val holder = Holder().apply { items.add(Leaky()) }
        expectFailure("leak") { PlatformLeaks.assertNoneReachableFrom("held", holder, Leaky::class.java) }
    }

    fun testObjectKeptByStaticIsReportedFromAllRoots() {
        staticHolder.add(Leaky())
        try {
            expectFailure("leak") { PlatformLeaks.assertNoneReachable("static", Leaky::class.java) }
        } finally {
            staticHolder.clear()
        }
        PlatformLeaks.assertNoneReachable("static-cleared", Leaky::class.java)
    }

    fun testReleasedEditorScenarioPasses() {
        PlatformLeaks.assertScenarioClean("released editor") {
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(factory.createDocument("x = 1\n"))
            factory.releaseEditor(editor)
        }
    }

    fun testUnreleasedEditorScenarioFails() {
        val factory = EditorFactory.getInstance()
        var created: Editor? = null
        try {
            expectFailure("not released") {
                PlatformLeaks.assertScenarioClean("unreleased editor") {
                    created = factory.createEditor(factory.createDocument("x = 1\n"))
                }
            }
        } finally {
            created?.let { factory.releaseEditor(it) }
        }
    }

    fun testReleasedButReferencedEditorIsReported() {
        val factory = EditorFactory.getInstance()
        val holder = Holder()
        val editor = factory.createEditor(factory.createDocument("x = 1\n"))
        holder.items.add(editor)
        factory.releaseEditor(editor)
        expectFailure("leak") {
            PlatformLeaks.assertNoneReachableFrom("released editor held", holder, Editor::class.java) { it.isDisposed }
        }
    }

    fun testChildDisposedWithParent() {
        val parent = Disposer.newDisposable("parent")
        val child = Leaky()
        Disposer.register(parent, child)
        PlatformLeaks.assertDisposedWithParent("registered", parent, child)
    }

    fun testChildWithoutParentIsReported() {
        val parent = Disposer.newDisposable("parent")
        val orphan = Leaky()
        Disposer.register(testRootDisposable, orphan)
        expectFailure("no parent registration") { PlatformLeaks.assertDisposedWithParent("orphan", parent, orphan) }
    }

    fun testFastBlockStaysWithinBudgetOnEdt() {
        PlatformPerf.assertWithinOnEdt("platform-fast", budgetMs = 200, runs = 5) { Thread.sleep(1) }
    }

    fun testSlowBlockFailsBudgetOnEdt() {
        expectFailure("exceeds the budget") {
            PlatformPerf.assertWithinOnEdt("platform-slow", budgetMs = 5, warmups = 0, runs = 3) { Thread.sleep(40) }
        }
    }

    private companion object {
        val staticHolder = ArrayList<Any>()
    }
}
