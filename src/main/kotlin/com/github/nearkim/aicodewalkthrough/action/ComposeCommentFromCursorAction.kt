package com.github.nearkim.aicodewalkthrough.action

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class ComposeCommentFromCursorAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val context = CursorActionSupport.cursorContext(project, editor) ?: return
        CursorActionSupport.runAnalysis(
            project = project,
            prompt = CursorActionSupport.buildCommentPrompt(context),
            mode = AnalysisMode.COMMENT,
            context = context,
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null &&
            editor != null &&
            CursorActionSupport.isEnabled(project)
    }
}
