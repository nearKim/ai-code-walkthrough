package com.github.nearkim.aicodewalkthrough.action

import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class StartTourAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<TourSessionService>().startTour()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val session = project.service<TourSessionService>()
        e.presentation.isEnabledAndVisible =
            session.state == TourState.OVERVIEW && session.currentFlowMap != null
    }
}
