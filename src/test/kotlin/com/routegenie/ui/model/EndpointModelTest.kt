package com.routegenie.ui.model

import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointModelTest {
    private fun ep(
        path: String, method: HttpMethod = HttpMethod.GET, framework: Framework = Framework.FASTAPI, file: String = "app/main.py",
        line: Int = 1, handler: String = "h", tags: List<String> = emptyList(), deprecated: Boolean = false,
    ) = Endpoint(method, path, framework, Handler(handler, "$file.$handler"), SourceRef(file, 0, line), tags = tags, deprecated = deprecated)

    private fun paths(list: List<Endpoint>) = list.map { "${it.method} ${it.path}" }

    private val sample = listOf(
        ep("/users", HttpMethod.GET, tags = listOf("users"), file = "routers/users.py", handler = "list_users"),
        ep("/users", HttpMethod.POST, tags = listOf("users"), file = "routers/users.py", handler = "create_user"),
        ep("/api/items", HttpMethod.GET, file = "routers/items.py"),
        ep("/old", HttpMethod.GET, deprecated = true),
        ep("/page", HttpMethod.ANY, Framework.DJANGO, "web/views.py"),
        ep("/api/drf", HttpMethod.PUT, Framework.DRF, "web/api.py"),
    )

    @Test
    fun `query parses tags lists and free text`() {
        val q = EndpointQuery.parse("method:post,PUT framework:fastapi tag:users path:/api file:routers users")
        assertEquals(setOf(HttpMethod.POST, HttpMethod.PUT), q.methods)
        assertEquals(listOf("fastapi"), q.frameworks)
        assertEquals(listOf("users"), q.tags)
        assertEquals(listOf("/api"), q.paths)
        assertEquals(listOf("routers"), q.files)
        assertEquals(listOf("users"), q.words)
    }

    @Test
    fun `unfinished or unknown tags never fail`() {
        assertTrue(EndpointQuery.parse("method:").isEmpty)
        assertTrue(EndpointQuery.parse("   ").isEmpty)
        assertEquals(listOf("http://x", "foo:bar"), EndpointQuery.parse("http://x foo:bar").words)
        assertEquals(setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH), EndpointQuery.parse("method:P").methods)
        assertEquals(emptySet<HttpMethod>(), EndpointQuery.parse("method:zzz").methods)
    }

    @Test
    fun `index sorts by path then method and is stable`() {
        val index = EndpointIndex.build(sample.reversed())
        assertEquals(
            listOf("PUT /api/drf", "GET /api/items", "GET /old", "ANY /page", "GET /users", "POST /users"),
            paths(index.endpoints),
        )
    }

    @Test
    fun `empty query returns the same list and filters combine`() {
        val index = EndpointIndex.build(sample)
        assertSame(index.endpoints, index.filter(EndpointQuery.EMPTY))
        assertEquals(listOf("GET /users", "POST /users"), paths(index.filter(EndpointQuery.parse("tag:users"))))
        assertEquals(listOf("POST /users"), paths(index.filter(EndpointQuery.parse("tag:users method:post"))))
        assertEquals(listOf("GET /api/items"), paths(index.filter(EndpointQuery.parse("file:routers path:/api"))))
        assertEquals(listOf("POST /users"), paths(index.filter(EndpointQuery.parse("handler:create"))))
        assertEquals(listOf("GET /users", "POST /users"), paths(index.filter(EndpointQuery.parse("USERS"))))
    }

    @Test
    fun `framework filter accepts name or display name`() {
        val index = EndpointIndex.build(sample)
        assertEquals(listOf("ANY /page", "PUT /api/drf"), paths(index.filter(EndpointQuery.parse("framework:django"))).sorted())
        assertEquals(listOf("PUT /api/drf"), paths(index.filter(EndpointQuery.parse("framework:drf"))))
    }

    @Test
    fun `ANY matches every method filter`() {
        val index = EndpointIndex.build(sample)
        assertTrue("ANY /page" in paths(index.filter(EndpointQuery.parse("method:DELETE"))))
        assertTrue("ANY /page" in paths(index.filter(EndpointQuery.parse("method:get"))))
        assertTrue("GET /users" !in paths(index.filter(EndpointQuery.parse("method:DELETE"))))
    }

    @Test
    fun `deprecated can be hidden and free text needs every word`() {
        val index = EndpointIndex.build(sample)
        assertTrue("GET /old" !in paths(index.filter(EndpointQuery.EMPTY, includeDeprecated = false)))
        assertEquals(listOf("POST /users"), paths(index.filter(EndpointQuery.parse("users create"))))
        assertEquals(emptyList<Endpoint>(), index.filter(EndpointQuery.parse("users nonexistent")))
    }

    @Test
    fun `logical tree groups by framework and merges one-item chains`() {
        val list = listOf(
            ep("/api/v1/users", file = "a.py"), ep("/api/v1/users/{id}", file = "a.py"), ep("/api/v1/items", file = "a.py"),
            ep("/health"), ep("/deep/er/only"), ep("/page", HttpMethod.ANY, Framework.DJANGO),
        )
        val tree = EndpointTree.build(EndpointIndex.build(list).endpoints, GroupMode.LOGICAL)
        assertEquals(6, tree.count)
        val roots = tree.root.children.map { (it as Group).label }
        assertEquals(listOf("FastAPI", "Django"), roots)
        val fastapi = tree.root.children[0] as Group
        // /deep/er/only sits alone, so its three single-child groups collapse into the endpoint itself.
        assertTrue(fastapi.children.any { it is Leaf && it.endpoint.path == "/deep/er/only" })
        val api = fastapi.children.filterIsInstance<Group>().single()
        assertEquals("/api/v1", api.label)
        assertEquals(3, api.count)
    }

    @Test
    fun `file prefix and flat modes`() {
        val list = EndpointIndex.build(
            listOf(ep("/a/x", file = "z.py", line = 9), ep("/a/y", file = "z.py", line = 2), ep("/b", file = "b.py"), ep("/")),
        ).endpoints
        val byFile = EndpointTree.build(list, GroupMode.BY_FILE)
        assertEquals(listOf("app/main.py", "b.py", "z.py"), byFile.root.children.map { (it as Group).label })
        assertEquals(listOf(2, 9), ((byFile.root.children[2] as Group).children).map { (it as Leaf).endpoint.source.line })
        assertEquals(listOf("/", "/a", "/b"), EndpointTree.build(list, GroupMode.BY_PREFIX).root.children.map { (it as Group).label })
        assertTrue(EndpointTree.build(list, GroupMode.NONE).root.children.all { it is Leaf })
    }

    @Test
    fun `find leaf by key and parent chain`() {
        val list = EndpointIndex.build(sample).endpoints
        val tree = EndpointTree.build(list, GroupMode.LOGICAL)
        val target = list.first { it.path == "/users" && it.method == HttpMethod.POST }
        val leaf = tree.findLeaf(target.key)!!
        assertEquals(target, leaf.endpoint)
        assertTrue(leaf.parent != null)
        assertNull(tree.findLeaf("nope"))
        assertEquals(leaf.index, leaf.parent!!.children.indexOf(leaf))
    }

    private fun bigList(n: Int): List<Endpoint> {
        val verbs = listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)
        return List(n) { i ->
            ep(
                "/api/v${i % 3}/resource${i % 500}/items/${i}", verbs[i % verbs.size], Framework.entries[i % 3],
                "app/module${i % 2000}/routes.py", i % 300 + 1, "handler$i", if (i % 7 == 0) listOf("users") else emptyList(),
            )
        }
    }

    @Test
    fun `filter keystroke on 50000 endpoints stays within the budget`() {
        val index = EndpointIndex.build(bigList(50_000))
        val query = EndpointQuery.parse("method:post resource12 items")
        val result = PerfBudget.assertWithin("ui-filter-keystroke-50k", Limits.BUDGET_FILTER_KEYSTROKE_MS, warmups = 20, runs = 30) {
            index.filter(query)
        }
        println("filter keystroke 50k: median ${result.medianMs} ms (budget ${Limits.BUDGET_FILTER_KEYSTROKE_MS})")
        val tagged = EndpointQuery.parse("tag:users path:/api/v1 file:module1")
        PerfBudget.assertWithin("ui-filter-tagged-50k", Limits.BUDGET_FILTER_KEYSTROKE_MS, warmups = 20, runs = 30) { index.filter(tagged) }
    }

    @Test
    fun `building index and tree for 50000 endpoints stays within the budget`() {
        val list = bigList(50_000)
        for (mode in GroupMode.entries) {
            val result = PerfBudget.assertWithin("ui-model-build-50k-$mode", Limits.BUDGET_MODEL_BUILD_50K_MS, warmups = 2, runs = 5) {
                EndpointTree.build(EndpointIndex.build(list).endpoints, mode)
            }
            println("model build 50k $mode: median ${result.medianMs} ms (budget ${Limits.BUDGET_MODEL_BUILD_50K_MS})")
        }
    }
}
