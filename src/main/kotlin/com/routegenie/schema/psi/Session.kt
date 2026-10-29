package com.routegenie.schema.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.python.psi.PyFile
import com.routegenie.Limits
import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Severity
import java.util.IdentityHashMap

/** Thrown when one extraction has used up its time or work budget; the endpoint then has no template. */
internal class BudgetExceeded : RuntimeException("extraction budget exceeded", null, false, false)

/** Why a field, a model or a body was left out. Internal vocabulary: the names only appear in logs and diagnostics. */
internal enum class Gap {
    UNRESOLVED_TYPE,
    UNRESOLVED_ALIAS,
    UNRESOLVED_DEFAULT,
    UNRESOLVED_CLASS,
    UNRESOLVED_SOURCE,
    UNSUPPORTED_FIELD,
    UNSUPPORTED_BODY,
    NOT_REPRESENTABLE,
    NO_SCHEMA_DECLARED,
    AMBIGUOUS,
}

/**
 * State of one extraction call: budgets, the per-file symbol tables, and what was left out. Lives for one call inside one
 * read action and is dropped afterwards, so it may hold PSI; nothing it holds is ever stored in the schema cache.
 */
internal class Session(val project: Project) {
    private val deadline = System.nanoTime() + Limits.SCHEMA_EXTRACT_BUDGET_MS * NANOS_PER_MS
    private var classesRead = 0
    private var nodesBuilt = 0
    private val tables = IdentityHashMap<PyFile, FileSyms>()
    private val modules = HashMap<String, PyFile?>()

    val diagnostics = ArrayList<Diagnostic>()
    val validators = LinkedHashSet<String>()
    val symbols = Symbols(this)

    /** Cancellation and the time budget; cheap enough to call per class and per field. */
    fun check() {
        ProgressManager.checkCanceled()
        if (System.nanoTime() > deadline) throw BudgetExceeded()
    }

    fun enterClass() {
        check()
        if (++classesRead > Limits.SCHEMA_MAX_CLASSES_PER_CALL) throw BudgetExceeded()
    }

    fun node() {
        if (++nodesBuilt > Limits.SCHEMA_MAX_NODES) throw BudgetExceeded()
    }

    fun skipped(where: String, reason: Gap) {
        diagnostics += Diagnostic(Severity.INFO, DiagnosticCode.SCHEMA_FIELD_SKIPPED, listOf(where, reason.name))
    }

    fun undetermined(reason: Gap, detail: String = "") {
        diagnostics += Diagnostic(Severity.INFO, DiagnosticCode.SCHEMA_NOT_DETERMINED, listOf(if (detail.isEmpty()) reason.name else "${reason.name}: $detail"))
    }

    fun table(file: PyFile): FileSyms = tables.getOrPut(file) { FileSyms(file) }

    /**
     * The project file that the dotted [module] names when written in [from] with [level] leading dots, or null when the
     * project has none (a library). Absolute names are looked up from the importing file's directory upwards, which finds the
     * source root without asking the IDE for roots, and only inside the content root of the importing file.
     */
    fun moduleFile(from: PyFile, module: String, level: Int): PyFile? {
        val directory = from.virtualFile?.parent ?: from.originalFile.virtualFile?.parent ?: return null
        val key = "${directory.path}|$level|$module"
        if (modules.containsKey(key)) return modules[key]
        val found = locate(from, directory, module, level)
        modules[key] = found
        return found
    }

    private fun locate(from: PyFile, directory: VirtualFile, module: String, level: Int): PyFile? {
        val parts = module.split('.').filter { it.isNotEmpty() }
        if (level > 0) {
            var base: VirtualFile? = directory
            repeat(level - 1) { base = base?.parent }
            return base?.let { fileIn(it, parts) }
        }
        if (parts.isEmpty()) return null
        val ceiling = ProjectFileIndex.getInstance(project).let { it.getContentRootForFile(directory) ?: it.getSourceRootForFile(directory) }
        var current: VirtualFile? = directory
        var hops = 0
        while (current != null && hops++ <= Limits.SCHEMA_RESOLVE_DEPTH) {
            fileIn(current, parts)?.let { return it }
            if (current == ceiling) break
            current = current.parent
        }
        return null
    }

    private fun fileIn(directory: VirtualFile, parts: List<String>): PyFile? {
        val vf = if (parts.isEmpty()) {
            directory.findChild(INIT)
        } else {
            val relative = parts.joinToString("/")
            directory.findFileByRelativePath("$relative.py") ?: directory.findFileByRelativePath("$relative/$INIT")
        }
        if (vf == null || vf.isDirectory) return null
        return PsiManager.getInstance(project).findFile(vf) as? PyFile
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val INIT = "__init__.py"
    }
}
