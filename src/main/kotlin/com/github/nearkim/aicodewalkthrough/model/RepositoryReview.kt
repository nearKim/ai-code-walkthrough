package com.github.nearkim.aicodewalkthrough.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CodePointer(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("end_line") val endLine: Int? = null,
    val symbol: String? = null,
    val label: String? = null,
)

@Serializable
data class FeatureEntrypoint(
    val id: String = "",
    val title: String = "",
    @SerialName("file_path") val filePath: String = "",
    val symbol: String? = null,
    val label: String? = null,
    @SerialName("start_line") val startLine: Int? = null,
    @SerialName("end_line") val endLine: Int? = null,
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
) {
    val name: String
        get() = title.ifBlank { label.orEmpty() }
}

@Serializable
data class RepositoryFinding(
    val id: String,
    val title: String,
    val summary: String,
    val severity: String,
    @SerialName("risk_type") val category: String? = null,
    @SerialName("affected_files") val affectedFiles: List<String> = emptyList(),
    @SerialName("related_path_ids") val pathIds: List<String> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList(),
    @SerialName("suggested_action") val suggestedAction: String? = null,
    @SerialName("test_gap") val testGap: String? = null,
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
) {
    val findingType: String?
        get() = category

    val riskType: String?
        get() = category
}

@Serializable
data class FeaturePath(
    val id: String,
    val title: String,
    @SerialName("description") val summary: String,
    val rationale: String? = null,
    @SerialName("default_mode") val mode: String? = null,
    @SerialName("prompt_seed") val promptSeed: String,
    @SerialName("entrypoint_id") val entrypointId: String? = null,
    @SerialName("file_paths") val filePaths: List<String> = emptyList(),
    @SerialName("focus_files") val focusFiles: List<String> = emptyList(),
    @SerialName("likely_entry_files") val likelyEntryFiles: List<String> = emptyList(),
    @SerialName("starting_points") val startingPoints: List<FeatureEntrypoint> = emptyList(),
    @SerialName("boundary_notes") val boundaryNotes: List<String> = emptyList(),
    @SerialName("boundary_note") val boundaryNote: String? = null,
    @SerialName("walkthrough_question") val walkthroughQuestion: String? = null,
    @SerialName("entry_file_path") val entryFilePath: String? = null,
    @SerialName("entry_symbol") val entrySymbol: String? = null,
    @SerialName("supporting_symbols") val supportingSymbols: List<String> = emptyList(),
    val evidence: List<EvidenceItem> = emptyList(),
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val broken: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
) {
    val name: String
        get() = title

    val description: String
        get() = summary

    val defaultMode: String?
        get() = mode
}

@Serializable
data class RepositoryFeature(
    val id: String,
    @SerialName("name") val title: String,
    val summary: String,
    val category: String? = null,
    @SerialName("business_value") val businessValue: String? = null,
    @SerialName("why_this_matters") val whyImportant: String? = null,
    @SerialName("file_paths") val filePaths: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    @SerialName("owned_paths") val ownedPaths: List<String> = emptyList(),
    @SerialName("shared_paths") val sharedPaths: List<String> = emptyList(),
    @SerialName("entry_hints") val entryHints: List<FeatureEntrypoint> = emptyList(),
    val entrypoints: List<FeatureEntrypoint> = emptyList(),
    val paths: List<FeaturePath> = emptyList(),
    val findings: List<RepositoryFinding> = emptyList(),
    @SerialName("suggested_tests") val suggestedTests: List<SuggestedTest> = emptyList(),
    @SerialName("review_summary") val reviewSummary: String? = null,
    @SerialName("overall_risk") val overallRisk: String? = null,
    @SerialName("primary_path_id") val primaryPathId: String? = null,
    val notes: List<String> = emptyList(),
    val uncertain: Boolean = false,
    @kotlinx.serialization.Transient val validationNote: String? = null,
) {
    val name: String
        get() = title

    val files: List<String>
        get() = filePaths

    val whyThisMatters: String?
        get() = whyImportant
}

@Serializable
data class RepositoryFingerprint(
    @SerialName("repo_root") val repoRoot: String? = null,
    @SerialName("git_head") val gitHead: String? = null,
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("git_dirty") val gitDirty: Boolean = false,
    @SerialName("tracked_file_count") val trackedFileCount: Int = 0,
    @SerialName("file_digest") val fileDigest: String? = null,
    @SerialName("generated_at_ms") val generatedAtMs: Long = System.currentTimeMillis(),
) {
    val gitRevision: String?
        get() = gitHead

    val dirtyWorktree: Boolean
        get() = gitDirty

    val generatedAtEpochMs: Long
        get() = generatedAtMs
}

@Serializable
data class RepositoryReviewSnapshot(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val summary: String,
    @SerialName("review_summary") val reviewSummary: String? = null,
    @SerialName("repository_summary") val repositorySummary: String? = null,
    @SerialName("overall_assessment") val overallAssessment: String? = null,
    @SerialName("overall_risk") val overallRisk: String? = null,
    @SerialName("cross_cutting_findings") val crossCuttingFindings: List<RepositoryFinding> = emptyList(),
    val features: List<RepositoryFeature> = emptyList(),
    @SerialName("repo_fingerprint") val repoFingerprint: RepositoryFingerprint? = null,
    @SerialName("analysis_trace") val analysisTrace: AnalysisTrace? = null,
    @SerialName("generated_at_ms") val generatedAtMs: Long = System.currentTimeMillis(),
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
) {
    val featureSlices: List<RepositoryFeature>
        get() = features

    val fingerprint: RepositoryFingerprint?
        get() = repoFingerprint

    val generatedAtEpochMs: Long
        get() = generatedAtMs

    val providerLabel: String?
        get() = providerName
}

@Serializable
data class ReviewRunFeatureArtifact(
    val id: String,
    val title: String,
    @SerialName("json_path") val jsonPath: String,
    @SerialName("markdown_path") val markdownPath: String,
    @SerialName("file_paths") val filePaths: List<String> = emptyList(),
    @SerialName("path_count") val pathCount: Int = 0,
    @SerialName("finding_count") val findingCount: Int = 0,
)

@Serializable
data class RepositoryReviewFileFeatureIndex(
    @SerialName("by_file_path") val byFilePath: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class ReviewRunMetadata(
    @SerialName("run_id") val runId: String,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("generated_at_ms") val generatedAtMs: Long = System.currentTimeMillis(),
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    @SerialName("repo_fingerprint") val repoFingerprint: RepositoryFingerprint? = null,
    @SerialName("snapshot_path") val snapshotPath: String? = null,
    @SerialName("latest_snapshot_path") val latestSnapshotPath: String? = null,
    @SerialName("summary_path") val summaryPath: String? = null,
    @SerialName("file_to_feature_index_path") val fileToFeatureIndexPath: String? = null,
    @SerialName("feature_artifacts") val featureArtifacts: List<ReviewRunFeatureArtifact> = emptyList(),
    @SerialName("feature_count") val featureCount: Int = 0,
    @SerialName("cross_cutting_finding_count") val crossCuttingFindingCount: Int = 0,
)

@Serializable
data class RepositoryReviewResponse(
    val type: String,
    val summary: String? = null,
    @SerialName("review_summary") val reviewSummary: String? = null,
    @SerialName("repository_summary") val repositorySummary: String? = null,
    @SerialName("overall_assessment") val overallAssessment: String? = null,
    @SerialName("overall_risk") val overallRisk: String? = null,
    @SerialName("cross_cutting_findings") val crossCuttingFindings: List<RepositoryFinding>? = null,
    val features: List<RepositoryFeature>? = null,
    @SerialName("analysis_trace") val analysisTrace: AnalysisTrace? = null,
) {
    fun toSnapshot(
        id: String,
        fingerprint: RepositoryFingerprint?,
        providerId: String,
        providerName: String,
    ): RepositoryReviewSnapshot? {
        if (type != "repository_review" || summary == null || features == null) return null
        return RepositoryReviewSnapshot(
            id = id,
            summary = summary,
            reviewSummary = reviewSummary,
            repositorySummary = repositorySummary,
            overallAssessment = overallAssessment,
            overallRisk = overallRisk,
            crossCuttingFindings = crossCuttingFindings ?: emptyList(),
            features = features,
            repoFingerprint = fingerprint,
            analysisTrace = analysisTrace,
            providerId = providerId,
            providerName = providerName,
        )
    }
}

@Serializable
data class RepositoryInventoryResponse(
    val type: String,
    val summary: String? = null,
    val features: List<RepositoryFeature>? = null,
    @SerialName("analysis_trace") val analysisTrace: AnalysisTrace? = null,
)

typealias RepositoryReviewEvidence = EvidenceItem
typealias ReviewEntrypoint = FeatureEntrypoint
typealias ReviewFinding = RepositoryFinding
typealias RepositoryReviewFinding = RepositoryFinding
typealias FeatureSlice = RepositoryFeature
typealias FeatureWalkthroughPath = FeaturePath
