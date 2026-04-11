package com.github.nearkim.aicodewalkthrough.application.review

import com.github.nearkim.aicodewalkthrough.model.FeatureEntrypoint
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureWalkthroughContextFactoryTest {

    @Test
    fun `create builds feature scope and query context from feature path`() {
        val feature = RepositoryFeature(
            id = "feature-a",
            title = "Feature A",
            summary = "Handles feature A.",
            filePaths = listOf("src/App.kt"),
            ownedPaths = listOf("src/App.kt"),
            sharedPaths = listOf("src/Shared.kt"),
            entrypoints = listOf(
                FeatureEntrypoint(
                    id = "entry-1",
                    title = "Entrypoint",
                    filePath = "src/App.kt",
                    symbol = "run",
                    startLine = 10,
                    endLine = 20,
                ),
            ),
        )
        val path = FeaturePath(
            id = "path-1",
            title = "Primary path",
            summary = "Trace the main execution path.",
            promptSeed = "Trace the main execution path.",
            entryFilePath = "src/Feature.kt",
            entrySymbol = "Feature.run",
            filePaths = listOf("src/Feature.kt"),
            supportingSymbols = listOf("Feature.validate"),
            boundaryNotes = listOf("Stop at shared infrastructure."),
            mode = "trace",
        )

        val launch = FeatureWalkthroughContextFactory.create(feature, path)

        assertEquals("Trace the main execution path.", launch.question)
        assertEquals("feature-a", launch.featureScope.featureId)
        assertEquals(listOf("src/App.kt", "src/Feature.kt"), launch.featureScope.allowedFilePaths)
        assertEquals("src/Feature.kt", launch.queryContext.filePath)
        assertEquals("Feature.run", launch.queryContext.symbol)
        assertTrue(launch.featureScope.supportingSymbols.contains("Feature.validate"))
    }
}
