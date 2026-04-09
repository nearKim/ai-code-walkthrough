package com.github.nearkim.aicodewalkthrough.action

import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

data class CursorContext(
    val filePath: String,
    val fileName: String,
    val caretLine: Int,
    val selectionStartLine: Int? = null,
    val selectionEndLine: Int? = null,
    val selectedText: String? = null,
    val symbolName: String? = null,
)

object CursorActionSupport {
    private const val TOOL_WINDOW_ID = "Code Tour"

    fun isEnabled(project: Project): Boolean = project.service<CodeTourSettings>().state.enableCursorActions

    fun runAnalysis(project: Project, prompt: String) {
        if (!isEnabled(project)) return
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
        project.service<TourSessionService>().startMapping(prompt)
    }

    fun cursorContext(project: Project, editor: Editor): CursorContext? {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val caretOffset = editor.caretModel.offset.coerceIn(0, document.textLength)
        val caretLine = document.getLineNumber(caretOffset) + 1

        val selectedText = editor.selectionModel.selectedText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::truncate)

        val hasSelection = editor.selectionModel.hasSelection()
        val selectionStartLine = if (hasSelection) {
            document.getLineNumber(editor.selectionModel.selectionStart.coerceAtLeast(0)) + 1
        } else {
            null
        }
        val selectionEndLine = if (hasSelection) {
            val endOffset = (editor.selectionModel.selectionEnd - 1).coerceIn(0, document.textLength)
            document.getLineNumber(endOffset) + 1
        } else {
            null
        }

        val symbolName = findSymbolName(project, document, caretOffset)
        val filePath = relativePath(project, file.path) ?: file.path

        return CursorContext(
            filePath = filePath,
            fileName = file.name,
            caretLine = caretLine,
            selectionStartLine = selectionStartLine,
            selectionEndLine = selectionEndLine,
            selectedText = selectedText,
            symbolName = symbolName,
        )
    }

    fun buildReviewPrompt(context: CursorContext): String =
        buildPrompt(
            action = "review-current-file",
            context = context,
            focus = "Review the current file for bugs, regressions, risky assumptions, and missing tests. Rank findings by severity and keep the answer concise.",
        )

    fun buildCursorQuestionPrompt(context: CursorContext): String =
        buildPrompt(
            action = "ask-about-cursor",
            context = context,
            focus = "Explain what the current code does, why it exists, and how it connects to the surrounding flow.",
        )

    fun buildCommentPrompt(context: CursorContext): String =
        buildPrompt(
            action = "compose-comment-from-cursor",
            context = context,
            focus = "Draft a concise code review comment from this cursor context. Focus on one actionable issue when possible, and include the most important evidence in the explanation.",
        )

    private fun buildPrompt(action: String, context: CursorContext, focus: String): String {
        return buildString {
            appendLine("You are inside a JetBrains IDE.")
            appendLine("Action: $action")
            appendLine("Goal: $focus")
            appendLine()
            appendLine("Context:")
            appendLine("- file_path: ${context.filePath}")
            appendLine("- file_name: ${context.fileName}")
            appendLine("- caret_line: ${context.caretLine}")
            context.selectionStartLine?.let { start ->
                val end = context.selectionEndLine ?: start
                appendLine("- selection_range: L$start-L$end")
            }
            context.symbolName?.let { appendLine("- symbol: $it") }
            context.selectedText?.let {
                appendLine("- selection_text:")
                appendLine("```text")
                appendLine(it)
                appendLine("```")
            }
            appendLine()
            appendLine("Instructions:")
            appendLine("- Use the context as the anchor for your answer.")
            appendLine("- Be specific and evidence-based.")
            appendLine("- Return a review-oriented walkthrough that can be shown in the IDE.")
        }
    }

    private fun findSymbolName(project: Project, document: com.intellij.openapi.editor.Document, caretOffset: Int): String? {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val element = psiFile.findElementAt(caretOffset) ?: return null
        val named = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
        return named?.name?.takeIf { it.isNotBlank() }
    }

    private fun relativePath(project: Project, absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val base = Path.of(basePath).toAbsolutePath().normalize()
            val file = Path.of(absolutePath).toAbsolutePath().normalize()
            base.relativize(file).toString().replace('\\', '/')
        } catch (_: Exception) {
            null
        }
    }

    private fun truncate(text: String, maxChars: Int = 4000): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n... [truncated]"
    }
}
