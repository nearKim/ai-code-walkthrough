package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.ProviderModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeTourSettingsTest {

    @Test
    fun `loadState migrates unsupported provider model values`() {
        val settings = CodeTourSettings()

        settings.loadState(
            CodeTourSettings.State(
                codexModel = "gpt-5.5",
                codexReasoningEffort = "high",
                claudeModel = "claude-opus-4-7",
            ),
        )

        assertEquals(ProviderModelCatalog.CODEX_MODEL, settings.state.codexModel)
        assertEquals(
            ProviderModelCatalog.DEFAULT_CODEX_REASONING_EFFORT,
            settings.state.codexReasoningEffort,
        )
        assertEquals(ProviderModelCatalog.DEFAULT_CLAUDE_MODEL, settings.state.claudeModel)
    }
}
