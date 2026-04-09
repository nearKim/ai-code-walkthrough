package com.github.nearkim.aicodewalkthrough.model

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
    RISK("risk", "Risk"),
    COMMENT("comment", "Comment"),
    ;

    companion object {
        fun fromId(id: String?): AnalysisMode = entries.firstOrNull { it.id == id } ?: UNDERSTAND
    }

    override fun toString(): String = displayName
}

enum class CursorActionType(
    val prompt: String,
) {
    EXPLAIN("Explain this symbol."),
    WHY_HERE("Why is this here?"),
    WHAT_BREAKS("What could break if this changes?"),
    WRITE_COMMENT("Write a concise code review comment for this."),
    SUGGEST_TESTS("Suggest the most important tests for this."),
    TRACE_USAGE("Trace the important callers and callees for this."),
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
    val invokedFromCursor: Boolean = false,
) {
    fun summaryParts(): List<String> = buildList {
        filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
        symbol?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (selectionStartLine != null && selectionEndLine != null) {
            add("L$selectionStartLine-L$selectionEndLine")
        }
        failingTestName?.takeIf { it.isNotBlank() }?.let { add("failing test: $it") }
    }
}

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
