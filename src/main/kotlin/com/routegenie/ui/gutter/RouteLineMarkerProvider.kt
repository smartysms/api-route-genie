package com.routegenie.ui.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.MergeableLineMarkerInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import com.routegenie.Branding
import com.routegenie.RouteGenieBundle
import com.routegenie.discovery.ProjectPaths
import com.routegenie.settings.RouteGenieSettings
import java.util.function.Supplier
import javax.swing.Icon

/** One icon on the first token of a line that declares endpoints; several methods or paths on a line share it. */
class RouteMarkerInfo(element: PsiElement, val entry: LineEntry) : MergeableLineMarkerInfo<PsiElement>(
    element,
    element.textRange,
    GutterIcons.ENDPOINT,
    Function<PsiElement, String> { entry.label },
    RouteNavigation.handler(entry),
    GutterIconRenderer.Alignment.RIGHT,
    Supplier { RouteGenieBundle.message("gutter.accessible", entry.label, Branding.SHORT) },
) {
    override fun canMergeWith(info: MergeableLineMarkerInfo<*>): Boolean = info is RouteMarkerInfo

    override fun getCommonIcon(infos: List<MergeableLineMarkerInfo<*>>): Icon = GutterIcons.ENDPOINT

    override fun getCommonTooltip(infos: List<MergeableLineMarkerInfo<*>>): Function<in PsiElement, String> {
        val text = infos.filterIsInstance<RouteMarkerInfo>().joinToString("; ") { it.entry.label }
        return Function { text }
    }

    override fun getElementPresentation(element: PsiElement): String = entry.label
}

/**
 * Markers come from [RouteGutterIndex], never from PSI analysis or resolve: the work per file is one map lookup plus a
 * scan of the already built tree for the first token of each indexed line. It runs in the slow pass, i.e. on the
 * daemon's background thread, never on the UI thread.
 */
class RouteLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = RouteGenieBundle.message("gutter.name", Branding.SHORT)

    override fun getIcon(): Icon = GutterIcons.ENDPOINT

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        if (elements.isEmpty() || !RouteGenieSettings.getInstance().showGutterIcons) return
        val file = elements[0].containingFile ?: return
        val virtualFile = file.originalFile.virtualFile ?: return
        if (virtualFile.extension != PYTHON_EXTENSION) return
        val project = file.project
        val index = RouteGutterIndex.getInstance(project)
        val lines = index.model.linesOf(ProjectPaths.relative(project, virtualFile))
        if (lines == null) {
            if (!index.isActivated) index.activateIfRouteFile(file.viewProvider.contents)
            return
        }
        val document = file.viewProvider.document ?: return
        collect(document, lines, elements, result)
    }

    companion object {
        private const val PYTHON_EXTENSION = "py"

        /** Public for tests: the marker computation without any service lookup. */
        fun collect(document: Document, lines: Map<Int, LineEntry>, elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
            val text = document.immutableCharSequence
            val anchors = HashMap<Int, Int>(lines.size * 2)
            for (line in lines.keys) {
                if (line < 1 || line > document.lineCount) continue
                var offset = document.getLineStartOffset(line - 1)
                val end = document.getLineEndOffset(line - 1)
                while (offset < end && text[offset].isWhitespace()) offset++
                if (offset < end) anchors[offset] = line
            }
            if (anchors.isEmpty()) return
            for (element in elements) {
                if (element.firstChild != null) continue
                val line = anchors[element.textRange.startOffset] ?: continue
                result += RouteMarkerInfo(element, lines.getValue(line))
            }
        }
    }
}
