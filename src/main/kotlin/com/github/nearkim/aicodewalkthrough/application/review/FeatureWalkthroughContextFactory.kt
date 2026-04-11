package com.github.nearkim.aicodewalkthrough.application.review

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureEntrypoint
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature

data class FeatureWalkthroughLaunch(
    val question: String,
    val mode: AnalysisMode,
    val featureScope: FeatureScopeContext,
    val queryContext: QueryContext,
)

object FeatureWalkthroughContextFactory {

    fun create(feature: RepositoryFeature, path: FeaturePath): FeatureWalkthroughLaunch {
        val entryPoint = resolveEntrypoint(feature, path)
        val allowedFiles = (
            feature.filePaths +
                path.filePaths +
                listOfNotNull(path.entryFilePath, entryPoint?.filePath)
            ).distinct()
        val featureScope = FeatureScopeContext(
            featureId = feature.id,
            featureName = feature.name,
            featureSummary = feature.summary,
            featureReviewSummary = feature.reviewSummary ?: feature.overallRisk,
            allowedFilePaths = allowedFiles,
            selectedPathId = path.id,
            selectedPathName = path.title,
            selectedPathDescription = path.description,
            promptSeed = path.promptSeed,
            ownedPaths = feature.ownedPaths.ifEmpty { feature.filePaths },
            sharedPaths = feature.sharedPaths.ifEmpty { path.filePaths.filterNot { it in feature.filePaths } },
            supportingSymbols = (
                listOfNotNull(path.entrySymbol, entryPoint?.symbol) +
                    path.supportingSymbols +
                    feature.entrypoints.mapNotNull { it.symbol }
                ).distinct(),
            boundaryNotes = path.boundaryNotes,
        )
        return FeatureWalkthroughLaunch(
            question = path.promptSeed,
            mode = AnalysisMode.fromId(path.defaultMode),
            featureScope = featureScope,
            queryContext = QueryContext(
                filePath = path.entryFilePath ?: entryPoint?.filePath ?: allowedFiles.firstOrNull(),
                symbol = path.entrySymbol ?: entryPoint?.symbol,
                selectionStartLine = entryPoint?.startLine,
                selectionEndLine = entryPoint?.endLine,
                featureScope = featureScope,
            ),
        )
    }

    private fun resolveEntrypoint(feature: RepositoryFeature, path: FeaturePath): FeatureEntrypoint? {
        val allEntrypoints = path.startingPoints + feature.entrypoints + feature.entryHints
        return path.entrypointId
            ?.takeIf { it.isNotBlank() }
            ?.let { entrypointId -> allEntrypoints.firstOrNull { it.id == entrypointId } }
            ?: allEntrypoints.firstOrNull()
    }
}
