package com.github.nearkim.aicodewalkthrough.model

enum class AiProvider(
    val id: String,
    val displayName: String,
    val requiresApiKey: Boolean,
) {
    CODEX_CLI("codex_cli", "Codex CLI", false),
    CHATGPT_API("chatgpt_api", "ChatGPT API", true),
    CLAUDE_CLI("claude_cli", "Claude CLI", false),
    CLAUDE_API("claude_api", "Claude API", true),
    GEMINI_API("gemini_api", "Gemini API", true),
    ;

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: CLAUDE_CLI
    }

    override fun toString(): String = displayName
}
