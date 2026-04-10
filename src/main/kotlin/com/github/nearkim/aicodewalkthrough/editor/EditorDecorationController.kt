package com.github.nearkim.aicodewalkthrough.editor

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.nio.file.Path
import kotlin.math.min

@Service(Service.Level.PROJECT)
class EditorDecorationController(private val project: Project) : Disposable {

    private val activeHighlighters = mutableListOf<RangeHighlighter>()
    private val activeInlays = mutableListOf<Inlay<*>>()
    private val settings get() = project.service<CodeTourSettings>()

    fun showStep(
        step: FlowStep,
        stepIndex: Int,
        totalSteps: Int,
        nextStep: FlowStep? = null,
        nextEdge: StepEdge? = null,
    ) {
        clearDecorations()

        if (step.broken) return

        val virtualFile = LocalFileSystem.getInstance()
            .findFileByNioFile(Path.of(project.basePath!!).resolve(step.filePath))
            ?: return

        FileEditorManager.getInstance(project).openFile(virtualFile, true)

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document

        if (document.lineCount == 0) return

        val startLine = (step.startLine - 1).coerceIn(0, document.lineCount - 1)
        val endLine = (step.endLine - 1).coerceIn(0, document.lineCount - 1)
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(min(endLine, document.lineCount - 1))

        editor.caretModel.moveToOffset(startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

        val highlightAttributes = TextAttributes().apply {
            backgroundColor = JBColor(Color(232, 242, 252), Color(47, 60, 75))
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION - 1,
            highlightAttributes,
            HighlighterTargetArea.LINES_IN_RANGE,
        )
        activeHighlighters.add(highlighter)

        // Step header inlay (higher priority so it appears above any annotation at the same offset)
        val reviewBadgesEnabled = settings.state.enableReviewBadges
        val severity = normalizedSeverity(step).takeIf { reviewBadgesEnabled }
        val confidence = normalizedConfidence(step).takeIf { reviewBadgesEnabled }
        val stepRenderer = StepInlayRenderer(
            editor = editor,
            stepLabel = "Step ${stepIndex + 1}/$totalSteps \u2014 ${step.title}",
            explanation = step.explanation,
            severityLabel = severity?.let { "Severity: ${it.replaceFirstChar { ch -> ch.uppercase() }}" },
            confidenceLabel = confidence?.let { "Confidence: ${it.replaceFirstChar { ch -> ch.uppercase() }}" },
            nextHopLabel = nextStep?.let { resolvedNextStep ->
                "Next hop: ${resolvedNextStep.title}" +
                    nextEdge?.kind?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            },
            backgroundColor = backgroundColorForSeverity(severity, step.broken),
            accentColor = accentColorForSeverity(severity, step.broken),
        )
        val inlay = editor.inlayModel.addBlockElement(startOffset, true, true, 10, stepRenderer)
        if (inlay != null) activeInlays.add(inlay)

        // Line annotation inlays
        for (annotation in step.lineAnnotations) {
            val annotLine = (annotation.startLine - 1).coerceIn(0, document.lineCount - 1)
            val annotOffset = document.getLineStartOffset(annotLine)
            val annotRenderer = LineAnnotationInlayRenderer(editor, annotation.text)
            val annotInlay = editor.inlayModel.addBlockElement(annotOffset, true, true, 5, annotRenderer)
            if (annotInlay != null) activeInlays.add(annotInlay)
        }

        // Next-step preview: prefer the validated hop call site, then fall back to symbol matching.
        val nextStepAttrs = TextAttributes().apply {
            foregroundColor = JBColor(Color(160, 90, 0), Color(220, 160, 60))
            effectColor = JBColor(Color(180, 110, 0), Color(220, 160, 60))
            effectType = EffectType.BOLD_LINE_UNDERSCORE
        }

        val resolvedNextEdge = nextEdge
        val previewRendered = if (
            nextStep != null &&
            resolvedNextEdge != null &&
            resolvedNextEdge.callSiteFilePath == step.filePath &&
            resolvedNextEdge.callSiteStartLine != null &&
            resolvedNextEdge.callSiteEndLine != null
        ) {
            val callStartLine = (resolvedNextEdge.callSiteStartLine - 1).coerceIn(startLine, endLine)
            val callEndLine = (resolvedNextEdge.callSiteEndLine - 1).coerceIn(callStartLine, endLine)
            val callStartOffset = document.getLineStartOffset(callStartLine)
            val callEndOffset = document.getLineEndOffset(callEndLine)
            val highlighter = editor.markupModel.addRangeHighlighter(
                callStartOffset,
                callEndOffset,
                HighlighterLayer.SELECTION,
                nextStepAttrs,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.errorStripeTooltip = buildNextStepTooltip(nextStep, resolvedNextEdge)
            activeHighlighters.add(highlighter)
            true
        } else {
            false
        }

        if (!previewRendered) {
            val nextSymbol = nextStep?.symbol
            if (nextSymbol != null) {
                val stepText = document.getText(TextRange(startOffset, endOffset))
                // Word-boundary match: symbol must not be immediately preceded/followed by word chars or $
                val pattern = Regex("(?<![\\w$])${Regex.escape(nextSymbol)}(?![\\w$])")
                for (match in pattern.findAll(stepText)) {
                    val matchStart = startOffset + match.range.first
                    val matchEnd = startOffset + match.range.last + 1
                    val highlighter = editor.markupModel.addRangeHighlighter(
                        matchStart,
                        matchEnd,
                        HighlighterLayer.SELECTION,
                        nextStepAttrs,
                        HighlighterTargetArea.EXACT_RANGE,
                    )
                    highlighter.errorStripeTooltip = buildNextStepTooltip(nextStep, resolvedNextEdge)
                    activeHighlighters.add(highlighter)
                }
            }
        }
    }

    fun clearDecorations() {
        activeHighlighters.forEach { it.dispose() }
        activeHighlighters.clear()

        activeInlays.forEach { it.dispose() }
        activeInlays.clear()
    }

    override fun dispose() {
        clearDecorations()
    }

    private class StepInlayRenderer(
        private val editor: Editor,
        private val stepLabel: String,
        private val explanation: String,
        private val severityLabel: String?,
        private val confidenceLabel: String?,
        private val nextHopLabel: String?,
        private val backgroundColor: JBColor,
        private val accentColor: JBColor,
    ) : EditorCustomElementRenderer {

        private val vPad = JBUI.scale(5)
        private val hPad = JBUI.scale(8)
        private val lineGap = JBUI.scale(3)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(JBUI.scale(600))

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val width = calcWidthInPixels(inlay)
            val boldFont = editor.colorsScheme.getFont(EditorFontType.BOLD)
            val plainFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
            val smallFont = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            val boldFm = editor.contentComponent.getFontMetrics(boldFont)
            val plainFm = editor.contentComponent.getFontMetrics(plainFont)
            val smallFm = editor.contentComponent.getFontMetrics(smallFont)
            val availableWidth = width - hPad * 2
            val metaLines = buildMetaText().takeIf { it.isNotBlank() }?.let { wrapText(smallFm, it, availableWidth) } ?: emptyList()
            val explLines = wrapText(plainFm, explanation, availableWidth)
            val metaHeight = if (metaLines.isEmpty()) 0 else metaLines.size * (smallFm.height + lineGap)
            val gapAfterMeta = if (metaLines.isEmpty()) 0 else lineGap
            return vPad + boldFm.height + gapAfterMeta + metaHeight + lineGap + explLines.size * (plainFm.height + lineGap) + vPad
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: java.awt.Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                g2.color = backgroundColor
                g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
                g2.color = accentColor
                g2.fillRect(targetRegion.x, targetRegion.y, JBUI.scale(4), targetRegion.height)

                val scheme = editor.colorsScheme
                val boldFont = scheme.getFont(EditorFontType.BOLD)
                val plainFont = scheme.getFont(EditorFontType.PLAIN)
                val smallFont = scheme.getFont(EditorFontType.ITALIC)
                val boldFm = g2.getFontMetrics(boldFont)
                val plainFm = g2.getFontMetrics(plainFont)
                val smallFm = g2.getFontMetrics(smallFont)
                val availableWidth = targetRegion.width - hPad * 2

                val x = targetRegion.x + hPad
                var y = targetRegion.y + vPad + boldFm.ascent

                g2.font = boldFont
                g2.color = JBColor(Color(30, 30, 30), Color(220, 220, 220))
                g2.drawString(stepLabel, x, y)
                y += boldFm.height + lineGap

                val metaText = buildMetaText()
                if (metaText.isNotBlank()) {
                    g2.font = smallFont
                    g2.color = accentColor
                    for (line in wrapText(smallFm, metaText, availableWidth)) {
                        g2.drawString(line, x, y)
                        y += smallFm.height + lineGap
                    }
                }

                g2.font = plainFont
                g2.color = JBColor(Color(60, 60, 60), Color(180, 180, 180))
                for (line in wrapText(plainFm, explanation, availableWidth)) {
                    g2.drawString(line, x, y)
                    y += plainFm.height + lineGap
                }
            } finally {
                g2.dispose()
            }
        }

        private fun buildMetaText(): String =
            listOfNotNull(severityLabel, confidenceLabel, nextHopLabel).joinToString("  ·  ")
    }

    private class LineAnnotationInlayRenderer(
        private val editor: Editor,
        private val text: String,
    ) : EditorCustomElementRenderer {

        private val vPad = JBUI.scale(3)
        private val hPad = JBUI.scale(8)
        private val lineGap = JBUI.scale(2)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(JBUI.scale(600))

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            val width = calcWidthInPixels(inlay)
            val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            val fm = editor.contentComponent.getFontMetrics(font)
            val lines = wrapText(fm, text, width - hPad * 2)
            return vPad + lines.size * (fm.height + lineGap) + vPad
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: java.awt.Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val bg = JBColor(Color(240, 246, 255), Color(35, 45, 58))
                g2.color = bg
                g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

                val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
                val fm = g2.getFontMetrics(font)
                val availableWidth = targetRegion.width - hPad * 2

                g2.font = font
                g2.color = JBColor(Color(70, 100, 150), Color(130, 160, 210))

                val x = targetRegion.x + hPad
                var y = targetRegion.y + vPad + fm.ascent
                for (line in wrapText(fm, text, availableWidth)) {
                    g2.drawString(line, x, y)
                    y += fm.height + lineGap
                }
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        private fun buildNextStepTooltip(nextStep: FlowStep, nextEdge: StepEdge?): String {
            val detail = nextEdge?.rationale?.takeIf { it.isNotBlank() }
                ?: nextEdge?.validationNote?.takeIf { it.isNotBlank() }
            return if (detail != null) {
                "Next: ${nextStep.title}\n$detail"
            } else {
                "Next: ${nextStep.title}"
            }
        }

        private fun normalizedSeverity(step: FlowStep): String? {
            val severity = step.severity?.trim()?.lowercase()
            return when (severity) {
                "high", "medium", "low", "info" -> severity
                null -> if (step.broken) "high" else null
                else -> severity
            }
        }

        private fun normalizedConfidence(step: FlowStep): String? {
            val confidence = step.confidence?.trim()?.lowercase()
            return when (confidence) {
                "high", "medium", "low", "uncertain", "estimated" -> confidence
                null -> if (step.uncertain) "uncertain" else null
                else -> confidence
            }
        }

        private fun backgroundColorForSeverity(severity: String?, broken: Boolean): JBColor {
            return when {
                broken -> JBColor(Color(250, 235, 235), Color(66, 38, 38))
                severity == "high" -> JBColor(Color(250, 235, 235), Color(66, 38, 38))
                severity == "medium" -> JBColor(Color(252, 244, 226), Color(72, 58, 34))
                severity == "low" -> JBColor(Color(235, 243, 252), Color(38, 50, 66))
                severity == "info" -> JBColor(Color(236, 241, 247), Color(39, 46, 56))
                else -> JBColor(Color(218, 232, 248), Color(40, 52, 66))
            }
        }

        private fun accentColorForSeverity(severity: String?, broken: Boolean): JBColor {
            return when {
                broken || severity == "high" -> JBColor(Color(190, 70, 60), Color(235, 130, 120))
                severity == "medium" -> JBColor(Color(190, 120, 40), Color(240, 185, 100))
                severity == "low" -> JBColor(Color(70, 110, 170), Color(130, 170, 225))
                severity == "info" -> JBColor(Color(90, 110, 130), Color(150, 170, 190))
                else -> JBColor(Color(70, 110, 170), Color(130, 170, 225))
            }
        }

        fun wrapText(fm: FontMetrics, text: String, maxWidth: Int): List<String> {
            if (maxWidth <= 0) return listOf(text)
            val words = text.split(' ')
            val lines = mutableListOf<String>()
            val current = StringBuilder()
            for (word in words) {
                if (word.isEmpty()) continue
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
            return lines.ifEmpty { listOf("") }
        }
    }
}
