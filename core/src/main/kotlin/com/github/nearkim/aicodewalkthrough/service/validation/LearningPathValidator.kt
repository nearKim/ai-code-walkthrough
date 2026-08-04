package com.github.nearkim.aicodewalkthrough.service.validation

import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LearningStage

/**
 * Keeps curriculum stages pointing at validated components and steps, assigning each step to at
 * most one stage. In mechanical mode stage prose is rewritten from the verified symbols themselves.
 */
internal class LearningPathValidator(private val mechanicalMode: Boolean) {

    fun validate(
        learningPath: List<LearningStage>,
        architecture: CodebaseArchitecture?,
        steps: List<FlowStep>,
    ): List<LearningStage> {
        val componentIds = architecture?.components?.mapTo(mutableSetOf()) { it.id }.orEmpty()
        val navigableSteps = steps.filterNot { it.broken }
        val assignedStepIds = mutableSetOf<String>()

        val validatedStages = learningPath.distinctBy { it.id }.mapNotNull { stage ->
            if (stage.id.isBlank() || stage.title.isBlank() || stage.goal.isBlank()) return@mapNotNull null
            val validStageSteps = navigableSteps
                .filter { it.id in stage.stepIds && assignedStepIds.add(it.id) }
            val stepPaths = validStageSteps.mapTo(mutableSetOf()) { it.filePath }
            val derivedComponents = architecture?.components.orEmpty()
                .filter { component -> component.keyPaths.any(stepPaths::contains) }
                .map { it.id }
            val validStageComponents = (stage.componentIds.filter { it in componentIds } + derivedComponents).distinct()
            val validStageStepIds = validStageSteps.map { it.id }
            if (validStageComponents.isEmpty() && validStageStepIds.isEmpty()) return@mapNotNull null
            stage.copy(
                title = if (!mechanicalMode) stage.title else validStageSteps.firstOrNull()?.title ?: stage.title,
                goal = when {
                    !mechanicalMode -> stage.goal
                    validStageSteps.isEmpty() -> "Inspect verified Python structure."
                    else -> validStageSteps.joinToString(prefix = "Inspect ", separator = ", ", postfix = ".") {
                        it.symbol ?: it.title
                    }
                },
                componentIds = validStageComponents,
                stepIds = validStageStepIds,
                checkpoint = stage.checkpoint?.trim()?.takeIf { it.isNotEmpty() && !mechanicalMode },
            )
        }.toMutableList()

        val unassignedStepIds = navigableSteps.map { it.id }.filter { it !in assignedStepIds }
        if (validatedStages.isNotEmpty() && unassignedStepIds.isNotEmpty()) {
            val lastIndex = validatedStages.lastIndex
            validatedStages[lastIndex] = validatedStages[lastIndex].copy(
                stepIds = (validatedStages[lastIndex].stepIds + unassignedStepIds).distinct(),
            )
        }

        if (validatedStages.isEmpty() && architecture != null && navigableSteps.isNotEmpty()) {
            return listOf(
                LearningStage(
                    id = "derived-guided-path",
                    title = "Guided code path",
                    goal = "Connect the system architecture to its representative runtime behavior.",
                    componentIds = architecture.components.map { it.id },
                    stepIds = navigableSteps.map { it.id },
                ),
            )
        }
        return validatedStages
    }
}
