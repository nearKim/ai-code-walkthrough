package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WalkthroughSettings(
    @SerialName("provider_id") val providerId: String = AiProvider.CLAUDE_CLI.id,
    @SerialName("codex_cli_path") val codexCliPath: String = "codex",
    @SerialName("codex_model") val codexModel: String = ProviderModelCatalog.CODEX_MODEL,
    @SerialName("codex_reasoning_effort") val codexReasoningEffort: String =
        ProviderModelCatalog.DEFAULT_CODEX_REASONING_EFFORT,
    @SerialName("claude_path") val claudePath: String = "claude",
    @SerialName("claude_model") val claudeModel: String = ProviderModelCatalog.DEFAULT_CLAUDE_MODEL,
    @SerialName("claude_effort") val claudeEffort: String = "max",
    @SerialName("max_steps") val maxSteps: Int = 20,
    @SerialName("default_mode_id") val defaultModeId: String = AnalysisMode.UNDERSTAND.id,
    @SerialName("enable_mcp") val enableMcp: Boolean = false,
    @SerialName("mcp_config_path") val mcpConfigPath: String = "",
) {
    val provider: AiProvider
        get() = AiProvider.fromId(providerId)

    val defaultMode: AnalysisMode
        get() = AnalysisMode.fromId(defaultModeId)

    fun normalized(): WalkthroughSettings = copy(
        providerId = provider.id,
        codexCliPath = codexCliPath.trim().ifEmpty { "codex" },
        codexModel = ProviderModelCatalog.normalizeCodexModel(codexModel),
        codexReasoningEffort = ProviderModelCatalog.normalizeCodexReasoningEffort(codexReasoningEffort),
        claudePath = claudePath.trim().ifEmpty { "claude" },
        claudeModel = ProviderModelCatalog.normalizeClaudeModel(claudeModel),
        claudeEffort = claudeEffort.trim().ifEmpty { "max" },
        maxSteps = maxSteps.coerceIn(1, 100),
        defaultModeId = defaultMode.id,
        mcpConfigPath = mcpConfigPath.trim(),
    )
}
