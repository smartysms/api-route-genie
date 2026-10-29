package com.routegenie.json

import com.routegenie.Limits
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.ProjectFiles
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speed and memory of the JSON engine on synthetic API responses. Budgets are in [Limits]; every measurement is written to
 * build/reports/routegenie/perf/ by [PerfBudget] and the extra numbers to build/reports/routegenie/json/engine-numbers.txt.
 */
class JsonPerfTest {
    companion object {
        private lateinit var tenMb: ByteArray
        private lateinit var fiftyMbFile: File
        private val numbers = LinkedHashMap<String, String>()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            tenMb = T.users(10_000_000)
            fiftyMbFile = File.createTempFile("rg-json-50mb-", ".json")
            T.writeUsers(fiftyMbFile, 50_000_000L)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            fiftyMbFile.delete()
            val out = File(ProjectFiles.root, "build/reports/routegenie/json/engine-numbers.txt")
            out.parentFile.mkdirs()
            out.writeText(numbers.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        }

        private fun note(key: String, value: String) {
            numbers[key] = value
            println("$key = $value")
        }
    }

    private class CountingSink : CharSink {
        var chars = 0L
        override fun append(c: Char) {
            chars++
        }

        override fun append(chars: CharArray, off: Int, len: Int) {
            this.chars += len
        }

        override fun append(text: String) {
            chars += text.length
        }
    }

    @Test
    fun `indexing 10 MB stays within budget`() {
        val r = PerfBudget.assertWithin("json.index.10mb", Limits.BUDGET_JSON_INDEX_10MB_MS, warmups = 2, runs = 5) { T.scanBytes(tenMb) }
        val index = T.scanBytes(tenMb)
        note("index.10mb.median_ms", "%.1f".format(r.medianMs))
        note("index.10mb.nodes", index.nodeCount.toString())
        note("index.10mb.mb_per_s", "%.0f".format(10.0 / (r.medianMs / 1000.0)))
    }

    @Test
    fun `searching 10 MB stays within budget`() {
        val index = T.scanBytes(tenMb)
        var count = 0
        val plain = PerfBudget.assertWithin("json.search.10mb", Limits.BUDGET_JSON_SEARCH_10MB_MS, warmups = 2, runs = 5) {
            count = JsonSearch.search(index, SearchQuery("User number 7777", inKeys = false)).count
        }
        assertTrue(count > 0)
        val keys = PerfBudget.measure("json.search.keys.10mb", Limits.BUDGET_JSON_SEARCH_10MB_MS, warmups = 1, runs = 3) {
            JsonSearch.search(index, SearchQuery("zzz-no-key", inValues = false), maxResults = 100)
        }
        val regex = PerfBudget.measure("json.search.regex.10mb", Limits.BUDGET_JSON_SEARCH_10MB_MS * 4, warmups = 1, runs = 3) {
            JsonSearch.search(index, SearchQuery("zzz\\d+@example", regex = true, inKeys = false), maxResults = 100)
        }
        note("search.10mb.plain_ms", "%.1f".format(plain.medianMs))
        note("search.10mb.keys_ms", "%.1f".format(keys.medianMs))
        note("search.10mb.regex_ms", "%.1f".format(regex.medianMs))
    }

    @Test
    fun `pretty printing 10 MB streams within budget`() {
        val index = T.scanBytes(tenMb)
        val sink = CountingSink()
        val r = PerfBudget.assertWithin("json.pretty.10mb", Limits.BUDGET_JSON_PRETTY_10MB_MS, warmups = 1, runs = 3) {
            sink.chars = 0
            JsonPrinter.write(index, index.root, PrintOptions(), sink)
        }
        note("pretty.10mb_ms", "%.1f".format(r.medianMs))
        note("pretty.10mb.chars", sink.chars.toString())
        val plan = PerfBudget.measure("json.pages.plan.10mb", Limits.BUDGET_JSON_PRETTY_10MB_MS, warmups = 1, runs = 3) {
            JsonPrinter.plan(index, PrintOptions(), Limits.JSON_PAGE_UNITS)
        }
        note("pretty.10mb.plan_ms", "%.1f".format(plan.medianMs))
        val one = StringSink()
        val page = PerfBudget.measure("json.pages.render", Limits.BUDGET_JSON_PRETTY_10MB_MS, warmups = 1, runs = 5) {
            one.builder.setLength(0)
            JsonPrinter(index, PrintOptions()).also { it.begin(index.root) }.step(one, Limits.JSON_PAGE_UNITS)
        }
        note("pretty.page_render_ms", "%.1f".format(page.medianMs))
    }

    @Test
    fun `comparing two 1 MB documents stays within budget`() {
        val leftBytes = T.users(1_000_000)
        val rightText = String(leftBytes, Charsets.UTF_8).replace("User number 5", "User numbeR 5").replace("\"active\":true", "\"active\":false")
        val left = T.scanBytes(leftBytes)
        val right = T.scanBytes(rightText.toByteArray(Charsets.UTF_8))
        var differences = 0
        val r = PerfBudget.assertWithin("json.diff.1mb", Limits.BUDGET_JSON_DIFF_1MB_MS, warmups = 2, runs = 5) {
            val result = JsonDiff.compare(left, right)!!
            differences = result.totalDifferences
            JsonDiffRows.build(result)
        }
        assertTrue(differences > 1000)
        note("diff.1mb_ms", "%.1f".format(r.medianMs))
        note("diff.1mb.differences", differences.toString())
        val same = PerfBudget.measure("json.diff.1mb.identical", Limits.BUDGET_JSON_DIFF_1MB_MS, warmups = 2, runs = 5) {
            JsonDiff.compare(left, T.scanBytes(leftBytes))
        }
        note("diff.1mb.identical_incl_scan_ms", "%.1f".format(same.medianMs))
    }

    @Test
    fun `the first tree rows of a 50 MB file are available within budget`() {
        val times = DoubleArray(5)
        for (round in times.indices) {
            val latch = CountDownLatch(1)
            val first = LongArray(1)
            var seen = 0
            val start = System.nanoTime()
            val worker = Thread {
                val index = JsonScanner.scan(JsonContent.File(fiftyMbFile.toPath()), ScanOptions(), object : ScanListener {
                    override fun isCancelled() = latch.count == 0L && seen > 2
                    override fun onProgress(index: JsonIndex) {
                        seen++
                        if (first[0] == 0L && index.nodeCount > 100) {
                            first[0] = System.nanoTime()
                            latch.countDown()
                        }
                    }
                })
                index.close()
            }
            worker.start()
            assertTrue(latch.await(30, TimeUnit.SECONDS))
            // The first rows: the root and its first children, as the tree model would ask for them.
            times[round] = (first[0] - start) / 1_000_000.0
            worker.join(60_000)
        }
        val sorted = times.sorted()
        note("firstpaint.50mb.cold_first_ms", "%.1f".format(times[0]))
        note("firstpaint.50mb.median_ms", "%.1f".format(sorted[sorted.size / 2]))
        assertTrue("first rows of 50 MB after ${sorted[sorted.size / 2]} ms", sorted[sorted.size / 2] <= Limits.BUDGET_JSON_TREE_FIRST_PAINT_50MB_MS)
    }

    @Test
    fun `a node costs no more than the budgeted bytes and heap follows the index`() {
        val before = LeakProbe.usedHeapBytes()
        val index = T.scanBytes(tenMb)
        val after = LeakProbe.usedHeapBytes()
        val perNodeLayout = index.memoryBytes().toDouble() / index.nodeCount
        val perNodeHeap = (after - before).toDouble() / index.nodeCount
        note("memory.bytes_per_node_layout", "%.2f".format(perNodeLayout))
        note("memory.bytes_per_node_heap", "%.2f".format(perNodeHeap))
        assertTrue("layout $perNodeLayout", perNodeLayout <= Limits.JSON_MAX_BYTES_PER_NODE)
        assertTrue("heap $perNodeHeap bytes per node", perNodeHeap <= Limits.JSON_MAX_BYTES_PER_NODE + 2)
        assertEquals(JsonIndex.BYTES_PER_NODE.toDouble(), perNodeLayout, 0.5)
    }

    /** Opens the file, uses every part of the engine once, closes it, and returns the heap held while it was open. */
    private fun openUseAndClose(): Long {
        val baseline = LeakProbe.usedHeapBytes()
        val index = JsonScanner.scan(JsonContent.File(fiftyMbFile.toPath()))
        assertEquals(null, index.error)
        val search = JsonSearch.search(index, SearchQuery("User number 4999999", inKeys = false))
        val tree = JsonTreeCore(index)
        val rootKids = tree.childCount(tree.root())
        val page = StringSink()
        JsonPrinter(index, PrintOptions()).also { it.begin(index.root) }.step(page, Limits.JSON_PAGE_UNITS)
        val plan = JsonPrinter.plan(index, PrintOptions(), Limits.JSON_PAGE_UNITS)!!
        assertTrue(rootKids > 0 && page.builder.isNotEmpty() && plan.pageCount > 10)
        val open = LeakProbe.usedHeapBytes() - baseline
        note("memory.50mb.nodes", index.nodeCount.toString())
        note("memory.50mb.heap_while_open_mb", "%.1f".format(open / 1048576.0))
        note("memory.50mb.text_mb", "%.1f".format(fiftyMbFile.length() / 1048576.0))
        assertTrue("heap while open ${open / 1048576} MB for ${index.nodeCount} nodes", open <= index.nodeCount.toLong() * (Limits.JSON_MAX_BYTES_PER_NODE + 2) + 8L * 1048576)
        assertTrue(search.count >= 0)
        index.close()
        return open
    }

    @Test
    fun `opening and closing a 50 MB document returns the heap to the baseline`() {
        val baseline = LeakProbe.usedHeapBytes()
        openUseAndClose()
        val afterClose = LeakProbe.usedHeapBytes() - baseline
        note("memory.50mb.heap_after_close_mb", "%.2f".format(afterClose / 1048576.0))
        assertTrue("heap after close ${afterClose / 1048576} MB", afterClose <= 4L * 1048576)
    }
}
