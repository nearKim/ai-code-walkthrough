package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
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

        builder.appendLine()
        builder.appendLine("## Steps")

        flowMap.steps.forEachIndexed { index, step ->
            appendStep(
                builder = builder,
                step = step,
                stepNumber = index + 1,
                activeStepId = activeStepId,
            )
        }

        return builder.toString().trimEnd()
    }

    private fun appendStep(
        builder: StringBuilder,
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
        step.severity?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Severity: $it") }
        step.riskType?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Risk: $it") }
        if (step.id == activeStepId) {
            builder.appendLine("- Focus: active step in the IDE")
        }
        if (step.uncertain) {
            builder.appendLine("- Confidence: uncertain")
        }
        if (step.broken) {
            builder.appendLine("- Status: needs repair")
            step.breakReason?.takeIf { it.isNotBlank() }?.let { builder.appendLine("- Repair note: $it") }
        }

        builder.appendLine()
        builder.appendLine(step.explanation)
        builder.appendLine()
        builder.appendLine("Why it matters: ${step.whyIncluded}")

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

    private fun formatDuration(durationMs: Long): String =
        "%.1fs".format(Locale.US, durationMs / 1000.0)
}
