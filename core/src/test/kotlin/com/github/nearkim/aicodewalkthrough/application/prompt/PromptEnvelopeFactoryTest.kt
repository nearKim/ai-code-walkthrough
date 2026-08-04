package com.github.nearkim.aicodewalkthrough.application.prompt

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.service.ProviderCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptEnvelopeFactoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `build walkthrough prompt includes normalized feature scope`() {
        val prompt = PromptEnvelopeFactory.buildWalkthroughPrompt(
            question = "Trace this feature",
            mode = AnalysisMode.TRACE,
            maxSteps = 12,
            queryContext = QueryContext(filePath = "src/App.kt", symbol = "run"),
            followUpContext = null,
            featureScope = FeatureScopeContext(
                featureId = "feature-a",
                featureName = "Feature A",
                featureSummary = "Handles feature A.",
                featureReviewSummary = "Medium risk",
                allowedFilePaths = listOf("src/App.kt", "src/Feature.kt"),
                ownedPaths = listOf("src/App.kt"),
                sharedPaths = listOf("src/Feature.kt"),
                selectedPathId = "path-1",
                selectedPathName = "Primary path",
                selectedPathDescription = "Trace the main execution flow.",
                promptSeed = "Trace the primary path",
                supportingSymbols = listOf("Feature.run"),
                boundaryNotes = listOf("Stop at shared infrastructure boundaries."),
            ),
            providerCapabilities = ProviderCapabilities(
                supportsRepoGroundedWalkthrough = true,
                supportsSemanticNavigationHints = true,
            ),
            json = json,
        )

        val envelope = json.parseToJsonElement(prompt).jsonObject
        val featureScope = envelope.getValue("feature_scope").jsonObject

        assertEquals("feature-a", featureScope.getValue("feature_id").jsonPrimitive.content)
        assertEquals("Feature A", featureScope.getValue("feature_name").jsonPrimitive.content)
        assertEquals(
            listOf("src/App.kt", "src/Feature.kt"),
            featureScope.getValue("allowed_file_paths").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(featureScope.getValue("supporting_symbols").jsonArray.any { it.jsonPrimitive.content == "Feature.run" })
        assertTrue(featureScope.getValue("boundary_notes").jsonArray.any { it.jsonPrimitive.content.contains("shared infrastructure") })
    }

    @Test
    fun `blank understand prompt requests an architecture first whole codebase lesson`() {
        val question = AnalysisMode.UNDERSTAND.resolveQuestion("")
            ?: error("Understand mode should provide a default question")
        val prompt = PromptEnvelopeFactory.buildWalkthroughPrompt(
            question = question,
            mode = AnalysisMode.UNDERSTAND,
            maxSteps = 20,
            queryContext = null,
            followUpContext = null,
            featureScope = null,
            providerCapabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true),
            json = json,
        )

        val envelope = json.parseToJsonElement(prompt).jsonObject
        val strategy = envelope.getValue("learning_strategy").jsonObject

        assertEquals(AnalysisMode.DEFAULT_CODEBASE_QUESTION, envelope.getValue("question").jsonPrimitive.content)
        assertEquals("12", envelope.getValue("max_steps").jsonPrimitive.content)
        assertEquals("true", strategy.getValue("architecture_first").jsonPrimitive.content)
        assertEquals("whole_codebase", strategy.getValue("breadth").jsonPrimitive.content)
        assertEquals("purpose_to_system_map_to_relationships_to_representative_paths_to_code", strategy.getValue("progression").jsonPrimitive.content)
        assertEquals("progressive_disclosure", strategy.getValue("detail_policy").jsonPrimitive.content)
        assertEquals("true", strategy.getValue("verified_facts_only").jsonPrimitive.content)
        assertEquals("true", strategy.getValue("persisted_mechanical_facts").jsonPrimitive.content)
        assertEquals("true", strategy.getValue("behavior_search").jsonPrimitive.content)
        assertEquals("bounded_parallel_when_available", strategy.getValue("delegation_policy").jsonPrimitive.content)
        assertEquals("consume_verified_tool_results_when_available", strategy.getValue("symbolic_execution_policy").jsonPrimitive.content)
        assertTrue(AnalysisMode.REVIEW.resolveQuestion("  ") == null)
    }

    @Test
    fun `walkthrough prompt includes the precomputed mechanical symbol inventory`() {
        val inventory = buildJsonObject {
            put("tool", "python_stdlib_ast")
            put("symbol_count", 42)
        }
        val prompt = PromptEnvelopeFactory.buildWalkthroughPrompt(
            question = "Map the repository",
            mode = AnalysisMode.UNDERSTAND,
            maxSteps = 12,
            queryContext = null,
            followUpContext = null,
            featureScope = null,
            providerCapabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true),
            json = json,
            mechanicalSymbolInventory = inventory,
        )

        val embedded = json.parseToJsonElement(prompt).jsonObject
            .getValue("mechanical_symbol_inventory").jsonObject
        assertEquals("python_stdlib_ast", embedded.getValue("tool").jsonPrimitive.content)
        assertEquals("42", embedded.getValue("symbol_count").jsonPrimitive.content)
    }

    /**
     * A 1.2 MB inventory made Codex fail the turn with "Input exceeds the maximum length of
     * 1048576 characters". Architecture evidence duplicates the compact module list, so it is the
     * first thing dropped, and modules are truncated only if the envelope is still too large.
     */
    @Test
    fun `oversized inventory is trimmed to fit the provider input limit`() {
        val text = "x".repeat(2_000)
        val inventory = buildJsonObject {
            put("tool", "python_stdlib_ast")
            put("truncated", false)
            put("modules", buildJsonArray {
                repeat(400) { index -> add(buildJsonObject { put("p", "module$index.py"); put("d", text) }) }
            })
            put("architecture", buildJsonObject {
                put("components", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "python-root")
                        put("evidence", buildJsonArray { repeat(200) { add(buildJsonObject { put("text", text) }) } })
                        put("responsibilities", buildJsonArray {
                            repeat(200) { index ->
                                add(buildJsonObject {
                                    put("id", "class-$index")
                                    put("title", "Owns something")
                                    put("evidence", buildJsonArray { repeat(10) { add(buildJsonObject { put("text", text) }) } })
                                })
                            }
                        })
                    })
                })
            })
        }

        val prompt = PromptEnvelopeFactory.buildWalkthroughPrompt(
            question = "Map the repository",
            mode = AnalysisMode.UNDERSTAND,
            maxSteps = 12,
            queryContext = null,
            followUpContext = null,
            featureScope = null,
            providerCapabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true),
            json = json,
            mechanicalSymbolInventory = inventory,
        )

        assertTrue("Prompt was ${prompt.length} characters", prompt.length <= 1_000_000)
        val embedded = json.parseToJsonElement(prompt).jsonObject
            .getValue("mechanical_symbol_inventory").jsonObject
        val component = embedded.getValue("architecture").jsonObject
            .getValue("components").jsonArray.single().jsonObject
        assertTrue(component["evidence"] == null)
        assertTrue(component.getValue("responsibilities").jsonArray.all { it.jsonObject["evidence"] == null })
        assertEquals("Owns something", component.getValue("responsibilities").jsonArray.first().jsonObject.getValue("title").jsonPrimitive.content)
        assertTrue(embedded.getValue("modules").jsonArray.isNotEmpty())
    }
}
