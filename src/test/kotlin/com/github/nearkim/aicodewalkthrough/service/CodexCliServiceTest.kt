package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexCliServiceTest {

    @Test
    fun `command resolves configured executable before launch`() {
        var requestedExecutable = ""

        val command = CodexCliCommand.build(
            state = CodeTourSettings.State(codexCliPath = "codex"),
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
}
