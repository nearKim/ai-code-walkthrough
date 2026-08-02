package com.github.nearkim.aicodewalkthrough.model

enum class AiProvider(
    val id: String,
    val displayName: String,
) {
    CLAUDE_CLI("claude_cli", "Claude CLI"),
    CODEX_CLI("codex_cli", "Codex CLI"),
    ;

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: CLAUDE_CLI
    }

    override fun toString(): String = displayName
}
