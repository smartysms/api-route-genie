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
        // A leak adds to the heap in every round; the collector's timing, JIT code and caches that fill once do not. So the
        // check passes as soon as one round stays under the limit and fails only when every round grows past it.
        var smallest = Long.MAX_VALUE
        repeat(ROUNDS) {
            val before = usedHeapBytes()
            repeat(iterations) { block() }
            val growth = usedHeapBytes() - before
            if (growth <= maxGrowthBytes) return
            smallest = minOf(smallest, growth)
        }
        fail("$label: heap grew by at least $smallest bytes in each of $ROUNDS rounds of $iterations runs (limit $maxGrowthBytes)")
    }

    private const val ROUNDS = 3
}
