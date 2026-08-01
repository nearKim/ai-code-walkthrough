package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.ProviderModelCatalog
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
        var codexModel: String = ProviderModelCatalog.CODEX_MODEL,
        var codexReasoningEffort: String = ProviderModelCatalog.DEFAULT_CODEX_REASONING_EFFORT,
        var claudePath: String = "claude",
        var claudeModel: String = ProviderModelCatalog.DEFAULT_CLAUDE_MODEL,
        var claudeEffort: String = "max",
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
        state.codexModel = ProviderModelCatalog.normalizeCodexModel(state.codexModel)
        state.codexReasoningEffort =
            ProviderModelCatalog.normalizeCodexReasoningEffort(state.codexReasoningEffort)
        state.claudeModel = ProviderModelCatalog.normalizeClaudeModel(state.claudeModel)
        this.state = state
    }
}
