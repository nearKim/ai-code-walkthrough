package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewResponse
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

data class RepositoryReviewResult(
    val snapshot: RepositoryReviewSnapshot,
    val metadata: ResponseMetadata?,
)

@Service(Service.Level.PROJECT)
class RepositoryReviewPlannerService(private val project: Project) {

    private val providerService = project.service<LlmProviderService>()
    private val settings get() = project.service<com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings>()
    private val fingerprintService = project.service<RepositoryFingerprintService>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun reviewRepository(
        onProgress: ((String) -> Unit)? = null,
    ): Result<RepositoryReviewResult> {
        return try {
            val provider = providerService.currentProvider()
            providerService.requireRepositoryReviewSupport(provider)
            val prompt = buildPrompt(provider)
            val providerResponse = provider.query(
                prompt = prompt,
                promptKind = PromptKind.REPOSITORY_REVIEW,
                onProgress = onProgress,
            )
            val cleaned = stripMarkdownFences(providerResponse.content)
            val response = json.decodeFromString<RepositoryReviewResponse>(cleaned)
            val fingerprint = fingerprintService.capture()
            val snapshot = response.toSnapshot(
                id = UUID.randomUUID().toString(),
                fingerprint = fingerprint,
                providerId = provider.provider.id,
                providerName = provider.provider.displayName,
            )
                ?: return Result.failure(IllegalStateException("Unexpected repository review response from LLM"))
            val validated = validator().validate(snapshot)
            Result.success(
                RepositoryReviewResult(
                    snapshot = validated.snapshot,
                    metadata = providerResponse.metadata,
                ),
            )
        } catch (e: SerializationException) {
            thisLogger().warn("Failed to parse repository review response", e)
            Result.failure(IllegalStateException("Failed to parse repository review response: ${e.message}", e))
        } catch (e: IllegalStateException) {
            thisLogger().warn("Repository review provider error", e)
            Result.failure(e)
        } catch (e: Exception) {
            thisLogger().warn("Unexpected repository review error", e)
            Result.failure(IllegalStateException("Unexpected repository review error: ${e.message}", e))
        }
    }

    fun cancel() {
        providerService.cancel()
    }

    private fun buildPrompt(provider: LlmProvider): String {
        return buildJsonObject {
            put("request_type", "repository_review")
            put("question", "Perform a thorough code review of the entire repository, split it into durable business features or capability slices, and produce bounded walkthrough paths the IDE can reuse later.")
            put("max_features", settings.state.maxRepositoryFeatures)
            put("grounding_capabilities", buildJsonObject {
                put("repo_grounded_walkthrough", provider.capabilities.supportsRepoGroundedWalkthrough)
                put("semantic_navigation_hints", provider.capabilities.supportsSemanticNavigationHints)
                put("delegated_analysis_hints", provider.capabilities.supportsDelegatedAnalysisHints)
            })
            put("review_goals", buildJsonArray {
                add(JsonPrimitive("Use symbolic analysis first and raw file reads only when needed."))
                add(JsonPrimitive("Prioritize correctness, regression risk, invariants, security, concurrency, data flow, API contracts, and test gaps."))
                add(JsonPrimitive("Split the repository into the smallest set of meaningful business features or capability slices that a human would actually use."))
                add(JsonPrimitive("For every feature, produce at least one bounded walkthrough path that can be executed later without re-reviewing the whole repository."))
                add(JsonPrimitive("Prefer high-signal findings with evidence over exhaustive low-value noise."))
                add(JsonPrimitive("Call out shared infrastructure separately when it materially affects multiple features."))
            })
        }.toString()
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

    private fun validator(): RepositoryReviewValidator {
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is not available")
        return RepositoryReviewValidator(basePath)
    }
}
