package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
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
            prompt = "Explain the codebase",
            promptKind = PromptKind.WALKTHROUGH,
            resolveExecutable = { it },
        )

        assertEquals("plan", command[command.indexOf("--permission-mode") + 1])
        assertEquals("Bash,Edit,Write,NotebookEdit", command[command.indexOf("--disallowedTools") + 1])
    }
}
