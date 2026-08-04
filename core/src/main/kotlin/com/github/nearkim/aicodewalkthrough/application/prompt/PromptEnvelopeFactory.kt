package com.github.nearkim.aicodewalkthrough.application.prompt

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.service.ProviderCapabilities
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
        mechanicalSymbolInventory: JsonObject? = null,
    ): String {
        val stepLimit = if (
            mode == AnalysisMode.UNDERSTAND && question == AnalysisMode.DEFAULT_CODEBASE_QUESTION
        ) {
            minOf(maxSteps, 12)
        } else {
            maxSteps
        }
        return buildJsonObject {
            put("mode", mode.id)
            put("max_steps", stepLimit)
            put("question", question)
            put("grounding_capabilities", groundingCapabilities(providerCapabilities))
            mechanicalSymbolInventory?.let { put("mechanical_symbol_inventory", it) }
            if (mode == AnalysisMode.UNDERSTAND) {
                put("learning_strategy", learningStrategy(question))
            }
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

    private fun learningStrategy(question: String) = buildJsonObject {
        val wholeCodebase = question == AnalysisMode.DEFAULT_CODEBASE_QUESTION
        put("architecture_first", true)
        put(
            "breadth",
            if (wholeCodebase) "whole_codebase" else "question_scoped",
        )
        put("progression", "purpose_to_system_map_to_relationships_to_representative_paths_to_code")
        put("detail_policy", "progressive_disclosure")
        put("report_coverage_gaps", true)
        put("verified_facts_only", true)
        put("persisted_mechanical_facts", true)
        if (wholeCodebase) {
            put("behavior_search", true)
            put("delegation_policy", "bounded_parallel_when_available")
            put("symbolic_execution_policy", "consume_verified_tool_results_when_available")
        }
    }

    private fun queryContextPayload(context: QueryContext) = buildJsonObject {
        context.filePath?.let { put("file_path", it) }
        context.symbol?.let { put("symbol", it) }
        context.selectionStartLine?.let { put("selection_start_line", it) }
        context.selectionEndLine?.let { put("selection_end_line", it) }
    }

    private fun followUpContextPayload(followUp: FollowUpContext, json: Json) = buildJsonObject {
        put("original_question", followUp.originalQuestion)
        followUp.activeStepId?.let { put("active_step_id", it) }
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
