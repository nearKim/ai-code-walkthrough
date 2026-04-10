package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FeatureEntrypoint
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryFinding
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewFileFeatureIndex
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import java.nio.file.Files
import java.nio.file.Path

data class RepositoryReviewValidationResult(
    val snapshot: RepositoryReviewSnapshot,
    val notes: List<String> = emptyList(),
    val fileToFeatureIndex: RepositoryReviewFileFeatureIndex = RepositoryReviewFileFeatureIndex(),
)

class RepositoryReviewValidator(private val projectBasePath: String) {

    private val rootPath = Path.of(projectBasePath).toAbsolutePath().normalize()
    private val fileCache = mutableMapOf<Path, List<String>>()

    fun validate(snapshot: RepositoryReviewSnapshot): RepositoryReviewValidationResult {
        val notes = mutableListOf<String>()
        val fileIndex = linkedMapOf<String, MutableSet<String>>()

        val validatedFeatures = snapshot.features.mapNotNull { feature ->
            validateFeature(feature, notes, fileIndex)
        }

        if (validatedFeatures.isEmpty()) {
            throw IllegalStateException("Repository review did not yield any valid feature slices")
        }

        val crossCutting = snapshot.crossCuttingFindings.map { finding ->
            sanitizeFinding(
                finding = finding,
                defaultFilePath = validatedFeatures.firstOrNull()?.filePaths?.firstOrNull(),
                notes = notes,
            )
        }

        return RepositoryReviewValidationResult(
            snapshot = snapshot.copy(
                features = validatedFeatures,
                crossCuttingFindings = crossCutting,
            ),
            notes = notes.distinct(),
            fileToFeatureIndex = RepositoryReviewFileFeatureIndex(
                byFilePath = fileIndex.mapValues { (_, values) -> values.toList() },
            ),
        )
    }

    private fun validateFeature(
        feature: RepositoryFeature,
        notes: MutableList<String>,
        fileIndex: MutableMap<String, MutableSet<String>>,
    ): RepositoryFeature? {
        val sanitizedFilePaths = sanitizeRelativePaths(feature.filePaths, notes)
        val sanitizedOwnedPaths = sanitizeRelativePaths(feature.ownedPaths, notes)
        val sanitizedSharedPaths = sanitizeRelativePaths(feature.sharedPaths, notes)
        val sanitizedEntryHints = feature.entryHints.mapNotNull { sanitizeEntrypoint(it, sanitizedFilePaths.firstOrNull(), notes) }
        val sanitizedEntrypoints = feature.entrypoints.mapNotNull { sanitizeEntrypoint(it, sanitizedFilePaths.firstOrNull(), notes) }
        val sanitizedFindings = feature.findings.map { finding ->
            sanitizeFinding(finding, sanitizedFilePaths.firstOrNull(), notes)
        }
        val sanitizedPaths = if (feature.paths.isEmpty()) {
            listOf(
                FeaturePath(
                    id = stableId(feature.id, feature.name) + "-review",
                    title = "Primary review path",
                    summary = "Trace the main execution path and major risks in ${feature.name}.",
                    rationale = "Fallback review path generated because the model did not provide feature paths.",
                    promptSeed = "Review the ${feature.name} feature thoroughly. Trace the primary execution path, highlight the main risks, and stay bounded to this feature unless you need to note a boundary dependency.",
                    focusFiles = sanitizedFilePaths,
                    likelyEntryFiles = sanitizedFilePaths,
                    startingPoints = sanitizedEntrypoints.ifEmpty { sanitizedEntryHints },
                    mode = "review",
                    uncertain = sanitizedFilePaths.isEmpty(),
                ),
            )
        } else {
            feature.paths.map { path -> sanitizePath(path, feature.name, sanitizedFilePaths, notes) }
        }

        val sanitizedFeature = feature.copy(
            id = stableId(feature.id, feature.name),
            title = feature.title.ifBlank { feature.name.ifBlank { "Unnamed feature" } },
            summary = feature.summary.ifBlank { "Repository feature slice." },
            filePaths = sanitizedFilePaths,
            ownedPaths = sanitizedOwnedPaths,
            sharedPaths = sanitizedSharedPaths,
            entryHints = sanitizedEntryHints,
            entrypoints = sanitizedEntrypoints,
            findings = sanitizedFindings,
            paths = sanitizedPaths,
            uncertain = feature.uncertain || sanitizedFilePaths.isEmpty(),
            validationNote = buildValidationNote(
                feature,
                sanitizedFilePaths,
                sanitizedPaths,
                notes,
            ),
        )

        sanitizedFeature.filePaths.forEach { filePath ->
            fileIndex.getOrPut(filePath) { linkedSetOf() }.add(sanitizedFeature.id)
        }
        sanitizedFeature.paths.forEach { path ->
            path.filePaths.forEach { filePath ->
                fileIndex.getOrPut(filePath) { linkedSetOf() }.add(sanitizedFeature.id)
            }
        }
        sanitizedFeature.findings.forEach { finding ->
            finding.affectedFiles.forEach { filePath ->
                fileIndex.getOrPut(filePath) { linkedSetOf() }.add(sanitizedFeature.id)
            }
        }

        return sanitizedFeature
    }

    private fun sanitizePath(
        path: FeaturePath,
        featureName: String,
        fallbackFiles: List<String>,
        notes: MutableList<String>,
    ): FeaturePath {
        val sanitizedFiles = sanitizeRelativePaths(path.filePaths.ifEmpty { path.focusFiles }, notes)
            .ifEmpty { fallbackFiles }
        val startingPoints = path.startingPoints.mapNotNull { sanitizeEntrypoint(it, sanitizedFiles.firstOrNull(), notes) }
        val promptSeed = path.promptSeed.ifBlank {
            "Trace and review the ${path.title.ifBlank { featureName }} path. Stay bounded to this feature and explain the important hops."
        }
        return path.copy(
            id = stableId(path.id, path.title.ifBlank { featureName }),
            title = path.title.ifBlank { "Recommended path" },
            summary = path.summary.ifBlank { "Trace the most relevant path through $featureName." },
            rationale = path.rationale?.takeIf { it.isNotBlank() } ?: "Feature-bounded walkthrough path.",
            promptSeed = promptSeed,
            focusFiles = sanitizedFiles,
            likelyEntryFiles = sanitizedFiles,
            startingPoints = startingPoints,
            boundaryNote = path.boundaryNote?.takeIf { it.isNotBlank() },
            entryFilePath = sanitizedFiles.firstOrNull() ?: path.entryFilePath?.takeIf { it.isNotBlank() },
            entrySymbol = path.entrySymbol?.takeIf { it.isNotBlank() },
            supportingSymbols = path.supportingSymbols.distinct(),
            mode = path.mode?.takeIf { it.isNotBlank() } ?: "review",
            uncertain = path.uncertain || sanitizedFiles.isEmpty(),
        )
    }

    private fun sanitizeFinding(
        finding: RepositoryFinding,
        defaultFilePath: String?,
        notes: MutableList<String>,
    ): RepositoryFinding {
        val evidence = finding.evidence.map { item ->
            sanitizeEvidence(item, defaultFilePath, notes)
        }
        val affectedFiles = sanitizeRelativePaths(finding.affectedFiles, notes)
        return finding.copy(
            title = finding.title.ifBlank { "Repository finding" },
            summary = finding.summary.ifBlank { "The model produced a finding without a summary." },
            evidence = evidence,
            affectedFiles = affectedFiles,
            uncertain = finding.uncertain || affectedFiles.isEmpty(),
        )
    }

    private fun sanitizeEntrypoint(
        entrypoint: FeatureEntrypoint,
        fallbackFilePath: String?,
        notes: MutableList<String>,
    ): FeatureEntrypoint? {
        val targetPath = sanitizeRelativePath(entrypoint.filePath, notes)
            ?: fallbackFilePath
            ?: return null
        val lines = readFileLines(targetPath) ?: return null
        val fileLineCount = lines.size
        val start = entrypoint.startLine?.coerceIn(1, fileLineCount)
        val end = entrypoint.endLine?.coerceIn(start ?: 1, fileLineCount)
        return entrypoint.copy(
            title = entrypoint.title.ifBlank { entrypoint.label.orEmpty().ifBlank { entrypoint.symbol.orEmpty().ifBlank { targetPath } } },
            filePath = targetPath,
            startLine = start,
            endLine = end,
        )
    }

    private fun sanitizeEvidence(
        item: EvidenceItem,
        defaultFilePath: String?,
        notes: MutableList<String>,
    ): EvidenceItem {
        val targetPath = sanitizeRelativePath(item.filePath, notes) ?: defaultFilePath
        if (targetPath == null) {
            return item.copy(filePath = null, startLine = null, endLine = null)
        }
        val lines = readFileLines(targetPath) ?: return item.copy(filePath = null, startLine = null, endLine = null)
        val fileLineCount = lines.size
        val start = item.startLine?.coerceIn(1, fileLineCount)
        val requestedEnd = item.endLine ?: start
        val end = requestedEnd?.coerceIn(start ?: 1, fileLineCount)
        return item.copy(
            filePath = targetPath,
            startLine = start,
            endLine = end,
        )
    }

    private fun sanitizeRelativePaths(paths: List<String>, notes: MutableList<String>): List<String> {
        return paths.mapNotNull { sanitizeRelativePath(it, notes) }.distinct()
    }

    private fun sanitizeRelativePath(rawPath: String?, notes: MutableList<String>): String? {
        val candidate = rawPath?.trim().orEmpty()
        if (candidate.isBlank()) return null

        val resolved = rootPath.resolve(candidate).normalize()
        if (!resolved.startsWith(rootPath)) {
            notes += "Dropped path outside project root: $candidate"
            return null
        }

        if (!Files.exists(resolved)) {
            notes += "Dropped missing path: $candidate"
            return null
        }

        return rootPath.relativize(resolved).toString().replace('\\', '/')
    }

    private fun stableId(rawId: String, fallbackName: String): String {
        val seed = rawId.ifBlank { fallbackName }
        return seed
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "feature" }
    }

    private fun readFileLines(relativePath: String): List<String>? {
        val resolvedPath = rootPath.resolve(relativePath).normalize()
        return fileCache.getOrPut(resolvedPath) {
            if (!Files.exists(resolvedPath)) {
                return null
            }
            Files.readAllLines(resolvedPath)
        }
    }

    private fun buildValidationNote(
        feature: RepositoryFeature,
        sanitizedFilePaths: List<String>,
        sanitizedPaths: List<FeaturePath>,
        notes: MutableList<String>,
    ): String? {
        val messages = buildList {
            if (feature.filePaths.size != sanitizedFilePaths.size) {
                add("Some feature files were dropped during validation.")
            }
            if (sanitizedPaths.any { it.uncertain }) {
                add("One or more paths were marked uncertain after sanitization.")
            }
            addAll(notes)
        }.distinct()
        return messages.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
