package com.routegenie.testing

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.SourceFacts
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import java.util.concurrent.atomic.AtomicInteger

/** Facts of the fake provider: the route paths found in one file. Plain data, like real facts must be. */
data class FakeSource(val routes: List<String>, override val weight: Int = WEIGHT) : SourceFacts {
    companion object {
        /** A fixed estimate, so tests can size the cache bound in whole entries. */
        const val WEIGHT = 100
    }
}

/**
 * A provider that needs none of the framework code: every line `# route /x` in a .py file is one GET endpoint. It
 * records on which thread it ran and how often it extracted, and can be told to fail, so the discovery service can be
 * driven and its isolation behavior checked without depending on the real extractors.
 */
class FakeRouteProvider(override val id: String = "fake") : RouteProvider {
    val extractCount = AtomicInteger()
    val linkCount = AtomicInteger()

    @Volatile var failCandidates = false
    @Volatile var failLink = false

    /** Endpoints added to every link result, to simulate a provider that lets unresolved routes through. */
    @Volatile var extraEndpoints: List<Endpoint> = emptyList()
    @Volatile var failOnFileName: String? = null

    /** Called inside extract; tests use it to block until released or to observe the thread. */
    @Volatile var onExtract: (PsiFile) -> Unit = {}

    /** Stands in for the index lookup that real providers use to leave out files without framework imports. */
    @Volatile var candidateFilter: (VirtualFile) -> Boolean = { true }

    @Volatile var onEdt: Boolean = false
    @Volatile var sawEdt: Boolean = false

    override fun findCandidates(project: Project): Collection<VirtualFile> {
        noteThread()
        if (failCandidates) error("candidates failed on purpose")
        return FilenameIndex.getAllFilesByExt(project, "py", GlobalSearchScope.projectScope(project)).filter(candidateFilter).sortedBy { it.path }
    }

    override fun extract(file: PsiFile): SourceFacts? {
        noteThread()
        extractCount.incrementAndGet()
        ProgressManager.checkCanceled()
        onExtract(file)
        if (file.name == failOnFileName) error("extract failed on purpose: ${file.name}")
        val routes = file.text.lineSequence().filter { it.startsWith(ROUTE_PREFIX) }.map { it.removePrefix(ROUTE_PREFIX).trim() }.toList()
        return if (routes.isEmpty()) null else FakeSource(routes)
    }

    override fun link(facts: Collection<FileFacts>): LinkResult {
        noteThread()
        linkCount.incrementAndGet()
        if (failLink) error("link failed on purpose")
        val endpoints = ArrayList<Endpoint>()
        for (located in facts.filterIsInstance<LocatedFacts>().sortedBy { it.path }) {
            val fact = located.source as? FakeSource ?: continue
            val path = located.path
            fact.routes.forEachIndexed { index, route ->
                endpoints += Endpoint(
                    method = HttpMethod.GET,
                    path = route,
                    framework = Framework.FASTAPI,
                    handler = Handler("h$index", "$path.h$index"),
                    source = SourceRef(path, 0, index + 1),
                )
            }
        }
        endpoints += extraEndpoints
        return LinkResult(endpoints)
    }

    private fun noteThread() {
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isDispatchThread) sawEdt = true
    }

    companion object {
        const val ROUTE_PREFIX = "# route "
    }
}
