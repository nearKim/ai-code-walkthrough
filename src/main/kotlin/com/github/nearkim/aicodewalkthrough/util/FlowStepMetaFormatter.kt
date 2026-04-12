package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.FlowStep

object FlowStepMetaFormatter {

    fun format(step: FlowStep): String =
        buildList {
            add(step.filePath)
            add("L${step.startLine}-L${step.endLine}")
            step.stepType?.takeIf { it.isNotBlank() }?.let { add("type: $it") }
            step.importance?.takeIf { it.isNotBlank() }?.let { add("importance: $it") }
            step.severity?.takeIf { it.isNotBlank() }?.let { add("severity: $it") }
            (step.confidence?.takeIf { it.isNotBlank() } ?: if (step.uncertain) "uncertain" else null)?.let {
                add("confidence: $it")
            }
            step.riskType?.takeIf { it.isNotBlank() }?.let { add("risk: $it") }
            if (step.potentialBugs.isNotEmpty()) {
                add("bugs: ${step.potentialBugs.size}")
            }
            if (step.evidence.isNotEmpty()) {
                add("evidence: ${step.evidence.size}")
            }
            if (!step.testGap.isNullOrBlank()) {
                add("test gap")
            }
            if (!step.suggestedAction.isNullOrBlank()) {
                add("action")
            }
            if (step.broken) {
                add("needs repair")
            }
        }.joinToString("  ·  ")
}
