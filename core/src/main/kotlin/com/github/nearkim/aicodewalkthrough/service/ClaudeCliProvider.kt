package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ClaudeEnvelope
import com.github.nearkim.aicodewalkthrough.model.ProviderModelCatalog
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class ClaudeCliProvider(
    private val projectRoot: Path,
    private val settings: () -> WalkthroughSettings,
) : LlmProvider {

    private val json = Json { ignoreUnknownKeys = true }
    override val provider = AiProvider.CLAUDE_CLI
    override val capabilities: ProviderCapabilities
        get() = ProviderCapabilities(
            supportsRepoGroundedWalkthrough = true,
            supportsSemanticNavigationHints = settings().normalized().enableMcp,
            supportsDelegatedAnalysisHints = true,
        )

    @Volatile
    private var activeProcess: Process? = null

    override suspend fun query(
        prompt: String,
        promptKind: PromptKind,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = withContext(Dispatchers.IO) {
        val state = settings().normalized()
        val command = ClaudeCliCommand.build(state, promptKind)
        val processBuilder = ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
        CliEnvironment.augmentPath(processBuilder)
        val process = processBuilder.start()
        activeProcess = process

        try {
            val stderrLines = ArrayDeque<String>()
            val stdoutLines = ArrayDeque<String>()
            val resultJson = AtomicReference<String?>()
            val semanticTools = SemanticToolCapture()
            CliProcessRunner.runUntilExit(
                process = process,
                stdin = prompt,
                onStderrLine = { stderrLines.appendBounded(it, MAX_CAPTURE_LINES) },
                onStdoutLine = stdout@{ line ->
                    if (line.isBlank()) return@stdout
                    stdoutLines.appendBounded(line, MAX_CAPTURE_LINES)
                    val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@stdout
                    semanticTools.consume(event)
                    when (event["type"]?.jsonPrimitive?.content) {
                        "assistant" -> progressMessages(event).forEach { onProgress?.invoke(it) }
                        "result" -> resultJson.set(line)
                    }
                },
            )

            if (process.exitValue() != 0) {
                throw IllegalStateException(
                    buildCliFailureMessage(process.exitValue(), stdoutLines.capture(), stderrLines.capture()),
                )
            }
            val resultLine = resultJson.get()
            if (resultLine.isNullOrBlank()) {
                val detail = extractCliErrorDetail(stdoutLines.capture(), stderrLines.capture())
                throw IllegalStateException(
                    detail?.let { "Claude CLI returned no result event: $it" }
                        ?: "Claude CLI returned no result event",
                )
            }
            val envelope = json.decodeFromString<ClaudeEnvelope>(resultLine)
            if (envelope.isError) throw IllegalStateException("Claude returned error: ${envelope.result}")
            val content = envelope.result ?: throw IllegalStateException("Claude envelope has null result")
            ProviderResponse(
                content = content,
                metadata = ResponseMetadata(
                    durationMs = envelope.durationMs ?: 0,
                    costUsd = envelope.costUsd,
                    numTurns = envelope.numTurns ?: 0,
                    stepCount = 0,
                    fileCount = 0,
                ),
                toolResults = semanticTools.results(),
            )
        } finally {
            activeProcess = null
        }
    }

    override suspend fun checkAvailability(): ProviderStatus = withContext(Dispatchers.IO) {
        try {
            val executable = CliPathResolver.resolve(settings().claudePath)
            val auth = runQuickCommand(listOf(executable, "auth", "status", "--text"))
            val authDetail = extractCliErrorDetail(auth.stdout, auth.stderr)
            if (auth.exitCode == 0) {
                return@withContext ProviderStatus(
                    provider,
                    true,
                    auth.stdout.ifBlank { "Claude CLI authenticated" },
                )
            }
            if (authDetail?.contains("not authenticated", ignoreCase = true) == true) {
                return@withContext ProviderStatus(provider, false, authDetail)
            }

            val version = runQuickCommand(listOf(executable, "--version"))
            if (version.exitCode == 0) {
                ProviderStatus(provider, false, "Claude CLI is installed but not authenticated. Run claude auth login.")
            } else {
                ProviderStatus(
                    provider,
                    false,
                    extractCliErrorDetail(version.stdout, version.stderr)
                        ?: "claude exited with code ${version.exitCode}",
                )
            }
        } catch (error: IOException) {
            ProviderStatus(provider, false, error.message ?: "claude not found")
        }
    }

    override fun cancel() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    private fun progressMessages(event: JsonObject): List<String> {
        val content = event["message"]?.jsonObject?.get("content") as? JsonArray ?: return emptyList()
        return content.mapNotNull { item ->
            val toolUse = item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.content == "tool_use" }
            toolUse?.let(::formatToolUse)
        }
    }

    private fun formatToolUse(toolUse: JsonObject): String? {
        val rawName = toolUse["name"]?.jsonPrimitive?.content ?: return null
        val name = semanticToolName(rawName) ?: rawName
        val input = toolUse["input"]?.jsonObject ?: return name
        return when (name) {
            "Read" -> input["file_path"]?.jsonPrimitive?.content?.let { "Reading $it" } ?: "Reading file..."
            "Glob" -> input["pattern"]?.jsonPrimitive?.content?.let { "Finding files: $it" } ?: "Searching files..."
            "Grep" -> input["pattern"]?.jsonPrimitive?.content?.let { "Searching for: $it" } ?: "Searching code..."
            "find_symbol", "get_symbols_overview", "find_referencing_symbols" -> listOfNotNull(
                name,
                input["name_path"]?.jsonPrimitive?.content,
                input["relative_path"]?.jsonPrimitive?.content,
            ).joinToString(" ")
            else -> name
        }
    }

    private fun runQuickCommand(command: List<String>): QuickCommandResult {
        val processBuilder = ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.from(CliEnvironment.nullInput()))
        CliEnvironment.augmentPath(processBuilder)
        val process = processBuilder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val finished = CliProcessRunner.run(
            process = process,
            timeout = Duration.ofSeconds(5),
            onStdoutLine = { stdout.appendLine(it) },
            onStderrLine = { stderr.appendLine(it) },
        )
        if (!finished) return QuickCommandResult(-1, stdout.toString().trim(), "Claude CLI timed out")
        return QuickCommandResult(process.exitValue(), stdout.toString().trim(), stderr.toString().trim())
    }

    private fun ArrayDeque<String>.appendBounded(line: String, limit: Int) = synchronized(this) {
        addLast(line)
        while (size > limit) removeFirst()
    }

    private fun ArrayDeque<String>.capture(): String = synchronized(this) { joinToString("\n").take(500) }

    companion object {
        private const val MAX_CAPTURE_LINES = 64

        internal fun buildCliFailureMessage(exitCode: Int, stdoutOutput: String, stderrOutput: String): String {
            val detail = extractCliErrorDetail(stdoutOutput, stderrOutput)
            return detail?.let { "Claude CLI exited with code $exitCode: $it" }
                ?: "Claude CLI exited with code $exitCode"
        }

        internal fun extractCliErrorDetail(stdoutOutput: String, stderrOutput: String): String? {
            val combined = listOf(stdoutOutput.trim(), stderrOutput.trim())
                .filter(String::isNotBlank)
                .joinToString("\n")
                .trim()
            if (combined.isBlank()) return null
            return when {
                combined.contains("Not logged in", ignoreCase = true) ||
                    combined.contains("auth login", ignoreCase = true) ||
                    combined.contains("/login", ignoreCase = true) ->
                    "Claude CLI is not authenticated. Run claude auth login and retry."
                else -> combined
            }.take(500)
        }
    }
}

internal class SemanticToolCapture {
    private val pending = linkedMapOf<String, ProviderToolResult>()

    fun consume(event: JsonObject) {
        val content = event["message"]?.jsonObject?.get("content") as? JsonArray ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "assistant" -> content.forEach { item ->
                val block = item as? JsonObject ?: return@forEach
                if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_use") return@forEach
                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = block["name"]?.jsonPrimitive?.contentOrNull?.let(::semanticToolName) ?: return@forEach
                pending[id] = ProviderToolResult(name, block["input"]?.toString() ?: "{}")
            }
            "user" -> content.forEach { item ->
                val block = item as? JsonObject ?: return@forEach
                if (block["type"]?.jsonPrimitive?.contentOrNull != "tool_result") return@forEach
                val id = block["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val existing = pending[id] ?: return@forEach
                pending[id] = existing.copy(output = toolResultText(block["content"]))
            }
        }
    }

    fun results(): List<ProviderToolResult> = pending.values.toList()

    private fun toolResultText(content: kotlinx.serialization.json.JsonElement?): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.joinToString("\n") { item ->
            (item as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull ?: item.toString()
        }
        null -> null
        else -> content.toString()
    }
}

private val semanticToolNames = setOf(
    "find_symbol",
    "get_symbols_overview",
    "find_referencing_symbols",
    "find_declaration",
    "find_implementations",
    "get_diagnostics_for_file",
    "get_diagnostics_for_symbol",
)

private fun semanticToolName(name: String): String? = name.substringAfterLast("__").takeIf { it in semanticToolNames }

internal object ClaudeCliCommand {
    fun build(
        state: WalkthroughSettings,
        promptKind: PromptKind,
        resolveExecutable: (String) -> String = CliPathResolver::resolve,
    ): List<String> = buildList {
        add(resolveExecutable(state.claudePath))
        add("--print")
        add("--output-format"); add("stream-json")
        add("--verbose")
        add("--permission-mode"); add("plan")
        add("--disallowedTools"); add("Bash,Edit,Write,NotebookEdit")
        add("--system-prompt"); add(PromptContract.buildSystemPrompt(promptKind, state.enableMcp))
        add("--model"); add(ProviderModelCatalog.normalizeClaudeModel(state.claudeModel))
        state.claudeEffort.trim().takeIf(String::isNotEmpty)?.let { add("--effort"); add(it) }
        state.mcpConfigPath.trim().takeIf(String::isNotEmpty)?.let { add("--mcp-config"); add(it) }
    }
}

internal data class QuickCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
