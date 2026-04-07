package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FlowStep(
    val id: String,
    val title: String,
    @SerialName("file_path") val filePath: String,
    val symbol: String? = null,
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    val explanation: String,
    @SerialName("why_included") val whyIncluded: String,
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val broken: Boolean = false,
    @kotlinx.serialization.Transient val breakReason: String? = null,
)

@Serializable
data class FlowMap(
    val summary: String,
    val steps: List<FlowStep>,
)

@Serializable
data class ClarificationResponse(
    @SerialName("clarification_question") val clarificationQuestion: String,
)

@Serializable
data class LlmResponse(
    val type: String,
    val summary: String? = null,
    val steps: List<FlowStep>? = null,
    @SerialName("clarification_question") val clarificationQuestion: String? = null,
) {
    fun toFlowMap(): FlowMap? {
        if (type != "flow_map" || summary == null || steps == null) return null
        return FlowMap(summary = summary, steps = steps)
    }

    fun toClarification(): ClarificationResponse? {
        if (type != "clarification" || clarificationQuestion == null) return null
        return ClarificationResponse(clarificationQuestion = clarificationQuestion)
    }
}

@Serializable
data class ClaudeEnvelope(
    val type: String,
    val subtype: String? = null,
    @SerialName("is_error") val isError: Boolean = false,
    val result: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("cost_usd") val costUsd: Double? = null,
    @SerialName("num_turns") val numTurns: Int? = null,
)

data class ResponseMetadata(
    val durationMs: Long,
    val costUsd: Double?,
    val numTurns: Int,
    val stepCount: Int,
    val fileCount: Int,
)
