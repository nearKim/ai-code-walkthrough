package com.github.nearkim.aicodewalkthrough.service

import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContractTest {

    @Test
    fun `whole codebase prompt coordinates grounded parallel behavior checks`() {
        val prompt = PromptContract.buildSystemPrompt(enableSemanticTools = false)

        assertTrue(prompt.contains("WHOLE-CODEBASE REVIEW AND BEHAVIOR SEARCH"))
        assertTrue(prompt.contains("at most three independent read-only workers"))
        assertTrue(prompt.contains("SYMBOLIC OR PATH-SENSITIVE TOOL RESULTS"))
        assertTrue(prompt.contains("Never invent tool output"))
        assertTrue(prompt.contains("persisted deterministic analyzer output"))
        assertTrue(prompt.contains("immutable facts"))
        assertTrue(prompt.contains("\"diagram_sections\""))
        assertTrue(prompt.contains("system overview, component map, and feature walkthrough"))
    }
}
