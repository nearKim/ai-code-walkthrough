package com.github.nearkim.aicodewalkthrough.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCliServiceTest {

    @Test
    fun `extractCliErrorDetail maps auth failures to actionable guidance`() {
        val detail = ClaudeCliService.extractCliErrorDetail(
            stdoutOutput = "Not logged in. Run claude auth login to authenticate.",
            stderrOutput = "",
        )

        assertEquals("Claude CLI is not authenticated. Run claude auth login and retry.", detail)
    }

    @Test
    fun `buildCliFailureMessage includes actionable auth guidance`() {
        val message = ClaudeCliService.buildCliFailureMessage(
            exitCode = 1,
            stdoutOutput = "Not logged in · Please run /login",
            stderrOutput = "",
        )

        assertTrue(message.contains("Claude CLI exited with code 1"))
        assertTrue(message.contains("claude auth login"))
    }
}
