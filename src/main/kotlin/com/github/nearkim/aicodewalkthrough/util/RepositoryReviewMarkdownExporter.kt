package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryFinding
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RepositoryReviewMarkdownExporter {

    fun build(snapshot: RepositoryReviewSnapshot): String = buildSnapshotMarkdown(snapshot)

    fun buildSnapshotMarkdown(snapshot: RepositoryReviewSnapshot): String = buildString {
        appendLine("# Repository Review")
        appendLine()
        appendLine(snapshot.reviewSummary?.takeIf { it.isNotBlank() } ?: snapshot.summary)

        snapshot.repositorySummary?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Repository Summary")
            appendLine()
            appendLine(it)
        }

        snapshot.overallAssessment?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Overall Assessment")
            appendLine()
            appendLine(it)
        }

        snapshot.overallRisk?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Overall Risk")
            appendLine()
            appendLine(it)
        }

        appendLine()
        appendLine("## Metadata")
        appendLine()
        appendLine("- Generated: ${formatTimestamp(snapshot.generatedAtMs)}")
        snapshot.providerName?.takeIf { it.isNotBlank() }?.let { appendLine("- Provider: $it") }
        snapshot.repoFingerprint?.gitHead?.takeIf { it.isNotBlank() }?.let { appendLine("- Git head: $it") }
        snapshot.repoFingerprint?.gitBranch?.takeIf { it.isNotBlank() }?.let { appendLine("- Git branch: $it") }
        appendLine("- Dirty worktree: ${snapshot.repoFingerprint?.gitDirty ?: false}")
        appendLine("- Feature slices: ${snapshot.features.size}")
        appendLine("- Cross-cutting findings: ${snapshot.crossCuttingFindings.size}")

        if (snapshot.crossCuttingFindings.isNotEmpty()) {
            appendLine()
            appendLine("## Cross-Cutting Findings")
            appendLine()
            snapshot.crossCuttingFindings.forEach { finding ->
                appendFindingLine(finding)
            }
        }

        appendLine()
        appendLine("## Features")
        snapshot.features.forEach { feature ->
            appendFeatureSummary(feature)
        }
    }.trimEnd()

    fun buildFeature(feature: RepositoryFeature): String = buildFeatureMarkdown(feature)

    fun buildFeatureMarkdown(feature: RepositoryFeature): String = buildString {
        appendLine("# ${feature.name}")
        appendLine()
        appendLine(feature.summary)

        feature.businessValue?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Business Value")
            appendLine()
            appendLine(it)
        }

        feature.whyImportant?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Why Important")
            appendLine()
            appendLine(it)
        }

        feature.reviewSummary?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Review Summary")
            appendLine()
            appendLine(it)
        }

        feature.overallRisk?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Overall Risk")
            appendLine()
            appendLine(it)
        }

        if (feature.filePaths.isNotEmpty()) {
            appendLine()
            appendLine("## Files")
            appendLine()
            feature.filePaths.forEach { appendLine("- `$it`") }
        }

        if (feature.ownedPaths.isNotEmpty()) {
            appendLine()
            appendLine("## Owned Paths")
            appendLine()
            feature.ownedPaths.forEach { appendLine("- `$it`") }
        }

        if (feature.sharedPaths.isNotEmpty()) {
            appendLine()
            appendLine("## Shared Paths")
            appendLine()
            feature.sharedPaths.forEach { appendLine("- `$it`") }
        }

        if (feature.entrypoints.isNotEmpty() || feature.entryHints.isNotEmpty()) {
            appendLine()
            appendLine("## Entrypoints")
            appendLine()
            (feature.entrypoints.ifEmpty { feature.entryHints }).forEach { entrypoint ->
                val filePath = entrypoint.filePath.takeIf { it.isNotBlank() } ?: "unknown"
                val label = entrypoint.title.ifBlank { entrypoint.label.orEmpty().ifBlank { entrypoint.symbol.orEmpty() } }
                appendLine("- ${label.ifBlank { "Entrypoint" }} @ `$filePath`")
            }
        }

        if (feature.paths.isNotEmpty()) {
            appendLine()
            appendLine("## Recommended Paths")
            appendLine()
            feature.paths.forEach { path ->
                appendLine("### ${path.name}")
                appendLine()
                appendLine(path.description)
                appendLine()
                appendLine("- Mode: ${path.defaultMode ?: "review"}")
                appendLine("- Prompt seed: ${path.promptSeed}")
                path.walkthroughQuestion?.takeIf { it.isNotBlank() }?.let {
                    appendLine("- Walkthrough question: $it")
                }
                if (path.filePaths.isNotEmpty()) {
                    appendLine("- Files: ${path.filePaths.joinToString(", ")}")
                }
                if (path.startingPoints.isNotEmpty()) {
                    appendLine("- Starting points: ${path.startingPoints.joinToString(", ") { point -> point.filePath }}")
                }
                path.boundaryNote?.takeIf { it.isNotBlank() }?.let {
                    appendLine("- Boundary note: $it")
                }
                if (path.rationale?.isNotBlank() == true) {
                    appendLine("- Rationale: ${path.rationale}")
                }
            }
        }

        if (feature.findings.isNotEmpty()) {
            appendLine()
            appendLine("## Findings")
            appendLine()
            feature.findings.forEach { finding ->
                appendFindingLine(finding)
            }
        }
    }.trimEnd()

    private fun StringBuilder.appendFeatureSummary(feature: RepositoryFeature) {
        appendLine()
        appendLine("### ${feature.name}")
        appendLine()
        appendLine(feature.summary)
        feature.reviewSummary?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(it)
        }
        if (feature.findings.isNotEmpty()) {
            appendLine()
            feature.findings.forEach { finding ->
                appendFindingLine(finding)
            }
        }
        if (feature.paths.isNotEmpty()) {
            appendLine()
            appendLine("Paths:")
            feature.paths.forEach { path ->
                appendLine("- ${path.name}: ${path.description}")
            }
        }
    }

    private fun StringBuilder.appendFindingLine(finding: RepositoryFinding) {
        val prefix = buildList {
            finding.severity.takeIf { it.isNotBlank() }?.let { add(it) }
            finding.category?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" / ")
        appendLine("- ${finding.title}${if (prefix.isNotBlank()) " [$prefix]" else ""}: ${finding.summary}")
    }

    private fun formatTimestamp(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        return formatter.format(Date(epochMs))
    }
}
