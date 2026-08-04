package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCliProviderTest {

    @Test
    fun `extractCliErrorDetail maps auth failures to actionable guidance`() {
        val detail = ClaudeCliProvider.extractCliErrorDetail(
            stdoutOutput = "Not logged in. Run claude auth login to authenticate.",
            stderrOutput = "",
        )

        assertEquals("Claude CLI is not authenticated. Run claude auth login and retry.", detail)
    }

    @Test
    fun `buildCliFailureMessage includes actionable auth guidance`() {
        val message = ClaudeCliProvider.buildCliFailureMessage(
            exitCode = 1,
            stdoutOutput = "Not logged in · Please run /login",
            stderrOutput = "",
        )

        assertTrue(message.contains("Claude CLI exited with code 1"))
        assertTrue(message.contains("claude auth login"))
    }

    @Test
    fun `command blocks repository mutation tools`() {
        val command = ClaudeCliCommand.build(
            state = WalkthroughSettings(),
            promptKind = PromptKind.WALKTHROUGH,
            resolveExecutable = { it },
        )

        assertEquals("plan", command[command.indexOf("--permission-mode") + 1])
        assertEquals("Bash,Edit,Write,NotebookEdit", command[command.indexOf("--disallowedTools") + 1])
    }

    @Test
    fun `semantic tool capture records actual Serena input and result`() {
        val capture = SemanticToolCapture()
        capture.consume(
            Json.parseToJsonElement(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool-1","name":"mcp__serena__find_symbol","input":{"name_path":"Runner/run","relative_path":"app.py"}}]}}""",
            ).jsonObject,
        )
        capture.consume(
            Json.parseToJsonElement(
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"Runner.run at app.py:4-6"}]}}""",
            ).jsonObject,
        )

        assertEquals(
            listOf(ProviderToolResult("find_symbol", "{\"name_path\":\"Runner/run\",\"relative_path\":\"app.py\"}", "Runner.run at app.py:4-6")),
            capture.results(),
        )
    }
}
