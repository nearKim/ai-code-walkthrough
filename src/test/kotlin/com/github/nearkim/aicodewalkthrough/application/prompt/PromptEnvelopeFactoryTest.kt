package com.github.nearkim.aicodewalkthrough.application.prompt

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.service.ProviderCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
}
