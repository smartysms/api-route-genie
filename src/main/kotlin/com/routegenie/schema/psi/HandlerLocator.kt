package com.routegenie.schema.psi

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.routegenie.frameworks.common.PyProviderSupport
import com.routegenie.model.Endpoint

/** What an endpoint's handler is in the code: a function, or a member of a class (a view method or a viewset action). */
internal sealed interface Target {
    class Function(val fn: PyFunction) : Target

    /** [member] is the method or action name when the handler names one (`BookViewSet.create`), else null. */
    class Member(val cls: PyClass, val member: String?, val method: PyFunction?) : Target
}

/**
 * Finds the code of an endpoint from the names a snapshot holds: the project-relative file of the route and the handler's
 * qualified name. The file offset is deliberately not used, because it goes stale with every edit above it.
 */
internal object HandlerLocator {
    fun locate(session: Session, endpoint: Endpoint): Target? {
        val source = projectFile(session, endpoint.source.path)
        // The qualified name is `module.Name` or `module.Class.member`; the longest module prefix that is a project file wins.
        val qualified = endpoint.handler.qualifiedName.split('.').filter { it.isNotEmpty() }
        for (cut in qualified.size - 1 downTo 1) {
            val file = moduleFile(session, source, qualified.take(cut).joinToString(".")) ?: continue
            find(session, file, qualified.drop(cut))?.let { return it }
        }
        // Otherwise the route's own file may name the handler (an imported view), as long as it is the same name.
        val parts = endpoint.handler.name.split('.').filter { it.isNotEmpty() }
        if (source == null || parts.isEmpty() || !endpoint.handler.qualifiedName.endsWith(endpoint.handler.name)) return null
        return find(session, source, parts)
    }

    private fun find(session: Session, file: PyFile, parts: List<String>): Target? {
        val head = session.symbols.lookup(file, parts.first())
        val member = parts.getOrNull(1)
        return when (head) {
            is Sym.Fn -> if (parts.size == 1) Target.Function(head.fn) else null
            is Sym.Cls -> {
                val method = member?.let { Hierarchy(session).findMethod(head.cls, it) }
                Target.Member(head.cls, member, method)
            }
            else -> null
        }
    }

    private fun moduleFile(session: Session, from: PyFile?, module: String): PyFile? {
        from?.let { session.moduleFile(it, module, 0)?.let { found -> return found } }
        val vf = PyProviderSupport.findModuleFile(session.project, module) ?: return null
        return PsiManager.getInstance(session.project).findFile(vf) as? PyFile
    }

    /** The file at a project-relative [path], from the project directory or any content root. */
    fun projectFile(session: Session, path: String): PyFile? {
        val project = session.project
        val vf: VirtualFile? = project.guessProjectDir()?.findFileByRelativePath(path)?.takeIf { it.isValid }
            ?: ProjectRootManager.getInstance(project).contentRoots.firstNotNullOfOrNull { root -> root.findFileByRelativePath(path)?.takeIf { it.isValid } }
        return vf?.let { PsiManager.getInstance(project).findFile(it) as? PyFile }
    }
}
