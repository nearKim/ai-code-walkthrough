package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.AnalysisTrace
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.github.nearkim.aicodewalkthrough.model.SuggestedTest
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMapMarkdownExporterTest {

    @Test
    fun `build includes question metadata and active step`() {
        val flowMap = FlowMap(
            summary = "Entry point delegates into a service.",
            entryStepId = "step-1",
            terminalStepIds = listOf("step-1"),
            edges = listOf(
                StepEdge(
                    id = "edge-1",
                    fromStepId = "step-1",
                    toStepId = "step-1",
                    kind = "return",
                    rationale = "The walkthrough terminates at the handler for this simple example.",
                    callSiteFilePath = "src/App.kt",
                    callSiteStartLine = 28,
                    callSiteEndLine = 28,
                    callSiteLabel = "returns response",
                    uncertain = true,
                ),
            ),
            reviewSummary = "The handler is straightforward but worth testing around route normalization.",
            overallRisk = "Low risk unless normalization changes request semantics.",
            analysisTrace = AnalysisTrace(
                entrypointReason = "The request first enters the app through handleRequest.",
                pathEndReason = "No further internal hop is needed after the response is produced.",
                semanticToolsUsed = listOf("find_symbol", "find_referencing_symbols"),
                delegatedAgents = listOf("symbol-resolution worker"),
            ),
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
                    validationNote = "Re-anchored to symbol handleRequest at L12-L30.",
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
        assertTrue(markdown.contains("## Execution Path"))
        assertTrue(markdown.contains("- Entrypoint: Parse request"))
        assertTrue(markdown.contains("- Path ends at: Parse request"))
        assertTrue(markdown.contains("## Grounding Trace"))
        assertTrue(markdown.contains("- Semantic tools: find_symbol, find_referencing_symbols"))
        assertTrue(markdown.contains("## Suggested Tests"))
        assertTrue(markdown.contains("route normalization preserves canonical paths"))
        assertTrue(markdown.contains("- Duration: 2.5s"))
        assertTrue(markdown.contains("- Cost: $0.0312"))
        assertTrue(markdown.contains("- Focus: active step in the IDE"))
        assertTrue(markdown.contains("- Path role: entrypoint"))
        assertTrue(markdown.contains("- Path role: terminal"))
        assertTrue(markdown.contains("Path hops:"))
        assertTrue(markdown.contains("- Grounding note: Re-anchored to symbol handleRequest at L12-L30."))
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
