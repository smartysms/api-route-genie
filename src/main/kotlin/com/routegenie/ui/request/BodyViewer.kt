package com.routegenie.ui.request

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.routegenie.Limits
import java.awt.BorderLayout

/**
 * The plain body viewer: one read-only editor per shown document, released through EditorFactory when replaced or disposed
 * (research 03, 4.1). A finished body arrives as a [Document] built off the UI thread; a live body is appended in bounded
 * pieces and trimmed from the front, so a stream cannot grow the document without limit.
 */
class BodyViewer(private val project: Project, parent: Disposable) : JBPanel<BodyViewer>(BorderLayout()), Disposable {
    private var editor: EditorEx? = null
    private var editorScope: Disposable? = null
    private var document: Document? = null
    private var programmatic = false

    /** Follow the end of a live body. Turned off when the user scrolls up, back on when the end is reached again. */
    var autoScroll = true
        set(value) {
            field = value
            onAutoScrollChanged?.invoke(value)
            if (value) scrollToEnd()
        }
    var onAutoScrollChanged: ((Boolean) -> Unit)? = null
    var live = false

    /** Characters removed from the front of the live document so far. */
    var trimmedChars = 0L
        private set

    val text: String get() = document?.text.orEmpty()
    val textLength: Int get() = document?.textLength ?: 0
    val hasEditor: Boolean get() = editor != null

    init {
        Disposer.register(parent, this)
    }

    /** Builds a document that may be created on any thread and updated on the UI thread without a write action. */
    fun newDocument(text: CharSequence): Document = EditorFactory.getInstance().createDocument(text, false, true)

    fun show(doc: Document, extension: String?, highlight: Boolean, softWrap: Boolean) {
        release()
        trimmedChars = 0
        document = doc
        val created = EditorFactory.getInstance().createViewer(doc, project, EditorKind.MAIN_EDITOR) as EditorEx
        val scope = Disposer.newDisposable(this, "body-viewer-editor")
        created.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isRightMarginShown = false
            isUseSoftWraps = softWrap
            isAdditionalPageAtBottom = false
            isCaretRowShown = false
        }
        if (highlight && extension != null) {
            val type: FileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
            if (type !is PlainTextFileType && !type.isBinary) {
                created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type)
            }
        }
        created.scrollingModel.addVisibleAreaListener({ _ ->
            if (live && !programmatic) {
                val atEnd = atEnd(created)
                if (!atEnd && autoScroll) { autoScroll = false } else if (atEnd && !autoScroll) { autoScroll = true }
            }
        }, scope)
        editor = created
        editorScope = scope
        add(created.component, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun showText(text: CharSequence, extension: String?, highlight: Boolean, softWrap: Boolean) =
        show(newDocument(text), extension, highlight, softWrap)

    fun clear() = showText("", null, false, false)

    /** Appends on the UI thread; [text] is bounded by the caller (see Limits.LIVE_APPEND_MAX_CHARS). */
    fun append(text: String) {
        val doc = document ?: return
        if (text.isEmpty()) return
        doc.insertString(doc.textLength, text)
        val excess = doc.textLength - Limits.VIEWER_DISPLAY_CAP_CHARS
        if (excess > 0) {
            doc.deleteString(0, excess)
            trimmedChars += excess
        }
        if (autoScroll) scrollToEnd()
    }

    private fun atEnd(e: EditorEx): Boolean {
        val area = e.scrollingModel.visibleArea
        return area.y + area.height >= e.contentComponent.height - e.lineHeight * END_SLACK_LINES
    }

    private fun scrollToEnd() {
        val e = editor ?: return
        programmatic = true
        try {
            val doc = e.document
            e.caretModel.moveToOffset(doc.textLength)
            e.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        } finally {
            programmatic = false
        }
    }

    private fun release() {
        val e = editor ?: return
        editor = null
        remove(e.component)
        editorScope?.let { Disposer.dispose(it) }
        editorScope = null
        EditorFactory.getInstance().releaseEditor(e)
        document = null
    }

    override fun dispose() {
        release()
        onAutoScrollChanged = null
    }

    private companion object {
        const val END_SLACK_LINES = 2
    }
}
