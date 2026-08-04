package com.github.nearkim.aicodewalkthrough.service.validation

import com.github.nearkim.aicodewalkthrough.model.ArchitectureComponent
import com.github.nearkim.aicodewalkthrough.model.ArchitectureResponsibility
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.ComponentRelationship
import com.github.nearkim.aicodewalkthrough.service.ProjectFiles

/**
 * Grounds the component map: drops components without resolvable paths, filters relationships and
 * responsibility references down to the ones that actually resolve to other validated components.
 */
internal class ArchitectureValidator(
    private val projectFiles: ProjectFiles,
    private val evidenceSanitizer: EvidenceSanitizer,
) {

    fun validate(architecture: CodebaseArchitecture?): CodebaseArchitecture? {
        architecture ?: return null
        if (architecture.systemPurpose.isBlank()) return null
        val components = architecture.components
            .mapNotNull(::validateComponent)
            .distinctBy { it.id }
        if (components.isEmpty()) return null

        val componentById = components.associateBy { it.id }
        val relationships = architecture.relationships
            .mapNotNull { validateRelationship(it, componentById) }
            .groupBy { Triple(it.fromComponentId, it.toComponentId, it.kind) }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<ComponentRelationship> { if (it.uncertain) 0 else 1 }
                        .thenBy { it.evidence.size },
                ) ?: duplicates.first()
            }
        val relationshipById = relationships.associateBy { it.id }
        val groundedComponents = components.map { component ->
            validateResponsibilityReferences(component, componentById.keys, relationshipById)
        }

        return architecture.copy(
            components = groundedComponents,
            relationships = relationships,
            crossCuttingConcerns = architecture.crossCuttingConcerns.cleanTextItems(),
            coverageNotes = architecture.coverageNotes.cleanTextItems(),
        )
    }

    private fun validateComponent(component: ArchitectureComponent): ArchitectureComponent? {
        if (component.id.isBlank() || component.name.isBlank() || component.responsibility.isBlank()) return null

        val validationNotes = mutableListOf<String>()
        val keyPaths = component.keyPaths.mapNotNull { projectFiles.normalizeExisting(it) }.distinct()
        val pathsChanged = keyPaths.size != component.keyPaths.distinct().size
        if (pathsChanged) {
            validationNotes += "Removed architecture paths that do not resolve inside the project."
        }
        if (keyPaths.isEmpty()) return null

        val defaultEvidencePath = defaultEvidencePath(keyPaths)
        val evidenceResult = evidenceSanitizer.sanitize(component.evidence, defaultEvidencePath, validationNotes)
        val responsibilities = component.responsibilities
            .mapNotNull { validateResponsibility(it, defaultEvidencePath) }
            .distinctBy { it.id }
        val responsibilitiesChanged = responsibilities.size != component.responsibilities.size
        if (responsibilitiesChanged) {
            validationNotes += "Removed architecture responsibilities without a grounded code owner or component collaborator."
        }

        return component.copy(
            kind = component.kind.ifBlank { "component" },
            responsibilities = responsibilities,
            keyPaths = keyPaths,
            keySymbols = component.keySymbols.cleanTextItems(),
            evidence = evidenceResult.value,
            uncertain = component.uncertain || pathsChanged || evidenceResult.changed || responsibilitiesChanged,
            validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
        )
    }

    private fun validateResponsibility(
        responsibility: ArchitectureResponsibility,
        defaultEvidencePath: String,
    ): ArchitectureResponsibility? {
        if (responsibility.id.isBlank() || responsibility.title.isBlank() || responsibility.description.isBlank()) {
            return null
        }

        val validationNotes = mutableListOf<String>()
        val evidenceResult = evidenceSanitizer.sanitize(responsibility.evidence, defaultEvidencePath, validationNotes)
        val codeEvidence = evidenceResult.value.filter { it.filePath != null && it.startLine != null }
        val collaborators = responsibility.collaboratorComponentIds.cleanTextItems()
        val relationships = responsibility.relationshipIds.cleanTextItems()
        val evidenceChanged = codeEvidence.size != responsibility.evidence.size
        if (evidenceChanged) {
            validationNotes += "Removed responsibility evidence without an exact project line."
        }
        if (codeEvidence.isEmpty() && collaborators.isEmpty()) return null

        return responsibility.copy(
            evidence = codeEvidence,
            collaboratorComponentIds = collaborators,
            relationshipIds = relationships,
            uncertain = responsibility.uncertain || evidenceResult.changed || evidenceChanged,
            validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
        )
    }

    private fun validateResponsibilityReferences(
        component: ArchitectureComponent,
        componentIds: Set<String>,
        relationshipById: Map<String, ComponentRelationship>,
    ): ArchitectureComponent {
        var componentChanged = false
        val responsibilities = component.responsibilities.mapNotNull { responsibility ->
            val validationNotes = mutableListOf<String>()
            responsibility.validationNote?.let(validationNotes::add)
            val collaborators = responsibility.collaboratorComponentIds
                .filter { it in componentIds && it != component.id }
                .distinct()
            if (collaborators.size != responsibility.collaboratorComponentIds.size) {
                componentChanged = true
                validationNotes += "Removed responsibility collaborators that do not resolve to another component."
            }
            val relationshipIds = responsibility.relationshipIds.filter { id ->
                val relationship = relationshipById[id] ?: return@filter false
                val otherComponentId = when (component.id) {
                    relationship.fromComponentId -> relationship.toComponentId
                    relationship.toComponentId -> relationship.fromComponentId
                    else -> return@filter false
                }
                collaborators.isEmpty() || otherComponentId in collaborators
            }.distinct()
            if (relationshipIds.size != responsibility.relationshipIds.size) {
                componentChanged = true
                validationNotes += "Removed responsibility relationships that do not touch its component collaborators."
            }
            if (responsibility.evidence.isEmpty() && collaborators.isEmpty()) {
                componentChanged = true
                return@mapNotNull null
            }
            responsibility.copy(
                collaboratorComponentIds = collaborators,
                relationshipIds = relationshipIds,
                uncertain = responsibility.uncertain || validationNotes.isNotEmpty(),
                validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
            )
        }
        return component.copy(
            responsibilities = responsibilities,
            uncertain = component.uncertain || componentChanged,
            validationNote = listOfNotNull(
                component.validationNote,
                "Removed invalid responsibility mappings.".takeIf { componentChanged },
            ).joinToString(" ").orNullIfBlank(),
        )
    }

    private fun validateRelationship(
        relationship: ComponentRelationship,
        componentById: Map<String, ArchitectureComponent>,
    ): ComponentRelationship? {
        if (relationship.id.isBlank() || relationship.description.isBlank()) return null
        val fromComponent = componentById[relationship.fromComponentId] ?: return null
        if (componentById[relationship.toComponentId] == null) return null
        if (relationship.fromComponentId == relationship.toComponentId) return null

        val validationNotes = mutableListOf<String>()
        val evidenceResult = evidenceSanitizer.sanitize(
            relationship.evidence,
            defaultEvidencePath(fromComponent.keyPaths),
            validationNotes,
        )
        val missingFileEvidence = evidenceResult.value.none { !it.filePath.isNullOrBlank() }
        if (missingFileEvidence) {
            validationNotes += "No valid file evidence was supplied for this component relationship."
        }

        return relationship.copy(
            kind = relationship.kind.ifBlank { "depends_on" },
            evidence = evidenceResult.value,
            uncertain = relationship.uncertain || evidenceResult.changed || missingFileEvidence,
            validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
        )
    }

    private fun defaultEvidencePath(keyPaths: List<String>): String = keyPaths.firstOrNull { path ->
        projectFiles.resolveExisting(path, requireRegularFile = true) != null
    }.orEmpty()
}
