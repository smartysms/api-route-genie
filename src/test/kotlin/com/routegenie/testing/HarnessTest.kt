package com.routegenie.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.ref.WeakReference

/** The harness itself must be able to fail, otherwise a green budget or leak test proves nothing. */
class HarnessTest {
    @Test
    fun `a fast block stays within its budget`() {
        val result = PerfBudget.assertWithin("harness-fast", budgetMs = 200, runs = 5) { Thread.sleep(1) }
        assertTrue(result.medianMs <= 200)
    }

    @Test
    fun `a slow block fails its budget`() {
        try {
            PerfBudget.assertWithin("harness-slow", budgetMs = 5, warmups = 0, runs = 3) { Thread.sleep(40) }
            fail("expected the budget to be exceeded")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("exceeds the budget"))
        }
    }

    @Test
    fun `an object nobody references is collected`() {
        var owner: Any? = ByteArray(1024)
        val ref = WeakReference(owner)
        owner = null
        LeakProbe.assertCollected("unreferenced", ref)
        assertEquals(null, ref.get())
    }

    @Test
    fun `an object held by a static is reported as leaked`() {
        val held = ByteArray(1024)
        holder.add(held)
        val ref = WeakReference(held)
        try {
            LeakProbe.assertCollected("held", ref, timeoutMs = 300)
            fail("expected a leak report")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("still reachable"))
        } finally {
            holder.clear()
        }
    }

    @Test
    fun `a block that allocates only garbage passes the heap growth check`() {
        LeakProbe.assertNoHeapGrowth("garbage-only", maxGrowthBytes = 1024 * 1024) { ByteArray(4096) }
    }

    @Test
    fun `a block that retains memory fails the heap growth check`() {
        try {
            LeakProbe.assertNoHeapGrowth("retaining", maxGrowthBytes = 256 * 1024, warmups = 0, iterations = 600) {
                leaked.add(ByteArray(8192))
            }
            fail("expected a growth report")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("heap grew"))
        } finally {
            leaked.clear()
        }
    }

    private companion object {
        val holder = ArrayList<Any>()
        val leaked = ArrayList<ByteArray>()
    }
}
