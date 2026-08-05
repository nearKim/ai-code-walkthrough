package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.domain.session.TourNavigator
import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.WalkthroughEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

class WebSession(
    projectRoot: Path,
    private val settingsStore: WebSettingsStore,
    private val engine: WalkthroughEngine,
    private val sessionStore: WebSessionStore = WebSessionStore(projectRoot),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val root = projectRoot.toAbsolutePath().normalize()
    private val restored = sessionStore.load()
    private val lock = Any()
    private val navigator = TourNavigator()
    private val requestGeneration = AtomicLong()
    private val answerGeneration = AtomicLong()
    private val mutableEvents = MutableSharedFlow<OutboundEvent>(extraBufferCapacity = 256)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    val events: SharedFlow<OutboundEvent> = mutableEvents

    private var state = restoredState(restored)
    private var question: String? = restored?.question
    private var mode = restored?.mode?.let(AnalysisMode::fromId) ?: settingsStore.get().defaultMode
    private var provider = restored?.provider?.let(AiProvider::fromId) ?: settingsStore.get().provider
    private var flowMap: FlowMap? = restored?.flowMap
    private var metadata: ResponseMetadata? = restored?.metadata
    private var errorMessage: String? = null
    private var currentStepIndex = restored?.currentStepIndex?.takeIf { restored.flowMap.hasStepAt(it) } ?: -1
    private var previewStepIndex = restored?.previewStepIndex?.takeIf { restored.flowMap.hasStepAt(it) } ?: -1
    private var stepAnswer: StepAnswer? = restored?.stepAnswer
    private var stepAnswerLoading = false
    private var stepAnswerError: String? = restored?.stepAnswerError
    private var followUpContext: FollowUpContext? = restored?.flowMap?.let { FollowUpContext(question.orEmpty(), it, restored.activeStepId) }
    private var activeSectionId: String? = restored?.activeSectionId?.takeIf { sectionId ->
        restored.flowMap?.diagramSections?.any { it.id == sectionId } == true
    }
    private var activeStageId: String? = restored?.activeStageId?.takeIf { stageId ->
        restored.flowMap?.learningPath?.any { it.id == stageId } == true
    }
    private val history = restoredHistory(restored)
    private val completedStepIds = restored?.completedStepIds
        ?.filter { id -> restored.flowMap?.steps?.any { it.id == id && !it.broken } == true }
        ?.toMutableSet()
        ?: mutableSetOf()
    private val progressLines = ArrayDeque<String>()
    private var mappingJob: Job? = null

    init {
        if (state == TourState.TOUR_ACTIVE && currentStepIndex < 0) state = TourState.OVERVIEW
        if (state == TourState.TOUR_ACTIVE && history.isEmpty()) history += currentStepIndex
        if (state != TourState.TOUR_ACTIVE || allowedStepIds(flowMap, activeSectionId, activeStageId).isNullOrEmpty()) {
            activeSectionId = null
            activeStageId = null
        }
    }

    fun snapshot(): SessionSnapshot = synchronized(lock, ::snapshotLocked)

    fun startMapping(question: String, mode: AnalysisMode, provider: AiProvider): SessionSnapshot {
        engine.cancel()
        mappingJob?.cancel()
        val generation = requestGeneration.incrementAndGet()
        answerGeneration.incrementAndGet()
        val loadingSnapshot = mutate {
            this.question = question
            this.mode = mode
            this.provider = provider
            state = TourState.LOADING
            flowMap = null
            metadata = null
            errorMessage = null
            currentStepIndex = -1
            previewStepIndex = -1
            stepAnswer = null
            stepAnswerLoading = false
            stepAnswerError = null
            followUpContext = null
            activeSectionId = null
            activeStageId = null
            history.clear()
            completedStepIds.clear()
            progressLines.clear()
        }
        mappingJob = scope.launch {
            val result = engine.mapFlow(
                question = question,
                mode = mode,
                providerOverride = provider,
                onProgress = ::recordProgress,
            )
            if (requestGeneration.get() != generation) return@launch
            result.fold(
                onSuccess = { mapping ->
                    val mappedFlow = mapping.response.toFlowMap()
                    if (mappedFlow == null) {
                        failMapping("Unexpected response from LLM")
                    } else {
                        mutate {
                            flowMap = mappedFlow
                            metadata = mapping.metadata
                            followUpContext = FollowUpContext(question, mappedFlow)
                            state = TourState.OVERVIEW
                        }
                    }
                },
                onFailure = { error -> failMapping(error.message ?: "Unknown provider error") },
            )
        }
        return loadingSnapshot
    }

    fun showSample(): SessionSnapshot {
        engine.cancel()
        mappingJob?.cancel()
        mappingJob = null
        requestGeneration.incrementAndGet()
        answerGeneration.incrementAndGet()
        val sample = SampleWalkthrough.flowMap()
        return mutate {
            question = SampleWalkthrough.question
            mode = AnalysisMode.UNDERSTAND
            flowMap = sample
            metadata = null
            errorMessage = null
            currentStepIndex = -1
            previewStepIndex = -1
            stepAnswer = null
            stepAnswerLoading = false
            stepAnswerError = null
            followUpContext = FollowUpContext(SampleWalkthrough.question, sample)
            activeSectionId = null
            activeStageId = null
            history.clear()
            completedStepIds.clear()
            progressLines.clear()
            state = TourState.OVERVIEW
        }
    }

    fun isSampleSourceAvailable(path: String): Boolean = synchronized(lock) {
        state != TourState.INPUT && path == SampleWalkthrough.sourcePath && question == SampleWalkthrough.question &&
            flowMap?.steps?.any { it.filePath == path } == true
    }

    fun cancelMapping(): SessionSnapshot {
        requestGeneration.incrementAndGet()
        mappingJob?.cancel()
        mappingJob = null
        engine.cancel()
        return mutate {
            state = TourState.INPUT
            errorMessage = null
            progressLines.clear()
        }
    }

    fun tour(
        action: String,
        stepId: String?,
        sectionId: String? = null,
        stageId: String? = null,
    ): Result<SessionSnapshot> = runCatching {
        when (action) {
            "start" -> startTour(stepId)
            "start_section" -> startSection(sectionId ?: throw IllegalArgumentException("section_id is required for start_section"))
            "start_stage" -> startStage(stageId ?: throw IllegalArgumentException("stage_id is required for start_stage"))
            "preview" -> preview(stepId ?: throw IllegalArgumentException("step_id is required for preview"))
            "next" -> next()
            "previous" -> previous()
            "stop" -> stopTour()
            "new" -> newQuestion()
            else -> throw IllegalArgumentException("Unknown tour action: $action")
        }
    }

    suspend fun answerCurrentStep(question: String): Result<SessionSnapshot> {
        val answerRequest = synchronized(lock) {
            val currentFlow = flowMap ?: return Result.failure(IllegalStateException("No walkthrough is active"))
            val step = currentFlow.steps.getOrNull(currentStepIndex)
                ?: return Result.failure(IllegalStateException("No tour step is active"))
            val generation = answerGeneration.incrementAndGet()
            stepAnswer = null
            stepAnswerError = null
            stepAnswerLoading = true
            Triple(step, generation, provider)
        }
        emitSnapshot()
        val (step, generation, selectedProvider) = answerRequest
        val result = engine.answerStepQuestion(
            question = question,
            step = step,
            mode = mode,
            queryContext = QueryContext(
                filePath = step.filePath,
                symbol = step.symbol,
                selectionStartLine = step.startLine,
                selectionEndLine = step.endLine,
            ),
            followUpContext = followUpContext,
            providerOverride = selectedProvider,
        )
        if (answerGeneration.get() != generation) return Result.success(snapshot())
        return result.fold(
            onSuccess = { answer ->
                Result.success(mutate {
                    stepAnswer = answer.answer
                    stepAnswerError = null
                    stepAnswerLoading = false
                })
            },
            onFailure = { error ->
                Result.success(mutate {
                    stepAnswer = null
                    stepAnswerError = error.message ?: "Unknown provider error"
                    stepAnswerLoading = false
                })
            },
        )
    }

    private fun startSection(sectionId: String): SessionSnapshot = startTour(null, sectionId)

    private fun startStage(stageId: String): SessionSnapshot = startTour(null, stageId = stageId)

    private fun startTour(
        requestedStepId: String?,
        sectionId: String? = null,
        stageId: String? = null,
    ): SessionSnapshot {
        val currentFlow = synchronized(lock) { flowMap } ?: throw IllegalStateException("No walkthrough is mapped")
        if (sectionId != null && stageId != null) throw IllegalArgumentException("A tour can target one scope at a time")
        val allowedStepIds = allowedStepIds(currentFlow, sectionId, stageId)
        if ((sectionId != null || stageId != null) && allowedStepIds.isNullOrEmpty()) {
            throw IllegalArgumentException("Learning scope has no navigable code stops")
        }
        val requestedIndex = requestedStepId?.let { id -> currentFlow.steps.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: allowedStepIds?.firstNotNullOfOrNull { id ->
                currentFlow.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            }
            ?: currentFlow.entryStepId?.let { id -> currentFlow.steps.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val index = navigator.findNextNavigableStepIndex(currentFlow, requestedIndex, allowedStepIds)
            ?: throw IllegalStateException("The walkthrough contains no navigable steps")
        answerGeneration.incrementAndGet()
        return mutate {
            state = TourState.TOUR_ACTIVE
            currentStepIndex = index
            previewStepIndex = -1
            activeSectionId = sectionId
            activeStageId = stageId
            history.clear()
            history += index
            clearAnswerLocked()
            updateActiveStepLocked()
        }
    }

    private fun preview(stepId: String): SessionSnapshot {
        val currentFlow = synchronized(lock) { flowMap } ?: throw IllegalStateException("No walkthrough is mapped")
        val index = currentFlow.steps.indexOfFirst { it.id == stepId && !it.broken }
        if (index < 0) throw IllegalArgumentException("Unknown or broken step: $stepId")
        return mutate {
            previewStepIndex = index
            followUpContext = followUpContext?.copy(activeStepId = stepId)
        }
    }

    private fun next(): SessionSnapshot {
        val currentFlow = synchronized(lock) { flowMap } ?: throw IllegalStateException("No walkthrough is mapped")
        if (state != TourState.TOUR_ACTIVE) throw IllegalStateException("The guided tour is not active")
        val allowedStepIds = allowedStepIds(currentFlow, activeSectionId, activeStageId)
        val visited = history.mapNotNull { currentFlow.steps.getOrNull(it)?.id }.toSet()
        val nextIndex = navigator.findPreferredNextNavigableStepIndex(currentFlow, currentStepIndex, visited, allowedStepIds)
            ?: navigator.findNextNavigableStepIndex(currentFlow, currentStepIndex + 1, allowedStepIds)
            ?: return stopTour(markCurrentStepDigested = true)
        answerGeneration.incrementAndGet()
        return mutate {
            markCurrentStepDigestedLocked()
            currentStepIndex = nextIndex
            if (history.lastOrNull() != nextIndex) history += nextIndex
            clearAnswerLocked()
            updateActiveStepLocked()
        }
    }

    private fun previous(): SessionSnapshot {
        if (state != TourState.TOUR_ACTIVE) throw IllegalStateException("The guided tour is not active")
        if (history.size <= 1) return snapshot()
        answerGeneration.incrementAndGet()
        return mutate {
            history.removeLast()
            currentStepIndex = history.last()
            clearAnswerLocked()
            updateActiveStepLocked()
        }
    }

    private fun stopTour(markCurrentStepDigested: Boolean = false): SessionSnapshot {
        answerGeneration.incrementAndGet()
        return mutate {
            if (markCurrentStepDigested) markCurrentStepDigestedLocked()
            followUpContext = followUpContext?.copy(activeStepId = flowMap?.steps?.getOrNull(currentStepIndex)?.id)
            state = TourState.OVERVIEW
            currentStepIndex = -1
            previewStepIndex = -1
            activeSectionId = null
            activeStageId = null
            history.clear()
            clearAnswerLocked()
        }
    }

    private fun newQuestion(): SessionSnapshot {
        answerGeneration.incrementAndGet()
        return mutate {
            state = TourState.INPUT
            currentStepIndex = -1
            previewStepIndex = -1
            activeSectionId = null
            activeStageId = null
            history.clear()
            completedStepIds.clear()
            clearAnswerLocked()
        }
    }

    private fun failMapping(message: String) {
        mutate {
            state = TourState.INPUT
            errorMessage = message
            mappingJob = null
        }
    }

    private fun recordProgress(line: String) {
        if (line.isBlank()) return
        synchronized(lock) {
            if (state != TourState.LOADING) return
            progressLines.addLast(line)
            while (progressLines.size > MAX_PROGRESS_LINES) progressLines.removeFirst()
        }
        mutableEvents.tryEmit(OutboundEvent("progress", json.encodeToString(ProgressEvent(line))))
    }

    private fun mutate(block: WebSession.() -> Unit): SessionSnapshot {
        val (snapshot, stored) = synchronized(lock) {
            block()
            val snapshot = snapshotLocked()
            snapshot to storedSessionLocked()
        }
        persist(stored)
        emit(snapshot)
        return snapshot
    }

    private fun emitSnapshot() = emit(snapshot())

    private fun emit(snapshot: SessionSnapshot) {
        mutableEvents.tryEmit(OutboundEvent("session", json.encodeToString(snapshot)))
    }

    private fun snapshotLocked(): SessionSnapshot {
        val displayedIndex = if (state == TourState.TOUR_ACTIVE) currentStepIndex else previewStepIndex
        val displayedStep = flowMap?.steps?.getOrNull(displayedIndex)
        val visited = if (state == TourState.TOUR_ACTIVE) {
            history.mapNotNull { flowMap?.steps?.getOrNull(it)?.id }.toSet()
        } else {
            emptySet()
        }
        val allowedStepIds = allowedStepIds(flowMap, activeSectionId, activeStageId)
        val nextEdge = displayedStep?.let { navigator.preferredNextHop(flowMap, it.id, visited, allowedStepIds) }
        val nextStep = nextEdge?.let { edge -> flowMap?.steps?.firstOrNull { it.id == edge.toStepId } }
            ?: navigator.findNextNavigableStepIndex(flowMap, displayedIndex + 1, allowedStepIds)?.let { flowMap?.steps?.get(it) }
        return SessionSnapshot(
            state = state.name,
            repository = root.fileName?.toString() ?: root.toString(),
            repositoryPath = root.toString(),
            question = question,
            mode = mode.id,
            provider = provider.id,
            flowMap = flowMap,
            metadata = metadata,
            currentStepIndex = currentStepIndex,
            displayedStepIndex = displayedIndex,
            displayedStep = displayedStep,
            nextStep = nextStep,
            nextEdge = nextEdge,
            brokenStepIds = flowMap?.steps?.filter { it.broken }?.map { it.id }.orEmpty(),
            stepAnswer = stepAnswer,
            stepAnswerLoading = stepAnswerLoading,
            stepAnswerError = stepAnswerError,
            errorMessage = errorMessage,
            progressLines = progressLines.toList(),
            canPrevious = history.size > 1,
            activeSectionId = activeSectionId,
            activeLearningStageId = activeStageId,
            completedStepIds = completedStepIdsLocked(),
        )
    }

    private fun clearAnswerLocked() {
        stepAnswer = null
        stepAnswerLoading = false
        stepAnswerError = null
    }

    private fun updateActiveStepLocked() {
        val step = flowMap?.steps?.getOrNull(currentStepIndex) ?: return
        followUpContext = followUpContext?.copy(activeStepId = step.id)
    }

    private fun markCurrentStepDigestedLocked() {
        flowMap?.steps?.getOrNull(currentStepIndex)
            ?.takeUnless { it.broken }
            ?.let { completedStepIds += it.id }
    }

    private fun completedStepIdsLocked(): List<String> = flowMap?.steps
        ?.map { it.id }
        ?.filter(completedStepIds::contains)
        .orEmpty()

    private fun storedSessionLocked(): StoredWebSession? {
        val currentFlow = flowMap ?: return null
        if (state == TourState.LOADING) return null
        return StoredWebSession(
            repositoryPath = root.toString(),
            state = state.name,
            question = question,
            mode = mode.id,
            provider = provider.id,
            flowMap = currentFlow,
            metadata = metadata,
            currentStepIndex = currentStepIndex,
            previewStepIndex = previewStepIndex,
            stepAnswer = stepAnswer,
            stepAnswerError = stepAnswerError,
            activeStepId = followUpContext?.activeStepId,
            activeSectionId = activeSectionId,
            activeStageId = activeStageId,
            historyStepIds = history.mapNotNull { currentFlow.steps.getOrNull(it)?.id },
            completedStepIds = completedStepIdsLocked(),
        )
    }

    private fun persist(stored: StoredWebSession?) {
        if (stored == null) return
        try {
            sessionStore.save(stored)
        } catch (error: IOException) {
            System.err.println("Could not persist web session: ${error.message}")
        }
    }

    private fun allowedStepIds(
        flowMap: FlowMap?,
        sectionId: String?,
        stageId: String?,
    ): Set<String>? = when {
        sectionId != null -> flowMap?.diagramSections?.firstOrNull { it.id == sectionId }?.stepIds?.toSet()
        stageId != null -> flowMap?.learningPath?.firstOrNull { it.id == stageId }?.stepIds?.toSet()
        else -> null
    }

    override fun close() {
        requestGeneration.incrementAndGet()
        answerGeneration.incrementAndGet()
        engine.cancel()
        scope.cancel()
    }

    companion object {
        private const val MAX_PROGRESS_LINES = 200
    }
}

private fun restoredState(stored: StoredWebSession?): TourState {
    if (stored?.flowMap == null) return TourState.INPUT
    return TourState.entries.firstOrNull { it.name == stored.state }
        ?.takeUnless { it == TourState.LOADING }
        ?: TourState.OVERVIEW
}

private fun FlowMap?.hasStepAt(index: Int): Boolean = this != null && index in steps.indices

private fun restoredHistory(stored: StoredWebSession?): MutableList<Int> {
    val flowMap = stored?.flowMap ?: return mutableListOf()
    return stored.historyStepIds.mapNotNull { id ->
        flowMap.steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }.toMutableList()
}
