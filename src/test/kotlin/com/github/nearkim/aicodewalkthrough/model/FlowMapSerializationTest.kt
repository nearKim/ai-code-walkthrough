package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FlowMapSerializationTest {

    @Test
    fun `flow map response decodes architecture and learning path`() {
        val response = Json.decodeFromString<LlmResponse>(
            """
            {
              "type": "flow_map",
              "mode": "understand",
              "summary": "The UI delegates to the session service.",
              "architecture": {
                "system_purpose": "Guide developers through grounded code paths.",
                "components": [
                  {
                    "id": "ui",
                    "name": "Tool window",
                    "kind": "presentation",
                    "responsibility": "Renders walkthrough state.",
                    "key_paths": ["src/CodeTourPanel.kt"],
                    "key_symbols": ["CodeTourPanel"]
                  }
                ],
                "relationships": [],
                "cross_cutting_concerns": ["Grounding"],
                "coverage_notes": ["Settings were not traced deeply."]
              },
              "learning_path": [
                {
                  "id": "orientation",
                  "title": "Orientation",
                  "goal": "Understand the UI boundary.",
                  "component_ids": ["ui"],
                  "step_ids": ["step-1"],
                  "checkpoint": "What owns walkthrough state?"
                }
              ],
              "steps": [
                {
                  "id": "step-1",
                  "title": "Submit a question",
                  "file_path": "src/CodeTourPanel.kt",
                  "start_line": 1,
                  "end_line": 10,
                  "explanation": "Delegates the request.",
                  "why_included": "This is the UI boundary."
                }
              ]
            }
            """.trimIndent(),
        )

        val flowMap = response.toFlowMap() ?: error("Expected a flow map")

        assertEquals("Guide developers through grounded code paths.", flowMap.architecture?.systemPurpose)
        assertEquals(listOf("src/CodeTourPanel.kt"), flowMap.architecture?.components?.single()?.keyPaths)
        assertEquals(listOf("step-1"), flowMap.learningPath.single().stepIds)
        assertFalse(flowMap.learningPath.single().checkpoint.isNullOrBlank())
    }
}
