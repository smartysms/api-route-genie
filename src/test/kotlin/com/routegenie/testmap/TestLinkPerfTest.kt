package com.routegenie.testmap

import com.routegenie.Limits
import com.routegenie.discovery.core.FStr
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.Interp
import com.routegenie.discovery.core.Lit
import com.routegenie.discovery.core.Ref
import com.routegenie.frameworks.common.RefFact
import com.routegenie.frameworks.common.SourcePos
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathParam
import com.routegenie.model.SourceRef
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

/** Linking is pure, so its speed and its memory are measured without an IDE: 20,000 test calls against 5,000 endpoints. */
class TestLinkPerfTest {
    private val resources = 50
    private val itemsPerResource = 100
    private val files = 400
    private val callsPerFile = 50

    /** Every route starts with `/api/v1/`: the shape of a large API, where a first-segment index would not help at all. */
    private fun endpoints(): List<Endpoint> = (0 until resources).flatMap { r ->
        (0 until itemsPerResource).map { i ->
            Endpoint(
                HttpMethod.GET, "/api/v1/r$r/item$i/{id}", Framework.FASTAPI, Handler("h", "app.r$r.h$i"),
                SourceRef("app/r$r.py", i, 1), pathParams = listOf(PathParam("id", "int")),
            )
        }
    }

    private fun pos(n: Int) = SourcePos(n, n + 1)

    private fun fileFacts(f: Int): LocatedTestFacts {
        val calls = ArrayList<ClientCallFact>()
        val functions = ArrayList<TestFunctionFact>()
        for (k in 0 until callsPerFile) {
            val n = f * callsPerFile + k
            val r = n % resources
            val i = (n / resources) % itemsPerResource
            // Half literal paths, half with a computed id (an f-string hole).
            val url = if (k % 2 == 0) UrlFact.Text(Lit("/api/v1/r$r/item$i/5"))
            else UrlFact.Text(FStr(listOf(Lit("/api/v1/r$r/item$i/"), Interp(Ref(listOf("item_id"))))))
            functions += TestFunctionFact("test_$k", null, "test_$k", listOf("client"), false, false, TestMark.NONE, emptyList(), emptyList(), pos(k))
            calls += ClientCallFact(RefFact(listOf("client")), null, "get", url, null, "test_$k", null, emptyList(), pos(k * 10))
        }
        val facts = TestFileFacts(
            imports = listOf(ImportBinding("TestClient", "starlette.testclient", "TestClient")), stars = emptyList(), consts = emptyList(),
            classes = emptyList(), functions = functions, bindings = emptyList(), calls = calls, asserts = emptyList(), assigns = emptyList(),
            testLikeFunctions = callsPerFile,
        )
        return LocatedTestFacts("tests/test_f$f.py", facts)
    }

    private fun allFacts(): List<LocatedTestFacts> = (0 until files).map(::fileFacts)

    @Test
    fun `linking 20000 calls against 5000 endpoints stays within the budget`() {
        val facts = allFacts()
        val endpoints = endpoints()
        val index = TestLinker.link(facts, endpoints)
        assertEquals(files * callsPerFile, index.stats.clientCalls)
        assertTrue("unattributed: ${index.unattributed.take(3)}", index.unattributed.isEmpty())
        assertEquals("every call is exact: a literal id or a computed one", files * callsPerFile, index.stats.mappedCalls)
        PerfBudget.assertWithin("test-link-20000-calls", Limits.BUDGET_TEST_LINK_20K_CALLS_MS, warmups = 2, runs = 7) {
            TestLinker.link(facts, endpoints)
        }
    }

    @Test
    fun `an index of 20000 calls is collectable once nothing holds it`() {
        val ref = scenario()
        LeakProbe.assertCollected("test index", ref)
    }

    private fun scenario(): WeakReference<TestIndex> = WeakReference(TestLinker.link(allFacts().take(40), endpoints()))

    @Test
    fun `the weight of the facts of a file is bounded by its size`() {
        val facts = fileFacts(0).source
        assertTrue(facts.weight > Limits.FACT_ENTRY_BYTES)
        assertTrue("50 calls must weigh far less than a megabyte: ${facts.weight}", facts.weight < 1024 * 1024)
    }
}
