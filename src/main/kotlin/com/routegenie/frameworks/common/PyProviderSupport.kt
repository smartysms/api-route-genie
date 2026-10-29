package com.routegenie.frameworks.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.stubs.PyDecoratorStubIndex

/** Everything the Python providers do identically around the extractor: finding candidates, the text prefilter, extraction. */
object PyProviderSupport {
    private const val PYTHON_EXTENSION = "py"
    private const val INIT_FILE = "__init__.py"
    private const val INIT_SUFFIX = ".$PYTHON_EXTENSION"

    /**
     * Files that may declare or attach routes, without parsing any. Two sources, both persisted platform indexes:
     * the word index for constructors and attachment calls (`APIRouter`, `include_router`), and the decorator stub index for
     * route decorators (`router.get`, `app.route`), which finds routes in a file that never names the framework because its
     * router is imported from another module. The framework's own name is deliberately not a word: it would select every
     * file that imports it.
     *
     * Not found: a decorator that is not written as `<name>.<verb>` (a router stored in a dict, a `getattr`), and files
     * excluded by [PyCandidateRules].
     */
    fun candidates(project: Project, spec: PyExtractionSpec): List<VirtualFile> =
        candidatesByWords(project, spec.candidateWords, spec.decoratorMethods)

    fun candidatesByWords(project: Project, words: Collection<String>, decoratorMethods: Set<String> = emptySet()): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val found = LinkedHashSet<VirtualFile>()
        val cache = CacheManager.getInstance(project)
        for (word in words) {
            found += cache.getVirtualFilesWithWord(word, UsageSearchContext.IN_CODE, scope, true).filter { it.extension == PYTHON_EXTENSION }
        }
        if (decoratorMethods.isNotEmpty()) found += decoratorFiles(project, decoratorMethods, scope)
        return PyCandidateRules.select(project, found)
    }

    /** Files that carry a decorator whose last name segment is one of [methods], from the stub index (no AST is loaded). */
    fun decoratorFiles(project: Project, methods: Set<String>, scope: GlobalSearchScope): Set<VirtualFile> {
        val index = StubIndex.getInstance()
        val key = PyDecoratorStubIndex.KEY
        val keys = ArrayList<String>()
        index.processAllKeys(key, project, Processor<String> { name ->
            if (name.substringAfterLast('.') in methods) keys += name
            true
        })
        val files = LinkedHashSet<VirtualFile>()
        // Keys can outlive their files; asking with the scope drops the dead ones.
        for (name in keys) files += index.getContainingFiles(key, name, project, scope)
        return files
    }

    /** The project file of a dotted module name (`a.b` is `a/b.py` or `a/b/__init__.py`), matched by path suffix; null when absent. */
    fun findModuleFile(project: Project, module: String): VirtualFile? {
        val parts = module.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val scope = GlobalSearchScope.projectScope(project)
        val relative = parts.joinToString("/")
        val modules = FilenameIndex.getVirtualFilesByName(parts.last() + INIT_SUFFIX, scope).filter { it.path.endsWith("/$relative$INIT_SUFFIX") }
        val packages = FilenameIndex.getVirtualFilesByName(INIT_FILE, scope).filter { it.path.endsWith("/$relative/$INIT_FILE") }
        return PyCandidateRules.select(project, modules + packages).firstOrNull()
    }

    /** Whether [file] could contain something the spec extracts: one marker group fully present. No parsing. */
    fun passesPrefilter(file: PsiFile, spec: PyExtractionSpec): Boolean {
        val text = file.viewProvider.contents
        return spec.textMarkers.any { group -> group.all { StringUtil.contains(text, it) } }
    }

    /** Extracts one file with [spec]; null for anything that is not a Python file or holds no routing facts. */
    fun extract(file: PsiFile, spec: PyExtractionSpec): PyFileFacts? {
        if (!passesPrefilter(file, spec)) return null
        val py = file as? PyFile ?: return null
        return PyFactsExtractor.extract(py, spec)
    }

    /** Like [extract] but without the prefilter, and returning imports and constants of files that declare no routes. */
    fun extractDependency(file: PsiFile, spec: PyExtractionSpec): PyFileFacts? {
        val py = file as? PyFile ?: return null
        return PyFactsExtractor.extract(py, spec, asDependency = true)
    }
}
