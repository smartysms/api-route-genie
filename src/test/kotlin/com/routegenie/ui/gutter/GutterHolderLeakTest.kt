package com.routegenie.ui.gutter

import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.RouteSnapshot
import com.routegenie.model.SourceRef
import com.routegenie.testing.LeakProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** The holder collects a flow in a scope; closing it or cancelling the scope must end that, release it and keep memory flat. */
class GutterHolderLeakTest {
    private fun snapshot(generation: Long, routes: Int) = RouteSnapshot(
        generation,
        (1..routes).map {
            Endpoint(HttpMethod.GET, "/g$generation/$it", Framework.FASTAPI, Handler("h", "m.h"), SourceRef("a.py", it, it))
        },
    )

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun `closing stops the collection and drops the model`() {
        val flow = MutableStateFlow(RouteSnapshot.EMPTY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val changes = AtomicInteger()
        val holder = GutterModelHolder(flow, scope) { changes.incrementAndGet() }
        flow.value = snapshot(1, 3)
        await("first model") { !holder.model.isEmpty }
        holder.close()
        assertTrue(holder.model.isEmpty)
        val before = changes.get()
        flow.value = snapshot(2, 4)
        Thread.sleep(150)
        assertEquals("no collector reacts after close", before, changes.get())
        scope.cancel()
    }

    @Test
    fun `cancelling the owner scope also ends it and the holder can be collected`() {
        val flow = MutableStateFlow(RouteSnapshot.EMPTY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var holder: GutterModelHolder? = GutterModelHolder(flow, scope) { }
        flow.value = snapshot(1, 3)
        await("model") { !holder!!.model.isEmpty }
        val ref = WeakReference(holder)
        scope.cancel()
        holder!!.close()
        holder = null
        LeakProbe.assertCollected("GutterModelHolder", ref)
        assertFalse(flow.subscriptionCount.value > 0)
    }

    @Test
    fun `many snapshot generations do not grow the heap`() {
        val flow = MutableStateFlow(RouteSnapshot.EMPTY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val holder = GutterModelHolder(flow, scope) { }
        var generation = 0L
        LeakProbe.assertNoHeapGrowth("gutter model generations", maxGrowthBytes = 1024L * 1024, warmups = 50, iterations = 300) {
            generation++
            flow.value = snapshot(generation, 200)
            await("generation $generation") { holder.model.linesOf("a.py")?.get(1)?.label?.startsWith("GET /g$generation/") == true }
        }
        holder.close()
        scope.cancel()
    }
}
