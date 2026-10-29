package com.routegenie.frameworks.fastapi

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.discovery.SourceFacts
import com.routegenie.frameworks.common.PyProviderSupport
import com.routegenie.frameworks.common.PyResolver

/** Finds FastAPI routes. Stateless: all per-file state lives in the facts the engine caches. */
class FastApiRouteProvider : RouteProvider {
    override val id: String = FastApiCatalog.PROVIDER_ID

    override fun findCandidates(project: Project): Collection<VirtualFile> =
        PyProviderSupport.candidates(project, FastApiCatalog.table)

    override fun extract(file: PsiFile): SourceFacts? = PyProviderSupport.extract(file, FastApiCatalog.table)

    override fun extractDependency(file: PsiFile): SourceFacts? = PyProviderSupport.extractDependency(file, FastApiCatalog.table)

    override fun missingModules(facts: Collection<FileFacts>): Set<String> =
        PyResolver.missingModules(facts, FastApiCatalog.PROVIDER_ID, FastApiCatalog.table.importRoots)

    override fun findModuleFile(project: Project, module: String): VirtualFile? = PyProviderSupport.findModuleFile(project, module)

    override fun link(facts: Collection<FileFacts>): LinkResult = FastApiLinker.link(facts)
}
