package com.github.nearkim.aicodewalkthrough.service

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
class ClaudeCliService(private val project: Project) : Disposable {

    private val settings get() = project.service<CodeTourSettings>()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeProcess: Process? = null

    suspend fun query(
        prompt: String,
        onProgress: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is not available")

        val command = listOf(
            settings.state.claudePath,
            "--print",
            "--output-format", "stream-json",
            "--verbose",
            "--system-prompt", SYSTEM_PROMPT,
            "-p", prompt,
        )

        val processBuilder = ProcessBuilder(command)
            .directory(java.io.File(basePath))
            .redirectErrorStream(false)
            .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))

        augmentPath(processBuilder)

        val process = processBuilder.start()
        activeProcess = process

        try {
            val stderrLines = mutableListOf<String>()
            val stderrThread = Thread.ofVirtual().start {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        thisLogger().debug("claude stderr: $line")
                        stderrLines.add(line)
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
                val stderrOutput = stderrLines.joinToString("\n").take(500)
                throw IllegalStateException(
                    "Claude CLI exited with code $exitCode${if (stderrOutput.isNotBlank()) ": $stderrOutput" else ""}"
                )
            }

            if (resultJson.isNullOrBlank()) {
                throw IllegalStateException("Claude CLI returned no result event")
            }

            resultJson
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
            else -> name
        }
    }

    suspend fun checkAvailability(): CliStatus = withContext(Dispatchers.IO) {
        try {
            val processBuilder = ProcessBuilder(settings.state.claudePath, "--version")
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
            augmentPath(processBuilder)
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext CliStatus(false, "claude CLI timed out")
            }
            if (process.exitValue() == 0) {
                CliStatus(true, output)
            } else {
                CliStatus(false, "claude exited with code ${process.exitValue()}")
            }
        } catch (e: Exception) {
            CliStatus(false, e.message ?: "claude not found")
        }
    }

    fun cancel() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    override fun dispose() {
        cancel()
    }

    data class CliStatus(val available: Boolean, val versionOrError: String)

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
        private val SYSTEM_PROMPT = """
            You are a code walkthrough assistant. Analyze the codebase and respond with ONLY valid JSON matching one of these schemas:

            Flow map response:
            {
              "type": "flow_map",
              "summary": "One-paragraph high-level answer.",
              "steps": [
                {
                  "id": "step-1",
                  "title": "Short title",
                  "file_path": "relative/path/to/file.kt",
                  "symbol": "functionOrClassName",
                  "start_line": 1,
                  "end_line": 10,
                  "explanation": "1-2 sentence explanation of what this code does.",
                  "why_included": "Why this step matters in the flow.",
                  "uncertain": false
                }
              ]
            }

            Clarification response:
            {
              "type": "clarification",
              "clarification_question": "Your clarifying question here."
            }

            Rules:
            1. Always respond with valid JSON matching one of the schemas above. No markdown, no extra text.
            2. Explore the codebase thoroughly using your built-in tools (file reading, grep, etc.) before answering.
            3. Use file paths relative to the project root.
            4. Return type "clarification" when the question is ambiguous or you need more information to give a useful answer.
            5. Order steps by execution sequence. Linearize branching control flow (try/catch, if/else) by following the most common/happy path and noting alternatives in the explanation field.
            6. Keep explanation to 1-2 sentences. Put deeper reasoning in why_included.
            7. Mark uncertain: true for steps that are inferred rather than directly traced from code.
            8. Always populate the symbol field when the step targets a specific function, class, or method.
        """.trimIndent()
    }
}
