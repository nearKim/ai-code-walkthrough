package com.github.nearkim.aicodewalkthrough.editor

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
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
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.nio.file.Path
import kotlin.math.min

@Service(Service.Level.PROJECT)
class EditorDecorationController(private val project: Project) : Disposable {

    private val activeHighlighters = mutableListOf<RangeHighlighter>()
    private val activeInlays = mutableListOf<Inlay<*>>()

    fun showStep(step: FlowStep, stepIndex: Int, totalSteps: Int) {
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

        val renderer = StepInlayRenderer(
            editor,
            stepLabel = "Step ${stepIndex + 1}/$totalSteps \u2014 ${step.title}",
            explanation = step.explanation,
        )
        val inlay = editor.inlayModel.addBlockElement(
            startOffset,
            true,
            true,
            1,
            renderer,
        )
        if (inlay != null) {
            activeInlays.add(inlay)
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
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int =
            editor.scrollingModel.visibleArea.width.coerceAtLeast(JBUI.scale(600))

        override fun calcHeightInPixels(inlay: Inlay<*>): Int =
            editor.lineHeight + JBUI.scale(4)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: java.awt.Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val bg = JBColor(Color(218, 232, 248), Color(40, 52, 66))
                g2.color = bg
                g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

                val scheme = editor.colorsScheme
                val boldFont = scheme.getFont(EditorFontType.BOLD)
                val plainFont = scheme.getFont(EditorFontType.PLAIN)

                val yBaseline = targetRegion.y + targetRegion.height - JBUI.scale(4)
                val xStart = targetRegion.x + JBUI.scale(8)

                g2.font = boldFont
                g2.color = JBColor(Color(30, 30, 30), Color(220, 220, 220))
                g2.drawString(stepLabel, xStart, yBaseline)

                val labelWidth = g2.fontMetrics.stringWidth(stepLabel)
                val gap = JBUI.scale(12)

                g2.font = plainFont
                g2.color = JBColor(Color(80, 80, 80), Color(170, 170, 170))
                g2.drawString(explanation, xStart + labelWidth + gap, yBaseline)
            } finally {
                g2.dispose()
            }
        }
    }
}
