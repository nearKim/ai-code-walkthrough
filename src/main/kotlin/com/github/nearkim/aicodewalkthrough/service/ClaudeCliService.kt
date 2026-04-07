package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ClaudeCliService(private val project: Project) : Disposable {

    private val settings get() = project.service<CodeTourSettings>()

    @Volatile
    private var activeProcess: Process? = null

    suspend fun query(prompt: String): String = withContext(Dispatchers.IO) {
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is not available")

        val command = listOf(
            settings.state.claudePath,
            "--print",
            "--output-format", "json",
            "-s", SYSTEM_PROMPT,
            "-p", prompt,
        )

        val processBuilder = ProcessBuilder(command)
            .directory(java.io.File(basePath))
            .redirectErrorStream(false)

        val process = processBuilder.start()
        activeProcess = process

        try {
            val stderr = Thread.ofVirtual().start {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> thisLogger().debug("claude stderr: $line") }
                }
            }

            val stdout = process.inputStream.bufferedReader().readText()

            val timeoutSeconds = settings.state.requestTimeout.toLong()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Claude CLI timed out after ${timeoutSeconds}s")
            }

            stderr.join(1000)

            val exitCode = process.exitValue()
            if (exitCode != 0) {
                throw IllegalStateException("Claude CLI exited with code $exitCode")
            }

            if (stdout.isBlank()) {
                throw IllegalStateException("Claude CLI returned empty response")
            }

            stdout
        } finally {
            activeProcess = null
        }
    }

    fun cancel() {
        activeProcess?.destroyForcibly()
        activeProcess = null
    }

    override fun dispose() {
        cancel()
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
