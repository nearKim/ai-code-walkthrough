package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "CodeTourSettings", storages = [Storage("codeTourSettings.xml")])
class CodeTourSettings : PersistentStateComponent<CodeTourSettings.State> {

    data class State(
        var providerId: String = AiProvider.CLAUDE_CLI.id,
        var codexCliPath: String = "codex",
        var claudePath: String = "claude",
        var openAiModel: String = "gpt-5",
        var claudeApiModel: String = "claude-sonnet-4-20250514",
        var geminiModel: String = "gemini-2.5-pro",
        var openAiApiKeyConfigured: Boolean = false,
        var claudeApiKeyConfigured: Boolean = false,
        var geminiApiKeyConfigured: Boolean = false,
        var requestTimeout: Int = 120,
        var maxSteps: Int = 20,
        var defaultModeId: String = AnalysisMode.UNDERSTAND.id,
        var enableMcp: Boolean = false,
        var mcpConfigPath: String = "",
        var enableCursorActions: Boolean = true,
        var enableReviewBadges: Boolean = true,
        var enableCommentComposer: Boolean = true,
        var showRawProgressLog: Boolean = true,
    ) {
        val provider: AiProvider
            get() = AiProvider.fromId(providerId)
        val defaultMode: AnalysisMode
            get() = AnalysisMode.fromId(defaultModeId)
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
