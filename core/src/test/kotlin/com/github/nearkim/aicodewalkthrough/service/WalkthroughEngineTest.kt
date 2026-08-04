package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WalkthroughEngineTest {

    @Test
    fun `mapping rejects non Python targets before calling the provider`() = runBlocking {
        val root = Files.createTempDirectory("walkthrough-engine-non-python")
        Files.writeString(root.resolve("Main.kt"), "fun main() = Unit\n")
        val provider = CapturingProvider()
        val engine = WalkthroughEngine(
            projectRoot = root,
            settings = { WalkthroughSettings(providerId = AiProvider.CODEX_CLI.id) },
            providerFor = { provider },
            analysisRoot = Files.createTempDirectory("walkthrough-engine-non-python-analysis"),
        )

        val result = engine.mapFlow("Map this repository")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Only Python target repositories"))
        assertTrue(!provider.called)
    }

    @Test
    fun `mechanical symbols are collected before the provider maps them`() = runBlocking {
        val root = Files.createTempDirectory("walkthrough-engine-symbols")
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"sample\"\n")
        Files.writeString(root.resolve("app.py"), "class Application:\n    def run(self):\n        return 1\n")
        val progress = mutableListOf<String>()
        val provider = CapturingProvider()
        val engine = WalkthroughEngine(
            projectRoot = root,
            settings = { WalkthroughSettings(providerId = AiProvider.CODEX_CLI.id) },
            providerFor = { provider },
            analysisRoot = Files.createTempDirectory("walkthrough-engine-analysis"),
        )

        val result = engine.mapFlow(
            question = "Map this repository",
            mode = AnalysisMode.UNDERSTAND,
            onProgress = progress::add,
        ).getOrThrow()

        val envelope = Json.parseToJsonElement(provider.prompt).jsonObject
        assertTrue(envelope.containsKey("mechanical_symbol_inventory"))
        assertTrue(progress.indexOfFirst { it.startsWith("Indexed 1 Python files") } < progress.indexOf("provider-called"))
        assertEquals(listOf("python_stdlib_ast"), result.response.analysisTrace?.semanticToolsUsed)
    }

    private class CapturingProvider : LlmProvider {
        override val provider = AiProvider.CODEX_CLI
        override val capabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true)
        lateinit var prompt: String
        var called = false

        override suspend fun query(
            prompt: String,
            promptKind: PromptKind,
            onProgress: ((String) -> Unit)?,
        ): ProviderResponse {
            called = true
            this.prompt = prompt
            onProgress?.invoke("provider-called")
            return ProviderResponse(
                Json.encodeToString(
                    LlmResponse(
                        type = "flow_map",
                        summary = "Sample application.",
                        steps = listOf(
                            FlowStep(
                                id = "application",
                                title = "Run the application",
                                filePath = "app.py",
                                symbol = "Application.run",
                                startLine = 2,
                                endLine = 3,
                                explanation = "Runs the application.",
                                whyIncluded = "It is the executable behavior.",
                            ),
                        ),
                        entryStepId = "application",
                        terminalStepIds = listOf("application"),
                    ),
                ),
            )
        }

        override suspend fun checkAvailability() = ProviderStatus(provider, true, "Available")

        override fun cancel() = Unit
    }
}
