package com.routegenie.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LeakHunter
import org.junit.Assert.fail
import java.util.function.Predicate

/**
 * Platform-level leak assertions on top of the IDE's own LeakHunter (build 262: static checkLeak/allRoots) and Disposer.
 *
 * LeakHunter walks the heap from the static roots (or from an explicit root) and fails when an instance of the suspect
 * class is still reachable. "Disposed but still reachable" is the typical plugin leak: a listener, cache, coroutine or
 * static keeps a closed project, editor or tool window alive. Each check below has a test that makes it fail on purpose
 * (PlatformHarnessTest), otherwise a green result would prove nothing.
 *
 * Run these from a platform test after the scenario released its objects; they are slow (heap walk), so call them once
 * per scenario, not per iteration.
 */
object PlatformLeaks {
    /** Fails when any instance of [type] that satisfies [isLeak] is reachable from the static roots. */
    fun <T : Any> assertNoneReachable(label: String, type: Class<T>, isLeak: (T) -> Boolean = { true }) {
        check(label) { LeakHunter.checkLeak(LeakHunter.allRoots(), type, Predicate { isLeak(it) }) }
    }

    /** Same as [assertNoneReachable] but searches only below [root]; used to prove a specific holder is clean or dirty. */
    fun <T : Any> assertNoneReachableFrom(label: String, root: Any, type: Class<T>, isLeak: (T) -> Boolean = { true }) {
        check(label) { LeakHunter.checkLeak(root, type, Predicate { isLeak(it) }) }
    }

    /** A disposed Project that something still references means the project (and its indexes and PSI) cannot be freed. */
    fun assertNoDisposedProjectReachable(label: String) =
        assertNoneReachable(label, Project::class.java) { it.isDisposed }

    /** A released editor that something still references keeps its document and highlighters alive. */
    fun assertNoDisposedEditorReachable(label: String) =
        assertNoneReachable(label, Editor::class.java) { it.isDisposed }

    /**
     * Runs [block] and then asserts the platform objects it created were released: no editor is left registered, and no
     * disposed project or editor is still reachable. The light test project itself is alive during a test and is not
     * flagged because it is not disposed.
     */
    fun assertScenarioClean(label: String, block: () -> Unit) {
        val factory = EditorFactory.getInstance()
        val before = factory.allEditors.toSet()
        block()
        val left = factory.allEditors.filter { it !in before }
        if (left.isNotEmpty()) fail("$label: ${left.size} editor(s) were created and not released")
        assertNoDisposedEditorReachable(label)
        assertNoDisposedProjectReachable(label)
    }

    /** Disposing [parent] must dispose [child]; proves the child was registered below the parent (or is gone already). */
    fun assertDisposedWithParent(label: String, parent: Disposable, child: Disposable) {
        Disposer.dispose(parent)
        if (!Disposer.isDisposed(child)) fail("$label: child was not disposed with its parent, it has no parent registration")
    }

    private fun check(label: String, body: () -> Unit) {
        try {
            body()
        } catch (e: AssertionError) {
            throw AssertionError("$label: ${e.message}", e)
        }
    }
}
