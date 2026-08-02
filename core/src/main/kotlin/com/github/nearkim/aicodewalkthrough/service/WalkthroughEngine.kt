package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.application.prompt.PromptEnvelopeFactory
import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

data class MappingResult(
    val response: LlmResponse,
    val metadata: ResponseMetadata?,
)

data class StepAnswerResult(
    val answer: StepAnswer,
    val metadata: ResponseMetadata?,
)

class WalkthroughEngine(
    private val projectRoot: Path,
    private val settings: () -> WalkthroughSettings,
    private val providerFor: (AiProvider) -> LlmProvider,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val activeProvider = AtomicReference<LlmProvider?>()

    suspend fun mapFlow(
        question: String,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        providerOverride: AiProvider? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<MappingResult> {
        val provider = providerFor(providerOverride ?: settings().provider)
        return try {
            requireRepoGroundedWalkthroughSupport(provider)
            activeProvider.set(provider)
            val prompt = PromptEnvelopeFactory.buildWalkthroughPrompt(
                question = question,
                mode = mode,
                maxSteps = settings().maxSteps,
                queryContext = queryContext,
                followUpContext = followUpContext,
                featureScope = featureScope,
                providerCapabilities = provider.capabilities,
                json = json,
            )
            val providerResponse = provider.query(prompt, PromptKind.WALKTHROUGH, onProgress)
            val response = decodeResponse(providerResponse.content)
            val validatedResponse = if (response.type == "flow_map" && response.steps != null) {
                val flowMap = response.toFlowMap()
                    ?: return Result.failure(IllegalStateException("Unexpected flow map response from LLM"))
                val validated = StepValidator(projectRoot.toString()).validate(flowMap)
                response.copy(
                    steps = validated.steps,
                    architecture = validated.architecture,
                    learningPath = validated.learningPath,
                    entryStepId = validated.entryStepId,
                    terminalStepIds = validated.terminalStepIds,
                    edges = validated.edges,
                    analysisTrace = validated.analysisTrace,
                )
            } else {
                response
            }
            Result.success(MappingResult(validatedResponse, buildMetadata(providerResponse.metadata, validatedResponse)))
        } catch (error: SerializationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Failed to parse response: ${error.message}", error))
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Provider I/O error: ${error.message}", error))
        } catch (error: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Unexpected provider error: ${error.message}", error))
        } finally {
            activeProvider.compareAndSet(provider, null)
        }
    }

    suspend fun answerStepQuestion(
        question: String,
        step: FlowStep,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        providerOverride: AiProvider? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<StepAnswerResult> {
        val provider = providerFor(providerOverride ?: settings().provider)
        return try {
            requireRepoGroundedWalkthroughSupport(provider)
            activeProvider.set(provider)
            val prompt = PromptEnvelopeFactory.buildStepQuestionPrompt(
                question = question,
                step = step,
                mode = mode,
                queryContext = queryContext,
                followUpContext = followUpContext,
                featureScope = featureScope,
                providerCapabilities = provider.capabilities,
                json = json,
            )
            val providerResponse = provider.query(prompt, PromptKind.WALKTHROUGH, onProgress)
            val answer = decodeResponse(providerResponse.content).toStepAnswer()
                ?: return Result.failure(IllegalStateException("Unexpected response from LLM"))
            Result.success(
                StepAnswerResult(
                    answer = sanitizeStepAnswer(answer, step),
                    metadata = providerResponse.metadata,
                ),
            )
        } catch (error: SerializationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Failed to parse response: ${error.message}", error))
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Provider I/O error: ${error.message}", error))
        } catch (error: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Unexpected provider error: ${error.message}", error))
        } finally {
            activeProvider.compareAndSet(provider, null)
        }
    }

    suspend fun checkAvailability(provider: AiProvider): ProviderStatus = providerFor(provider).checkAvailability()

    fun cancel() {
        activeProvider.getAndSet(null)?.cancel()
    }

    private fun decodeResponse(content: String): LlmResponse {
        val cleaned = JsonResponseSanitizer.extractTopLevelObject(content)
        return json.decodeFromString(cleaned)
    }

    private fun buildMetadata(metadata: ResponseMetadata?, response: LlmResponse): ResponseMetadata? {
        val durationMs = metadata?.durationMs ?: return null
        val steps = response.steps.orEmpty()
        return ResponseMetadata(
            durationMs = durationMs,
            costUsd = metadata.costUsd,
            numTurns = metadata.numTurns,
            stepCount = steps.size,
            fileCount = steps.map(FlowStep::filePath).distinct().size,
        )
    }

    private fun sanitizeStepAnswer(answer: StepAnswer, step: FlowStep): StepAnswer {
        val validator = StepValidator(projectRoot.toString())
        return answer.copy(
            importantLines = answer.importantLines.mapNotNull { annotation ->
                val start = annotation.startLine.coerceIn(step.startLine, step.endLine)
                val end = annotation.endLine.coerceIn(step.startLine, step.endLine)
                annotation.copy(startLine = start, endLine = end).takeIf { start <= end }
            },
            evidence = validator.sanitizeEvidenceItems(answer.evidence, step.filePath),
        )
    }

    private fun requireRepoGroundedWalkthroughSupport(provider: LlmProvider) {
        if (!provider.capabilities.supportsRepoGroundedWalkthrough) {
            throw IllegalStateException(
                "${provider.provider.displayName} cannot safely inspect the local repository. " +
                    "Use Codex CLI or Claude CLI for grounded walkthroughs.",
            )
        }
    }
}
