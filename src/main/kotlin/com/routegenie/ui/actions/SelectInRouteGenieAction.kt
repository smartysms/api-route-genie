package com.routegenie.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.jetbrains.python.psi.PyDecoratable
import com.jetbrains.python.psi.PyStatement
import com.routegenie.discovery.ProjectPaths
import com.routegenie.ui.gutter.GutterModel
import com.routegenie.ui.gutter.LineEntry
import com.routegenie.ui.gutter.RouteGutterIndex
import com.routegenie.ui.gutter.RouteNavigation

/**
 * Finds the endpoint declaration around a caret using only the line index plus a short walk up the tree; no resolve.
 * "Around" means the declaration's own lines: inside a decorator or call, or, for a decorated function, on the `def` line.
 */
object CaretEndpoint {
    fun find(model: GutterModel, path: String, file: PsiFile, document: Document, offset: Int): LineEntry? {
        val lines = model.linesOf(path) ?: return null
        fun lineOf(element: PsiElement) = document.getLineNumber(element.textRange.startOffset) + 1
        val caretLine = document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1
        if (!PsiDocumentManager.getInstance(file.project).isCommitted(document)) return lines[caretLine]
        var element: PsiElement? = file.findElementAt(offset) ?: file.findElementAt(offset - 1)
        while (element != null && element !is PsiFile) {
            if (element is PyDecoratable) {
                // Inside the body the function's own start line (its first decorator) must not count.
                val name = (element as? PsiNameIdentifierOwner)?.nameIdentifier
                if (name != null && lineOf(name) == caretLine) {
                    element.decoratorList?.decorators?.firstNotNullOfOrNull { lines[lineOf(it)] }?.let { return it }
                }
                return null
            }
            lines[lineOf(element)]?.let { return it }
            if (element is PyStatement) return null
            element = element.parent
        }
        return null
    }
}

/** "Select in <product>": reveals the endpoint declared at the caret in the tool window. Disabled elsewhere. */
class SelectInRouteGenieAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val enabled = e.project != null && entryFor(e) != null
        e.presentation.isEnabled = enabled
        // A context menu full of disabled entries is noise; the main menu keeps it visible so it can be found.
        e.presentation.isVisible = enabled || !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val entry = entryFor(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        RouteNavigation.open(project, entry) { popup ->
            if (editor != null) popup.showInBestPositionFor(editor) else popup.showInFocusCenter()
        }
    }

    private fun entryFor(e: AnActionEvent): LineEntry? {
        val project = e.project ?: return null
        val model = RouteGutterIndex.getInstance(project).model
        if (model.isEmpty) return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val path = ProjectPaths.relative(project, virtualFile)
        if (model.linesOf(path) == null) return null
        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document) ?: return null
        return CaretEndpoint.find(model, path, file, document, editor.caretModel.offset)
    }
}
