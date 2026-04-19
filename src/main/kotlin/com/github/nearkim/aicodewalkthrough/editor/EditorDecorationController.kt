package com.github.nearkim.aicodewalkthrough.editor

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.nio.file.Path
import javax.swing.Icon

@Service(Service.Level.PROJECT)
class EditorDecorationController(private val project: Project) : Disposable {

    private val activeHighlighters = mutableListOf<RangeHighlighter>()
    private val activeInlays = mutableListOf<Inlay<*>>()

    private var attachedDocument: Document? = null
    private var attachedListener: DocumentListener? = null

    fun showStep(
        step: FlowStep,
        stepIndex: Int,
        totalSteps: Int,
        nextStep: FlowStep? = null,
        nextEdge: StepEdge? = null,
    ) {
        clearDecorations()

        if (step.broken) return

        val basePath = project.basePath ?: return
        val virtualFile = LocalFileSystem.getInstance()
            .findFileByNioFile(Path.of(basePath).resolve(step.filePath))
            ?: return

        FileEditorManager.getInstance(project).openFile(virtualFile, true)

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        if (document.lineCount == 0) return

        val startLine = (step.startLine - 1).coerceIn(0, document.lineCount - 1)
        val endLine = (step.endLine - 1).coerceIn(startLine, document.lineCount - 1)
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)

        // 1. Background highlighter on the step range.
        val bgAttrs = TextAttributes().apply {
            backgroundColor = JBColor(Color(236, 244, 252), Color(43, 54, 68))
        }
        val bgHighlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION - 1,
            bgAttrs,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        activeHighlighters.add(bgHighlighter)

        // 2. Header inlay.
        val accent = accentColorFor(step)
        val headerLabel = "Step ${stepIndex + 1}/$totalSteps · ${step.title} · ${step.filePath}:${startLine + 1}-${endLine + 1}"
        val headerHint = "← prev   → next   esc to stop"
        val headerRenderer = HeaderRenderer(editor, headerLabel, headerHint, accent)
        editor.inlayModel.addBlockElement(startOffset, true, true, 100, headerRenderer)?.let {
            activeInlays.add(it)
        }

        // 3. Summary inlay — below the header.
        val summaryText = step.explanation.ifBlank { step.whyIncluded }
        if (summaryText.isNotBlank()) {
            val summaryRenderer = SummaryRenderer(editor, summaryText)
            editor.inlayModel.addBlockElement(startOffset, true, true, 200, summaryRenderer)?.let {
                activeInlays.add(it)
            }
        }

        // 4. Per-line annotation inlays.
        step.lineAnnotations.forEach { annotation ->
            addAnnotationInlay(editor, annotation, document, startLine, endLine)
        }

        // 5. Next-hop marker.
        val nextAttrs = TextAttributes().apply {
            effectColor = JBColor(Color(180, 110, 0), Color(220, 160, 60))
            effectType = EffectType.BOLD_LINE_UNDERSCORE
        }
        val nextTooltip = nextStep?.let { "Next: ${it.title}" }
        val renderedNextHop = if (
            nextStep != null &&
            nextEdge != null &&
            nextEdge.callSiteFilePath == step.filePath &&
            nextEdge.callSiteStartLine != null &&
            nextEdge.callSiteEndLine != null
        ) {
            val callStart = (nextEdge.callSiteStartLine - 1).coerceIn(startLine, endLine)
            val callEnd = (nextEdge.callSiteEndLine - 1).coerceIn(callStart, endLine)
            val callStartOffset = document.getLineStartOffset(callStart)
            val callEndOffset = document.getLineEndOffset(callEnd)
            val hl = editor.markupModel.addRangeHighlighter(
                callStartOffset,
                callEndOffset,
                HighlighterLayer.SELECTION,
                nextAttrs,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            hl.errorStripeTooltip = nextTooltip
            hl.gutterIconRenderer = NextHopGutterIcon(nextTooltip ?: "Next step")
            activeHighlighters.add(hl)
            true
        } else {
            false
        }

        if (!renderedNextHop) {
            nextStep?.symbol?.let { symbol ->
                highlightNextSymbolMatches(editor, symbol, startOffset, endOffset, nextAttrs, nextTooltip)
            }
        }

        // 6. Scroll to step start.
        editor.caretModel.moveToOffset(startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

        // Attach document listener that clears decorations on edit.
        attachDocumentListener(document)
    }

    fun clearDecorations() {
        activeHighlighters.forEach { it.dispose() }
        activeHighlighters.clear()

        activeInlays.forEach { it.dispose() }
        activeInlays.clear()

        detachDocumentListener()
    }

    override fun dispose() {
        clearDecorations()
    }

    private fun attachDocumentListener(document: Document) {
        detachDocumentListener()
        val listener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                clearDecorations()
            }
        }
        document.addDocumentListener(listener)
        attachedDocument = document
        attachedListener = listener
    }

    private fun detachDocumentListener() {
        val doc = attachedDocument
        val listener = attachedListener
        if (doc != null && listener != null) {
            doc.removeDocumentListener(listener)
        }
        attachedDocument = null
        attachedListener = null
    }

    private fun addAnnotationInlay(
        editor: Editor,
        annotation: LineAnnotation,
        document: Document,
        startLine: Int,
        endLine: Int,
    ) {
        val line = (annotation.startLine - 1).coerceIn(startLine, endLine)
        val lineEndOffset = document.getLineEndOffset(line)
        val renderer = AnnotationRenderer(editor, annotation.text)
        editor.inlayModel.addAfterLineEndElement(lineEndOffset, false, renderer)?.let {
            activeInlays.add(it)
        }
    }

    private fun highlightNextSymbolMatches(
        editor: Editor,
        symbol: String,
        startOffset: Int,
        endOffset: Int,
        attributes: TextAttributes,
        tooltip: String?,
    ) {
        if (symbol.isBlank() || startOffset >= endOffset) return
        val chars = editor.document.charsSequence
        var searchStart = startOffset
        var matchCount = 0
        while (searchStart < endOffset && matchCount < MAX_NEXT_SYMBOL_MATCHES) {
            val matchStart = indexOfWithin(chars, symbol, searchStart, endOffset)
            if (matchStart < 0) break
            val matchEnd = matchStart + symbol.length
            if (isSymbolBoundary(chars, matchStart - 1) && isSymbolBoundary(chars, matchEnd)) {
                val hl = editor.markupModel.addRangeHighlighter(
                    matchStart,
                    matchEnd,
                    HighlighterLayer.SELECTION,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE,
                )
                hl.errorStripeTooltip = tooltip
                activeHighlighters.add(hl)
                matchCount++
            }
            searchStart = matchStart + symbol.length
        }
    }

    private class HeaderRenderer(
        private val editor: Editor,
        private val label: String,
        private val hint: String,
        private val accent: JBColor,
    ) : EditorCustomElementRenderer {

        private val vPad = JBUI.scale(4)
        private val hPad = JBUI.scale(10)
        private val barWidth = JBUI.scale(3)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(JBUI.scale(600))

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val fm = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.BOLD))
            return vPad * 2 + fm.height
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                g2.color = JBColor(Color(236, 244, 252), Color(43, 54, 68))
                g2.fillRect(region.x, region.y, region.width, region.height)
                g2.color = accent
                g2.fillRect(region.x, region.y, barWidth, region.height)

                val scheme = editor.colorsScheme
                val boldFont = scheme.getFont(EditorFontType.BOLD)
                val italicFont = scheme.getFont(EditorFontType.ITALIC)
                val boldFm = editor.contentComponent.getFontMetrics(boldFont)
                val italicFm = editor.contentComponent.getFontMetrics(italicFont)

                val y = region.y + vPad + boldFm.ascent
                val x = region.x + barWidth + hPad

                g2.font = boldFont
                g2.color = JBColor(Color(30, 30, 30), Color(220, 220, 220))
                g2.drawString(label, x, y)

                g2.font = italicFont
                g2.color = JBColor(Color(120, 120, 120), Color(160, 160, 160))
                val hintWidth = italicFm.stringWidth(hint)
                val rightX = region.x + region.width - hPad - hintWidth
                if (rightX > x + boldFm.stringWidth(label) + hPad) {
                    g2.drawString(hint, rightX, y)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    private class SummaryRenderer(
        private val editor: Editor,
        private val text: String,
    ) : EditorCustomElementRenderer {

        private val vPad = JBUI.scale(4)
        private val hPad = JBUI.scale(14)
        private val lineGap = JBUI.scale(2)

        private var cachedWidth: Int = -1
        private var cachedLines: List<String> = emptyList()

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(JBUI.scale(600))

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val width = calcWidthInPixels(inlay)
            val fm = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.ITALIC))
            val lines = layoutLines(width, fm).size.coerceAtLeast(1)
            return vPad * 2 + lines * fm.height + (lines - 1) * lineGap
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                g2.color = JBColor(Color(240, 246, 252), Color(46, 56, 70))
                g2.fillRect(region.x, region.y, region.width, region.height)

                val italic = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                val fm = editor.contentComponent.getFontMetrics(italic)
                val lines = layoutLines(region.width, fm)

                g2.font = italic
                g2.color = JBColor(Color(100, 115, 130), Color(170, 180, 195))

                var y = region.y + vPad + fm.ascent
                val x = region.x + hPad
                for (line in lines) {
                    g2.drawString(line, x, y)
                    y += fm.height + lineGap
                }
            } finally {
                g2.dispose()
            }
        }

        private fun layoutLines(width: Int, fm: FontMetrics): List<String> {
            if (width == cachedWidth && cachedLines.isNotEmpty()) return cachedLines
            val available = (width - hPad * 2).coerceAtLeast(JBUI.scale(200))
            val wrapped = wrapText(fm, text, available).take(MAX_SUMMARY_LINES)
            cachedWidth = width
            cachedLines = wrapped
            return wrapped
        }
    }

    private class AnnotationRenderer(
        private val editor: Editor,
        private val text: String,
    ) : EditorCustomElementRenderer {

        private val hPad = JBUI.scale(8)
        private val display = "  \u2190 $text"

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val fm = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.ITALIC))
            return fm.stringWidth(display) + hPad
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, region: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val italic = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                val fm = editor.contentComponent.getFontMetrics(italic)
                g2.font = italic
                g2.color = JBColor(Color(110, 130, 160), Color(150, 175, 215))
                g2.drawString(display, region.x, region.y + fm.ascent)
            } finally {
                g2.dispose()
            }
        }
    }

    private class NextHopGutterIcon(private val tooltip: String) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.Actions.Forward
        override fun getTooltipText(): String = tooltip
        override fun equals(other: Any?): Boolean = other is NextHopGutterIcon && other.tooltip == tooltip
        override fun hashCode(): Int = tooltip.hashCode()
    }

    companion object {
        private const val MAX_NEXT_SYMBOL_MATCHES = 20
        private const val MAX_SUMMARY_LINES = 4

        private fun accentColorFor(step: FlowStep): JBColor {
            val severity = step.severity?.trim()?.lowercase()
            return when {
                step.broken || severity == "high" -> JBColor(Color(190, 70, 60), Color(235, 130, 120))
                severity == "medium" -> JBColor(Color(190, 120, 40), Color(240, 185, 100))
                severity == "low" -> JBColor(Color(70, 110, 170), Color(130, 170, 225))
                else -> JBColor(Color(70, 110, 170), Color(130, 170, 225))
            }
        }

        private fun isSymbolBoundary(chars: CharSequence, index: Int): Boolean {
            if (index < 0 || index >= chars.length) return true
            val ch = chars[index]
            return !ch.isLetterOrDigit() && ch != '_' && ch != '$'
        }

        private fun indexOfWithin(chars: CharSequence, needle: String, start: Int, end: Int): Int {
            if (needle.isEmpty()) return -1
            val lastStart = end - needle.length
            var index = start.coerceAtLeast(0)
            while (index <= lastStart) {
                var matches = true
                for (offset in needle.indices) {
                    if (chars[index + offset] != needle[offset]) {
                        matches = false
                        break
                    }
                }
                if (matches) return index
                index++
            }
            return -1
        }

        fun wrapText(fm: FontMetrics, text: String, maxWidth: Int): List<String> {
            if (maxWidth <= 0) return listOf(text)
            val words = text.split(' ', '\n').filter { it.isNotEmpty() }
            val lines = mutableListOf<String>()
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (fm.stringWidth(candidate) <= maxWidth) {
                    current.clear()
                    current.append(candidate)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current.clear()
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            return lines.ifEmpty { listOf(text) }
        }
    }
}
