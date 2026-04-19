package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.Serializable

data class ClarificationExchange(
    val question: String,
    val answer: String,
)

enum class AnalysisMode(
    val id: String,
    val displayName: String,
) {
    UNDERSTAND("understand", "Understand"),
    REVIEW("review", "Review"),
    TRACE("trace", "Trace"),
    ;

    companion object {
        fun fromId(id: String?): AnalysisMode = entries.firstOrNull { it.id == id } ?: UNDERSTAND
    }

    override fun toString(): String = displayName
}

enum class CommentTone(
    val id: String,
    val displayName: String,
) {
    NEUTRAL("neutral", "Neutral"),
    DIRECT("direct", "Direct"),
    FRIENDLY("friendly", "Friendly"),
    ;

    override fun toString(): String = displayName
}

data class QueryContext(
    val filePath: String? = null,
    val symbol: String? = null,
    val selectionStartLine: Int? = null,
    val selectionEndLine: Int? = null,
    val selectedText: String? = null,
    val diffSummary: String? = null,
    val failingTestName: String? = null,
    val featureScope: FeatureScopeContext? = null,
    val invokedFromCursor: Boolean = false,
) {
    fun summaryParts(): List<String> = buildList {
        filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
        symbol?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (selectionStartLine != null && selectionEndLine != null) {
            add("L$selectionStartLine-L$selectionEndLine")
        }
        failingTestName?.takeIf { it.isNotBlank() }?.let { add("failing test: $it") }
        featureScope?.featureName?.takeIf { it.isNotBlank() }?.let { add("feature: $it") }
    }
}

@Serializable
data class FeatureScopeContext(
    val featureId: String,
    val featureName: String,
    val featureSummary: String? = null,
    val featureReviewSummary: String? = null,
    val allowedFilePaths: List<String> = emptyList(),
    val ownedPaths: List<String> = emptyList(),
    val sharedPaths: List<String> = emptyList(),
    val selectedPathId: String? = null,
    val selectedPathName: String? = null,
    val selectedPathDescription: String? = null,
    val promptSeed: String? = null,
    val supportingSymbols: List<String> = emptyList(),
    val boundaryNotes: List<String> = emptyList(),
)

data class FollowUpContext(
    val originalQuestion: String,
    val previousFlowMap: FlowMap,
    val activeStepId: String? = null,
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
) {
    companion object {
        private const val MAX_CLARIFICATIONS = 5
    }

    fun withClarification(question: String, answer: String): FollowUpContext {
        val updated = clarificationHistory + ClarificationExchange(question, answer)
        val trimmed = if (updated.size > MAX_CLARIFICATIONS) {
            updated.drop(updated.size - MAX_CLARIFICATIONS)
        } else {
            updated
        }
        return copy(clarificationHistory = trimmed)
    }
}

enum class TourState {
    INPUT,
    LOADING,
    OVERVIEW,
    TOUR_ACTIVE,
}
