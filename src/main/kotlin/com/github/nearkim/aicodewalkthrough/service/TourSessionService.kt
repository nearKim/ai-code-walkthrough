package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.editor.EditorDecorationController
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
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
        fun onProgressLine(line: String) {}
        fun onStepChanged(stepIndex: Int, step: FlowStep) {}
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
    var lastMetadata: ResponseMetadata? = null
        private set
    var currentStepIndex: Int = -1
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

    private fun notifyProgress(line: String) {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onProgressLine(line) }
        }
    }

    fun startTour() {
        currentStepIndex = 0
        transitionTo(TourState.TOUR_ACTIVE)
        navigateToCurrentStep()
    }

    fun nextStep() {
        val steps = currentFlowMap?.steps ?: return
        var next = currentStepIndex + 1
        while (next < steps.size && steps[next].broken) {
            next++
        }
        if (next >= steps.size) {
            stopTour()
            return
        }
        currentStepIndex = next
        navigateToCurrentStep()
    }

    fun prevStep() {
        val steps = currentFlowMap?.steps ?: return
        var prev = currentStepIndex - 1
        while (prev > 0 && steps[prev].broken) {
            prev--
        }
        if (prev < 0) prev = 0
        if (steps[prev].broken) return
        currentStepIndex = prev
        navigateToCurrentStep()
    }

    fun skipStep() {
        nextStep()
    }

    fun stopTour() {
        project.service<EditorDecorationController>().clearDecorations()
        currentStepIndex = -1
        transitionTo(TourState.OVERVIEW)
    }

    private fun navigateToCurrentStep() {
        val steps = currentFlowMap?.steps ?: return
        if (currentStepIndex < 0 || currentStepIndex >= steps.size) return

        val step = steps[currentStepIndex]
        if (step.broken) {
            nextStep()
            return
        }

        // Find next non-broken step so the decoration controller can preview it
        var nextIdx = currentStepIndex + 1
        while (nextIdx < steps.size && steps[nextIdx].broken) nextIdx++
        val upcomingStep = steps.getOrNull(nextIdx)

        val totalSteps = steps.size
        val decorationController = project.service<EditorDecorationController>()
        ApplicationManager.getApplication().invokeLater {
            decorationController.showStep(step, currentStepIndex, totalSteps, upcomingStep)
            listeners.forEach { it.onStepChanged(currentStepIndex, step) }
        }
    }

    fun startMapping(question: String) {
        currentQuestion = question
        errorMessage = null
        lastMetadata = null
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, followUpContext) { line ->
                notifyProgress(line)
            }
            handleMappingResult(result)
        }
    }

    fun submitFollowUp(question: String) {
        currentQuestion = question
        errorMessage = null
        lastMetadata = null
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, followUpContext) { line ->
                notifyProgress(line)
            }
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
        lastMetadata = null
        transitionTo(TourState.INPUT)
    }

    private fun handleMappingResult(result: Result<MappingResult>) {
        ApplicationManager.getApplication().invokeLater {
            result.fold(
                onSuccess = { mappingResult ->
                    lastMetadata = mappingResult.metadata
                    val response = mappingResult.response
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
