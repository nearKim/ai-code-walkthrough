package com.github.nearkim.aicodewalkthrough.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "CodeTourSettings", storages = [Storage("codeTourSettings.xml")])
class CodeTourSettings : PersistentStateComponent<CodeTourSettings.State> {

    data class State(
        var claudePath: String = "claude",
        var requestTimeout: Int = 120,
        var maxSteps: Int = 20,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
