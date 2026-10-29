package com.routegenie.testing

import org.junit.Assert.fail
import java.io.File
import java.util.Locale

/**
 * Measures a block against a budget from com.routegenie.Limits. The median of several timed runs after warm-up is
 * compared, so one slow run (GC, scheduler) does not fail a test but a real regression does. Every measurement is
 * written to build/reports/routegenie/perf/<label>.txt so each phase can quote measured numbers next to the budgets.
 */
object PerfBudget {
    data class Result(val label: String, val budgetMs: Long, val medianMs: Double, val bestMs: Double)

    fun measure(label: String, budgetMs: Long, warmups: Int = 3, runs: Int = 9, block: () -> Unit): Result {
        require(runs >= 1) { "runs must be at least 1" }
        repeat(warmups) { block() }
        val samples = DoubleArray(runs) {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / NANOS_PER_MS
        }.sortedArray()
        val result = Result(label, budgetMs, medianMs = samples[samples.size / 2], bestMs = samples.first())
        report(result)
        return result
    }

    fun assertWithin(label: String, budgetMs: Long, warmups: Int = 3, runs: Int = 9, block: () -> Unit): Result {
        val result = measure(label, budgetMs, warmups, runs, block)
        if (result.medianMs > result.budgetMs) {
            fail("$label: median ${format(result.medianMs)} ms exceeds the budget of ${result.budgetMs} ms (best ${format(result.bestMs)} ms)")
        }
        return result
    }

    private fun report(result: Result) {
        runCatching {
            val file = File(ProjectFiles.root, "build/reports/routegenie/perf/${result.label.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt")
            file.parentFile.mkdirs()
            file.writeText("budget_ms=${result.budgetMs}\nmedian_ms=${format(result.medianMs)}\nbest_ms=${format(result.bestMs)}\n")
        }
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private const val NANOS_PER_MS = 1_000_000.0
}
