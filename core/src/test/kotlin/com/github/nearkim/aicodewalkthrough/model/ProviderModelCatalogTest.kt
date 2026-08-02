package com.github.nearkim.aicodewalkthrough.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelCatalogTest {

    @Test
    fun `codex catalog exposes only sol with ultra and max`() {
        assertEquals(listOf(ProviderModelCatalog.CODEX_MODEL), ProviderModelCatalog.codexModels.map { it.id })
        assertEquals(listOf("ultra", "max"), ProviderModelCatalog.codexReasoningEfforts)
    }

    @Test
    fun `legacy codex settings normalize to supported defaults`() {
        assertEquals(
            ProviderModelCatalog.CODEX_MODEL,
            ProviderModelCatalog.normalizeCodexModel("gpt-5.5"),
        )
        assertEquals(
            ProviderModelCatalog.DEFAULT_CODEX_REASONING_EFFORT,
            ProviderModelCatalog.normalizeCodexReasoningEffort("high"),
        )
        assertEquals("max", ProviderModelCatalog.normalizeCodexReasoningEffort("max"))
    }

    @Test
    fun `claude catalog exposes only fable and opus 5 aliases`() {
        assertEquals(
            listOf(ProviderModelCatalog.CLAUDE_FABLE_MODEL, ProviderModelCatalog.CLAUDE_OPUS_MODEL),
            ProviderModelCatalog.claudeModels.map { it.id },
        )
        assertEquals(
            ProviderModelCatalog.DEFAULT_CLAUDE_MODEL,
            ProviderModelCatalog.normalizeClaudeModel("claude-opus-4-7"),
        )
    }
}
