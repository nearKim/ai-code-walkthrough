package com.github.nearkim.aicodewalkthrough.service.validation

import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.DiagramSection
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LearningStage

/** Keeps independently navigable diagram views anchored to validated components and steps. */
internal class DiagramSectionValidator {

    fun validate(
        sections: List<DiagramSection>,
        architecture: CodebaseArchitecture?,
        steps: List<FlowStep>,
    ): List<DiagramSection> {
        val components = architecture?.components.orEmpty()
        val componentIds = components.mapTo(mutableSetOf()) { it.id }
        val navigableSteps = steps.filterNot { it.broken }

        return sections.distinctBy { it.id }.mapNotNull { section ->
            if (section.id.isBlank() || section.title.isBlank()) return@mapNotNull null

            val sectionSteps = navigableSteps.filter { it.id in section.stepIds }
            val stepPaths = sectionSteps.mapTo(mutableSetOf()) { it.filePath }
            val derivedComponentIds = components
                .filter { component -> component.keyPaths.any(stepPaths::contains) }
                .map { it.id }
            val sectionComponentIds = (
                section.componentIds.filter { it in componentIds } + derivedComponentIds
                ).distinct()
            val sectionStepIds = sectionSteps.map { it.id }
            if (sectionComponentIds.isEmpty() && sectionStepIds.isEmpty()) return@mapNotNull null

            section.copy(
                title = section.title.trim(),
                summary = section.summary?.trim()?.takeIf(String::isNotBlank),
                componentIds = sectionComponentIds,
                stepIds = sectionStepIds,
            )
        }
    }

    fun addLearnFallbacks(
        sections: List<DiagramSection>,
        architecture: CodebaseArchitecture?,
        steps: List<FlowStep>,
        learningPath: List<LearningStage>,
    ): List<DiagramSection> {
        val fallbackSections = learningPath.map { stage ->
            DiagramSection(
                id = "feature-${stage.id}",
                title = stage.title,
                summary = stage.goal,
                componentIds = stage.componentIds,
                stepIds = stage.stepIds,
            )
        }
        val presentIds = sections.mapTo(mutableSetOf()) { it.id }
        return sections + validate(fallbackSections, architecture, steps).filter { it.id !in presentIds }
    }
}
