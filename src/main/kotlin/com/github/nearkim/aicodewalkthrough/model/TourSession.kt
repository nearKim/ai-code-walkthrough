package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.Serializable

enum class AnalysisMode(
    val id: String,
    val displayName: String,
    val description: String,
    val example: String,
) {
    UNDERSTAND("understand", "Understand", "Explain what the code does and how pieces connect",
        "e.g. \"How does the caching layer work?\""),
    REVIEW("review", "Review", "Find bugs, regressions, and actionable improvements",
        "e.g. \"Is this PR safe to merge?\""),
    TRACE("trace", "Trace", "Follow a concrete execution path through callers and callees",
        "e.g. \"What happens when a request hits /api/login?\""),
    ;

    companion object {
        fun fromId(id: String?): AnalysisMode = entries.firstOrNull { it.id == id } ?: UNDERSTAND
    }

    override fun toString(): String = displayName
}

data class QueryContext(
    val filePath: String? = null,
    val symbol: String? = null,
    val selectionStartLine: Int? = null,
    val selectionEndLine: Int? = null,
    val featureScope: FeatureScopeContext? = null,
) {
    fun summaryParts(): List<String> = buildList {
        filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
        symbol?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (selectionStartLine != null && selectionEndLine != null) {
            add("L$selectionStartLine-L$selectionEndLine")
        }
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
)

enum class TourState {
    INPUT,
    LOADING,
    OVERVIEW,
    TOUR_ACTIVE,
}
