package com.github.nearkim.aicodewalkthrough.service.validation

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.StepEdge

/**
 * Owns path topology: which hops survive, where the path starts, and where it ends. Call sites are
 * clamped to their source step so the editor preview can never point outside the current step.
 */
internal class StepGraphValidator {

    fun validateEdges(edges: List<StepEdge>, steps: List<FlowStep>): List<StepEdge> {
        val stepById = steps.associateBy { it.id }
        val sourceEdges = if (edges.isEmpty()) synthesizeSequentialEdges(steps) else edges
        return deduplicateEdges(sourceEdges.mapNotNull { edge -> validateEdge(edge, stepById) })
    }

    fun resolveEntryStepId(
        requestedEntryStepId: String?,
        steps: List<FlowStep>,
        edges: List<StepEdge>,
    ): String? {
        val navigableSteps = steps.filterNot { it.broken }
        if (navigableSteps.isEmpty()) return null

        requestedEntryStepId?.takeIf { candidate ->
            navigableSteps.any { it.id == candidate }
        }?.let { return it }

        val incomingTargets = edges.map { it.toStepId }.toSet()
        return navigableSteps.firstOrNull { it.id !in incomingTargets }?.id
            ?: navigableSteps.first().id
    }

    fun resolveTerminalStepIds(
        requestedTerminalStepIds: List<String>,
        steps: List<FlowStep>,
        edges: List<StepEdge>,
    ): List<String> {
        val navigableSteps = steps.filterNot { it.broken }
        if (navigableSteps.isEmpty()) return emptyList()

        val validRequested = requestedTerminalStepIds.filter { candidate ->
            navigableSteps.any { it.id == candidate }
        }
        val outgoingSources = edges.map { it.fromStepId }.toSet()
        val inferred = navigableSteps.filter { it.id !in outgoingSources }.map { it.id }
        val merged = (validRequested + inferred).distinct()
        return if (merged.isNotEmpty()) merged else listOf(navigableSteps.last().id)
    }

    private fun validateEdge(edge: StepEdge, stepById: Map<String, FlowStep>): StepEdge? {
        val fromStep = stepById[edge.fromStepId] ?: return null
        val toStep = stepById[edge.toStepId] ?: return null
        if (fromStep.broken || toStep.broken || fromStep.id == toStep.id) return null

        val validationNotes = mutableListOf<String>()
        var downgradeConfidence = edge.uncertain

        val callSiteFilePath = edge.callSiteFilePath?.takeIf { it.isNotBlank() } ?: fromStep.filePath
        var callSiteStartLine = edge.callSiteStartLine
        var callSiteEndLine = edge.callSiteEndLine

        if (callSiteStartLine == null || callSiteEndLine == null) {
            downgradeConfidence = true
            validationNotes += "Missing explicit call-site lines for hop ${edge.id}; the next preview may fall back to symbol matching."
        } else if (callSiteFilePath == fromStep.filePath) {
            val clampedStart = callSiteStartLine.coerceIn(fromStep.startLine, fromStep.endLine)
            val clampedEnd = callSiteEndLine.coerceIn(clampedStart, fromStep.endLine)
            if (clampedStart != callSiteStartLine || clampedEnd != callSiteEndLine) {
                downgradeConfidence = true
                validationNotes += "Clamped edge ${edge.id} to the source step range L$clampedStart-L$clampedEnd."
            }
            callSiteStartLine = clampedStart
            callSiteEndLine = clampedEnd
        } else {
            downgradeConfidence = true
            validationNotes += "Edge ${edge.id} points to a call site outside ${fromStep.filePath}; the IDE preview stays step-scoped."
        }

        if (edge.evidence.isEmpty()) {
            downgradeConfidence = true
            validationNotes += "Edge ${edge.id} has no grounding evidence."
        }

        return edge.copy(
            callSiteFilePath = callSiteFilePath,
            callSiteStartLine = callSiteStartLine,
            callSiteEndLine = callSiteEndLine,
            uncertain = downgradeConfidence,
            validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
        )
    }

    private fun synthesizeSequentialEdges(steps: List<FlowStep>): List<StepEdge> {
        val navigableSteps = steps.filterNot { it.broken }
        if (navigableSteps.size < 2) return emptyList()

        return navigableSteps.zipWithNext().mapIndexed { index, (fromStep, toStep) ->
            StepEdge(
                id = "derived-edge-${index + 1}",
                fromStepId = fromStep.id,
                toStepId = toStep.id,
                kind = "implied_order",
                rationale = "Synthesized from the returned step order because no explicit path edges were provided.",
                importance = toStep.importance,
                uncertain = true,
            )
        }
    }

    private fun deduplicateEdges(edges: List<StepEdge>): List<StepEdge> {
        return edges
            .groupBy { Triple(it.fromStepId, it.toStepId, it.kind) }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<StepEdge> { if (it.broken) 0 else 1 }
                        .thenBy { if (it.uncertain) 0 else 1 }
                        .thenBy { it.evidence.size }
                        .thenBy { if (it.callSiteStartLine == null) 0 else 1 },
                ) ?: duplicates.first()
            }
    }
}
