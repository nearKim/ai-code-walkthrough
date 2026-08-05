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
import java.io.IOException
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
        assertEquals(AnalysisMode.UNDERSTAND.id, result.response.mode)
        assertTrue(result.response.diagramSections.orEmpty().any { it.id == "system-overview" })
    }

    @Test
    fun `provider faults map to a failed result and clear the cancellation handle`() = runBlocking {
        val root = Files.createTempDirectory("walkthrough-engine-failure")
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"sample\"\n")
        Files.writeString(root.resolve("app.py"), "def run():\n    return 1\n")
        val analysisRoot = Files.createTempDirectory("walkthrough-engine-failure-analysis")

        fun engineFor(provider: LlmProvider) = WalkthroughEngine(
            projectRoot = root,
            settings = { WalkthroughSettings(providerId = AiProvider.CODEX_CLI.id) },
            providerFor = { provider },
            analysisRoot = analysisRoot,
        )

        val ioFailure = engineFor(FailingProvider(IOException("socket closed"))).mapFlow("Map this repository")
        assertTrue(ioFailure.isFailure)
        assertTrue(ioFailure.exceptionOrNull()!!.message!!.contains("Provider I/O error"))

        val unexpected = engineFor(FailingProvider(IllegalArgumentException("boom"))).mapFlow("Map this repository")
        assertTrue(unexpected.isFailure)
        assertTrue(unexpected.exceptionOrNull()!!.message!!.contains("Unexpected provider error"))

        // A garbled payload has to fail the step answer rather than escape as a raw parse error.
        val garbled = engineFor(GarbledProvider()).answerStepQuestion(
            question = "What does this do?",
            step = FlowStep(
                id = "run",
                title = "run",
                filePath = "app.py",
                startLine = 1,
                endLine = 2,
                explanation = "Runs.",
                whyIncluded = "Entry point.",
            ),
        )
        assertTrue(garbled.isFailure)
    }

    @Test
    fun `an unsupported provider is rejected before any query`() = runBlocking {
        val root = Files.createTempDirectory("walkthrough-engine-unsupported")
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"sample\"\n")
        val provider = object : CapturingProvider() {
            override val capabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = false)
        }
        val engine = WalkthroughEngine(
            projectRoot = root,
            settings = { WalkthroughSettings(providerId = AiProvider.CODEX_CLI.id) },
            providerFor = { provider },
            analysisRoot = Files.createTempDirectory("walkthrough-engine-unsupported-analysis"),
        )

        val result = engine.mapFlow("Map this repository")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("cannot safely inspect the local repository"))
        assertTrue(!provider.called)
    }

    private class FailingProvider(private val error: Throwable) : LlmProvider {
        override val provider = AiProvider.CODEX_CLI
        override val capabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true)

        override suspend fun query(
            prompt: String,
            promptKind: PromptKind,
            onProgress: ((String) -> Unit)?,
        ): ProviderResponse = throw error

        override suspend fun checkAvailability() = ProviderStatus(provider, true, "Available")

        override fun cancel() = Unit
    }

    private class GarbledProvider : LlmProvider {
        override val provider = AiProvider.CODEX_CLI
        override val capabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true)

        override suspend fun query(
            prompt: String,
            promptKind: PromptKind,
            onProgress: ((String) -> Unit)?,
        ): ProviderResponse = ProviderResponse("{\"type\": ")

        override suspend fun checkAvailability() = ProviderStatus(provider, true, "Available")

        override fun cancel() = Unit
    }

    private open class CapturingProvider : LlmProvider {
        override val provider = AiProvider.CODEX_CLI
        override val capabilities: ProviderCapabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true)
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
