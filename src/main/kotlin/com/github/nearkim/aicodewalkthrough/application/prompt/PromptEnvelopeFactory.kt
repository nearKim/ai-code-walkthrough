package com.github.nearkim.aicodewalkthrough.application.prompt

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.service.ProviderCapabilities
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object PromptEnvelopeFactory {

    fun buildWalkthroughPrompt(
        question: String,
        mode: AnalysisMode,
        maxSteps: Int,
        queryContext: QueryContext?,
        followUpContext: FollowUpContext?,
        featureScope: FeatureScopeContext?,
        providerCapabilities: ProviderCapabilities,
        json: Json,
    ): String {
        return buildJsonObject {
            put("mode", mode.id)
            put("max_steps", maxSteps)
            put("question", question)
            put("grounding_capabilities", groundingCapabilities(providerCapabilities))
            queryContext?.let { put("query_context", queryContextPayload(it)) }
            followUpContext?.let { put("follow_up_context", followUpContextPayload(it, json)) }
            featureScope?.let { put("feature_scope", featureScopePayload(it)) }
        }.toString()
    }

    fun buildStepQuestionPrompt(
        question: String,
        step: FlowStep,
        mode: AnalysisMode,
        queryContext: QueryContext?,
        followUpContext: FollowUpContext?,
        featureScope: FeatureScopeContext?,
        providerCapabilities: ProviderCapabilities,
        json: Json,
    ): String {
        return buildJsonObject {
            put("request_type", "step_question")
            put("mode", mode.id)
            put("question", question)
            put("current_step", json.parseToJsonElement(json.encodeToString(step)))
            put("grounding_capabilities", groundingCapabilities(providerCapabilities))
            queryContext?.let { put("query_context", queryContextPayload(it)) }
            followUpContext?.let { put("follow_up_context", followUpContextPayload(it, json)) }
            featureScope?.let { put("feature_scope", featureScopePayload(it)) }
        }.toString()
    }

    private fun groundingCapabilities(providerCapabilities: ProviderCapabilities) = buildJsonObject {
        put("repo_grounded_walkthrough", providerCapabilities.supportsRepoGroundedWalkthrough)
        put("semantic_navigation_hints", providerCapabilities.supportsSemanticNavigationHints)
        put("delegated_analysis_hints", providerCapabilities.supportsDelegatedAnalysisHints)
    }

    private fun queryContextPayload(context: QueryContext) = buildJsonObject {
        context.filePath?.let { put("file_path", it) }
        context.symbol?.let { put("symbol", it) }
        context.selectionStartLine?.let { put("selection_start_line", it) }
        context.selectionEndLine?.let { put("selection_end_line", it) }
        context.selectedText?.takeIf { it.isNotBlank() }?.let { put("selected_text", it) }
        context.diffSummary?.takeIf { it.isNotBlank() }?.let { put("diff_summary", it) }
        context.failingTestName?.takeIf { it.isNotBlank() }?.let { put("failing_test_name", it) }
        put("invoked_from_cursor", context.invokedFromCursor)
    }

    private fun followUpContextPayload(followUp: FollowUpContext, json: Json) = buildJsonObject {
        put("original_question", followUp.originalQuestion)
        followUp.activeStepId?.let { put("active_step_id", it) }
        if (followUp.clarificationHistory.isNotEmpty()) {
            put("clarification_history", buildJsonArray {
                followUp.clarificationHistory.forEach { exchange ->
                    add(buildJsonObject {
                        put("question", exchange.question)
                        put("answer", exchange.answer)
                    })
                }
            })
        }
        put("previous_flow_map", json.parseToJsonElement(json.encodeToString(followUp.previousFlowMap)))
    }

    private fun featureScopePayload(scope: FeatureScopeContext) = buildJsonObject {
        put("feature_id", scope.featureId)
        put("feature_name", scope.featureName)
        scope.featureSummary?.takeIf { it.isNotBlank() }?.let { put("feature_summary", it) }
        scope.featureReviewSummary?.takeIf { it.isNotBlank() }?.let { put("feature_review_summary", it) }
        put("allowed_file_paths", buildJsonArray {
            val allowedPaths = scope.allowedFilePaths.ifEmpty { (scope.ownedPaths + scope.sharedPaths).distinct() }
            allowedPaths.forEach { add(JsonPrimitive(it)) }
        })
        put("owned_file_paths", buildJsonArray {
            scope.ownedPaths.forEach { add(JsonPrimitive(it)) }
        })
        put("supporting_file_paths", buildJsonArray {
            scope.sharedPaths.forEach { add(JsonPrimitive(it)) }
        })
        scope.selectedPathId?.takeIf { it.isNotBlank() }?.let { put("selected_path_id", it) }
        scope.selectedPathName?.takeIf { it.isNotBlank() }?.let { put("selected_path_name", it) }
        scope.selectedPathDescription?.takeIf { it.isNotBlank() }?.let { put("selected_path_description", it) }
        scope.promptSeed?.takeIf { it.isNotBlank() }?.let { put("prompt_seed", it) }
        put("supporting_symbols", buildJsonArray {
            scope.supportingSymbols.forEach { add(JsonPrimitive(it)) }
        })
        put("boundary_notes", buildJsonArray {
            scope.boundaryNotes.forEach { add(JsonPrimitive(it)) }
        })
    }
}
