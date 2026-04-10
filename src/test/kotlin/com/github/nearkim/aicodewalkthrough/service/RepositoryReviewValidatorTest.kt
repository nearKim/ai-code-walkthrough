package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FeatureEntrypoint
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryFingerprint
import com.github.nearkim.aicodewalkthrough.model.RepositoryFinding
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RepositoryReviewValidatorTest {

    @Test
    fun `validate strips paths outside the project root and clamps evidence`() {
        val root = Files.createTempDirectory("repo-review-validator")
        try {
            Files.createDirectories(root.resolve("src"))
            Files.writeString(
                root.resolve("src/Feature.kt"),
                """
                fun feature() {
                    val value = 1
                    println(value)
                }
                """.trimIndent(),
            )

            val snapshot = RepositoryReviewSnapshot(
                summary = "Feature review",
                repoFingerprint = RepositoryFingerprint(repoRoot = root.toString()),
                features = listOf(
                    RepositoryFeature(
                        id = "feature-1",
                        title = "Feature One",
                        summary = "Handles the feature path.",
                        filePaths = listOf("src/Feature.kt", "../outside.kt"),
                        entrypoints = listOf(
                            FeatureEntrypoint(
                                id = "entry-1",
                                title = "Entrypoint",
                                filePath = "../outside.kt",
                                symbol = "feature",
                                startLine = 1,
                                endLine = 2,
                            ),
                        ),
                        paths = listOf(
                            FeaturePath(
                                id = "path-1",
                                title = "Primary path",
                                summary = "The main walkthrough.",
                                rationale = "This is the business path.",
                                promptSeed = "Trace the primary execution path.",
                                entrypointId = "entry-1",
                                focusFiles = listOf("src/Feature.kt", "../bad.kt"),
                                startingPoints = listOf(
                                    FeatureEntrypoint(
                                        id = "entry-1",
                                        title = "Entrypoint",
                                        filePath = "../outside.kt",
                                        symbol = "feature",
                                    ),
                                ),
                                walkthroughQuestion = "How does this feature execute?",
                            ),
                        ),
                        findings = listOf(
                            RepositoryFinding(
                                id = "finding-1",
                                title = "Risk",
                                summary = "Something to check.",
                                severity = "medium",
                                affectedFiles = listOf("src/Feature.kt", "../bad.kt"),
                                pathIds = listOf("path-1"),
                                evidence = listOf(
                                    EvidenceItem(
                                        kind = "line_range",
                                        label = "Feature code",
                                        filePath = "src/Feature.kt",
                                        startLine = 2,
                                        endLine = 99,
                                        text = "The important block.",
                                    ),
                                    EvidenceItem(
                                        kind = "note",
                                        label = "Outside",
                                        filePath = "../bad.kt",
                                        text = "Should be redirected to the feature file.",
                                    ),
                                ),
                            ),
                        ),
                        primaryPathId = "path-1",
                    ),
                ),
            )

            val result = RepositoryReviewValidator(root.toString()).validate(snapshot)

            val feature = result.snapshot.features.single()
            assertEquals(listOf("src/Feature.kt"), feature.filePaths)
            assertEquals("src/Feature.kt", feature.entrypoints.single().filePath)
            assertEquals(listOf("src/Feature.kt"), feature.paths.single().focusFiles)
            assertEquals(listOf("src/Feature.kt"), feature.findings.single().affectedFiles)
            assertEquals("src/Feature.kt", feature.findings.single().evidence.first().filePath)
            assertEquals(2, feature.findings.single().evidence.first().startLine)
            assertEquals(4, feature.findings.single().evidence.first().endLine)
            assertEquals("src/Feature.kt", feature.findings.single().evidence.last().filePath)
            assertTrue(result.notes.isNotEmpty())
            assertFalse(result.fileToFeatureIndex.byFilePath.isEmpty())
            assertEquals(listOf("feature-1"), result.fileToFeatureIndex.byFilePath["src/Feature.kt"])
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
