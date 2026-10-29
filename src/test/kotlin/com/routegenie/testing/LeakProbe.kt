package com.routegenie.testing

import org.junit.Assert.fail
import java.lang.ref.WeakReference

/**
 * Leak checks that need no IDE. Platform-level checks (disposables, project and tool window leaks) live next to the code
 * they test and use the IDE's LeakHunter and Disposer assertions.
 */
object LeakProbe {
    /**
     * Forces garbage collection until [ref] is cleared. Fails when the referent is still strongly reachable after
     * [timeoutMs], which means something (a cache, a listener, a coroutine, a static) still holds on to it.
     */
    fun assertCollected(label: String, ref: WeakReference<*>, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (ref.get() != null && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20)
        }
        if (ref.get() != null) fail("$label: still reachable after garbage collection (leak)")
    }

    fun usedHeapBytes(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(10)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /**
     * Runs [block] repeatedly and fails when the retained heap keeps growing, i.e. when each run leaves something behind.
     * [maxGrowthBytes] must be well below what the leak per run times [iterations] would add up to.
     */
    fun assertNoHeapGrowth(
        label: String,
        maxGrowthBytes: Long = 512 * 1024,
        warmups: Int = 50,
        iterations: Int = 500,
        block: () -> Unit,
    ) {
        repeat(warmups) { block() }
        val before = usedHeapBytes()
        repeat(iterations) { block() }
        val growth = usedHeapBytes() - before
        if (growth > maxGrowthBytes) fail("$label: heap grew by $growth bytes over $iterations runs (limit $maxGrowthBytes)")
    }
}
