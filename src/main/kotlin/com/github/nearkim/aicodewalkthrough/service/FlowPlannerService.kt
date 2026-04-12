package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.application.prompt.PromptEnvelopeFactory
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class MappingResult(
    val response: LlmResponse,
    val metadata: ResponseMetadata?,
)

data class StepAnswerResult(
    val answer: StepAnswer,
    val metadata: ResponseMetadata?,
)

@Service(Service.Level.PROJECT)
class FlowPlannerService(private val project: Project) {

    private val providerService = project.service<LlmProviderService>()
    private val settings get() = project.service<com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings>()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun mapFlow(
        question: String,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<MappingResult> {
        return try {
            val provider = providerService.currentProvider()
            providerService.requireRepoGroundedWalkthroughSupport(provider)
            val prompt = buildPrompt(question, mode, queryContext, followUpContext, featureScope, provider)
            val providerResponse = provider.query(prompt, promptKind = PromptKind.WALKTHROUGH, onProgress = onProgress)
            val cleaned = stripMarkdownFences(providerResponse.content)
            val llmResponse = json.decodeFromString<LlmResponse>(cleaned)

            val finalResponse = if (llmResponse.type == "flow_map" && llmResponse.steps != null) {
                val flowMap = llmResponse.toFlowMap()
                    ?: return Result.failure(IllegalStateException("Unexpected flow map response from LLM"))
                val validatedFlow = validator().validate(flowMap)
                llmResponse.copy(
                    steps = validatedFlow.steps,
                    entryStepId = validatedFlow.entryStepId,
                    terminalStepIds = validatedFlow.terminalStepIds,
                    edges = validatedFlow.edges,
                    analysisTrace = validatedFlow.analysisTrace,
                )
            } else {
                llmResponse
            }

            val metadata = buildMetadata(providerResponse.metadata, finalResponse)
            Result.success(MappingResult(finalResponse, metadata))
        } catch (e: SerializationException) {
            thisLogger().warn("Failed to parse model response", e)
            Result.failure(IllegalStateException("Failed to parse response: ${e.message}", e))
        } catch (e: IllegalStateException) {
            thisLogger().warn("Provider error", e)
            Result.failure(e)
        } catch (e: Exception) {
            thisLogger().warn("Unexpected error during flow mapping", e)
            Result.failure(IllegalStateException("Unexpected error: ${e.message}", e))
        }
    }

    fun cancel() {
        providerService.cancel()
    }

    suspend fun answerStepQuestion(
        question: String,
        step: FlowStep,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<StepAnswerResult> {
        return try {
            val provider = providerService.currentProvider()
            providerService.requireRepoGroundedWalkthroughSupport(provider)
            val prompt = buildStepPrompt(question, step, mode, queryContext, followUpContext, featureScope, provider)
            val providerResponse = provider.query(prompt, promptKind = PromptKind.WALKTHROUGH, onProgress = onProgress)
            val cleaned = stripMarkdownFences(providerResponse.content)
            val llmResponse = json.decodeFromString<LlmResponse>(cleaned)
            val stepAnswer = llmResponse.toStepAnswer()
                ?: return Result.failure(IllegalStateException("Unexpected response from LLM"))
            val sanitizedAnswer = sanitizeStepAnswer(stepAnswer, step)

            Result.success(
                StepAnswerResult(
                    answer = sanitizedAnswer,
                    metadata = providerResponse.metadata,
                ),
            )
        } catch (e: SerializationException) {
            thisLogger().warn("Failed to parse step answer response", e)
            Result.failure(IllegalStateException("Failed to parse response: ${e.message}", e))
        } catch (e: IllegalStateException) {
            thisLogger().warn("Provider error during step answer", e)
            Result.failure(e)
        } catch (e: Exception) {
            thisLogger().warn("Unexpected error during step answer", e)
            Result.failure(IllegalStateException("Unexpected error: ${e.message}", e))
        }
    }

    private fun buildMetadata(rawMetadata: ResponseMetadata?, response: LlmResponse): ResponseMetadata? {
        val durationMs = rawMetadata?.durationMs ?: return null
        val steps = response.steps ?: emptyList()
        return ResponseMetadata(
            durationMs = durationMs,
            costUsd = rawMetadata.costUsd,
            numTurns = rawMetadata.numTurns,
            stepCount = steps.size,
            fileCount = steps.map { it.filePath }.distinct().size,
        )
    }

    private fun buildPrompt(
        question: String,
        mode: AnalysisMode,
        queryContext: QueryContext?,
        followUpContext: FollowUpContext?,
        featureScope: FeatureScopeContext?,
        provider: LlmProvider,
    ): String {
        return PromptEnvelopeFactory.buildWalkthroughPrompt(
            question = question,
            mode = mode,
            maxSteps = settings.state.maxSteps,
            queryContext = queryContext,
            followUpContext = followUpContext,
            featureScope = featureScope,
            providerCapabilities = provider.capabilities,
            json = json,
        )
    }

    private fun buildStepPrompt(
        question: String,
        step: FlowStep,
        mode: AnalysisMode,
        queryContext: QueryContext?,
        followUpContext: FollowUpContext?,
        featureScope: FeatureScopeContext?,
        provider: LlmProvider,
    ): String {
        return PromptEnvelopeFactory.buildStepQuestionPrompt(
            question = question,
            step = step,
            mode = mode,
            queryContext = queryContext,
            followUpContext = followUpContext,
            featureScope = featureScope,
            providerCapabilities = provider.capabilities,
            json = json,
        )
    }

    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed

        val withoutOpening = if (trimmed.startsWith("```json")) {
            trimmed.removePrefix("```json")
        } else {
            trimmed.removePrefix("```")
        }.trimStart('\n', '\r')

        return if (withoutOpening.endsWith("```")) {
            withoutOpening.removeSuffix("```").trimEnd('\n', '\r')
        } else {
            withoutOpening
        }
    }

    private fun sanitizeStepAnswer(answer: StepAnswer, step: FlowStep): StepAnswer {
        val stepValidator = validator()
        val importantLines = answer.importantLines.mapNotNull { annotation ->
            val start = annotation.startLine.coerceIn(step.startLine, step.endLine)
            val end = annotation.endLine.coerceIn(step.startLine, step.endLine)
            if (start > end) {
                null
            } else {
                annotation.copy(startLine = start, endLine = end)
            }
        }
        return answer.copy(
            importantLines = importantLines,
            evidence = stepValidator.sanitizeEvidenceItems(answer.evidence, step.filePath),
            potentialBugs = stepValidator.sanitizePotentialBugFindings(answer.potentialBugs, step.filePath),
        )
    }

    private fun validator(): StepValidator {
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is not available")
        return StepValidator(basePath)
    }
}
