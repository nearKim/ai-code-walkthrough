package com.github.nearkim.aicodewalkthrough.domain.session

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.StepEdge

class TourNavigator {

    fun preferredNextHop(
        flowMap: FlowMap?,
        stepId: String,
        visitedStepIds: Set<String> = emptySet(),
    ): StepEdge? {
        val currentFlowMap = flowMap ?: return null
        val candidateEdges = currentFlowMap.edges
            .filter { !it.broken && it.fromStepId == stepId }
            .filter { edge ->
                currentFlowMap.steps.any { step -> step.id == edge.toStepId && !step.broken } &&
                    edge.toStepId !in visitedStepIds
            }
        return candidateEdges.maxWithOrNull(
            compareBy<StepEdge> { importanceRank(it.importance) }
                .thenBy { if (it.uncertain) 0 else 1 }
                .thenBy { it.evidence.size }
                .thenBy { -currentFlowMap.steps.indexOfFirst { step -> step.id == it.toStepId } },
        )
    }

    fun findNextNavigableStepIndex(flowMap: FlowMap?, startIndex: Int): Int? {
        val steps = flowMap?.steps ?: return null
        var index = startIndex.coerceAtLeast(0)
        while (index < steps.size) {
            if (!steps[index].broken) return index
            index++
        }
        return null
    }

    fun findPreferredNextNavigableStepIndex(
        flowMap: FlowMap?,
        fromIndex: Int,
        visitedStepIds: Set<String>,
    ): Int? {
        val currentFlowMap = flowMap ?: return null
        val step = currentFlowMap.steps.getOrNull(fromIndex) ?: return null
        val preferredEdge = preferredNextHop(currentFlowMap, step.id, visitedStepIds)
            ?: return null
        val nextIndex = currentFlowMap.steps.indexOfFirst { candidate ->
            candidate.id == preferredEdge.toStepId && !candidate.broken
        }
        return nextIndex.takeIf { it >= 0 }
    }

    private fun importanceRank(value: String?): Int = when (value?.trim()?.lowercase()) {
        "critical", "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }
}
