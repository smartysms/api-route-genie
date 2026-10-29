package com.routegenie.testing

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.SourceFacts
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Wraps a real provider and records which files it read and how often it linked, so tests can prove what was NOT redone. */
class RecordingProvider(private val inner: RouteProvider) : RouteProvider {
    override val id: String = inner.id

    /** File names handed to [extract] or [extractDependency], in call order. */
    val extracted = CopyOnWriteArrayList<String>()
    val linkCount = AtomicInteger()

    fun reset() = extracted.clear()

    override fun findCandidates(project: Project): Collection<VirtualFile> = inner.findCandidates(project)

    override fun extract(file: PsiFile): SourceFacts? {
        extracted += file.name
        return inner.extract(file)
    }

    override fun extractDependency(file: PsiFile): SourceFacts? {
        extracted += file.name
        return inner.extractDependency(file)
    }

    override fun missingModules(facts: Collection<FileFacts>): Set<String> = inner.missingModules(facts)

    override fun findModuleFile(project: Project, module: String): VirtualFile? = inner.findModuleFile(project, module)

    override fun link(facts: Collection<FileFacts>): LinkResult {
        linkCount.incrementAndGet()
        return inner.link(facts)
    }
}
