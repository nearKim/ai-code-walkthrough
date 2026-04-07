package com.github.nearkim.aicodewalkthrough.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class StartTourAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = false
    }
}
