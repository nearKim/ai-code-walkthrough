package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LineAnnotation(
    @SerialName("start_line") val startLine: Int,
    @SerialName("end_line") val endLine: Int,
    val text: String,
)

@Serializable
data class EvidenceItem(
    val kind: String,
    val label: String,
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("end_line") val endLine: Int? = null,
    val text: String? = null,
)

@Serializable
data class CommentDraft(
    val type: String,
    val tone: String,
    val text: String,
)

@Serializable
data class SuggestedTest(
    val title: String,
    val description: String,
    @SerialName("file_hint") val fileHint: String? = null,
)

@Serializable
data class StepAnswer(
    val answer: String,
    @SerialName("why_it_matters") val whyItMatters: String? = null,
    @SerialName("important_lines") val importantLines: List<LineAnnotation> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList(),
    val confidence: String? = null,
    val uncertain: Boolean = false,
)

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
    @SerialName("line_annotations") val lineAnnotations: List<LineAnnotation> = emptyList(),
    val severity: String? = null,
    val confidence: String? = null,
    @SerialName("risk_type") val riskType: String? = null,
    val evidence: List<EvidenceItem> = emptyList(),
    @SerialName("suggested_action") val suggestedAction: String? = null,
    @SerialName("test_gap") val testGap: String? = null,
    @SerialName("comment_drafts") val commentDrafts: List<CommentDraft> = emptyList(),
    @kotlinx.serialization.Transient val broken: Boolean = false,
    @kotlinx.serialization.Transient val breakReason: String? = null,
    @kotlinx.serialization.Transient val validationNote: String? = null,
)

@Serializable
data class FlowMap(
    val mode: String? = null,
    val summary: String,
    val steps: List<FlowStep>,
    @SerialName("overall_risk") val overallRisk: String? = null,
    @SerialName("review_summary") val reviewSummary: String? = null,
    @SerialName("suggested_tests") val suggestedTests: List<SuggestedTest> = emptyList(),
)

@Serializable
data class ClarificationResponse(
    @SerialName("clarification_question") val clarificationQuestion: String,
)

@Serializable
data class LlmResponse(
    val type: String,
    val mode: String? = null,
    val summary: String? = null,
    val steps: List<FlowStep>? = null,
    val answer: String? = null,
    @SerialName("why_it_matters") val whyItMatters: String? = null,
    @SerialName("important_lines") val importantLines: List<LineAnnotation>? = null,
    val evidence: List<EvidenceItem>? = null,
    val confidence: String? = null,
    val uncertain: Boolean? = null,
    @SerialName("clarification_question") val clarificationQuestion: String? = null,
    @SerialName("overall_risk") val overallRisk: String? = null,
    @SerialName("review_summary") val reviewSummary: String? = null,
    @SerialName("suggested_tests") val suggestedTests: List<SuggestedTest>? = null,
) {
    fun toFlowMap(): FlowMap? {
        if (type != "flow_map" || summary == null || steps == null) return null
        return FlowMap(
            mode = mode,
            summary = summary,
            steps = steps,
            overallRisk = overallRisk,
            reviewSummary = reviewSummary,
            suggestedTests = suggestedTests ?: emptyList(),
        )
    }

    fun toClarification(): ClarificationResponse? {
        if (type != "clarification" || clarificationQuestion == null) return null
        return ClarificationResponse(clarificationQuestion = clarificationQuestion)
    }

    fun toStepAnswer(): StepAnswer? {
        if (type != "step_answer" || answer == null) return null
        return StepAnswer(
            answer = answer,
            whyItMatters = whyItMatters,
            importantLines = importantLines ?: emptyList(),
            evidence = evidence ?: emptyList(),
            confidence = confidence,
            uncertain = uncertain ?: false,
        )
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
