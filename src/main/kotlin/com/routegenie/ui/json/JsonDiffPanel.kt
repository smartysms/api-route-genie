package com.routegenie.ui.json

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.routegenie.Limits
import com.routegenie.RouteGenieBundle
import com.routegenie.json.DiffKind
import com.routegenie.json.DiffOptions
import com.routegenie.json.DiffResult
import com.routegenie.json.DiffRow
import com.routegenie.json.DiffRows
import com.routegenie.json.JsonContent
import com.routegenie.json.JsonDiff
import com.routegenie.json.JsonDiffRows
import com.routegenie.json.JsonErrors
import com.routegenie.json.JsonIndex
import com.routegenie.json.JsonScanner
import com.routegenie.json.RowKind
import com.routegenie.ui.design.Chip
import com.routegenie.ui.design.DesignTokens
import com.routegenie.ui.design.Divider
import com.routegenie.ui.design.EmptyState
import com.routegenie.ui.design.LoadingState
import com.routegenie.ui.design.Palette
import com.routegenie.ui.design.RouteGenieIcons
import com.routegenie.ui.design.Tone
import com.routegenie.ui.design.ToolbarStyle
import com.routegenie.ui.design.color.PaletteSpec
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/** What the options bar of the diff holds; G3 can preset it, and the panel reports changes through the same type. */
class JsonDiffSettings(
    val ignoreKeyOrder: Boolean = true,
    /** Keys or paths to skip, separated by commas or spaces. A token that starts with `$` is a path, anything else a key. */
    val ignore: String = "",
    /** Match array items by the value of this member instead of by position; empty for by position. */
    val arrayKey: String = "",
    val numericEquality: Boolean = true,
) {
    internal fun toOptions(): DiffOptions {
        val tokens = ignore.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        return DiffOptions(
            ignoreKeyOrder = ignoreKeyOrder,
            ignoreKeys = tokens.filter { !it.startsWith("$") }.toSet(),
            ignorePaths = tokens.filter { it.startsWith("$") },
            arrayKey = arrayKey.trim().ifEmpty { null },
            numericEquality = numericEquality,
        )
    }
}

/**
 * Compares two JSON documents and shows what differs, side by side or as one unified column, with unchanged regions collapsed.
 * Added, removed and changed values get the window's success, danger and warning tones. Options (ignore key order, ignore keys
 * or paths, match array items by a key, numbers by value) apply without reading the documents again.
 *
 * The documents are indexed and compared on background threads; both indexes are released when the panel is cleared, shows
 * another pair, or is disposed.
 */
class JsonDiffPanel(parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private var disposed = false
    private var generation = 0

    /** Indexes built by a background comparison that the panel has not taken over yet; closed if the comparison is dropped. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<JsonIndex>()
    private var left: JsonIndex? = null
    private var right: JsonIndex? = null
    private var result: DiffResult? = null
    private var rows: DiffRows? = null
    private var leftTitle = ""
    private var rightTitle = ""
    private var settings = JsonDiffSettings()
    private var unified = false
    private var groupCursor = -1
    private var currentCard = CARD_EMPTY

    private val cards = JPanel(CardLayout())
    private val view = DiffView()
    private val summary = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(DesignTokens.Space.XS), 0)).apply { isOpaque = false }
    private val message = JPanel(java.awt.BorderLayout())
    private val titles = JPanel(GridLayout(1, 2))
    private val leftTitleLabel = titleLabel()
    private val rightTitleLabel = titleLabel()
    private val ignoreField = SearchTextField(false)
    private val keyField = SearchTextField(false)
    private val loadingHost = JPanel(BorderLayout())
    private var ignoreOrder = true
    private var numeric = true

    init {
        Disposer.register(parent, this)
        cards.add(EmptyState(RouteGenieIcons.PRETTY_PRINT, RouteGenieBundle.message("json.diff.empty"), RouteGenieBundle.message("json.diff.empty.hint")), CARD_EMPTY)
        cards.add(loadingHost, CARD_LOADING)
        cards.add(message, CARD_MESSAGE)
        cards.add(buildResultPage(), CARD_RESULT)
        add(buildOptionsBar(), BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        showCard(CARD_EMPTY)
    }

    private fun titleLabel() = JBLabel().apply {
        font = DesignTokens.Type.captionStrong()
        foreground = Palette.textSecondary
        border = JBUI.Borders.empty(DesignTokens.Space.XS, DesignTokens.Space.M)
    }

    private fun buildResultPage(): JComponent {
        titles.isOpaque = true
        titles.background = Palette.raised
        titles.border = Divider.bottom()
        titles.add(leftTitleLabel)
        titles.add(rightTitleLabel)
        val scroll = JBScrollPane(view).apply { border = JBUI.Borders.empty() }
        return JPanel(BorderLayout()).apply {
            add(titles, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun buildOptionsBar(): JComponent {
        ignoreField.textEditor.emptyText.text = RouteGenieBundle.message("json.diff.ignore.placeholder")
        keyField.textEditor.emptyText.text = RouteGenieBundle.message("json.diff.array_key.placeholder")
        ignoreField.textEditor.toolTipText = RouteGenieBundle.message("json.diff.ignore.tooltip")
        keyField.textEditor.toolTipText = RouteGenieBundle.message("json.diff.array_key.tooltip")
        val onEdit = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = optionsChanged()
        }
        ignoreField.addDocumentListener(onEdit)
        keyField.addDocumentListener(onEdit)
        val group = DefaultActionGroup(
            JsonToggle(RouteGenieBundle.message("json.diff.ignore_order"), RouteGenieBundle.message("json.diff.ignore_order.tooltip"), AllIcons.Actions.GroupByModuleGroup, get = { ignoreOrder }) {
                ignoreOrder = it
                optionsChanged()
            },
            JsonToggle(RouteGenieBundle.message("json.diff.numeric"), RouteGenieBundle.message("json.diff.numeric.tooltip"), AllIcons.Actions.Refresh, get = { numeric }) {
                numeric = it
                optionsChanged()
            },
            JsonToggle(RouteGenieBundle.message("json.diff.unified"), RouteGenieBundle.message("json.diff.unified.tooltip"), AllIcons.Actions.PreviewDetailsVertically, get = { unified }) {
                unified = it
                showRows()
            },
            JsonAction(RouteGenieBundle.message("json.diff.previous"), RouteGenieBundle.message("json.diff.previous.tooltip"), AllIcons.Actions.PreviousOccurence, enabled = { (rows?.groupStarts?.size ?: 0) > 0 }) { step(-1) },
            JsonAction(RouteGenieBundle.message("json.diff.next"), RouteGenieBundle.message("json.diff.next.tooltip"), AllIcons.Actions.NextOccurence, enabled = { (rows?.groupStarts?.size ?: 0) > 0 }) { step(1) },
            JsonAction(RouteGenieBundle.message("json.diff.copy"), RouteGenieBundle.message("json.diff.copy.tooltip"), RouteGenieIcons.COPY, enabled = { rows != null }) { copyDiff(false) },
            JsonAction(RouteGenieBundle.message("json.diff.copy_paths"), RouteGenieBundle.message("json.diff.copy_paths.tooltip"), AllIcons.Actions.Copy, enabled = { result != null }) { copyDiff(true) },
        )
        val toolbar = ToolbarStyle.create(TOOLBAR_PLACE, group, this)
        val fields = JPanel(GridLayout(1, 2, JBUI.scale(DesignTokens.Space.S), 0)).apply {
            isOpaque = false
            add(ignoreField)
            add(keyField)
        }
        val top = ToolbarStyle.bar(toolbar, fields)
        val chips = JPanel(BorderLayout()).apply {
            background = Palette.raised
            border = JBUI.Borders.merge(DesignTokens.Space.border(DesignTokens.Space.XS, DesignTokens.Space.M), Divider.bottom(), false)
            add(summary, BorderLayout.CENTER)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(top)
            add(chips)
        }
    }

    // --- API ---------------------------------------------------------------------------------------------------------------------

    /** Presets the options bar; if a comparison is shown it is redone. */
    fun configure(newSettings: JsonDiffSettings) {
        settings = newSettings
        ignoreOrder = newSettings.ignoreKeyOrder
        numeric = newSettings.numericEquality
        silently {
            ignoreField.text = newSettings.ignore
            keyField.text = newSettings.arrayKey
        }
        if (left != null && right != null) recompute()
    }

    /** Compares [leftContent] with [rightContent] (the left one is the base, for example the previous response). */
    fun compare(leftContent: JsonContent, rightContent: JsonContent, leftTitle: String, rightTitle: String) {
        release()
        this.leftTitle = leftTitle
        this.rightTitle = rightTitle
        leftTitleLabel.text = leftTitle
        rightTitleLabel.text = rightTitle
        val id = ++generation
        if (leftContent.sizeBytes > Limits.JSON_DIFF_MAX_BYTES || rightContent.sizeBytes > Limits.JSON_DIFF_MAX_BYTES) {
            showMessage(RouteGenieBundle.message("json.diff.too_large", JsonUi.size(Limits.JSON_DIFF_MAX_BYTES)), "")
            return
        }
        showLoading()
        JsonUi.background {
            val a = scanQuietly(leftContent)
            val b = scanQuietly(rightContent)
            a?.let { inFlight += it }
            b?.let { inFlight += it }
            val problem = problemOf(a, b, id)
            if (problem != null) {
                closeInFlight(a, b)
                JsonUi.later({ disposed || id != generation }) { showMessage(problem.first, problem.second) }
                return@background
            }
            val diff = JsonDiff.compare(a!!, b!!, settings.toOptions()) { disposed || id != generation }
            if (diff == null) {
                closeInFlight(a, b)
                return@background
            }
            val built = JsonDiffRows.build(diff)
            JsonUi.later({ disposed }) {
                if (id != generation) {
                    closeInFlight(a, b)
                    return@later
                }
                inFlight -= a
                inFlight -= b
                left = a
                right = b
                result = diff
                rows = built
                showResult()
            }
        }
    }

    fun clear() {
        generation++
        release()
        showCard(CARD_EMPTY)
    }

    /** The differences of the last comparison, for tests and for callers that want the numbers. */
    fun differences(): DiffResult? = result

    override fun dispose() {
        disposed = true
        generation++
        release()
        // Documents scanned for a comparison that finished after the panel was gone.
        for (index in inFlight.toList()) closeInFlight(index)
    }

    private fun closeInFlight(vararg indexes: JsonIndex?) {
        for (index in indexes) {
            if (index != null && inFlight.remove(index)) index.close()
        }
    }

    // --- internals ---------------------------------------------------------------------------------------------------------------

    private fun scanQuietly(content: JsonContent): JsonIndex? = try {
        JsonScanner.scan(content)
    } catch (e: java.io.IOException) {
        null
    }

    /** A message for an unreadable or invalid side, or null when both can be compared. */
    private fun problemOf(a: JsonIndex?, b: JsonIndex?, id: Int): Pair<String, String>? {
        for ((side, index) in listOf(leftTitle to a, rightTitle to b)) {
            if (index == null) return RouteGenieBundle.message("json.diff.unreadable", side) to ""
            val error = index.error
            if (error != null) {
                val where = JsonErrors.locate(index.source, error.offset)
                val reason = RouteGenieBundle.message(error.code.bundleKey, error.detail ?: "")
                return RouteGenieBundle.message("json.diff.invalid", side) to
                    RouteGenieBundle.message("json.diff.invalid.hint", where.line, where.column, reason)
            }
        }
        return if (id == generation) null else (RouteGenieBundle.message("json.diff.empty") to "")
    }

    private fun release() {
        val a = left
        val b = right
        left = null
        right = null
        result = null
        rows = null
        groupCursor = -1
        view.setLines(emptyList())
        summary.removeAll()
        a?.close()
        b?.close()
    }

    private var silent = false
    private fun silently(block: () -> Unit) {
        silent = true
        try {
            block()
        } finally {
            silent = false
        }
    }

    private fun optionsChanged() {
        if (silent) return
        settings = JsonDiffSettings(ignoreOrder, ignoreField.text, keyField.text, numeric)
        if (left != null && right != null) recompute()
    }

    private fun recompute() {
        val a = left ?: return
        val b = right ?: return
        val id = ++generation
        val options = settings.toOptions()
        JsonUi.background {
            val diff = JsonDiff.compare(a, b, options) { disposed || id != generation } ?: return@background
            val built = JsonDiffRows.build(diff)
            JsonUi.later({ disposed || id != generation }) {
                result = diff
                rows = built
                groupCursor = -1
                showResult()
            }
        }
    }

    private fun showLoading() {
        loadingHost.removeAll()
        loadingHost.add(LoadingState(this, RouteGenieBundle.message("json.diff.loading")), BorderLayout.CENTER)
        loadingHost.revalidate()
        showCard(CARD_LOADING)
    }

    private fun showMessage(title: String, hint: String) {
        message.removeAll()
        message.add(EmptyState(RouteGenieIcons.EMPTY_SEARCH, title, hint.ifEmpty { null }), BorderLayout.CENTER)
        message.revalidate()
        showCard(CARD_MESSAGE)
    }

    private fun showCard(name: String) {
        currentCard = name
        (cards.layout as CardLayout).show(cards, name)
    }

    internal fun cardName(): String = currentCard
    internal fun lineCount(): Int = view.lineCount()
    internal fun summaryText(): String = (0 until summary.componentCount).joinToString(" ") { (summary.getComponent(it) as? Chip)?.text.orEmpty() }
    internal fun view(): JComponent = view

    private fun showResult() {
        val r = result ?: return
        summary.removeAll()
        if (r.identical) {
            summary.add(Chip(RouteGenieBundle.message("json.diff.identical"), Palette.success))
            showRows()
            showCard(CARD_RESULT)
            return
        }
        chip(r.count(DiffKind.ADDED), "json.diff.added", Palette.success)
        chip(r.count(DiffKind.REMOVED), "json.diff.removed", Palette.danger)
        chip(r.count(DiffKind.CHANGED), "json.diff.changed", Palette.warning)
        chip(r.count(DiffKind.TYPE_CHANGED), "json.diff.type_changed", Palette.warning)
        chip(r.count(DiffKind.ORDER_CHANGED), "json.diff.order_changed", Palette.info)
        if (r.notShown > 0) summary.add(Chip(RouteGenieBundle.message("json.diff.more", r.notShown), Palette.neutral))
        summary.revalidate()
        summary.repaint()
        showRows()
        showCard(CARD_RESULT)
    }

    private fun chip(count: Int, key: String, tone: Tone) {
        if (count > 0) summary.add(Chip(RouteGenieBundle.message(key, count), tone))
    }

    private fun showRows() {
        val built = rows ?: return
        view.setLines(buildLines(built.rows, unified))
        titles.isVisible = !unified
        view.revalidate()
        view.repaint()
    }

    private fun buildLines(source: List<DiffRow>, asUnified: Boolean): List<DiffLine> {
        val out = ArrayList<DiffLine>(source.size + source.size / 8)
        for ((i, row) in source.withIndex()) {
            if (asUnified && row.kind == RowKind.CHANGED) {
                out += DiffLine(RowKind.REMOVED, row.level, row.left, null, i)
                out += DiffLine(RowKind.ADDED, row.level, null, row.right, i)
            } else if (asUnified) {
                val text = row.left ?: row.right
                out += DiffLine(row.kind, row.level, if (row.kind == RowKind.ADDED) null else text, if (row.kind == RowKind.ADDED) text else null, i)
            } else {
                out += DiffLine(row.kind, row.level, row.left, row.right, i)
            }
        }
        return out
    }

    private fun step(delta: Int) {
        val built = rows ?: return
        val starts = built.groupStarts
        if (starts.isEmpty()) return
        groupCursor = ((groupCursor + delta) % starts.size + starts.size) % starts.size
        view.scrollToRow(starts[groupCursor])
    }

    private fun copyDiff(asPaths: Boolean) {
        val r = result ?: return
        val built = rows ?: return
        JsonUi.copy(if (asPaths) JsonDiffRows.pathLines(r) else JsonDiffRows.unifiedText(built, leftTitle, rightTitle))
    }

    // --- the painted rows ----------------------------------------------------------------------------------------------------------

    private class DiffLine(val kind: RowKind, val level: Int, val left: String?, val right: String?, val row: Int)

    /** Paints only the rows in view; its height is rows times the fixed row height, so a long diff costs nothing until scrolled. */
    private inner class DiffView : JComponent(), Scrollable {
        private var lines: List<DiffLine> = emptyList()
        private var selected = -1

        init {
            isOpaque = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val k = e.y / rowHeight()
                    selected = if (k in lines.indices) k else -1
                    repaint()
                }
            })
            toolTipText = ""
        }

        fun setLines(value: List<DiffLine>) {
            lines = value
            selected = -1
            revalidate()
            repaint()
        }

        fun lineCount(): Int = lines.size

        fun scrollToRow(row: Int) {
            val k = lines.indexOfFirst { it.row >= row }
            if (k < 0) return
            val rect = Rectangle(0, k * rowHeight(), 1, rowHeight())
            scrollRectToVisible(Rectangle(rect.x, maxOf(0, rect.y - rowHeight() * SCROLL_MARGIN_ROWS), rect.width, rect.height + 2 * rowHeight() * SCROLL_MARGIN_ROWS))
            selected = k
            repaint()
        }

        private fun rowHeight(): Int = getFontMetrics(DesignTokens.Type.mono()).height + 2 * JBUI.scale(DesignTokens.Space.HALF)

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(PREFERRED_WIDTH), lines.size * rowHeight())

        override fun getToolTipText(event: MouseEvent): String? {
            val line = lines.getOrNull(event.y / rowHeight()) ?: return null
            val half = width / 2
            val text = if (unified) (line.left ?: line.right) else if (event.x < half) line.left else line.right
            return text?.takeIf { it.length > TOOLTIP_MIN_CHARS }
        }

        private fun tint(tone: Tone): java.awt.Color = ColorUtil.mix(Palette.surface, tone.fill, PaletteSpec.TINT_ALPHA)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = Palette.surface
            g2.fillRect(0, 0, width, height)
            val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
            val rh = rowHeight()
            val first = (clip.y / rh).coerceAtLeast(0)
            val last = ((clip.y + clip.height) / rh + 1).coerceAtMost(lines.size - 1)
            val added = tint(Palette.success)
            val removed = tint(Palette.danger)
            val changed = tint(Palette.warning)
            val font = DesignTokens.Type.mono()
            g2.font = font
            val fm = g2.fontMetrics
            val baseline = (rh - fm.height) / 2 + fm.ascent
            val markerWidth = JBUI.scale(MARKER_WIDTH)
            val indent = fm.charWidth(' ') * INDENT_CHARS
            val sideWidth = if (unified) width else width / 2
            for (k in first..last) {
                val line = lines[k]
                val y = k * rh
                if (k == selected) {
                    g2.color = Palette.selection
                    g2.fillRect(0, y, width, rh)
                }
                if (unified) {
                    paintSide(g2, line.left ?: line.right, line.kind, 0, sideWidth, y, rh, baseline, markerWidth, indent, line.level, added, removed, changed, line.kind == RowKind.ADDED, k == selected)
                } else {
                    val leftKind = when (line.kind) { RowKind.ADDED -> null; RowKind.CHANGED -> RowKind.REMOVED; else -> line.kind }
                    val rightKind = when (line.kind) { RowKind.REMOVED -> null; RowKind.CHANGED -> RowKind.ADDED; else -> line.kind }
                    paintSide(g2, line.left, leftKind, 0, sideWidth, y, rh, baseline, markerWidth, indent, line.level, added, removed, changed, false, k == selected)
                    paintSide(g2, line.right, rightKind, sideWidth, width - sideWidth, y, rh, baseline, markerWidth, indent, line.level, added, removed, changed, true, k == selected)
                }
            }
            if (!unified) {
                g2.color = Palette.border
                g2.fillRect(sideWidth, clip.y, JBUI.scale(DesignTokens.Stroke.HAIRLINE), clip.height)
            }
        }

        private fun paintSide(
            g: Graphics2D, text: String?, kind: RowKind?, x: Int, w: Int, y: Int, rh: Int, baseline: Int, markerWidth: Int,
            indent: Int, level: Int, added: java.awt.Color, removed: java.awt.Color, changed: java.awt.Color, rightSide: Boolean, selectedRow: Boolean,
        ) {
            if (kind == null || text == null && kind != RowKind.COLLAPSED) return
            val background = when (kind) {
                RowKind.ADDED -> added
                RowKind.REMOVED -> removed
                RowKind.CHANGED -> changed
                else -> null
            }
            if (background != null && !selectedRow) {
                g.color = background
                g.fillRect(x, y, w, rh)
            }
            val marker = when (kind) {
                RowKind.ADDED -> "+"
                RowKind.REMOVED -> "-"
                RowKind.CHANGED -> "~"
                else -> ""
            }
            val fm = g.fontMetrics
            val tone = when (kind) {
                RowKind.ADDED -> Palette.success.text
                RowKind.REMOVED -> Palette.danger.text
                RowKind.CHANGED -> Palette.warning.text
                else -> Palette.textSecondary
            }
            g.color = if (selectedRow) Palette.selectionText else tone
            if (marker.isNotEmpty()) g.drawString(marker, x + (markerWidth - fm.stringWidth(marker)) / 2, y + baseline)
            val old = g.clip
            g.clipRect(x + markerWidth, y, w - markerWidth, rh)
            val label = if (kind == RowKind.COLLAPSED) RouteGenieBundle.message("json.diff.unchanged_rows", collapsedCount(y / rh)) else text.orEmpty()
            g.color = if (selectedRow) Palette.selectionText else if (kind == RowKind.CONTEXT || kind == RowKind.COLLAPSED || kind == RowKind.NOTICE) Palette.textSecondary else Palette.textPrimary
            g.drawString(label, x + markerWidth + level * indent, y + baseline)
            g.clip = old
        }

        private fun collapsedCount(lineIndex: Int): Int = rows?.rows?.getOrNull(lines.getOrNull(lineIndex)?.row ?: -1)?.collapsedCount ?: 0

        override fun getPreferredScrollableViewportSize(): Dimension = Dimension(JBUI.scale(PREFERRED_WIDTH), JBUI.scale(PREFERRED_HEIGHT))
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = rowHeight()
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height - rowHeight() else visibleRect.width

        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private companion object {
        const val CARD_EMPTY = "empty"
        const val CARD_LOADING = "loading"
        const val CARD_MESSAGE = "message"
        const val CARD_RESULT = "result"
        const val TOOLBAR_PLACE = "RouteGenie.JsonDiff"
        const val MARKER_WIDTH = 20
        const val INDENT_CHARS = 2
        const val PREFERRED_WIDTH = 600
        const val PREFERRED_HEIGHT = 300
        const val SCROLL_MARGIN_ROWS = 3
        const val TOOLTIP_MIN_CHARS = 60
    }
}
