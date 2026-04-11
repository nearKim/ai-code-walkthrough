package com.github.nearkim.aicodewalkthrough.action

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.EditorContextSnapshot
import com.github.nearkim.aicodewalkthrough.service.EditorContextFormatter
import com.github.nearkim.aicodewalkthrough.service.EditorContextService
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

object CursorActionSupport {
    private const val TOOL_WINDOW_ID = "Code Tour"

    fun isEnabled(project: Project): Boolean = project.service<CodeTourSettings>().state.enableCursorActions

    fun runAnalysis(
        project: Project,
        prompt: String,
        mode: AnalysisMode,
        context: EditorContextSnapshot? = null,
    ) {
        if (!isEnabled(project)) return
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
        project.service<TourSessionService>().startMapping(
            question = prompt,
            mode = mode,
            queryContext = context?.toQueryContext(invokedFromCursor = true),
        )
    }

    fun cursorContext(project: Project, editor: Editor): EditorContextSnapshot? =
        project.service<EditorContextService>().contextFor(editor)

    fun buildReviewPrompt(context: EditorContextSnapshot): String =
        buildPrompt(
            action = "review-current-file",
            context = context,
            focus = "Review the current file for bugs, regressions, risky assumptions, and missing tests. Rank findings by severity and keep the answer concise.",
        )

    fun buildCursorQuestionPrompt(context: EditorContextSnapshot): String =
        buildPrompt(
            action = "ask-about-cursor",
            context = context,
            focus = "Explain what the current code does, why it exists, and how it connects to the surrounding flow.",
        )

    fun buildCommentPrompt(context: EditorContextSnapshot): String =
        buildPrompt(
            action = "compose-comment-from-cursor",
            context = context,
            focus = "Draft a concise code review comment from this cursor context. Focus on one actionable issue when possible, and include the most important evidence in the explanation.",
        )

    private fun buildPrompt(action: String, context: EditorContextSnapshot, focus: String): String {
        return buildString {
            appendLine("You are inside a JetBrains IDE.")
            appendLine("Action: $action")
            appendLine("Goal: $focus")
            appendLine()
            appendLine("Context:")
            EditorContextFormatter.toBulletLines(context).forEach(::appendLine)
            appendLine()
            appendLine("Instructions:")
            appendLine("- Use the context as the anchor for your answer.")
            appendLine("- Be specific and evidence-based.")
            appendLine("- Return a review-oriented walkthrough that can be shown in the IDE.")
        }
    }
}
