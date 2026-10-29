package com.routegenie.frameworks.django

import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.Lit
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

/** Linking is a pure function over facts, so speed and leaks are checked without an IDE. */
class DjangoLinkerScaleTest {
    private val loc = Loc(0, 1)

    /** One settings file, [apps] URL modules of [routesPerApp] routes each, all included from one root module. */
    private fun syntheticProject(apps: Int, routesPerApp: Int): List<FileFacts> {
        val settings = facts("proj/settings.py", rootUrlconf = Lit("proj.urls"))
        val root = facts(
            "proj/urls.py",
            lists = mapOf(
                DjangoCatalog.URLPATTERNS to (0 until apps).map { app ->
                    RouteItem(RouteKind.PATH, Lit("app$app/"), IncludeTarget(ModuleSpec(Lit("app$app.urls")), null, null), null, loc)
                },
            ),
        )
        val modules = (0 until apps).map { app ->
            facts(
                "app$app/urls.py",
                imports = listOf(ImportBinding("views", "app$app", "views")),
                appName = Lit("app$app"),
                lists = mapOf(
                    DjangoCatalog.URLPATTERNS to (0 until routesPerApp).map { r ->
                        RouteItem(RouteKind.PATH, Lit("r$r/<int:pk>/"), ViewTarget("views.v$r", false), Lit("n$r"), loc)
                    },
                ),
            )
        }
        return listOf(settings, root) + modules
    }

    private fun facts(
        path: String,
        imports: List<ImportBinding> = emptyList(),
        rootUrlconf: Expr? = null,
        appName: Expr? = null,
        lists: Map<String, List<ListItem>> = emptyMap(),
    ): FileFacts = LocatedFacts(
        path,
        DjangoFacts(imports, emptyList(), emptyList(), rootUrlconf, appName, lists, emptyMap(), emptyList(), emptyMap(), emptyMap()),
    )

    @Test
    fun `linking 5000 endpoints stays within the budget`() {
        val project = syntheticProject(apps = 50, routesPerApp = 100)
        val linker = DjangoLinker()
        assertEquals(5000, linker.link(project).endpoints.size)
        PerfBudget.assertWithin("django-link-5000-endpoints", Limits.BUDGET_LINK_5000_ENDPOINTS_MS) { linker.link(project) }
    }

    @Test
    fun `the synthetic project resolves names prefixes and namespaces`() {
        val result = DjangoLinker().link(syntheticProject(apps = 2, routesPerApp = 2))
        val first = result.endpoints.first()
        assertEquals("/app0/r0/{pk}/", first.path)
        assertEquals("app0:n0", first.routeName)
        assertEquals("app0.views.v0", first.handler.qualifiedName)
    }

    @Test
    fun `facts are collectable once linking is done`() {
        var project: List<FileFacts>? = syntheticProject(apps = 3, routesPerApp = 10)
        val ref = WeakReference(project!![2])
        DjangoLinker().link(project)
        project = null
        LeakProbe.assertCollected("django facts after linking", ref)
    }

    @Test
    fun `repeated linking does not retain anything`() {
        val project = syntheticProject(apps = 10, routesPerApp = 50)
        val linker = DjangoLinker()
        LeakProbe.assertNoHeapGrowth("django-link", maxGrowthBytes = 2L * 1024 * 1024, warmups = 20, iterations = 200) { linker.link(project) }
    }

    @Test
    fun `an include chain deeper than the limit is cut and reported`() {
        val depth = Limits.MAX_URLCONF_DEPTH + 5
        val modules = (0..depth).map { i ->
            val items = if (i < depth) {
                listOf(RouteItem(RouteKind.PATH, Lit("n/"), IncludeTarget(ModuleSpec(Lit("m${i + 1}.urls")), null, null), null, loc))
            } else {
                listOf(RouteItem(RouteKind.PATH, Lit("end/"), ViewTarget("v", false), null, loc))
            }
            facts("m$i/urls.py", lists = mapOf(DjangoCatalog.URLPATTERNS to items))
        }
        val result = DjangoLinker().link(modules)
        assertTrue(result.endpoints.isEmpty())
        assertTrue(result.diagnostics.any { it.code.name == "UNRESOLVED_INCLUDE" })
    }
}
