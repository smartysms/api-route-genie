package com.routegenie.frameworks.flask

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.discovery.FileFacts
import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.common.PyProviderSupport

/** Finds Flask routes (apps, blueprints, add_url_rule, class based views). Stateless, like every provider. */
class FlaskRouteProvider : RouteProvider {
    override val id: String = FlaskCatalog.PROVIDER_ID

    override fun findCandidates(project: Project): Collection<VirtualFile> =
        PyProviderSupport.candidates(project, FlaskCatalog.table.candidateWords)

    override fun extract(file: PsiFile): FileFacts? = PyProviderSupport.extract(file, FlaskCatalog.table)

    override fun link(facts: Collection<FileFacts>): LinkResult = FlaskLinker.link(facts)
}
