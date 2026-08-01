package com.github.nearkim.aicodewalkthrough.model

data class ProviderModelOption(
    val id: String,
    val displayName: String,
) {
    override fun toString(): String = displayName
}

object ProviderModelCatalog {
    const val CODEX_MODEL = "gpt-5.6-sol"
    const val DEFAULT_CODEX_REASONING_EFFORT = "ultra"
    const val CLAUDE_FABLE_MODEL = "fable"
    const val CLAUDE_OPUS_MODEL = "opus"
    const val DEFAULT_CLAUDE_MODEL = CLAUDE_OPUS_MODEL

    val codexModels = listOf(
        ProviderModelOption(CODEX_MODEL, "GPT-5.6 Sol"),
    )
    val codexReasoningEfforts = listOf("ultra", "max")
    val claudeModels = listOf(
        ProviderModelOption(CLAUDE_FABLE_MODEL, "Claude Fable 5"),
        ProviderModelOption(CLAUDE_OPUS_MODEL, "Claude Opus 5"),
    )

    fun normalizeCodexModel(model: String): String =
        model.takeIf { candidate -> codexModels.any { it.id == candidate } } ?: CODEX_MODEL

    fun normalizeCodexReasoningEffort(effort: String): String =
        effort.takeIf { it in codexReasoningEfforts } ?: DEFAULT_CODEX_REASONING_EFFORT

    fun normalizeClaudeModel(model: String): String =
        model.takeIf { candidate -> claudeModels.any { it.id == candidate } } ?: DEFAULT_CLAUDE_MODEL

    fun codexOption(model: String): ProviderModelOption =
        codexModels.first { it.id == normalizeCodexModel(model) }

    fun claudeOption(model: String): ProviderModelOption =
        claudeModels.first { it.id == normalizeClaudeModel(model) }
}
