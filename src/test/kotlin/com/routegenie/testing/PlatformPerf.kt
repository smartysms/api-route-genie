package com.routegenie.testing

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.EdtTestUtil

/**
 * Timing for platform tests: the same median-after-warm-up rule and report files as [PerfBudget], with the block run on
 * the UI thread because that is where tool window content and actions are built. Platform tests normally already run
 * there; from another thread the hop (invokeAndWait) is included in the time, which only makes the check stricter.
 */
object PlatformPerf {
    fun assertWithinOnEdt(label: String, budgetMs: Long, warmups: Int = 3, runs: Int = 9, block: () -> Unit): PerfBudget.Result =
        PerfBudget.assertWithin(label, budgetMs, warmups, runs) { onEdt(block) }

    private fun onEdt(block: () -> Unit) {
        if (ApplicationManager.getApplication().isDispatchThread) block() else EdtTestUtil.runInEdtAndWait<RuntimeException> { block() }
    }
}
