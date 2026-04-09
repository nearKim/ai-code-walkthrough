package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.editor.EditorDecorationController
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.CommentTone
import com.github.nearkim.aicodewalkthrough.model.CursorActionType
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
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
    var clarificationQuestion: String? = null
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

    private fun notifyStepAnswerChanged() {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onStepAnswerChanged(currentStepAnswer, stepAnswerLoading, stepAnswerError) }
        }
    }

    fun startTour(startIndex: Int = 0) {
        val resolvedIndex = findNextNavigableStepIndex(startIndex) ?: return
        clearStepAnswer(notify = false)
        currentStepIndex = resolvedIndex
        currentContext = currentFlowMap?.steps?.getOrNull(resolvedIndex)?.toQueryContext()
        transitionTo(TourState.TOUR_ACTIVE)
        navigateToCurrentStep()
    }

    fun previewStep(stepIndex: Int) {
        val steps = currentFlowMap?.steps ?: return
        if (stepIndex !in steps.indices) return

        val step = steps[stepIndex]
        if (step.broken) return
        currentContext = step.toQueryContext()

        updateActiveStepContext(step.id)

        val upcomingStep = findNextNavigableStepIndex(stepIndex + 1)?.let { steps[it] }
        val decorationController = project.service<EditorDecorationController>()
        ApplicationManager.getApplication().invokeLater {
            decorationController.showStep(step, stepIndex, steps.size, upcomingStep)
        }
    }

    fun nextStep() {
        val next = findNextNavigableStepIndex(currentStepIndex + 1)
        if (next == null) {
            stopTour()
            return
        }
        currentStepIndex = next
        navigateToCurrentStep()
    }

    fun prevStep() {
        val prev = findPreviousNavigableStepIndex(currentStepIndex - 1) ?: return
        currentStepIndex = prev
        navigateToCurrentStep()
    }

    fun skipStep() {
        nextStep()
    }

    fun stopTour() {
        updateActiveStepContext(currentFlowMap?.steps?.getOrNull(currentStepIndex)?.id)
        project.service<EditorDecorationController>().clearDecorations()
        clearStepAnswer(notify = false)
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
        currentContext = step.toQueryContext()

        updateActiveStepContext(step.id)

        // Find next non-broken step so the decoration controller can preview it
        val upcomingStep = findNextNavigableStepIndex(currentStepIndex + 1)?.let { steps[it] }

        val totalSteps = steps.size
        val decorationController = project.service<EditorDecorationController>()
        ApplicationManager.getApplication().invokeLater {
            decorationController.showStep(step, currentStepIndex, totalSteps, upcomingStep)
            listeners.forEach { it.onStepChanged(currentStepIndex, step) }
        }
    }

    fun startMapping(
        question: String,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
    ) {
        currentQuestion = question
        currentMode = mode
        currentContext = queryContext
        errorMessage = null
        lastMetadata = null
        clearStepAnswer(notify = false)
        project.service<EditorDecorationController>().clearDecorations()
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, mode, queryContext, followUpContext) { line ->
                notifyProgress(line)
            }
            handleMappingResult(result)
        }
    }

    fun submitFollowUp(
        question: String,
        mode: AnalysisMode = currentMode,
        queryContext: QueryContext? = currentContext,
    ) {
        currentQuestion = question
        currentMode = mode
        currentContext = queryContext
        errorMessage = null
        lastMetadata = null
        clearStepAnswer(notify = false)
        project.service<EditorDecorationController>().clearDecorations()
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<FlowPlannerService>()
            val result = planner.mapFlow(question, mode, queryContext, followUpContext) { line ->
                notifyProgress(line)
            }
            handleMappingResult(result)
        }
    }

    fun startCursorAnalysis(action: CursorActionType, context: QueryContext) {
        val mode = when (action) {
            CursorActionType.EXPLAIN, CursorActionType.WHY_HERE -> AnalysisMode.UNDERSTAND
            CursorActionType.WHAT_BREAKS -> AnalysisMode.RISK
            CursorActionType.WRITE_COMMENT -> AnalysisMode.COMMENT
            CursorActionType.SUGGEST_TESTS -> AnalysisMode.REVIEW
            CursorActionType.TRACE_USAGE -> AnalysisMode.TRACE
        }
        startMapping(action.prompt, mode, context.copy(invokedFromCursor = true))
    }

    fun composeCommentForStep(stepId: String, tone: CommentTone = CommentTone.NEUTRAL) {
        val step = currentFlowMap?.steps?.firstOrNull { it.id == stepId } ?: return
        val prompt = buildString {
            append("Write a concise ")
            append(tone.displayName.lowercase())
            append(" code review comment for this step, grounded in the code evidence.")
            step.suggestedAction?.takeIf { it.isNotBlank() }?.let {
                append(' ')
                append("Suggested action: ")
                append(it)
            }
        }
        submitFollowUp(prompt, AnalysisMode.COMMENT, step.toQueryContext())
    }

    fun answerClarification(answer: String) {
        val question = clarificationQuestion
        if (question != null && followUpContext != null) {
            followUpContext = followUpContext!!.withClarification(question, answer)
        }
        clarificationQuestion = null
        startMapping(answer, currentMode, currentContext)
    }

    fun cancelRequest() {
        val planner = project.service<FlowPlannerService>()
        planner.cancel()
        project.service<EditorDecorationController>().clearDecorations()
        transitionTo(TourState.INPUT)
    }

    fun reset() {
        project.service<EditorDecorationController>().clearDecorations()
        currentFlowMap = null
        currentQuestion = null
        currentMode = AnalysisMode.UNDERSTAND
        currentContext = null
        followUpContext = null
        clarificationQuestion = null
        errorMessage = null
        lastMetadata = null
        clearStepAnswer(notify = false)
        transitionTo(TourState.INPUT)
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
                queryContext = step.toQueryContext(),
                followUpContext = followUpContext,
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
                    val clarification = response.toClarification()
                    when {
                        flowMap != null -> {
                            currentFlowMap = flowMap
                            clarificationQuestion = null
                            val previousContext = followUpContext
                            followUpContext = FollowUpContext(
                                originalQuestion = previousContext?.originalQuestion ?: currentQuestion ?: "",
                                previousFlowMap = flowMap,
                                clarificationHistory = previousContext?.clarificationHistory ?: emptyList(),
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

    private fun updateActiveStepContext(stepId: String?) {
        followUpContext = followUpContext?.copy(activeStepId = stepId)
    }

    private fun findNextNavigableStepIndex(startIndex: Int): Int? {
        val steps = currentFlowMap?.steps ?: return null
        var index = startIndex.coerceAtLeast(0)
        while (index < steps.size) {
            if (!steps[index].broken) return index
            index++
        }
        return null
    }

    private fun findPreviousNavigableStepIndex(startIndex: Int): Int? {
        val steps = currentFlowMap?.steps ?: return null
        var index = startIndex.coerceAtMost(steps.lastIndex)
        while (index >= 0) {
            if (!steps[index].broken) return index
            index--
        }
        return null
    }

    private fun clearStepAnswer(notify: Boolean = true) {
        currentStepAnswer = null
        stepAnswerLoading = false
        stepAnswerError = null
        if (notify) {
            notifyStepAnswerChanged()
        }
    }
}

private fun FlowStep.toQueryContext(): QueryContext =
    QueryContext(
        filePath = filePath,
        symbol = symbol,
        selectionStartLine = startLine,
        selectionEndLine = endLine,
    )
