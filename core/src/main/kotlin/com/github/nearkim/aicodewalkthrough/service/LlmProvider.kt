package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata

data class ProviderResponse(
    val content: String,
    val metadata: ResponseMetadata? = null,
)

data class ProviderCapabilities(
    val supportsRepoGroundedWalkthrough: Boolean,
    val supportsSemanticNavigationHints: Boolean = false,
    val supportsDelegatedAnalysisHints: Boolean = false,
)

enum class PromptKind {
    WALKTHROUGH,
}

data class ProviderStatus(
    val provider: AiProvider,
    val available: Boolean,
    val message: String,
    val walkthroughSupported: Boolean = true,
)

interface LlmProvider {
    val provider: AiProvider
    val capabilities: ProviderCapabilities

    suspend fun query(
        prompt: String,
        promptKind: PromptKind = PromptKind.WALKTHROUGH,
        onProgress: ((String) -> Unit)? = null,
    ): ProviderResponse

    suspend fun checkAvailability(): ProviderStatus

    fun cancel()
}
