package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata

data class ProviderResponse(
    val content: String,
    val metadata: ResponseMetadata? = null,
)

data class ProviderStatus(
    val provider: AiProvider,
    val available: Boolean,
    val message: String,
)

interface LlmProvider {
    val provider: AiProvider

    suspend fun query(
        prompt: String,
        onProgress: ((String) -> Unit)? = null,
    ): ProviderResponse

    suspend fun checkAvailability(): ProviderStatus

    fun cancel()
}
