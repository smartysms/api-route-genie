package com.routegenie.frameworks.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.resolve.QualifiedNameFinder

/** Everything the two Python providers do identically around the extractor. */
object PyProviderSupport {
    private const val PYTHON_EXTENSION = "py"
    private const val PACKAGE_FILE = "__init__.py"
    private const val PACKAGE_SUFFIX = ".__init__"

    /**
     * Files whose code mentions one of the framework's [words], from the word index: no parsing. A file that only
     * imports a router from another module and decorates with it is found through the shared words (for example `route`).
     */
    fun candidates(project: Project, words: Collection<String>): Collection<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val cache = CacheManager.getInstance(project)
        val found = LinkedHashSet<VirtualFile>()
        for (word in words) {
            for (file in cache.getVirtualFilesWithWord(word, UsageSearchContext.IN_CODE, scope, true)) {
                if (file.extension == PYTHON_EXTENSION) found += file
            }
        }
        // Package files re-export routers without naming the framework; their imports are needed to follow a name.
        found += FilenameIndex.getVirtualFilesByName(PACKAGE_FILE, scope)
        return found
    }

    /** Extracts one file with [spec]; null for anything that is not a Python file or holds no routing facts. */
    fun extract(file: PsiFile, spec: PyExtractionSpec): PyRouteFacts? {
        val py = file as? PyFile ?: return null
        return PyFactsExtractor.extract(py, locationOf(py), spec)
    }

    fun locationOf(file: PyFile): FileLocation {
        val virtual = file.virtualFile ?: file.originalFile.virtualFile
        val isPackage = file.name == PACKAGE_FILE
        val module = (QualifiedNameFinder.findShortestImportableQName(file)?.toString() ?: moduleFromName(file.name))
            .removeSuffix(PACKAGE_SUFFIX)
        return FileLocation(relativePath(file.project, virtual, file.name), module, isPackage)
    }

    private fun moduleFromName(fileName: String) = fileName.removeSuffix(".$PYTHON_EXTENSION")

    /** Project-relative path with '/', the form a SourceRef uses; falls back to the content root, then the full path. */
    private fun relativePath(project: Project, file: VirtualFile?, fallback: String): String {
        if (file == null) return fallback
        val path = file.path
        val base = project.guessProjectDir()?.path
        if (base != null && path.startsWith("$base/")) return path.removePrefix("$base/")
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        if (root != null && path.startsWith("${root.path}/")) return path.removePrefix("${root.path}/")
        return path.trimStart('/')
    }
}
