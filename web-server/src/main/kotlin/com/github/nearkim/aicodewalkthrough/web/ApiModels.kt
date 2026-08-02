package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MappingRequest(
    val question: String = "",
    val mode: String,
    val provider: String,
)

@Serializable
data class TourRequest(
    val action: String,
    @SerialName("step_id") val stepId: String? = null,
)

@Serializable
data class StepAnswerRequest(val question: String)

@Serializable
data class ProviderStatusResponse(
    val id: String,
    val name: String,
    val available: Boolean,
    val message: String,
)

@Serializable
data class SourceResponse(
    val path: String,
    val content: String,
)

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class ProgressEvent(val line: String)

@Serializable
data class SessionSnapshot(
    val state: String,
    val repository: String,
    @SerialName("repository_path") val repositoryPath: String,
    val question: String? = null,
    val mode: String = "understand",
    val provider: String = "claude_cli",
    @SerialName("flow_map") val flowMap: FlowMap? = null,
    val metadata: ResponseMetadata? = null,
    @SerialName("current_step_index") val currentStepIndex: Int = -1,
    @SerialName("displayed_step_index") val displayedStepIndex: Int = -1,
    @SerialName("displayed_step") val displayedStep: FlowStep? = null,
    @SerialName("next_step") val nextStep: FlowStep? = null,
    @SerialName("next_edge") val nextEdge: StepEdge? = null,
    @SerialName("broken_step_ids") val brokenStepIds: List<String> = emptyList(),
    @SerialName("step_answer") val stepAnswer: StepAnswer? = null,
    @SerialName("step_answer_loading") val stepAnswerLoading: Boolean = false,
    @SerialName("step_answer_error") val stepAnswerError: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("progress_lines") val progressLines: List<String> = emptyList(),
    @SerialName("can_previous") val canPrevious: Boolean = false,
)

@Serializable
data class SettingsResponse(val settings: WalkthroughSettings)

data class OutboundEvent(val name: String, val data: String)
