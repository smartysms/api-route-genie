package com.routegenie.frameworks.django

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.SourceFacts
import com.routegenie.frameworks.common.PyCandidateRules
import com.routegenie.frameworks.common.PyProviderSupport

/**
 * Django and Django REST Framework. Stateless: every call works on its arguments, so nothing here can outlive a project.
 * Candidate files come from the word index; [extract] reads one file into context-free [DjangoFacts]; [link] is the pure
 * [DjangoLinker]. View modules are not candidates: the linker's own references (imports of url modules) lead the engine
 * to them, see [missingModules].
 */
class DjangoRouteProvider : RouteProvider {
    override val id: String = DjangoCatalog.PROVIDER_ID

    override fun findCandidates(project: Project): Collection<VirtualFile> =
        PyProviderSupport.candidatesByWords(project, DjangoCatalog.CANDIDATE_WORDS)

    override fun extract(file: PsiFile): SourceFacts? {
        val python = file as? PyFile ?: return null
        val text = python.viewProvider.contents
        // Cheap gate before the tree is walked: a file that names none of the routing words cannot route anything.
        if (DjangoCatalog.TEXT_MARKERS.none { StringUtil.contains(text, it) }) return null
        return DjangoExtractor(python).extract().takeUnless { it.isEmpty }
    }

    override fun extractDependency(file: PsiFile): SourceFacts? {
        val python = file as? PyFile ?: return null
        val facts = DjangoExtractor(python).extract()
        val supports = !facts.isEmpty || facts.imports.isNotEmpty() || facts.stars.isNotEmpty() || facts.consts.isNotEmpty()
        return facts.takeIf { supports }
    }

    override fun missingModules(facts: Collection<FileFacts>): Set<String> =
        DjangoResolver.missingModules(facts, setOf(DjangoCatalog.DJANGO_ROOT, DjangoCatalog.DRF_ROOT))

    override fun findModuleFile(project: Project, module: String): VirtualFile? = PyProviderSupport.findModuleFile(project, module)

    override fun link(facts: Collection<FileFacts>): LinkResult = DjangoLinker().link(facts)
}
