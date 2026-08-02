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
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

class WebSession(
    projectRoot: Path,
    private val settingsStore: WebSettingsStore,
    private val engine: WalkthroughEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {

    private val root = projectRoot.toAbsolutePath().normalize()
    private val lock = Any()
    private val navigator = TourNavigator()
    private val requestGeneration = AtomicLong()
    private val answerGeneration = AtomicLong()
    private val mutableEvents = MutableSharedFlow<OutboundEvent>(extraBufferCapacity = 256)
    private val json = Json { encodeDefaults = true }

    val events: SharedFlow<OutboundEvent> = mutableEvents

    private var state = TourState.INPUT
    private var question: String? = null
    private var mode = settingsStore.get().defaultMode
    private var provider = settingsStore.get().provider
    private var flowMap: FlowMap? = null
    private var metadata: ResponseMetadata? = null
    private var errorMessage: String? = null
    private var currentStepIndex = -1
    private var previewStepIndex = -1
    private var stepAnswer: StepAnswer? = null
    private var stepAnswerLoading = false
    private var stepAnswerError: String? = null
    private var followUpContext: FollowUpContext? = null
    private val history = mutableListOf<Int>()
    private val progressLines = ArrayDeque<String>()
    private var mappingJob: Job? = null

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
            history.clear()
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

    fun tour(action: String, stepId: String?): Result<SessionSnapshot> = runCatching {
        when (action) {
            "start" -> startTour(stepId)
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

    private fun startTour(requestedStepId: String?): SessionSnapshot {
        val currentFlow = synchronized(lock) { flowMap } ?: throw IllegalStateException("No walkthrough is mapped")
        val requestedIndex = requestedStepId?.let { id -> currentFlow.steps.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: currentFlow.entryStepId?.let { id -> currentFlow.steps.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        val index = navigator.findNextNavigableStepIndex(currentFlow, requestedIndex)
            ?: throw IllegalStateException("The walkthrough contains no navigable steps")
        answerGeneration.incrementAndGet()
        return mutate {
            state = TourState.TOUR_ACTIVE
            currentStepIndex = index
            previewStepIndex = -1
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
        val visited = history.mapNotNull { currentFlow.steps.getOrNull(it)?.id }.toSet()
        val nextIndex = navigator.findPreferredNextNavigableStepIndex(currentFlow, currentStepIndex, visited)
            ?: navigator.findNextNavigableStepIndex(currentFlow, currentStepIndex + 1)
            ?: return stopTour()
        answerGeneration.incrementAndGet()
        return mutate {
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

    private fun stopTour(): SessionSnapshot {
        answerGeneration.incrementAndGet()
        return mutate {
            followUpContext = followUpContext?.copy(activeStepId = flowMap?.steps?.getOrNull(currentStepIndex)?.id)
            state = TourState.OVERVIEW
            currentStepIndex = -1
            previewStepIndex = -1
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
            history.clear()
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
        val snapshot = synchronized(lock) {
            block()
            snapshotLocked()
        }
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
        val nextEdge = displayedStep?.let { navigator.preferredNextHop(flowMap, it.id, visited) }
        val nextStep = nextEdge?.let { edge -> flowMap?.steps?.firstOrNull { it.id == edge.toStepId } }
            ?: navigator.findNextNavigableStepIndex(flowMap, displayedIndex + 1)?.let { flowMap?.steps?.get(it) }
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
