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
        var codexModel: String = "gpt-5.5",
        var codexReasoningEffort: String = "high",
        var claudePath: String = "claude",
        var claudeModel: String = "claude-opus-4-7",
        var claudeEffort: String = "max",
        var requestTimeout: Int = 120,
        var maxSteps: Int = 20,
        var defaultModeId: String = AnalysisMode.UNDERSTAND.id,
        var enableMcp: Boolean = false,
        var mcpConfigPath: String = "",
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
