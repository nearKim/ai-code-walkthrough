package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.domain.session.TourNavigator
import com.github.nearkim.aicodewalkthrough.editor.EditorDecorationController
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
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
        fun onProgressLines(lines: List<String>) {
            lines.forEach { onProgressLine(it) }
        }
        fun onStepChanged(stepIndex: Int, step: FlowStep) {}
        fun onStepAnswerChanged(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {}
    }

    var state: TourState = TourState.INPUT
        private set
    var currentFlowMap: FlowMap? = null
        private set
    var currentQuestion: String? = null
        private set
    var currentMode: AnalysisMode = AnalysisMode.UNDERSTAND
        private set
    var currentContext: QueryContext? = null
        private set
    var followUpContext: FollowUpContext? = null
        private set
    var currentFeatureScope: FeatureScopeContext? = null
        private set
    var errorMessage: String? = null
        private set
    var lastMetadata: ResponseMetadata? = null
        private set
    var currentStepIndex: Int = -1
        private set
    var currentStepAnswer: StepAnswer? = null
        private set
    var stepAnswerLoading: Boolean = false
        private set
    var stepAnswerError: String? = null
        private set

    private val listeners = mutableListOf<TourSessionListener>()
    private val tourStepHistory = mutableListOf<Int>()
    private val tourNavigator = TourNavigator()
    private val progressLock = Any()
    private val pendingProgressLines = ArrayDeque<String>()
    private var progressFlushQueued = false

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
        if (state != TourState.LOADING || line.isBlank()) return
        synchronized(progressLock) {
            pendingProgressLines.addLast(line)
            while (pendingProgressLines.size > MAX_PROGRESS_LINES) {
                pendingProgressLines.removeFirst()
            }
            if (progressFlushQueued) return
            progressFlushQueued = true
        }
        ApplicationManager.getApplication().invokeLater {
            val lines = synchronized(progressLock) {
                progressFlushQueued = false
                pendingProgressLines.toList().also { pendingProgressLines.clear() }
            }
            if (state == TourState.LOADING && lines.isNotEmpty()) {
                listeners.forEach { it.onProgressLines(lines) }
            }
        }
    }

    private fun notifyStepAnswerChanged() {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onStepAnswerChanged(currentStepAnswer, stepAnswerLoading, stepAnswerError) }
        }
    }

    fun startTour(startIndex: Int = 0) {
        val resolvedIndex = tourNavigator.findNextNavigableStepIndex(currentFlowMap, startIndex) ?: return
        clearStepAnswer(notify = false)
        tourStepHistory.clear()
        currentStepIndex = resolvedIndex
        tourStepHistory += resolvedIndex
        currentContext = currentFlowMap?.steps?.getOrNull(resolvedIndex)?.toQueryContext(currentFeatureScope)
        transitionTo(TourState.TOUR_ACTIVE)
        navigateToCurrentStep()
    }

    fun previewStep(step: FlowStep) {
        val steps = currentFlowMap?.steps ?: return
        val index = steps.indexOfFirst { it.id == step.id }
        if (index >= 0) previewStep(index)
    }

    fun previewStep(stepIndex: Int) {
        val steps = currentFlowMap?.steps ?: return
        if (stepIndex !in steps.indices) return

        val step = steps[stepIndex]
        if (step.broken) return
        currentContext = step.toQueryContext(currentFeatureScope)

        updateActiveStepContext(step.id)

        val upcomingEdge = preferredNextHop(step.id, visitedStepIds = emptySet())
        val upcomingStep = upcomingEdge?.let { edge -> steps.firstOrNull { it.id == edge.toStepId } }
            ?: tourNavigator.findNextNavigableStepIndex(currentFlowMap, stepIndex + 1)?.let { steps[it] }
        val decorationController = project.service<EditorDecorationController>()
        ApplicationManager.getApplication().invokeLater {
            decorationController.showStep(step, stepIndex, steps.size, upcomingStep, upcomingEdge)
        }
    }

    fun nextStep() {
        val next = tourNavigator.findPreferredNextNavigableStepIndex(currentFlowMap, currentStepIndex, visitedStepIds())
            ?: tourNavigator.findNextNavigableStepIndex(currentFlowMap, currentStepIndex + 1)
        if (next == null) {
            stopTour()
            return
        }
        currentStepIndex = next
        if (tourStepHistory.lastOrNull() != next) {
            tourStepHistory += next
        }
        navigateToCurrentStep()
    }

    fun prevStep() {
        if (tourStepHistory.size <= 1) return
        tourStepHistory.removeLastOrNull()
        currentStepIndex = tourStepHistory.lastOrNull() ?: return
        navigateToCurrentStep()
    }

    fun skipStep() {
        nextStep()
    }

    fun stopTour() {
        updateActiveStepContext(currentFlowMap?.steps?.getOrNull(currentStepIndex)?.id)
        project.service<EditorDecorationController>().clearDecorations()
        clearStepAnswer(notify = false)
        tourStepHistory.clear()
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
        clearStepAnswer(notify = false)
        currentContext = step.toQueryContext(currentFeatureScope)

        updateActiveStepContext(step.id)

        val visitedStepIds = visitedStepIds()
        val upcomingEdge = preferredNextHop(step.id, visitedStepIds)
        val upcomingStep = upcomingEdge?.let { edge -> steps.firstOrNull { it.id == edge.toStepId } }
            ?: tourNavigator.findNextNavigableStepIndex(currentFlowMap, currentStepIndex + 1)?.let { steps[it] }

        val totalSteps = steps.size
        val decorationController = project.service<EditorDecorationController>()
        ApplicationManager.getApplication().invokeLater {
            decorationController.showStep(step, currentStepIndex, totalSteps, upcomingStep, upcomingEdge)
            listeners.forEach { it.onStepChanged(currentStepIndex, step) }
        }
    }

    fun startMapping(
        question: String,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        featureScope: FeatureScopeContext? = currentFeatureScope,
    ) {
        beginWalkthroughRequest(question, mode, queryContext, featureScope)
    }

    private fun beginWalkthroughRequest(
        question: String,
        mode: AnalysisMode,
        queryContext: QueryContext?,
        featureScope: FeatureScopeContext?,
    ) {
        clearPendingProgress()
        currentQuestion = question
        currentMode = mode
        currentContext = queryContext
        currentFeatureScope = featureScope
        errorMessage = null
        lastMetadata = null
        clearStepAnswer(notify = false)
        currentStepIndex = -1
        tourStepHistory.clear()
        project.service<EditorDecorationController>().clearDecorations()
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, mode, queryContext, followUpContext, featureScope) { line ->
                notifyProgress(line)
            }
            handleMappingResult(result)
        }
    }

    fun askAboutCurrentStep(question: String) {
        val step = currentFlowMap?.steps?.getOrNull(currentStepIndex) ?: return
        currentStepAnswer = null
        stepAnswerError = null
        stepAnswerLoading = true
        notifyStepAnswerChanged()

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.answerStepQuestion(
                question = question,
                step = step,
                mode = currentMode,
                queryContext = step.toQueryContext(currentFeatureScope),
                followUpContext = followUpContext,
                featureScope = currentFeatureScope,
            )
            ApplicationManager.getApplication().invokeLater {
                stepAnswerLoading = false
                result.fold(
                    onSuccess = { answerResult ->
                        currentStepAnswer = answerResult.answer
                        stepAnswerError = null
                    },
                    onFailure = { error ->
                        currentStepAnswer = null
                        stepAnswerError = error.message ?: "Unknown error"
                    },
                )
                notifyStepAnswerChanged()
            }
        }
    }

    private fun handleMappingResult(result: Result<MappingResult>) {
        ApplicationManager.getApplication().invokeLater {
            result.fold(
                onSuccess = { mappingResult ->
                    lastMetadata = mappingResult.metadata
                    val response = mappingResult.response
                    val flowMap = response.toFlowMap()
                    if (flowMap != null) {
                        currentFlowMap = flowMap
                        followUpContext = FollowUpContext(
                            originalQuestion = currentQuestion ?: "",
                            previousFlowMap = flowMap,
                        )
                        transitionTo(TourState.OVERVIEW)
                    } else {
                        errorMessage = "Unexpected response from LLM"
                        transitionTo(TourState.INPUT)
                    }
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Unknown error"
                    transitionTo(TourState.INPUT)
                },
            )
        }
    }

    private fun updateActiveStepContext(stepId: String?) {
        followUpContext = followUpContext?.copy(activeStepId = stepId)
    }

    private fun preferredNextHop(stepId: String, visitedStepIds: Set<String> = visitedStepIds()): StepEdge? {
        return tourNavigator.preferredNextHop(currentFlowMap, stepId, visitedStepIds)
    }

    private fun clearStepAnswer(notify: Boolean = true) {
        currentStepAnswer = null
        stepAnswerLoading = false
        stepAnswerError = null
        if (notify) {
            notifyStepAnswerChanged()
        }
    }

    private fun clearPendingProgress() {
        synchronized(progressLock) {
            pendingProgressLines.clear()
            progressFlushQueued = false
        }
    }

    private fun visitedStepIds(): Set<String> {
        val flowMap = currentFlowMap ?: return emptySet()
        return tourStepHistory.mapNotNull { index -> flowMap.steps.getOrNull(index)?.id }.toSet()
    }

    companion object {
        private const val MAX_PROGRESS_LINES = 200
    }
}

private fun FlowStep.toQueryContext(featureScope: FeatureScopeContext? = null): QueryContext =
    QueryContext(
        filePath = filePath,
        symbol = symbol,
        selectionStartLine = startLine,
        selectionEndLine = endLine,
        featureScope = featureScope,
    )
