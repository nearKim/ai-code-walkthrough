package com.github.nearkim.aicodewalkthrough.service

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonResponseSanitizerTest {

    @Test
    fun `returns plain json unchanged`() {
        val response = """{"type":"flow_map","summary":"ok","steps":[]}"""

        val sanitized = JsonResponseSanitizer.extractTopLevelObject(response)

        assertEquals(response, sanitized)
    }

    @Test
    fun `extracts json object from markdown fence with leading prose`() {
        val response = """
            Now I'll generate the flow map.....

            ```json
            {"type":"flow_map","summary":"ok","steps":[]}
            ```
        """.trimIndent()

        val sanitized = JsonResponseSanitizer.extractTopLevelObject(response)

        assertEquals("""{"type":"flow_map","summary":"ok","steps":[]}""", sanitized)
    }

    @Test
    fun `skips invalid brace groups before the actual json object`() {
        val response = """
            I first considered {analysis}.
            Final answer:
            {"type":"step_answer","answer":"done"}
        """.trimIndent()

        val sanitized = JsonResponseSanitizer.extractTopLevelObject(response)

        assertEquals("""{"type":"step_answer","answer":"done"}""", sanitized)
    }
}
