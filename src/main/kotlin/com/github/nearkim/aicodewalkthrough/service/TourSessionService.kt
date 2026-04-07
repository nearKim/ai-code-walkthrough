package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class TourSessionService(private val project: Project, private val scope: CoroutineScope) {

    interface TourSessionListener {
        fun onStateChanged(state: TourState)
    }

    var state: TourState = TourState.INPUT
        private set
    var currentFlowMap: FlowMap? = null
        private set
    var currentQuestion: String? = null
        private set
    var followUpContext: FollowUpContext? = null
        private set
    var clarificationQuestion: String? = null
        private set
    var errorMessage: String? = null
        private set

    private val listeners = mutableListOf<TourSessionListener>()

    fun addListener(listener: TourSessionListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TourSessionListener) {
        listeners.remove(listener)
    }

    private fun transitionTo(newState: TourState) {
        state = newState
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onStateChanged(newState) }
        }
    }

    fun startMapping(question: String) {
        currentQuestion = question
        errorMessage = null
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, followUpContext)
            handleMappingResult(result)
        }
    }

    fun submitFollowUp(question: String) {
        currentQuestion = question
        errorMessage = null
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, followUpContext)
            handleMappingResult(result)
        }
    }

    fun answerClarification(answer: String) {
        val question = clarificationQuestion
        if (question != null && followUpContext != null) {
            followUpContext = followUpContext!!.withClarification(question, answer)
        }
        clarificationQuestion = null
        startMapping(answer)
    }

    fun cancelRequest() {
        val planner = project.service<FlowPlannerService>()
        planner.cancel()
        transitionTo(TourState.INPUT)
    }

    fun reset() {
        currentFlowMap = null
        currentQuestion = null
        followUpContext = null
        clarificationQuestion = null
        errorMessage = null
        transitionTo(TourState.INPUT)
    }

    private fun handleMappingResult(result: Result<LlmResponse>) {
        ApplicationManager.getApplication().invokeLater {
            result.fold(
                onSuccess = { response ->
                    val flowMap = response.toFlowMap()
                    val clarification = response.toClarification()
                    when {
                        flowMap != null -> {
                            currentFlowMap = flowMap
                            clarificationQuestion = null
                            followUpContext = FollowUpContext(
                                originalQuestion = currentQuestion ?: "",
                                previousFlowMap = flowMap,
                            )
                            transitionTo(TourState.OVERVIEW)
                        }
                        clarification != null -> {
                            clarificationQuestion = clarification.clarificationQuestion
                            transitionTo(TourState.OVERVIEW)
                        }
                        else -> {
                            errorMessage = "Unexpected response from LLM"
                            transitionTo(TourState.INPUT)
                        }
                    }
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Unknown error"
                    transitionTo(TourState.INPUT)
                },
            )
        }
    }
}
