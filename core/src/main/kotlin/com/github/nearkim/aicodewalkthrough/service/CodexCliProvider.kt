package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ProviderModelCatalog
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class CodexCliProvider(
    private val projectRoot: Path,
    private val settings: () -> WalkthroughSettings,
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }
    override val provider = AiProvider.CODEX_CLI
    override val capabilities = ProviderCapabilities(
        supportsRepoGroundedWalkthrough = true,
        supportsDelegatedAnalysisHints = true,
    )

    @Volatile
    private var activeProcess: Process? = null

    override suspend fun query(
        prompt: String,
        promptKind: PromptKind,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        val outputPath = Files.createTempFile("codex-last-message", ".json")
        try {
            val state = settings().normalized()
            val command = CodexCliCommand.build(
                state = state,
                basePath = projectRoot.toString(),
                outputPath = outputPath.toString(),
                prompt = buildPrompt(prompt, promptKind),
            )
            val process = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(false)
                .redirectInput(ProcessBuilder.Redirect.from(CliEnvironment.nullInput()))
                .start()
            activeProcess = process
            CliProcessRunner.runUntilExit(
                process = process,
                onStdoutLine = { line -> parseProgress(line)?.let { onProgress?.invoke(it) } },
                onStderrLine = { line -> line.trim().takeIf(String::isNotEmpty)?.let { onProgress?.invoke(it) } },
            )
            if (process.exitValue() != 0) {
                throw IllegalStateException("Codex CLI exited with code ${process.exitValue()}")
            }
            val content = Files.readString(outputPath).trim()
            if (content.isBlank()) throw IllegalStateException("Codex CLI returned no final message")
            ProviderResponse(content = content)
        } finally {
            Files.deleteIfExists(outputPath)
            activeProcess = null
        }
    }

    override suspend fun checkAvailability(): ProviderStatus = withContext(Dispatchers.IO) {
        try {
            val command = listOf(CliPathResolver.resolve(settings().codexCliPath), "--version")
            val process = ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.from(CliEnvironment.nullInput()))
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val finished = CliProcessRunner.run(
                process = process,
                timeout = Duration.ofSeconds(5),
                onStdoutLine = { stdout.appendLine(it) },
                onStderrLine = { stderr.appendLine(it) },
            )
            if (!finished) return@withContext ProviderStatus(provider, false, "Codex CLI timed out")
            val output = stdout.toString().trim()
            val error = stderr.toString().trim()
            if (process.exitValue() == 0) {
                ProviderStatus(provider, true, output.ifBlank { "Codex CLI is available" })
            } else {
                val message = "codex exited with code ${process.exitValue()}"
                ProviderStatus(provider, false, error.ifBlank { output }.takeIf(String::isNotBlank)?.let { "$message: ${it.take(500)}" } ?: message)
            }
        } catch (error: java.io.IOException) {
            ProviderStatus(provider, false, error.message ?: "codex not found")
        }
    }

    override fun cancel() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    private fun buildPrompt(prompt: String, promptKind: PromptKind): String = buildString {
        appendLine(PromptContract.buildSystemPrompt(promptKind, enableSemanticTools = false))
        appendLine()
        appendLine("User question:")
        append(prompt)
    }

    private fun parseProgress(line: String): String? {
        if (line.isBlank()) return null
        return runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()?.let { event ->
            when (event["type"]?.jsonPrimitive?.content) {
                "task.started" -> event["task"]?.jsonPrimitive?.content
                "item.completed" -> summarizeItem(event["item"]?.jsonObject)
                else -> null
            }
        }
    }

    private fun summarizeItem(item: JsonObject?): String? = when (item?.get("type")?.jsonPrimitive?.content) {
        "reasoning" -> "Thinking..."
        "command_execution" -> item["command"]?.jsonPrimitive?.content?.let { "Running: ${it.take(80)}" }
        else -> null
    }
}

internal object CodexCliCommand {
    fun build(
        state: WalkthroughSettings,
        basePath: String,
        outputPath: String,
        prompt: String,
        resolveExecutable: (String) -> String = CliPathResolver::resolve,
    ): List<String> = buildList {
        add(resolveExecutable(state.codexCliPath))
        add("exec")
        add("--json")
        add("--sandbox"); add("read-only")
        add("--skip-git-repo-check")
        add("-C"); add(basePath)
        add("-m"); add(ProviderModelCatalog.normalizeCodexModel(state.codexModel))
        val effort = ProviderModelCatalog.normalizeCodexReasoningEffort(state.codexReasoningEffort)
        add("-c"); add("model_reasoning_effort=\"$effort\"")
        add("-o"); add(outputPath)
        add(prompt)
    }
}
