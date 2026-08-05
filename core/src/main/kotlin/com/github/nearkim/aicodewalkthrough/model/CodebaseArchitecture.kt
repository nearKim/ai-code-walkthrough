package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CodebaseArchitecture(
    @SerialName("system_purpose") val systemPurpose: String,
    @SerialName("system_name") val systemName: String? = null,
    val containers: List<ArchitectureContainer> = emptyList(),
    val components: List<ArchitectureComponent> = emptyList(),
    val relationships: List<ComponentRelationship> = emptyList(),
    @SerialName("cross_cutting_concerns") val crossCuttingConcerns: List<String> = emptyList(),
    @SerialName("coverage_notes") val coverageNotes: List<String> = emptyList(),
)

@Serializable
data class ArchitectureContainer(
    val id: String,
    val name: String,
    val kind: String = "application",
    val responsibility: String,
    @SerialName("component_ids") val componentIds: List<String> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList(),
    val uncertain: Boolean = false,
)

@Serializable
data class ArchitectureComponent(
    val id: String,
    val name: String,
    val kind: String = "component",
    val responsibility: String,
    val responsibilities: List<ArchitectureResponsibility> = emptyList(),
    @SerialName("key_paths") val keyPaths: List<String> = emptyList(),
    @SerialName("key_symbols") val keySymbols: List<String> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList(),
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
)

@Serializable
data class ArchitectureResponsibility(
    val id: String,
    val title: String,
    val description: String,
    val evidence: List<EvidenceItem> = emptyList(),
    @SerialName("collaborator_component_ids") val collaboratorComponentIds: List<String> = emptyList(),
    @SerialName("relationship_ids") val relationshipIds: List<String> = emptyList(),
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
)

@Serializable
data class ComponentRelationship(
    val id: String,
    @SerialName("from_component_id") val fromComponentId: String,
    @SerialName("to_component_id") val toComponentId: String,
    val kind: String = "depends_on",
    val description: String,
    val evidence: List<EvidenceItem> = emptyList(),
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
)

@Serializable
data class LearningStage(
    val id: String,
    val title: String,
    val goal: String,
    @SerialName("component_ids") val componentIds: List<String> = emptyList(),
    @SerialName("step_ids") val stepIds: List<String> = emptyList(),
    val checkpoint: String? = null,
)
