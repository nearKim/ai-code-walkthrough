package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ProviderModelCatalog
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Duration

@Service(Service.Level.PROJECT)
class CodexCliService(private val project: Project) : Disposable, LlmProvider {

    private val settings get() = project.service<CodeTourSettings>()
    private val json = Json { ignoreUnknownKeys = true }
    private val codexBin get() = CliPathResolver.resolve(settings.state.codexCliPath)
    override val provider: AiProvider = AiProvider.CODEX_CLI
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
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
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is not available")

        val outputFile = Files.createTempFile("codex-last-message", ".json").toFile()
        val wrappedPrompt = buildPrompt(prompt, promptKind)
        val state = settings.state
        val command = buildList {
            add(state.codexCliPath)
            add("exec")
            add("--json")
            add("--sandbox"); add("read-only")
            add("--skip-git-repo-check")
            add("-C"); add(basePath)
            add("-m"); add(ProviderModelCatalog.normalizeCodexModel(state.codexModel))
            val reasoningEffort = ProviderModelCatalog.normalizeCodexReasoningEffort(state.codexReasoningEffort)
            add("-c"); add("model_reasoning_effort=\"$reasoningEffort\"")
            add("-o"); add(outputFile.absolutePath)
            add(wrappedPrompt)
        }

        val processBuilder = ProcessBuilder(command)
            .directory(File(basePath))
            .redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        val process = processBuilder.start()
        activeProcess = process

        try {
            val timeoutSeconds = settings.state.requestTimeout.toLong()
            val finished = CliProcessRunner.run(
                process = process,
                timeout = Duration.ofSeconds(timeoutSeconds),
                onStdoutLine = { line ->
                    parseProgress(line)?.let { onProgress?.invoke(it) }
                },
                onStderrLine = { line -> onProgress?.invoke(line.trim()) },
            )
            if (!finished) {
                throw IllegalStateException("Codex CLI timed out after ${timeoutSeconds}s")
            }

            if (process.exitValue() != 0) {
                throw IllegalStateException("Codex CLI exited with code ${process.exitValue()}")
            }

            val content = outputFile.readText().trim()
            if (content.isBlank()) {
                throw IllegalStateException("Codex CLI returned no final message")
            }

            ProviderResponse(content = content)
        } finally {
            outputFile.delete()
            activeProcess = null
        }
    }

    override suspend fun checkAvailability(): ProviderStatus = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(codexBin, "--version")
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val finished = CliProcessRunner.run(
                process = process,
                timeout = Duration.ofSeconds(5),
                onStdoutLine = { stdout.appendLine(it) },
                onStderrLine = { stderr.appendLine(it) },
            )
            if (!finished) {
                return@withContext ProviderStatus(provider, false, "Codex CLI timed out")
            }
            val stdoutText = stdout.toString().trim()
            val stderrText = stderr.toString().trim()
            if (process.exitValue() == 0) {
                ProviderStatus(provider, true, stdoutText.ifBlank { "Codex CLI is available" })
            } else {
                val detail = stderrText.ifBlank { stdoutText }
                val message = "codex exited with code ${process.exitValue()}"
                ProviderStatus(provider, false, if (detail.isBlank()) message else "$message: ${detail.take(500)}")
            }
        } catch (e: Exception) {
            ProviderStatus(provider, false, e.message ?: "codex not found")
        }
    }

    override fun cancel() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    override fun dispose() {
        cancel()
    }

    private fun buildPrompt(prompt: String, promptKind: PromptKind): String {
        return buildString {
            appendLine(PromptContract.buildSystemPrompt(promptKind, enableSemanticTools = false))
            appendLine()
            appendLine("User question:")
            append(prompt)
        }
    }

    private fun parseProgress(line: String): String? {
        if (line.isBlank()) return null
        return try {
            val event = json.parseToJsonElement(line).jsonObject
            when (event["type"]?.jsonPrimitive?.content) {
                "task.started" -> event["task"]?.jsonPrimitive?.content
                "item.completed" -> summarizeItem(event["item"]?.jsonObject)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun summarizeItem(item: JsonObject?): String? {
        val subtype = item?.get("type")?.jsonPrimitive?.content ?: return null
        return when (subtype) {
            "reasoning" -> "Thinking..."
            "command_execution" -> item["command"]?.jsonPrimitive?.content?.let { "Running: ${it.take(80)}" }
            else -> null
        }
    }
}
