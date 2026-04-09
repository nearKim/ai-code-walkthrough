package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
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
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CodexCliService(private val project: Project) : Disposable, LlmProvider {

    private val settings get() = project.service<CodeTourSettings>()
    private val json = Json { ignoreUnknownKeys = true }
    override val provider: AiProvider = AiProvider.CODEX_CLI

    @Volatile
    private var activeProcess: Process? = null

    override suspend fun query(
        prompt: String,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is not available")

        val outputFile = Files.createTempFile("codex-last-message", ".json").toFile()
        val wrappedPrompt = buildPrompt(prompt)
        val command = listOf(
            settings.state.codexCliPath,
            "exec",
            "--json",
            "--sandbox", "read-only",
            "--skip-git-repo-check",
            "-C", basePath,
            "-o", outputFile.absolutePath,
            wrappedPrompt,
        )

        val processBuilder = ProcessBuilder(command)
            .directory(File(basePath))
            .redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))

        val process = processBuilder.start()
        activeProcess = process

        try {
            val stderrThread = Thread.ofVirtual().start {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        onProgress?.invoke(line.trim())
                    }
                }
            }

            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    parseProgress(line)?.let { onProgress?.invoke(it) }
                }
            }

            val timeoutSeconds = settings.state.requestTimeout.toLong()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Codex CLI timed out after ${timeoutSeconds}s")
            }

            stderrThread.join(1000)

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
            val process = ProcessBuilder(settings.state.codexCliPath, "--version")
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ProviderStatus(provider, false, "Codex CLI timed out")
            }
            if (process.exitValue() == 0) {
                ProviderStatus(provider, true, output)
            } else {
                ProviderStatus(provider, false, "codex exited with code ${process.exitValue()}")
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

    private fun buildPrompt(prompt: String): String {
        return buildString {
            appendLine(PromptContract.buildSystemPrompt(enableSemanticTools = false))
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
