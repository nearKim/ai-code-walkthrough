package com.github.nearkim.aicodewalkthrough.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTest {

    @Test
    fun `parses one repository and launch options`() {
        assertEquals(
            LaunchOptions("/repo", port = 8080, noOpen = true),
            parseOptions(arrayOf("/repo", "--port", "8080", "--no-open")).getOrThrow(),
        )
        assertTrue(parseOptions(arrayOf("/one", "/two")).isFailure)
    }
}
