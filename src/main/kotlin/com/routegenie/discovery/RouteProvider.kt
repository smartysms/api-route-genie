package com.routegenie.discovery

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.routegenie.Ids
import com.routegenie.model.Diagnostic
import com.routegenie.model.Endpoint
import com.routegenie.model.UnresolvedSetting

/**
 * What one extraction of one file produced. Context-free: it is derived from the text of that file alone (unevaluated
 * expression trees and the import table), never from other files or from where the file lives, so one cached copy stays
 * valid while other files change and could be persisted. Implementations are immutable, hold no PSI, VirtualFile or
 * Project, and compare by value.
 */
interface SourceFacts {
    /** Estimated bytes this object keeps alive; the facts cache is bounded by the sum of these. */
    val weight: Int
}

/**
 * What [RouteProvider.link] consumes: one file's facts together with where the file is now. A rename or move gives new
 * [FileFacts] around the same [SourceFacts], so it needs a relink and no new extraction.
 */
interface FileFacts {
    /** Project-relative path with '/' separators; the same string a [com.routegenie.model.SourceRef] uses. */
    val path: String
}

/** [source] located at [path]; [sourceRoot] is the project-relative source root that contains it ("" when there is none). */
data class LocatedFacts(override val path: String, val source: SourceFacts, val sourceRoot: String = "") : FileFacts

/** What linking produced: the endpoints, and anything that could not be resolved. */
data class LinkResult(
    val endpoints: List<Endpoint>,
    val diagnostics: List<Diagnostic> = emptyList(),
    /** Settings without a pin that hide routes (see [UnresolvedSetting]); empty for providers that have none. */
    val settings: List<UnresolvedSetting> = emptyList(),
)

/**
 * Adds the routes of one framework family to the endpoint list. Pipeline:
 * [findCandidates] (index lookup, no parsing) -> [extract] (one pass over a file, cached per file) -> [link]
 * (pure function over all facts, no PSI).
 *
 * Threading: [findCandidates] and [extract] are called inside a read action and must be quick (one file per action);
 * [link] and [missingModules] are called with no lock held and must not touch the IDE at all.
 */
interface RouteProvider {
    /** Stable identifier, used for logging and to tell the provider's facts apart. */
    val id: String

    /** Files that may contain routes of this framework. Prefer indexes and import checks over parsing. */
    fun findCandidates(project: Project): Collection<VirtualFile>

    /** Reads one candidate. Null when the file turns out not to contain routes. */
    fun extract(file: PsiFile): SourceFacts?

    /**
     * Reads a file that only supports others (a constants module, a package that re-exports a router): returns its
     * facts even when it declares no routes itself, null only when it is not a readable source file.
     */
    fun extractDependency(file: PsiFile): SourceFacts? = extract(file)

    /** Dotted module names that linking [facts] would look up but that are not among them; pure, no IDE access. */
    fun missingModules(facts: Collection<FileFacts>): Set<String> = emptySet()

    /** The project file of a name returned by [missingModules], or null when the project has none. Runs in a read action. */
    fun findModuleFile(project: Project, module: String): VirtualFile? = null

    /** Resolves constants, prefixes, nested routers and includes over all facts of this provider. */
    fun link(facts: Collection<FileFacts>): LinkResult

    companion object {
        val EP_NAME: ExtensionPointName<RouteProvider> = ExtensionPointName.create(Ids.EP_ROUTE_PROVIDER)
    }
}
