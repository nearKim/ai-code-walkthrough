package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
                    "responsibilities": [
                      {
                        "id": "render-session",
                        "title": "Render walkthrough state",
                        "description": "Turns session state into the visible tool window.",
                        "evidence": [
                          {
                            "kind": "class",
                            "label": "CodeTourPanel",
                            "file_path": "src/CodeTourPanel.kt",
                            "start_line": 1,
                            "end_line": 10,
                            "text": "Owns the rendered walkthrough view."
                          }
                        ],
                        "collaborator_component_ids": [],
                        "relationship_ids": [],
                        "uncertain": false
                      }
                    ],
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
              "diagram_sections": [
                {
                  "id": "system-overview",
                  "title": "System overview",
                  "summary": "Start with the UI boundary.",
                  "component_ids": ["ui"],
                  "step_ids": ["step-1"]
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
        assertEquals("CodeTourPanel", flowMap.architecture?.components?.single()?.responsibilities?.single()?.evidence?.single()?.label)
        assertEquals(listOf("step-1"), flowMap.learningPath.single().stepIds)
        assertFalse(flowMap.learningPath.single().checkpoint.isNullOrBlank())
        assertEquals("system-overview", flowMap.diagramSections.single().id)
        assertEquals(listOf("ui"), flowMap.diagramSections.single().componentIds)
        assertEquals(listOf("step-1"), flowMap.diagramSections.single().stepIds)
        assertTrue(Json.encodeToString(flowMap).contains("\"diagram_sections\""))
    }
}
