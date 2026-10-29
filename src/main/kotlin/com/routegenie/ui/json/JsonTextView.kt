package com.routegenie.ui.json

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.json.JsonIndex
import com.routegenie.json.JsonKind
import com.routegenie.json.JsonPrinter
import com.routegenie.json.JsonSearch
import com.routegenie.json.PagePlan
import com.routegenie.json.PrintObserver
import com.routegenie.json.PrintOptions
import com.routegenie.json.SearchQuery
import com.routegenie.json.StringSink
import com.routegenie.json.TextSearchResult
import com.routegenie.ui.UiAction
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.ToolbarStyle
import java.awt.BorderLayout
import javax.swing.JPanel

internal enum class TextMode { PRETTY, RAW }

/** The text of one page, the fold ranges found while it was produced (start, end, brace) and where a requested node begins. */
internal class PageResult(val text: String, val folds: IntArray, val revealOffset: Int)

/** Where the text of a view comes from, one page at a time. All methods run on a background thread. */
internal interface PageSource {
    val pageCount: Int
    fun render(page: Int, revealNode: Int, revealByte: Long): PageResult
    fun pageOfNode(node: Int): Int
    fun pageOfByte(offset: Long): Int
}

/** Pretty text: the whole document when it is small, otherwise pages cut at the boundaries found by a dry run. */
internal class PrettyPages(private val index: JsonIndex, private val options: PrintOptions) : PageSource {
    @Volatile
    private var plan: PagePlan? = null
    private val full = index.source.length <= Limits.JSON_TEXT_FULL_MAX_CHARS / 2
    override val pageCount: Int get() = if (full) 1 else plan?.pageCount ?: 1
    val isPaged: Boolean get() = !full
    val planReady: Boolean get() = full || plan != null

    fun computePlan(cancelled: () -> Boolean) {
        if (full) return
        plan = JsonPrinter.plan(index, options, Limits.JSON_PAGE_UNITS, cancelled)
    }

    override fun render(page: Int, revealNode: Int, revealByte: Long): PageResult {
        val sink = StringSink()
        val folds = ArrayList<IntArray>()
        val observer = PrintObserver { node, open, close ->
            if (open >= 0 && close - open >= MIN_FOLD_CHARS) folds += intArrayOf(open.toInt(), close.toInt(), if (index.kind(node) == JsonKind.OBJECT) 0 else 1)
        }
        val current = plan
        val offset: Long
        if (full || current == null) {
            val printer = JsonPrinter(index, options)
            printer.observer = observer
            printer.revealNode = revealNode
            printer.begin(index.root)
            printer.step(sink, if (full) Long.MAX_VALUE else Limits.JSON_PAGE_UNITS)
            offset = printer.revealOffset
        } else {
            offset = current.render(index, options, page, sink, observer, revealNode).revealOffset
        }
        return PageResult(sink.builder.toString(), selectFolds(folds), offset.toInt())
    }

    /** The biggest regions win when a page has more than the editor can fold smoothly. */
    private fun selectFolds(all: List<IntArray>): IntArray {
        val chosen = if (all.size <= Limits.JSON_MAX_FOLD_REGIONS) all else all.sortedByDescending { it[1] - it[0] }.take(Limits.JSON_MAX_FOLD_REGIONS)
        val out = IntArray(chosen.size * 3)
        chosen.forEachIndexed { k, f ->
            out[k * 3] = f[0]
            out[k * 3 + 1] = f[1]
            out[k * 3 + 2] = f[2]
        }
        return out
    }

    override fun pageOfNode(node: Int): Int = if (options.sortKeys) 0 else plan?.pageOfNode(node) ?: 0
    override fun pageOfByte(offset: Long): Int = 0

    private companion object {
        const val MIN_FOLD_CHARS = 3
    }
}

/** The document exactly as received: whole when small, otherwise in byte pages aligned to line starts. */
internal class RawPages(private val index: JsonIndex) : PageSource {
    private val source = index.source
    private val pageBytes = Limits.JSON_PAGE_UNITS
    private val full = source.length <= Limits.JSON_TEXT_FULL_MAX_CHARS
    override val pageCount: Int get() = if (full) 1 else ((source.length + pageBytes - 1) / pageBytes).toInt()

    /** First byte of page [k]: its nominal start moved forward to the next line, or to a character boundary. */
    private fun boundary(k: Int): Long {
        if (k <= 0) return 0
        val nominal = k * pageBytes
        if (nominal >= source.length) return source.length
        val window = ByteArray(LOOKAHEAD)
        val got = source.read(nominal, window, 0, window.size)
        for (i in 0 until got) if (window[i] == LF) return nominal + i + 1
        var i = 0
        while (i < got && (window[i].toInt() and CONTINUATION_MASK) == CONTINUATION) i++
        return nominal + i
    }

    override fun render(page: Int, revealNode: Int, revealByte: Long): PageResult {
        val start = if (full) 0L else boundary(page)
        val end = if (full) source.length else boundary(page + 1)
        val bytes = ByteArray((end - start).toInt())
        var done = 0
        while (done < bytes.size) {
            val n = source.readBulk(start + done, bytes, done, bytes.size - done)
            if (n <= 0) break
            done += n
        }
        val target = when {
            revealByte >= 0 -> revealByte
            revealNode >= 0 -> index.start(revealNode).toLong()
            else -> -1L
        }
        val offset = if (target in start until end) String(bytes, 0, (target - start).toInt(), source.charset).length else -1
        return PageResult(String(bytes, 0, done, source.charset), IntArray(0), offset)
    }

    override fun pageOfNode(node: Int): Int = pageOfByte(index.start(node).toLong())

    override fun pageOfByte(offset: Long): Int {
        if (full) return 0
        var k = (offset / pageBytes).toInt().coerceIn(0, pageCount - 1)
        while (k > 0 && boundary(k) > offset) k--
        while (k < pageCount - 1 && boundary(k + 1) <= offset) k++
        return k
    }

    private companion object {
        const val LF = '\n'.code.toByte()
        const val LOOKAHEAD = 4096
        const val CONTINUATION_MASK = 0xC0
        const val CONTINUATION = 0x80
    }
}

internal interface TextViewCallbacks {
    /** The search over the visible text finished; [count] matches, [truncated] when more exist, [regexError] for a bad pattern. */
    fun textMatches(count: Int, truncated: Boolean, regexError: String?)
}

/**
 * Pretty or raw text in the IDE's own editor viewer: syntax colors of the user's theme, line numbers, folding of objects and
 * arrays (from the index, no PSI), soft wrap. Large documents are shown a page at a time; the editor never holds more than one
 * page. One editor is created on first use and its text is replaced per page.
 */
internal class JsonTextView(
    parent: Disposable,
    val mode: TextMode,
    private val callbacks: TextViewCallbacks,
) : JPanel(BorderLayout()), Disposable {
    private var editor: EditorEx? = null
    private var document: Document? = null
    private var scope: Disposable? = null
    private var source: PageSource? = null
    private var index: JsonIndex? = null
    private var page = 0
    private var loadId = 0
    private var searchId = 0
    private var wrap = false
    private var disposed = false

    private var query: SearchQuery? = null
    private var matches: TextSearchResult? = null
    private var current = -1
    private val highlighters = ArrayList<RangeHighlighter>()

    private val pageLabel = JBLabel().apply {
        font = DesignTokens.Type.caption()
        foreground = Palette.textSecondary
        border = JBUI.Borders.empty(0, DesignTokens.Space.S)
    }
    private val pageBar: JPanel
    private val pageToolbar: com.intellij.openapi.actionSystem.ActionToolbar
    private val previousPage = UiAction(RouteGenieBundle.message("json.page.previous"), RouteGenieBundle.message("json.page.previous.tooltip"), com.intellij.icons.AllIcons.Actions.Back, { page > 0 }) {
        showPage(page - 1, -1, -1)
    }
    private val nextPage = UiAction(RouteGenieBundle.message("json.page.next"), RouteGenieBundle.message("json.page.next.tooltip"), com.intellij.icons.AllIcons.Actions.Forward, { source?.let { page < it.pageCount - 1 } == true }) {
        showPage(page + 1, -1, -1)
    }

    init {
        Disposer.register(parent, this)
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup(previousPage, nextPage)
        pageToolbar = ToolbarStyle.create(PAGE_PLACE, group, this)
        pageBar = ToolbarStyle.bar(pageToolbar, pageLabel).also { it.isVisible = false } as JPanel
        add(pageBar, BorderLayout.NORTH)
    }

    val hasEditor: Boolean get() = editor != null
    val currentPage: Int get() = page
    val pageCount: Int get() = source?.pageCount ?: 0
    val text: String get() = document?.text.orEmpty()
    internal val foldRegionCount: Int get() = editor?.foldingModel?.allFoldRegions?.size ?: 0

    /** Shows [index] from the start, or from the page that holds [reveal] when that node is already known to be on page 0 or the text is raw. */
    fun bind(index: JsonIndex, options: PrintOptions = PrintOptions(), reveal: Int = -1) {
        this.index = index
        val s: PageSource = if (mode == TextMode.PRETTY) PrettyPages(index, options) else RawPages(index)
        source = s
        page = 0
        if (reveal >= 0 && s is RawPages) showPage(s.pageOfNode(reveal), reveal, -1) else showPage(0, reveal, -1)
        if (s is PrettyPages && s.isPaged) {
            JsonUi.background {
                s.computePlan { source !== s || disposed }
                JsonUi.later({ disposed || source !== s }) {
                    updatePageBar()
                    // The plan knows which page holds the selected node now; move there if it is not the first one.
                    if (reveal >= 0 && s.pageOfNode(reveal) != 0) reveal(reveal)
                }
            }
        }
    }

    fun setWrap(value: Boolean) {
        wrap = value
        editor?.settings?.isUseSoftWraps = value
    }

    /** Shows the page that contains [node] and puts the caret on it (pretty text only; raw text jumps to the node's bytes). */
    fun reveal(node: Int) {
        val s = source ?: return
        showPage(s.pageOfNode(node), node, -1)
    }

    /** Raw text: shows the page that contains byte [offset] and puts the caret there. */
    fun revealByte(offset: Long) {
        val s = source ?: return
        showPage(s.pageOfByte(offset), -1, offset)
    }

    fun showPage(target: Int, revealNode: Int, revealByte: Long) {
        val s = source ?: return
        val p = target.coerceIn(0, maxOf(0, s.pageCount - 1))
        val id = ++loadId
        JsonUi.background {
            val result = try {
                s.render(p, revealNode, revealByte)
            } catch (e: java.io.IOException) {
                return@background
            }
            JsonUi.later({ disposed || id != loadId }) {
                page = p
                apply(result)
                updatePageBar()
            }
        }
    }

    private fun updatePageBar() {
        val s = source ?: return
        val paged = s.pageCount > 1 || (s as? PrettyPages)?.let { it.isPaged } == true
        pageBar.isVisible = paged
        val pretty = s as? PrettyPages
        pageLabel.text = if (pretty != null && !pretty.planReady) {
            RouteGenieBundle.message("json.page.counting", page + 1)
        } else {
            RouteGenieBundle.message("json.page.of", page + 1, s.pageCount)
        }
        pageToolbar.updateActionsImmediately()
    }

    private fun apply(result: PageResult) {
        val doc = document
        val e = editor
        if (doc == null || e == null) {
            createEditor(result.text)
        } else {
            clearHighlights()
            doc.setText(result.text)
        }
        val ed = editor ?: return
        installFolds(ed, result.folds)
        val offset = result.revealOffset.coerceIn(0, ed.document.textLength)
        ed.caretModel.moveToOffset(if (result.revealOffset >= 0) offset else 0)
        ed.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER_UP)
        ed.scrollingModel.scrollHorizontally(0)
        ed.settings.isUseSoftWraps = wrap || hasLongLine(result.text)
        recomputeSearch()
    }

    private fun hasLongLine(text: String): Boolean {
        var run = 0
        for (c in text) {
            if (c == '\n') run = 0 else if (++run > Limits.VIEWER_LONG_LINE_CHARS) return true
        }
        return false
    }

    private fun installFolds(ed: EditorEx, folds: IntArray) {
        val model = ed.foldingModel
        model.runBatchFoldingOperation {
            for (region in model.allFoldRegions) model.removeFoldRegion(region)
            var k = 0
            while (k + 2 < folds.size) {
                val placeholder = if (folds[k + 2] == 0) OBJECT_PLACEHOLDER else ARRAY_PLACEHOLDER
                model.addFoldRegion(folds[k], folds[k + 1], placeholder)?.isExpanded = true
                k += 3
            }
        }
    }

    private fun createEditor(text: String) {
        val doc = EditorFactory.getInstance().createDocument(text, false, true)
        val created = EditorFactory.getInstance().createViewer(doc) as EditorEx
        val editorScope = Disposer.newDisposable(this, "json-text-editor")
        created.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = mode == TextMode.PRETTY
            isLineMarkerAreaShown = false
            isRightMarginShown = false
            isAdditionalPageAtBottom = false
            isCaretRowShown = true
            isUseSoftWraps = wrap
        }
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(JSON_EXTENSION)
        if (fileType !is PlainTextFileType && !fileType.isBinary) {
            created.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(null as Project?, fileType)
        }
        document = doc
        editor = created
        scope = editorScope
        add(created.component, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // --- search in the visible text -----------------------------------------------------------------------------------

    fun setQuery(q: SearchQuery?) {
        query = q
        recomputeSearch()
    }

    private fun clearHighlights() {
        val markup = editor?.markupModel
        for (h in highlighters) markup?.removeHighlighter(h)
        highlighters.clear()
        matches = null
        current = -1
    }

    private fun recomputeSearch() {
        clearHighlights()
        val q = query
        val doc = document
        if (q == null || q.isEmpty || doc == null) {
            callbacks.textMatches(0, false, null)
            return
        }
        val id = ++searchId
        val chars = doc.immutableCharSequence
        JsonUi.background {
            val result = JsonSearch.searchText(chars, q, cancelled = { id != searchId || disposed })
            JsonUi.later({ disposed || id != searchId }) { applyMatches(result) }
        }
    }

    private fun applyMatches(result: TextSearchResult) {
        matches = result
        val ed = editor
        if (ed != null) {
            val attributes = EditorColorsManager.getInstance().globalScheme.getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)
            val shown = minOf(result.count, MAX_HIGHLIGHTS)
            for (k in 0 until shown) {
                highlighters += ed.markupModel.addRangeHighlighter(
                    result.starts[k], result.starts[k] + result.lengths[k], HighlighterLayer.SELECTION - 1, attributes, HighlighterTargetArea.EXACT_RANGE,
                )
            }
        }
        callbacks.textMatches(result.count, result.truncated, result.regexError)
        if (result.count > 0) goTo(0)
    }

    /** Moves to match [i] (wrapping around) and returns its number, or -1 when there are none. */
    fun goTo(i: Int): Int {
        val m = matches ?: return -1
        if (m.count == 0) return -1
        val n = ((i % m.count) + m.count) % m.count
        current = n
        val ed = editor ?: return n
        val start = m.starts[n]
        ed.caretModel.moveToOffset(start)
        ed.selectionModel.setSelection(start, start + m.lengths[n])
        ed.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
        return n
    }

    fun nextMatch(): Int = goTo(current + 1)
    fun previousMatch(): Int = goTo(current - 1)
    val matchCount: Int get() = matches?.count ?: 0

    fun release() {
        loadId++
        searchId++
        clearHighlights()
        val e = editor
        editor = null
        document = null
        if (e != null) {
            remove(e.component)
            scope?.let { Disposer.dispose(it) }
            scope = null
            EditorFactory.getInstance().releaseEditor(e)
        }
        source = null
        index = null
        page = 0
        pageBar.isVisible = false
    }

    override fun dispose() {
        disposed = true
        release()
    }

    private companion object {
        const val JSON_EXTENSION = "json"
        const val PAGE_PLACE = "RouteGenie.JsonPages"
        const val OBJECT_PLACEHOLDER = "{...}"
        const val ARRAY_PLACEHOLDER = "[...]"
        const val MAX_HIGHLIGHTS = 1_000
    }
}
