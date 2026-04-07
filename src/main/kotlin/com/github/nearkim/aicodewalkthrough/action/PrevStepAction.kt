package com.github.nearkim.aicodewalkthrough.action

import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class PrevStepAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TourSessionService>().prevStep()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && project.service<TourSessionService>().state == TourState.TOUR_ACTIVE
    }
}
