package com.routegenie.frameworks.fastapi

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.common.PyProviderSupport

/** Finds FastAPI routes. Stateless: all per-file state lives in the facts the engine caches. */
class FastApiRouteProvider : RouteProvider {
    override val id: String = FastApiCatalog.PROVIDER_ID

    override fun findCandidates(project: Project): Collection<VirtualFile> =
        PyProviderSupport.candidates(project, FastApiCatalog.table.candidateWords)

    override fun extract(file: PsiFile): FileFacts? = PyProviderSupport.extract(file, FastApiCatalog.table)

    override fun link(facts: Collection<FileFacts>): LinkResult = FastApiLinker.link(facts)
}
