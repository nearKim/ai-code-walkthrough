package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class LlmProviderService(private val project: Project) {

    private val settings get() = project.service<CodeTourSettings>()

    fun currentProvider(): LlmProvider = providerFor(settings.state.provider)

    fun providerFor(provider: AiProvider): LlmProvider = when (provider) {
        AiProvider.CODEX_CLI -> project.service<CodexCliService>()
        AiProvider.CHATGPT_API -> project.service<OpenAiApiService>()
        AiProvider.CLAUDE_CLI -> project.service<ClaudeCliService>()
        AiProvider.CLAUDE_API -> project.service<ClaudeApiService>()
        AiProvider.GEMINI_API -> project.service<GeminiApiService>()
    }

    suspend fun checkAvailability(provider: AiProvider = settings.state.provider): ProviderStatus {
        return providerFor(provider).checkAvailability()
    }

    fun cancel() {
        currentProvider().cancel()
    }
}
