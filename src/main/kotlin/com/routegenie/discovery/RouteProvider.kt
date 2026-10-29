package com.routegenie.discovery

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.Ids
import com.routegenie.model.Diagnostic
import com.routegenie.model.Endpoint

/**
 * Immutable summary of what one file contributes to routing, produced by [RouteProvider.extract].
 * Implementations must be small, hold no PSI, VirtualFile or Project, and compare by value, because they are cached
 * per file and handed to the pure linker.
 */
interface FileFacts {
    /** Project-relative path with '/' separators; the same string a [com.routegenie.model.SourceRef] uses. */
    val path: String
}

/** What linking produced: the endpoints, and anything that could not be resolved. */
data class LinkResult(val endpoints: List<Endpoint>, val diagnostics: List<Diagnostic> = emptyList())

/**
 * Adds the routes of one framework family to the endpoint list. Pipeline:
 * [findCandidates] (index lookup, no parsing) -> [extract] (one pass over a file, cached per file) -> [link]
 * (pure function over all facts, no PSI).
 *
 * Threading: [findCandidates] and [extract] are called inside a read action and must be quick; [link] is called with
 * no lock held and must not touch the IDE at all.
 */
interface RouteProvider {
    /** Stable identifier, used for logging and to tell the provider's facts apart. */
    val id: String

    /** Files that may contain routes of this framework. Prefer indexes and import checks over parsing. */
    fun findCandidates(project: Project): Collection<VirtualFile>

    /** Reads one candidate. Null when the file turns out not to contain routes. */
    fun extract(file: PsiFile): FileFacts?

    /** Resolves prefixes, nested routers and includes over all facts of this provider. */
    fun link(facts: Collection<FileFacts>): LinkResult

    companion object {
        val EP_NAME: ExtensionPointName<RouteProvider> = ExtensionPointName.create(Ids.EP_ROUTE_PROVIDER)
    }
}
