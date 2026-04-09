package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.SuggestedTest
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMapMarkdownExporterTest {

    @Test
    fun `build includes question metadata and active step`() {
        val flowMap = FlowMap(
            summary = "Entry point delegates into a service.",
            reviewSummary = "The handler is straightforward but worth testing around route normalization.",
            overallRisk = "Low risk unless normalization changes request semantics.",
            suggestedTests = listOf(
                SuggestedTest(
                    title = "route normalization preserves canonical paths",
                    description = "Covers the branch that rewrites the route before dispatch.",
                    fileHint = "src/AppTest.kt",
                ),
            ),
            steps = listOf(
                FlowStep(
                    id = "step-1",
                    title = "Parse request",
                    filePath = "src/App.kt",
                    symbol = "handleRequest",
                    startLine = 12,
                    endLine = 30,
                    explanation = "The controller normalizes input and forwards it.",
                    whyIncluded = "It is the first boundary after the UI.",
                    lineAnnotations = listOf(
                        LineAnnotation(startLine = 14, endLine = 14, text = "Normalizes the route")
                    ),
                )
            ),
        )

        val markdown = FlowMapMarkdownExporter.build(
            question = "How does request handling work?",
            flowMap = flowMap,
            metadata = ResponseMetadata(
                durationMs = 2450,
                costUsd = 0.0312,
                numTurns = 2,
                stepCount = 1,
                fileCount = 1,
            ),
            activeStepId = "step-1",
        )

        assertTrue(markdown.contains("# AI Code Walkthrough"))
        assertTrue(markdown.contains("## Question"))
        assertTrue(markdown.contains("How does request handling work?"))
        assertTrue(markdown.contains("## Review Summary"))
        assertTrue(markdown.contains("## Overall Risk"))
        assertTrue(markdown.contains("## Suggested Tests"))
        assertTrue(markdown.contains("route normalization preserves canonical paths"))
        assertTrue(markdown.contains("- Duration: 2.5s"))
        assertTrue(markdown.contains("- Cost: $0.0312"))
        assertTrue(markdown.contains("- Focus: active step in the IDE"))
        assertTrue(markdown.contains("Line annotations:"))
        assertTrue(markdown.contains("- L14: Normalizes the route"))
    }

    @Test
    fun `build marks broken steps`() {
        val flowMap = FlowMap(
            summary = "A broken step still gets exported.",
            steps = listOf(
                FlowStep(
                    id = "step-2",
                    title = "Jump into helper",
                    filePath = "src/Helper.kt",
                    startLine = 40,
                    endLine = 44,
                    explanation = "The helper could not be resolved cleanly.",
                    whyIncluded = "It looked like the next hop in the call chain.",
                    broken = true,
                    breakReason = "Resolved line range exceeded file length",
                )
            ),
        )

        val markdown = FlowMapMarkdownExporter.build(
            question = null,
            flowMap = flowMap,
            metadata = null,
        )

        assertTrue(markdown.contains("- Status: needs repair"))
        assertTrue(markdown.contains("- Repair note: Resolved line range exceeded file length"))
    }
}
