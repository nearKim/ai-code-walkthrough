package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.editor.EditorDecorationController
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.CommentTone
import com.github.nearkim.aicodewalkthrough.model.CursorActionType
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.RecentWalkthrough
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
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
import java.util.UUID

@Service(Service.Level.PROJECT)
class TourSessionService(private val project: Project, private val scope: CoroutineScope) {

    interface TourSessionListener {
        fun onStateChanged(state: TourState)
        fun onProgressLine(line: String) {}
        fun onStepChanged(stepIndex: Int, step: FlowStep) {}
        fun onStepAnswerChanged(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {}
        fun onRecentWalkthroughsChanged(items: List<RecentWalkthrough>) {}
        fun onRepositoryReviewChanged(snapshot: RepositoryReviewSnapshot?) {}
    }

    var state: TourState = TourState.INPUT
        private set
    var currentFlowMap: FlowMap? = null
        private set
    var currentRepositoryReview: RepositoryReviewSnapshot? = null
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
    var clarificationQuestion: String? = null
        private set
    var errorMessage: String? = null
        private set
    var lastMetadata: ResponseMetadata? = null
        private set
    var lastRepositoryReviewMetadata: ResponseMetadata? = null
        private set
    var currentStepIndex: Int = -1
        private set
    var currentStepAnswer: StepAnswer? = null
        private set
    var stepAnswerLoading: Boolean = false
        private set
    var stepAnswerError: String? = null
        private set
    val recentWalkthroughs: List<RecentWalkthrough>
        get() = recentWalkthroughHistory.toList()

    private val listeners = mutableListOf<TourSessionListener>()
    private val recentWalkthroughHistory = ArrayDeque<RecentWalkthrough>()
    private val tourStepHistory = mutableListOf<Int>()
    private var currentRecentWalkthroughId: String? = null
    private val reviewArtifactStore = project.service<ReviewArtifactStore>()

    init {
        currentRepositoryReview = reviewArtifactStore.loadLatest()
    }

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

    private fun notifyRecentWalkthroughsChanged() {
        val snapshot = recentWalkthroughs
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onRecentWalkthroughsChanged(snapshot) }
        }
    }

    private fun notifyRepositoryReviewChanged() {
        val snapshot = currentRepositoryReview
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onRepositoryReviewChanged(snapshot) }
        }
    }

    fun startTour(startIndex: Int = 0) {
        val resolvedIndex = findNextNavigableStepIndex(startIndex) ?: return
        clearStepAnswer(notify = false)
        tourStepHistory.clear()
        currentStepIndex = resolvedIndex
        tourStepHistory += resolvedIndex
        currentContext = currentFlowMap?.steps?.getOrNull(resolvedIndex)?.toQueryContext(currentFeatureScope)
        transitionTo(TourState.TOUR_ACTIVE)
        navigateToCurrentStep()
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
            ?: findNextNavigableStepIndex(stepIndex + 1)?.let { steps[it] }
        val decorationController = project.service<EditorDecorationController>()
        ApplicationManager.getApplication().invokeLater {
            decorationController.showStep(step, stepIndex, steps.size, upcomingStep, upcomingEdge)
        }
    }

    fun nextStep() {
        val next = findPreferredNextNavigableStepIndex(currentStepIndex)
            ?: findNextNavigableStepIndex(currentStepIndex + 1)
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
            ?: findNextNavigableStepIndex(currentStepIndex + 1)?.let { steps[it] }

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
        currentRecentWalkthroughId = null
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

    fun submitFollowUp(
        question: String,
        mode: AnalysisMode = currentMode,
        queryContext: QueryContext? = currentContext,
        featureScope: FeatureScopeContext? = currentFeatureScope,
    ) {
        currentRecentWalkthroughId = null
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

    fun startCursorAnalysis(action: CursorActionType, context: QueryContext) {
        val mode = when (action) {
            CursorActionType.EXPLAIN, CursorActionType.WHY_HERE -> AnalysisMode.UNDERSTAND
            CursorActionType.WHAT_BREAKS -> AnalysisMode.RISK
            CursorActionType.WRITE_COMMENT -> AnalysisMode.COMMENT
            CursorActionType.SUGGEST_TESTS -> AnalysisMode.REVIEW
            CursorActionType.TRACE_USAGE -> AnalysisMode.TRACE
        }
        startMapping(action.prompt, mode, context.copy(invokedFromCursor = true), featureScope = null)
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
        submitFollowUp(prompt, AnalysisMode.COMMENT, step.toQueryContext(currentFeatureScope))
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
        project.service<RepositoryReviewPlannerService>().cancel()
        project.service<EditorDecorationController>().clearDecorations()
        currentFlowMap = null
        currentStepIndex = -1
        tourStepHistory.clear()
        transitionTo(TourState.INPUT)
    }

    fun reset() {
        project.service<EditorDecorationController>().clearDecorations()
        currentRecentWalkthroughId = null
        currentFlowMap = null
        currentQuestion = null
        currentMode = AnalysisMode.UNDERSTAND
        currentContext = null
        followUpContext = null
        currentFeatureScope = null
        clarificationQuestion = null
        errorMessage = null
        lastMetadata = null
        clearStepAnswer(notify = false)
        currentStepIndex = -1
        tourStepHistory.clear()
        transitionTo(TourState.INPUT)
    }

    fun restoreRecentWalkthrough(id: String, startTour: Boolean = false) {
        val snapshot = recentWalkthroughHistory.firstOrNull { it.id == id } ?: return
        project.service<EditorDecorationController>().clearDecorations()
        currentRecentWalkthroughId = snapshot.id
        currentFlowMap = snapshot.flowMap
        currentQuestion = snapshot.question
        currentMode = snapshot.mode
        currentContext = snapshot.queryContext
        followUpContext = snapshot.followUpContext
        currentFeatureScope = snapshot.featureScope
        clarificationQuestion = null
        errorMessage = null
        lastMetadata = snapshot.metadata
        clearStepAnswer(notify = false)
        currentStepIndex = -1
        tourStepHistory.clear()

        if (startTour) {
            startTour(resolveRecentStartIndex(snapshot))
        } else {
            transitionTo(TourState.OVERVIEW)
        }
    }

    fun startRepositoryReview() {
        val providerService = project.service<LlmProviderService>()
        currentQuestion = "Thorough repository review"
        currentMode = AnalysisMode.REVIEW
        currentContext = null
        currentFeatureScope = null
        errorMessage = null
        lastRepositoryReviewMetadata = null
        clearStepAnswer(notify = false)
        currentStepIndex = -1
        tourStepHistory.clear()
        project.service<EditorDecorationController>().clearDecorations()
        transitionTo(TourState.LOADING)

        scope.launch {
            val planner = project.service<RepositoryReviewPlannerService>()
            val result = planner.reviewRepository { line ->
                notifyProgress(line)
            }
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { reviewResult ->
                        currentRepositoryReview = reviewResult.snapshot
                        lastRepositoryReviewMetadata = reviewResult.metadata
                        errorMessage = null
                        reviewArtifactStore.write(reviewResult.snapshot)
                        notifyRepositoryReviewChanged()
                        transitionTo(TourState.REPO_REVIEW)
                    },
                    onFailure = { error ->
                        errorMessage = error.message ?: "Unknown error"
                        val provider = providerService.currentProvider()
                        if (!providerService.supportsRepositoryReview(provider)) {
                            errorMessage = errorMessage ?: "Repository review requires symbolic analysis."
                        }
                        transitionTo(TourState.INPUT)
                    },
                )
            }
        }
    }

    fun restoreStoredRepositoryReview(): Boolean {
        val snapshot = reviewArtifactStore.loadLatest() ?: return false
        project.service<EditorDecorationController>().clearDecorations()
        currentFlowMap = null
        currentStepIndex = -1
        tourStepHistory.clear()
        currentQuestion = "Thorough repository review"
        currentMode = AnalysisMode.REVIEW
        currentContext = null
        currentFeatureScope = null
        followUpContext = null
        currentRepositoryReview = snapshot
        lastRepositoryReviewMetadata = null
        errorMessage = null
        notifyRepositoryReviewChanged()
        transitionTo(TourState.REPO_REVIEW)
        return true
    }

    fun repositoryReviewIsStale(): Boolean {
        val snapshot = currentRepositoryReview ?: return true
        return reviewArtifactStore.isStale(snapshot)
    }

    fun startFeatureWalkthrough(featureId: String, pathId: String) {
        val review = currentRepositoryReview ?: return
        val feature = review.features.firstOrNull { it.id == featureId } ?: return
        val path = feature.paths.firstOrNull { it.id == pathId } ?: return
        val entryPoint = feature.entrypoints.firstOrNull()
        val allowedFiles = (
            feature.filePaths +
                path.filePaths +
                listOfNotNull(path.entryFilePath)
            ).distinct()
        val scope = FeatureScopeContext(
            featureId = feature.id,
            featureName = feature.name,
            featureSummary = feature.summary,
            featureReviewSummary = feature.reviewSummary ?: feature.overallRisk,
            allowedFilePaths = allowedFiles,
            selectedPathId = path.id,
            selectedPathName = path.title,
            selectedPathDescription = path.description,
            promptSeed = path.promptSeed,
            ownedPaths = feature.ownedPaths.ifEmpty { feature.filePaths },
            sharedPaths = feature.sharedPaths.ifEmpty { path.filePaths.filterNot { it in feature.filePaths } },
            supportingSymbols = (
                listOfNotNull(path.entrySymbol) +
                    path.supportingSymbols +
                    feature.entrypoints.mapNotNull { it.symbol }
                ).distinct(),
            boundaryNotes = path.boundaryNotes,
        )
        currentFeatureScope = scope
        startMapping(
            question = path.promptSeed,
            mode = AnalysisMode.fromId(path.defaultMode),
            queryContext = QueryContext(
                filePath = path.entryFilePath ?: entryPoint?.filePath ?: allowedFiles.firstOrNull(),
                symbol = path.entrySymbol ?: entryPoint?.symbol,
                selectionStartLine = entryPoint?.startLine,
                selectionEndLine = entryPoint?.endLine,
                featureScope = scope,
            ),
            featureScope = scope,
        )
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
                            rememberWalkthrough(
                                flowMap = flowMap,
                                question = currentQuestion,
                                mode = currentMode,
                                queryContext = currentContext,
                                followUpContext = followUpContext,
                                featureScope = currentFeatureScope,
                                metadata = lastMetadata,
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
        syncCurrentRecentWalkthrough()
    }

    fun preferredNextHop(stepId: String, visitedStepIds: Set<String> = visitedStepIds()): StepEdge? {
        val flowMap = currentFlowMap ?: return null
        val candidateEdges = flowMap.edges
            .filter { !it.broken && it.fromStepId == stepId }
            .filter { edge ->
                flowMap.steps.any { step -> step.id == edge.toStepId && !step.broken } &&
                    edge.toStepId !in visitedStepIds
            }
        return candidateEdges.maxWithOrNull(
            compareBy<StepEdge> { importanceRank(it.importance) }
                .thenBy { if (it.uncertain) 0 else 1 }
                .thenBy { it.evidence.size }
                .thenBy { -flowMap.steps.indexOfFirst { step -> step.id == it.toStepId } },
        )
    }

    fun outgoingHops(stepId: String): List<StepEdge> =
        currentFlowMap?.edges.orEmpty().filter { !it.broken && it.fromStepId == stepId }

    fun incomingHops(stepId: String): List<StepEdge> =
        currentFlowMap?.edges.orEmpty().filter { !it.broken && it.toStepId == stepId }

    fun isEntryStep(stepId: String): Boolean = currentFlowMap?.entryStepId == stepId

    fun isTerminalStep(stepId: String): Boolean = stepId in currentFlowMap?.terminalStepIds.orEmpty()

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

    private fun findPreferredNextNavigableStepIndex(fromIndex: Int): Int? {
        val flowMap = currentFlowMap ?: return null
        val step = flowMap.steps.getOrNull(fromIndex) ?: return null
        val preferredEdge = preferredNextHop(step.id, visitedStepIds())
            ?: return null
        val nextIndex = flowMap.steps.indexOfFirst { candidate ->
            candidate.id == preferredEdge.toStepId && !candidate.broken
        }
        return nextIndex.takeIf { it >= 0 }
    }

    private fun clearStepAnswer(notify: Boolean = true) {
        currentStepAnswer = null
        stepAnswerLoading = false
        stepAnswerError = null
        if (notify) {
            notifyStepAnswerChanged()
        }
    }

    private fun rememberWalkthrough(
        flowMap: FlowMap,
        question: String?,
        mode: AnalysisMode,
        queryContext: QueryContext?,
        followUpContext: FollowUpContext?,
        featureScope: FeatureScopeContext?,
        metadata: ResponseMetadata?,
    ) {
        val normalizedQuestion = question?.trim().orEmpty()
        if (normalizedQuestion.isEmpty()) return

        recentWalkthroughHistory.removeAll {
            it.question == normalizedQuestion && it.mode == mode && it.flowMap.summary == flowMap.summary
        }
        recentWalkthroughHistory.addFirst(
            RecentWalkthrough(
                id = UUID.randomUUID().toString(),
                displayTitle = summarizeQuestion(normalizedQuestion),
                question = normalizedQuestion,
                mode = mode,
                flowMap = flowMap,
                queryContext = queryContext,
                followUpContext = followUpContext,
                featureScope = featureScope,
                metadata = metadata,
            ),
        )
        currentRecentWalkthroughId = recentWalkthroughHistory.firstOrNull()?.id
        while (recentWalkthroughHistory.size > 5) {
            recentWalkthroughHistory.removeLast()
        }
        notifyRecentWalkthroughsChanged()
    }

    private fun resolveRecentStartIndex(snapshot: RecentWalkthrough): Int {
        val activeStepId = snapshot.followUpContext?.activeStepId ?: return 0
        return snapshot.flowMap.steps.indexOfFirst { it.id == activeStepId }.takeIf { it >= 0 } ?: 0
    }

    private fun syncCurrentRecentWalkthrough() {
        val recentId = currentRecentWalkthroughId ?: return
        val index = recentWalkthroughHistory.indexOfFirst { it.id == recentId }
        if (index < 0) return
        val existing = recentWalkthroughHistory.removeAt(index)
        recentWalkthroughHistory.add(index, existing.copy(followUpContext = followUpContext))
        notifyRecentWalkthroughsChanged()
    }

    private fun visitedStepIds(): Set<String> {
        val flowMap = currentFlowMap ?: return emptySet()
        return tourStepHistory.mapNotNull { index -> flowMap.steps.getOrNull(index)?.id }.toSet()
    }

    private fun importanceRank(value: String?): Int = when (value?.trim()?.lowercase()) {
        "critical", "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }

    private fun summarizeQuestion(question: String): String {
        val userRequestMarker = "User request:\n"
        if (question.contains(userRequestMarker)) {
            return question.substringAfter(userRequestMarker).trim().ifBlank { "Walkthrough" }
        }

        val action = question.lineSequence()
            .firstOrNull { it.startsWith("Action: ") }
            ?.removePrefix("Action: ")
            ?.replace('-', ' ')
            ?.trim()
        if (!action.isNullOrBlank()) {
            return action.replaceFirstChar { ch -> ch.uppercase() }
        }

        return question.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(120)
            ?: "Walkthrough"
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
