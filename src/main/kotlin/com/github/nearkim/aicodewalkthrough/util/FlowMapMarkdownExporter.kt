package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.RepositoryFinding
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import java.util.Locale

object FlowMapMarkdownExporter {

    fun build(
        question: String?,
        flowMap: FlowMap,
        metadata: ResponseMetadata?,
        activeStepId: String? = null,
    ): String {
        val builder = StringBuilder()
        builder.appendLine("# AI Code Walkthrough")

        question?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine()
            builder.appendLine("## Question")
            builder.appendLine()
            builder.appendLine(it)
        }

        builder.appendLine()
        builder.appendLine("## Summary")
        builder.appendLine()
        builder.appendLine(flowMap.summary)

        val entryTitle = flowMap.steps.firstOrNull { it.id == flowMap.entryStepId }?.title
        val terminalTitles = flowMap.terminalStepIds.mapNotNull { terminalId ->
            flowMap.steps.firstOrNull { it.id == terminalId }?.title
        }
        if (flowMap.entryStepId != null || terminalTitles.isNotEmpty() || flowMap.edges.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## Execution Path")
            builder.appendLine()
            flowMap.entryStepId?.let { builder.appendLine("- Entrypoint: ${entryTitle ?: it}") }
            if (terminalTitles.isNotEmpty()) {
                builder.appendLine("- Path ends at: ${terminalTitles.joinToString(", ")}")
            }
            if (flowMap.edges.isNotEmpty()) {
                builder.appendLine("- Validated hops: ${flowMap.edges.size}")
            }
        }

        flowMap.reviewSummary?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine()
            builder.appendLine("## Review Summary")
            builder.appendLine()
            builder.appendLine(it)
        }

        flowMap.overallRisk?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine()
            builder.appendLine("## Overall Risk")
            builder.appendLine()
            builder.appendLine(it)
        }

        metadata?.let {
            builder.appendLine()
            builder.appendLine("## Metadata")
            builder.appendLine()
            builder.appendLine("- Duration: ${formatDuration(it.durationMs)}")
            builder.appendLine("- Steps: ${it.stepCount}")
            builder.appendLine("- Files: ${it.fileCount}")
            builder.appendLine("- Turns: ${it.numTurns}")
            it.costUsd?.let { cost -> builder.appendLine("- Cost: $${"%.4f".format(Locale.US, cost)}") }
        }

        if (flowMap.suggestedTests.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("## Suggested Tests")
            builder.appendLine()
            flowMap.suggestedTests.forEach { test ->
                val hint = test.fileHint?.takeIf { it.isNotBlank() }?.let { " (${it})" }.orEmpty()
                builder.appendLine("- ${test.title}$hint: ${test.description}")
            }
        }

        flowMap.analysisTrace?.let { trace ->
            val traceLines = buildList {
                trace.entrypointReason?.takeIf { it.isNotBlank() }?.let { add("- Entrypoint reason: $it") }
                trace.pathEndReason?.takeIf { it.isNotBlank() }?.let { add("- Path end reason: $it") }
                if (trace.semanticToolsUsed.isNotEmpty()) {
                    add("- Semantic tools: ${trace.semanticToolsUsed.joinToString(", ")}")
                }
                if (trace.delegatedAgents.isNotEmpty()) {
                    add("- Delegated analysis: ${trace.delegatedAgents.joinToString(" | ")}")
                }
            }
            if (traceLines.isNotEmpty()) {
                builder.appendLine()
                builder.appendLine("## Grounding Trace")
                builder.appendLine()
                traceLines.forEach(builder::appendLine)
            }
        }

        builder.appendLine()
        builder.appendLine("## Steps")

        flowMap.steps.forEachIndexed { index, step ->
            appendStep(
                builder = builder,
                flowMap = flowMap,
                step = step,
                stepNumber = index + 1,
                activeStepId = activeStepId,
            )
        }

        return builder.toString().trimEnd()
    }

    private fun appendStep(
        builder: StringBuilder,
        flowMap: FlowMap,
        step: FlowStep,
        stepNumber: Int,
        activeStepId: String?,
    ) {
        builder.appendLine()
        builder.appendLine("### $stepNumber. ${step.title}")
        builder.appendLine()
        builder.appendLine("- File: `${step.filePath}`")
        builder.appendLine("- Lines: ${step.startLine}-${step.endLine}")
        step.symbol?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Symbol: `$it`") }
        step.stepType?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Type: $it") }
        step.importance?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Importance: $it") }
        step.severity?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Severity: $it") }
        step.riskType?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Risk: $it") }
        if (step.id == activeStepId) {
            builder.appendLine("- Focus: active step in the IDE")
        }
        if (step.id == flowMap.entryStepId) {
            builder.appendLine("- Path role: entrypoint")
        }
        if (step.id in flowMap.terminalStepIds) {
            builder.appendLine("- Path role: terminal")
        }
        if (step.uncertain) {
            builder.appendLine("- Confidence: uncertain")
        }
        if (step.broken) {
            builder.appendLine("- Status: needs repair")
            step.breakReason?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Repair note: $it") }
        }
        step.validationNote?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Grounding note: $it") }

        builder.appendLine()
        builder.appendLine(step.explanation)
        builder.appendLine()
        builder.appendLine("Why it matters: ${step.whyIncluded}")

        val incomingEdges = flowMap.edges.filter { it.toStepId == step.id }
        val outgoingEdges = flowMap.edges.filter { it.fromStepId == step.id }
        if (incomingEdges.isNotEmpty() || outgoingEdges.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Path hops:")
            incomingEdges.forEach { edge ->
                builder.appendLine("- From: ${formatEdge(flowMap, edge, showTarget = false)}")
            }
            outgoingEdges.forEach { edge ->
                builder.appendLine("- To: ${formatEdge(flowMap, edge, showTarget = true)}")
            }
        }

        if (step.lineAnnotations.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Line annotations:")
            step.lineAnnotations.forEach { annotation ->
                val range = if (annotation.startLine == annotation.endLine) {
                    "L${annotation.startLine}"
                } else {
                    "L${annotation.startLine}-L${annotation.endLine}"
                }
                builder.appendLine("- $range: ${annotation.text}")
            }
        }

        if (step.evidence.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Evidence:")
            step.evidence.forEach { evidence ->
                val location = buildList {
                    evidence.filePath?.let { add(it) }
                    if (evidence.startLine != null) {
                        add(
                            if (evidence.endLine != null && evidence.endLine != evidence.startLine) {
                                "L${evidence.startLine}-L${evidence.endLine}"
                            } else {
                                "L${evidence.startLine}"
                            }
                        )
                    }
                }.joinToString(":")
                val details = listOfNotNull(evidence.kind.takeIf { it.isNotBlank() }, location.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                val suffix = evidence.text?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                builder.appendLine("- ${evidence.label}${if (details.isNotBlank()) " ($details)" else ""}$suffix")
            }
        }

        if (step.potentialBugs.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Potential bugs:")
            step.potentialBugs.forEach { finding ->
                builder.appendLine("- [${finding.severity}] ${finding.title}: ${finding.summary}")
                finding.riskType?.takeIf { it.isNotBlank() }?.let { builder.appendLine("  Risk type: $it") }
                finding.suggestedAction?.takeIf { it.isNotBlank() }?.let { builder.appendLine("  Suggested action: $it") }
                finding.testGap?.takeIf { it.isNotBlank() }?.let { builder.appendLine("  Test gap: $it") }
                appendFindingEvidence(builder, finding)
            }
        }

        step.suggestedAction?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine()
            builder.appendLine("Suggested action: $it")
        }

        step.testGap?.takeIf { it.isNotBlank() }?.let {
            builder.appendLine()
            builder.appendLine("Test gap: $it")
        }

        if (step.commentDrafts.isNotEmpty()) {
            builder.appendLine()
            builder.appendLine("Comment drafts:")
            step.commentDrafts.forEach { draft ->
                builder.appendLine("- [${draft.type}/${draft.tone}] ${draft.text}")
            }
        }
    }

    private fun formatEdge(flowMap: FlowMap, edge: StepEdge, showTarget: Boolean): String {
        val peerStepId = if (showTarget) edge.toStepId else edge.fromStepId
        val peerTitle = flowMap.steps.firstOrNull { it.id == peerStepId }?.title ?: peerStepId
        val callSite = edge.callSiteStartLine?.let { start ->
            if (edge.callSiteEndLine != null && edge.callSiteEndLine != start) {
                "${edge.callSiteFilePath ?: ""}:L$start-L${edge.callSiteEndLine}"
            } else {
                "${edge.callSiteFilePath ?: ""}:L$start"
            }
        }?.trimStart(':')
        val details = buildList {
            edge.kind.takeIf { it.isNotBlank() }?.let { add(it) }
            edge.importance?.takeIf { it.isNotBlank() }?.let { add("importance: $it") }
            callSite?.takeIf { it.isNotBlank() }?.let { add(it) }
            edge.callSiteLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (edge.uncertain) add("uncertain")
            edge.validationNote?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")
        return if (details.isNotBlank()) "$peerTitle ($details)" else peerTitle
    }

    private fun formatDuration(durationMs: Long): String =
        "%.1fs".format(Locale.US, durationMs / 1000.0)

    private fun appendFindingEvidence(builder: StringBuilder, finding: RepositoryFinding) {
        if (finding.evidence.isEmpty()) return

        finding.evidence.forEach { evidence ->
            val location = buildList {
                evidence.filePath?.let { add(it) }
                if (evidence.startLine != null) {
                    add(
                        if (evidence.endLine != null && evidence.endLine != evidence.startLine) {
                            "L${evidence.startLine}-L${evidence.endLine}"
                        } else {
                            "L${evidence.startLine}"
                        },
                    )
                }
            }.joinToString(":")
            val details = listOfNotNull(evidence.kind.takeIf { it.isNotBlank() }, location.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            val suffix = evidence.text?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            builder.appendLine("  Evidence: ${evidence.label}${if (details.isNotBlank()) " ($details)" else ""}$suffix")
        }
    }
}
