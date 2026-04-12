package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ClaudeCliService(private val project: Project) : Disposable, LlmProvider {

    private val settings get() = project.service<CodeTourSettings>()
    private val json = Json { ignoreUnknownKeys = true }
    override val provider: AiProvider = AiProvider.CLAUDE_CLI
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsRepoGroundedWalkthrough = true,
        supportsSemanticNavigationHints = true,
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

        val state = settings.state
        val command = buildList {
            add(state.claudePath)
            add("--print")
            add("--output-format"); add("stream-json")
            add("--verbose")
            add("--system-prompt"); add(PromptContract.buildSystemPrompt(promptKind, state.enableMcp))
            val mcpPath = state.mcpConfigPath.trim()
            if (mcpPath.isNotEmpty()) {
                add("--mcp-config"); add(mcpPath)
            }
            add("-p"); add(prompt)
        }

        val processBuilder = ProcessBuilder(command)
            .directory(java.io.File(basePath))
            .redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))

        augmentPath(processBuilder)

        val process = processBuilder.start()
        activeProcess = process

        try {
            val stderrLines = ArrayDeque<String>()
            val stderrThread = Thread.ofVirtual().start {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        thisLogger().debug("claude stderr: $line")
                        synchronized(stderrLines) {
                            stderrLines.addLast(line)
                            while (stderrLines.size > MAX_STDERR_LINES) {
                                stderrLines.removeFirst()
                            }
                        }
                    }
                }
            }

            var resultJson: String? = null
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val event = json.parseToJsonElement(line).jsonObject
                        val type = event["type"]?.jsonPrimitive?.content ?: return@forEach

                        when (type) {
                            "assistant" -> {
                                val message = event["message"]?.jsonObject
                                val content = message?.get("content")
                                if (content is kotlinx.serialization.json.JsonArray) {
                                    for (item in content) {
                                        val obj = item.jsonObject
                                        if (obj["type"]?.jsonPrimitive?.content == "tool_use") {
                                            val progressMsg = formatToolUse(obj)
                                            if (progressMsg != null) onProgress?.invoke(progressMsg)
                                        }
                                    }
                                }
                            }
                            "result" -> {
                                resultJson = line
                            }
                        }
                    } catch (e: Exception) {
                        thisLogger().debug("Failed to parse stream event: $line")
                    }
                }
            }

            val timeoutSeconds = settings.state.requestTimeout.toLong()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Claude CLI timed out after ${timeoutSeconds}s")
            }

            stderrThread.join(1000)

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val stderrOutput = synchronized(stderrLines) {
                    stderrLines.joinToString("\n").take(500)
                }
                throw IllegalStateException(
                    "Claude CLI exited with code $exitCode${if (stderrOutput.isNotBlank()) ": $stderrOutput" else ""}"
                )
            }

            if (resultJson.isNullOrBlank()) {
                throw IllegalStateException("Claude CLI returned no result event")
            }

            val envelope = json.decodeFromString<com.github.nearkim.aicodewalkthrough.model.ClaudeEnvelope>(resultJson)
            if (envelope.isError) {
                throw IllegalStateException("Claude returned error: ${envelope.result}")
            }

            val content = envelope.result
                ?: throw IllegalStateException("Claude envelope has null result")

            ProviderResponse(
                content = content,
                metadata = ResponseMetadata(
                    durationMs = envelope.durationMs ?: 0,
                    costUsd = envelope.costUsd,
                    numTurns = envelope.numTurns ?: 0,
                    stepCount = 0,
                    fileCount = 0,
                ),
            )
        } finally {
            activeProcess = null
        }
    }

    private fun formatToolUse(toolUse: JsonObject): String? {
        val name = toolUse["name"]?.jsonPrimitive?.content ?: return null
        val input = toolUse["input"]?.jsonObject ?: return name

        return when (name) {
            "Read" -> {
                val filePath = input["file_path"]?.jsonPrimitive?.content ?: return "Reading file..."
                "Reading $filePath"
            }
            "Glob" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.content ?: return "Searching files..."
                "Finding files: $pattern"
            }
            "Grep" -> {
                val pattern = input["pattern"]?.jsonPrimitive?.content ?: return "Searching code..."
                "Searching for: $pattern"
            }
            "Bash" -> {
                val cmd = input["command"]?.jsonPrimitive?.content?.take(60) ?: return "Running command..."
                "Running: $cmd"
            }
            "Write", "Edit" -> {
                val filePath = input["file_path"]?.jsonPrimitive?.content ?: return "$name..."
                "$name $filePath"
            }
            "find_symbol", "get_symbols_overview", "find_referencing_symbols" -> {
                val symbol = input["name_path"]?.jsonPrimitive?.content
                val filePath = input["relative_path"]?.jsonPrimitive?.content
                listOfNotNull(name, symbol, filePath).joinToString(" ")
            }
            else -> name
        }
    }

    override suspend fun checkAvailability(): ProviderStatus = withContext(Dispatchers.IO) {
        try {
            val processBuilder = ProcessBuilder(settings.state.claudePath, "--version")
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            augmentPath(processBuilder)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ProviderStatus(provider, false, "Claude CLI timed out")
            }
            if (process.exitValue() == 0) {
                ProviderStatus(provider, true, output)
            } else {
                ProviderStatus(provider, false, "claude exited with code ${process.exitValue()}")
            }
        } catch (e: Exception) {
            ProviderStatus(provider, false, e.message ?: "claude not found")
        }
    }

    override fun cancel() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    override fun dispose() {
        cancel()
    }

    private fun augmentPath(processBuilder: ProcessBuilder) {
        val env = processBuilder.environment()
        val currentPath = env["PATH"] ?: ""
        val home = System.getProperty("user.home")
        val extraPaths = listOf(
            "$home/.local/bin",
            "$home/.npm-global/bin",
            "$home/.nvm/versions/node/current/bin",
            "/usr/local/bin",
            "/opt/homebrew/bin",
        )
        val missing = extraPaths.filter { it !in currentPath }
        if (missing.isNotEmpty()) {
            env["PATH"] = (listOf(currentPath) + missing).joinToString(":")
        }
    }

    companion object {
        private const val MAX_STDERR_LINES = 64
    }
}
