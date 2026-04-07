package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.ClaudeEnvelope
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
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

@Service(Service.Level.PROJECT)
class FlowPlannerService(private val project: Project) {

    private val claudeService = project.service<ClaudeCliService>()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun mapFlow(
        question: String,
        followUpContext: FollowUpContext? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<MappingResult> {
        return try {
            val prompt = buildPrompt(question, followUpContext)
            val rawResponse = claudeService.query(prompt, onStderrLine = onProgress)

            val envelope = json.decodeFromString<ClaudeEnvelope>(rawResponse)
            if (envelope.isError) {
                return Result.failure(
                    IllegalStateException("Claude returned error: ${envelope.result}")
                )
            }

            val resultText = envelope.result
                ?: return Result.failure(IllegalStateException("Claude envelope has null result"))

            val cleaned = stripMarkdownFences(resultText)
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

            val metadata = buildMetadata(envelope, finalResponse)
            Result.success(MappingResult(finalResponse, metadata))
        } catch (e: SerializationException) {
            thisLogger().warn("Failed to parse Claude response", e)
            Result.failure(IllegalStateException("Failed to parse response: ${e.message}", e))
        } catch (e: IllegalStateException) {
            thisLogger().warn("Claude CLI error", e)
            Result.failure(e)
        } catch (e: Exception) {
            thisLogger().warn("Unexpected error during flow mapping", e)
            Result.failure(IllegalStateException("Unexpected error: ${e.message}", e))
        }
    }

    fun cancel() {
        claudeService.cancel()
    }

    private fun buildMetadata(envelope: ClaudeEnvelope, response: LlmResponse): ResponseMetadata? {
        val durationMs = envelope.durationMs ?: return null
        val steps = response.steps ?: emptyList()
        return ResponseMetadata(
            durationMs = durationMs,
            costUsd = envelope.costUsd,
            numTurns = envelope.numTurns ?: 0,
            stepCount = steps.size,
            fileCount = steps.map { it.filePath }.distinct().size,
        )
    }

    private fun buildPrompt(question: String, followUpContext: FollowUpContext?): String {
        if (followUpContext == null) return question

        val wrapper = buildJsonObject {
            put("context", buildJsonObject {
                put("original_question", followUpContext.originalQuestion)
                followUpContext.activeStepId?.let { put("active_step_id", it) }
                if (followUpContext.clarificationHistory.isNotEmpty()) {
                    put("clarification_history", buildJsonArray {
                        followUpContext.clarificationHistory.forEach { exchange ->
                            add(buildJsonObject {
                                put("question", exchange.question)
                                put("answer", exchange.answer)
                            })
                        }
                    })
                }
                put("previous_flow_map", json.encodeToJsonElement(followUpContext.previousFlowMap))
            })
            put("follow_up", question)
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
}
