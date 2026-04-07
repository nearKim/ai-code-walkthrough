package com.github.nearkim.aicodewalkthrough.model

data class ClarificationExchange(
    val question: String,
    val answer: String,
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
