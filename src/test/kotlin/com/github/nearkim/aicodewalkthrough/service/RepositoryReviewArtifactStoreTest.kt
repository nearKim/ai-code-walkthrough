package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.FeatureEntrypoint
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryFingerprint
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import com.github.nearkim.aicodewalkthrough.model.ReviewRunMetadata
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RepositoryReviewArtifactStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `save writes the review artifact bundle`() {
        val root = Files.createTempDirectory("repo-review-store")
        try {
            Files.createDirectories(root.resolve("src"))
            Files.writeString(
                root.resolve("src/Feature.kt"),
                """
                fun entrypoint() {
                    println("hello")
                }
                """.trimIndent(),
            )

            val snapshot = RepositoryReviewSnapshot(
                generatedAtMs = 42L,
                summary = "Repository review",
                repoFingerprint = RepositoryFingerprint(
                    repoRoot = root.toString(),
                    gitHead = "abc123",
                    trackedFileCount = 1,
                    fileDigest = "digest-123",
                    generatedAtMs = 42L,
                ),
                features = listOf(
                    RepositoryFeature(
                        id = "feature-1",
                        title = "Feature One",
                        summary = "The core feature.",
                        filePaths = listOf("src/Feature.kt"),
                        entrypoints = listOf(
                            FeatureEntrypoint(
                                id = "entry-1",
                                title = "Entrypoint",
                                filePath = "src/Feature.kt",
                            ),
                        ),
                        paths = listOf(
                            FeaturePath(
                                id = "path-1",
                                title = "Primary path",
                                summary = "How the feature works.",
                                rationale = "This is the main business path.",
                                promptSeed = "How does this feature execute?",
                                entrypointId = "entry-1",
                                focusFiles = listOf("src/Feature.kt"),
                                walkthroughQuestion = "How does this feature execute?",
                            ),
                        ),
                    ),
                ),
            )

            val store = ReviewArtifactStore(root.toString())
            val metadata = store.save(snapshot)

            val reviewRoot = store.reviewRoot()
            assertTrue(Files.exists(reviewRoot.resolve(".gitignore")))
            assertTrue(Files.exists(reviewRoot.resolve("manifest.json")))
            assertTrue(Files.exists(reviewRoot.resolve("latest-snapshot.json")))
            assertTrue(Files.exists(reviewRoot.resolve("file-to-feature-index.json")))
            assertTrue(Files.exists(reviewRoot.resolve("features/feature-1.json")))
            assertTrue(Files.exists(reviewRoot.resolve("features/feature-1.md")))
            assertTrue(Files.exists(reviewRoot.resolve("snapshots/${metadata.runId}.json")))

            val manifest = json.decodeFromString(
                ReviewRunMetadata.serializer(),
                Files.readString(reviewRoot.resolve("manifest.json")),
            )
            assertEquals(metadata.runId, manifest.runId)
            assertEquals(1, manifest.featureArtifacts.size)
            assertEquals("feature-1", manifest.featureArtifacts.single().id)
            assertEquals(
                "features/feature-1.json",
                reviewRoot.relativize(reviewRoot.resolve(manifest.featureArtifacts.single().jsonPath)).toString(),
            )

            val reloadedSnapshot = store.loadLatestSnapshot()
            assertNotNull(reloadedSnapshot)
            assertEquals("Repository review", reloadedSnapshot!!.summary)
            assertEquals(1, reloadedSnapshot.features.size)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
