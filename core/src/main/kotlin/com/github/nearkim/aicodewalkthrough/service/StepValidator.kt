package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.ArchitectureResponsibility
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.service.validation.ArchitectureValidator
import com.github.nearkim.aicodewalkthrough.service.validation.DiagramSectionValidator
import com.github.nearkim.aicodewalkthrough.service.validation.EvidenceSanitizer
import com.github.nearkim.aicodewalkthrough.service.validation.LearningPathValidator
import com.github.nearkim.aicodewalkthrough.service.validation.StepAnchorValidator
import com.github.nearkim.aicodewalkthrough.service.validation.StepGraphValidator
import java.nio.file.Path

/**
 * Anti-hallucination layer. Coordinates the per-concern validators and re-assembles the flow map;
 * each collaborator owns one kind of grounding.
 */
class StepValidator(
    projectBasePath: String,
    private val mechanicalArchitecture: CodebaseArchitecture? = null,
) {

    private val projectFiles = ProjectFiles(Path.of(projectBasePath))
    private val evidenceSanitizer = EvidenceSanitizer(projectFiles)
    private val architectureValidator = ArchitectureValidator(projectFiles, evidenceSanitizer)
    private val diagramSectionValidator = DiagramSectionValidator()
    private val learningPathValidator = LearningPathValidator(mechanicalArchitecture != null)
    private val stepGraphValidator = StepGraphValidator()
    private val stepAnchorValidator = StepAnchorValidator(
        projectFiles,
        evidenceSanitizer,
        mechanicalArchitecture?.let(::mechanicalEvidence),
    )

    fun validate(flowMap: FlowMap): FlowMap {
        val validatedSteps = validate(flowMap.steps)
        val validatedEdges = stepGraphValidator.validateEdges(
            if (mechanicalArchitecture == null) flowMap.edges else emptyList(),
            validatedSteps,
        )
        val architecture = architectureValidator.validate(mechanicalArchitecture ?: flowMap.architecture)
        val learningPath = learningPathValidator.validate(flowMap.learningPath, architecture, validatedSteps)
        val entryStepId = stepGraphValidator.resolveEntryStepId(flowMap.entryStepId, validatedSteps, validatedEdges)
        val diagramSections = diagramSectionValidator.validate(flowMap.diagramSections, architecture, validatedSteps)
            .let { sections ->
                if (flowMap.mode == "understand") {
                    diagramSectionValidator.addLearnFallbacks(
                        sections,
                        architecture,
                        validatedSteps,
                        learningPath,
                    )
                } else {
                    sections
                }
            }

        return flowMap.copy(
            steps = validatedSteps,
            architecture = architecture,
            diagramSections = diagramSections,
            learningPath = learningPath,
            entryStepId = entryStepId,
            terminalStepIds = stepGraphValidator.resolveTerminalStepIds(
                flowMap.terminalStepIds,
                validatedSteps,
                validatedEdges,
            ),
            edges = validatedEdges,
        )
    }

    fun validate(steps: List<FlowStep>): List<FlowStep> = stepAnchorValidator.validate(steps)

    fun sanitizeEvidenceItems(
        evidence: List<EvidenceItem>,
        defaultFilePath: String,
    ): List<EvidenceItem> = evidenceSanitizer.sanitize(evidence, defaultFilePath, mutableListOf()).value

    private fun mechanicalEvidence(architecture: CodebaseArchitecture): List<EvidenceItem> =
        architecture.components.flatMap { component ->
            component.evidence + component.responsibilities.flatMap(ArchitectureResponsibility::evidence)
        }.filter { it.filePath != null && it.startLine != null }
}
