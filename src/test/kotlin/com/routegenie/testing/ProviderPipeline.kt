package com.routegenie.testing

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.routegenie.Limits
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LocatedFacts
import com.routegenie.discovery.ProjectPaths
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.SourceFacts

/**
 * The provider half of what the engine does, without caching or threads: candidates, extraction, then the files the facts
 * refer to, round after round. Tests use it to see exactly what the engine would hand to the linker.
 */
object ProviderPipeline {
    fun locate(project: Project, file: VirtualFile, source: SourceFacts): LocatedFacts {
        val path = ProjectPaths.relative(project, file)
        return LocatedFacts(path, source, ProjectPaths.sourceRoot(project, file, path))
    }

    fun locate(file: PsiFile, source: SourceFacts): LocatedFacts = locate(file.project, file.virtualFile, source)

    /** Located facts of every candidate of [provider] and of the files its linker will look at. */
    fun collect(project: Project, provider: RouteProvider): List<FileFacts> {
        val manager = PsiManager.getInstance(project)
        val located = LinkedHashMap<VirtualFile, LocatedFacts>()
        for (file in provider.findCandidates(project)) {
            val psi = manager.findFile(file) ?: continue
            provider.extract(psi)?.let { located[file] = locate(project, file, it) }
        }
        repeat(Limits.MAX_DEPENDENCY_ROUNDS) {
            val missing = provider.missingModules(located.values)
            val files = missing.mapNotNull { provider.findModuleFile(project, it) }.filter { it !in located }
            if (files.isEmpty()) return located.values.toList()
            for (file in files) {
                val psi = manager.findFile(file) ?: continue
                provider.extractDependency(psi)?.let { located[file] = locate(project, file, it) }
            }
        }
        return located.values.toList()
    }
}
