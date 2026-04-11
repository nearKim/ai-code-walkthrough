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
        val implementation = providerFor(provider)
        val status = implementation.checkAvailability()
        return if (status.available && !implementation.capabilities.supportsRepoGroundedWalkthrough) {
            status.copy(
                walkthroughSupported = false,
                message = "${status.message} · use Codex CLI or Claude CLI for repo-grounded walkthroughs",
            )
        } else {
            status.copy(walkthroughSupported = implementation.capabilities.supportsRepoGroundedWalkthrough)
        }
    }

    fun cancel() {
        currentProvider().cancel()
    }

    fun supportsRepositoryReview(provider: LlmProvider = currentProvider()): Boolean {
        return when (provider.provider) {
            AiProvider.CLAUDE_CLI -> settings.state.enableMcp
            else -> provider.capabilities.supportsSemanticNavigationHints
        }
    }

    fun requireRepoGroundedWalkthroughSupport(provider: LlmProvider = currentProvider()) {
        if (!provider.capabilities.supportsRepoGroundedWalkthrough) {
            throw IllegalStateException(
                "${provider.provider.displayName} cannot safely inspect the local repository. " +
                    "Use Codex CLI or Claude CLI for grounded walkthroughs.",
            )
        }
    }

    fun requireRepositoryReviewSupport(provider: LlmProvider = currentProvider()) {
        requireRepoGroundedWalkthroughSupport(provider)
        if (!supportsRepositoryReview(provider)) {
            throw IllegalStateException(
                "Thorough repository review requires symbolic analysis. " +
                    "Use Claude CLI with MCP semantic navigation enabled.",
            )
        }
    }
}
