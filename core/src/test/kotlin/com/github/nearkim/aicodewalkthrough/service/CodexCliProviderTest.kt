package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexCliProviderTest {

    @Test
    fun `command resolves configured executable before launch`() {
        var requestedExecutable = ""

        val command = CodexCliCommand.build(
            state = WalkthroughSettings(codexCliPath = "codex"),
            basePath = "/repo",
            outputPath = "/tmp/result.json",
            prompt = "Explain the codebase",
            resolveExecutable = { configured ->
                requestedExecutable = configured
                "/Users/test/.local/bin/codex"
            },
        )

        assertEquals("codex", requestedExecutable)
        assertEquals("/Users/test/.local/bin/codex", command.first())
    }

    @Test
    fun `command keeps repository access read only`() {
        val command = CodexCliCommand.build(
            state = WalkthroughSettings(),
            basePath = "/repo",
            outputPath = "/tmp/result.json",
            prompt = "Explain the codebase",
            resolveExecutable = { it },
        )

        assertEquals("read-only", command[command.indexOf("--sandbox") + 1])
    }
}
