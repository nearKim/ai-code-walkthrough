package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

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
        onProgress: ((String) -> Unit)? = null,
    ): Result<MappingResult> {
        return try {
            val prompt = buildPrompt(question, mode, queryContext, followUpContext)
            val providerResponse = providerService.currentProvider().query(prompt, onProgress = onProgress)
            val cleaned = stripMarkdownFences(providerResponse.content)
            val llmResponse = json.decodeFromString<LlmResponse>(cleaned)

            val finalResponse = if (llmResponse.type == "flow_map" && llmResponse.steps != null) {
                val basePath = project.basePath
                    ?: return Result.failure(IllegalStateException("Project base path is not available"))
                val validator = StepValidator(basePath)
                val validatedSteps = validator.validate(llmResponse.steps)
                llmResponse.copy(steps = validatedSteps)
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
        onProgress: ((String) -> Unit)? = null,
    ): Result<StepAnswerResult> {
        return try {
            val prompt = buildStepPrompt(question, step, mode, queryContext, followUpContext)
            val providerResponse = providerService.currentProvider().query(prompt, onProgress = onProgress)
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
    ): String {
        val wrapper = buildJsonObject {
            put("mode", mode.id)
            put("max_steps", settings.state.maxSteps)
            put("question", question)
            queryContext?.let { context ->
                put("query_context", buildJsonObject {
                    context.filePath?.let { put("file_path", it) }
                    context.symbol?.let { put("symbol", it) }
                    context.selectionStartLine?.let { put("selection_start_line", it) }
                    context.selectionEndLine?.let { put("selection_end_line", it) }
                    context.selectedText?.takeIf { it.isNotBlank() }?.let { put("selected_text", it) }
                    context.diffSummary?.takeIf { it.isNotBlank() }?.let { put("diff_summary", it) }
                    context.failingTestName?.takeIf { it.isNotBlank() }?.let { put("failing_test_name", it) }
                    put("invoked_from_cursor", context.invokedFromCursor)
                })
            }
            followUpContext?.let { followUp ->
                put("follow_up_context", buildJsonObject {
                    put("original_question", followUp.originalQuestion)
                    followUp.activeStepId?.let { put("active_step_id", it) }
                    if (followUp.clarificationHistory.isNotEmpty()) {
                        put("clarification_history", buildJsonArray {
                            followUp.clarificationHistory.forEach { exchange ->
                                add(buildJsonObject {
                                    put("question", exchange.question)
                                    put("answer", exchange.answer)
                                })
                            }
                        })
                    }
                    put("previous_flow_map", json.encodeToJsonElement(followUp.previousFlowMap))
                })
            }
        }
        return wrapper.toString()
    }

    private fun buildStepPrompt(
        question: String,
        step: FlowStep,
        mode: AnalysisMode,
        queryContext: QueryContext?,
        followUpContext: FollowUpContext?,
    ): String {
        val wrapper = buildJsonObject {
            put("request_type", "step_question")
            put("mode", mode.id)
            put("question", question)
            put("current_step", json.encodeToJsonElement(step))
            queryContext?.let { context ->
                put("query_context", buildJsonObject {
                    context.filePath?.let { put("file_path", it) }
                    context.symbol?.let { put("symbol", it) }
                    context.selectionStartLine?.let { put("selection_start_line", it) }
                    context.selectionEndLine?.let { put("selection_end_line", it) }
                    context.selectedText?.takeIf { it.isNotBlank() }?.let { put("selected_text", it) }
                    context.diffSummary?.takeIf { it.isNotBlank() }?.let { put("diff_summary", it) }
                    context.failingTestName?.takeIf { it.isNotBlank() }?.let { put("failing_test_name", it) }
                    put("invoked_from_cursor", context.invokedFromCursor)
                })
            }
            followUpContext?.let { followUp ->
                put("follow_up_context", buildJsonObject {
                    put("original_question", followUp.originalQuestion)
                    followUp.activeStepId?.let { put("active_step_id", it) }
                    if (followUp.clarificationHistory.isNotEmpty()) {
                        put("clarification_history", buildJsonArray {
                            followUp.clarificationHistory.forEach { exchange ->
                                add(buildJsonObject {
                                    put("question", exchange.question)
                                    put("answer", exchange.answer)
                                })
                            }
                        })
                    }
                    put("previous_flow_map", json.encodeToJsonElement(followUp.previousFlowMap))
                })
            }
        }
        return wrapper.toString()
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
        val importantLines = answer.importantLines.mapNotNull { annotation ->
            val start = annotation.startLine.coerceIn(step.startLine, step.endLine)
            val end = annotation.endLine.coerceIn(step.startLine, step.endLine)
            if (start > end) {
                null
            } else {
                annotation.copy(startLine = start, endLine = end)
            }
        }
        return answer.copy(importantLines = importantLines)
    }
}
