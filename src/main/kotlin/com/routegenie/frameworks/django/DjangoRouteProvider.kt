package com.routegenie.frameworks.django

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.common.PyProviderSupport

/**
 * Django and Django REST Framework. Stateless: every call works on its arguments, so nothing here can outlive a project.
 * Candidate files come from the word index; [extract] reads one file into [DjangoFacts]; [link] is the pure [DjangoLinker].
 */
class DjangoRouteProvider : RouteProvider {
    override val id: String = DjangoCatalog.PROVIDER_ID

    override fun findCandidates(project: Project): Collection<VirtualFile> =
        PyProviderSupport.candidates(project, DjangoCatalog.CANDIDATE_WORDS)

    override fun extract(file: PsiFile): FileFacts? {
        val python = file as? PyFile ?: return null
        val text = python.viewProvider.contents
        // Cheap gate before the tree is walked: a file that names none of the framework words cannot route anything.
        if (DjangoCatalog.CANDIDATE_WORDS.none { text.contains(it) }) return null
        return DjangoExtractor(python, relativePath(python)).extract().takeUnless { it.isEmpty }
    }

    override fun link(facts: Collection<FileFacts>): LinkResult = DjangoLinker().link(facts)

    private fun relativePath(file: PyFile): String {
        val virtual = file.virtualFile ?: file.originalFile.virtualFile ?: return file.name
        val path = virtual.path
        val base = file.project.guessProjectDir()?.path
        if (base != null && path.startsWith("$base/")) return path.removePrefix("$base/")
        val root = ProjectFileIndex.getInstance(file.project).getContentRootForFile(virtual)
        if (root != null && path.startsWith("${root.path}/")) return path.removePrefix("${root.path}/")
        return path.trimStart('/')
    }
}
