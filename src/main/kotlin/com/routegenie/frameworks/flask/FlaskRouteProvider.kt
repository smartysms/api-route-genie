package com.routegenie.frameworks.flask

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.SourceFacts
import com.routegenie.frameworks.common.PyProviderSupport
import com.routegenie.frameworks.common.PyResolver

/** Finds Flask routes. Stateless: all per-file state lives in the facts the engine caches. */
class FlaskRouteProvider : RouteProvider {
    override val id: String = FlaskCatalog.PROVIDER_ID

    override fun findCandidates(project: Project): Collection<VirtualFile> =
        PyProviderSupport.candidates(project, FlaskCatalog.table)

    override fun extract(file: PsiFile): SourceFacts? = PyProviderSupport.extract(file, FlaskCatalog.table)

    override fun extractDependency(file: PsiFile): SourceFacts? = PyProviderSupport.extractDependency(file, FlaskCatalog.table)

    override fun missingModules(facts: Collection<FileFacts>): Set<String> =
        PyResolver.missingModules(facts, FlaskCatalog.PROVIDER_ID, FlaskCatalog.table.importRoots)

    override fun findModuleFile(project: Project, module: String): VirtualFile? = PyProviderSupport.findModuleFile(project, module)

    override fun link(facts: Collection<FileFacts>): LinkResult = FlaskLinker.link(facts)
}
