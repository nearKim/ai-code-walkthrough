package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.RepositoryFinding
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StepValidatorTest {

    @Test
    fun `validate reanchors steps to the resolved symbol range`() {
        val root = Files.createTempDirectory("step-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(
                sourceDir.resolve("Handler.kt"),
                """
                class Handler {
                    fun handleRequest() {
                        val normalized = input.trim()
                        if (normalized.isEmpty()) return
                        process(normalized)
                    }
                }
                """.trimIndent(),
            )

            val validated = StepValidator(root.toString()).validate(
                listOf(
                    FlowStep(
                        id = "reanchor",
                        title = "Handle request",
                        filePath = "src/Handler.kt",
                        symbol = "handleRequest",
                        startLine = 1,
                        endLine = 1,
                        explanation = "Explains the request boundary.",
                        whyIncluded = "It is the entrypoint for the request.",
                    ),
                ),
            ).single()

            assertEquals(2, validated.startLine)
            assertEquals(6, validated.endLine)
            assertFalse(validated.uncertain)
            assertTrue(validated.validationNote!!.contains("Re-anchored to symbol handleRequest"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate marks weakly grounded steps as uncertain`() {
        val root = Files.createTempDirectory("step-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(
                sourceDir.resolve("Small.kt"),
                """
                fun first() = 1
                fun second() = 2
                fun third() = 3
                """.trimIndent(),
            )

            val validated = StepValidator(root.toString()).validate(
                listOf(
                    FlowStep(
                        id = "fallback",
                        title = "Fallback range",
                        filePath = "src/Small.kt",
                        symbol = "missingSymbol",
                        startLine = 0,
                        endLine = 99,
                        explanation = "The model guessed at a range.",
                        whyIncluded = "It looked like the next step in the path.",
                        confidence = "high",
                    ),
                ),
            ).single()

            assertEquals(1, validated.startLine)
            assertEquals(3, validated.endLine)
            assertTrue(validated.uncertain)
            assertEquals("uncertain", validated.confidence)
            val validationNote = validated.validationNote ?: error("Expected validator note")
            assertTrue(validationNote.contains("Symbol missingSymbol was not found"))
            assertTrue(validationNote.contains("Clamped the range to L1-L3"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate keeps the strongest duplicate when ranges collide`() {
        val root = Files.createTempDirectory("step-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(
                sourceDir.resolve("Handler.kt"),
                """
                class Handler {
                    fun handleRequest() {
                        process()
                    }
                }
                """.trimIndent(),
            )

            val validated = StepValidator(root.toString()).validate(
                listOf(
                    FlowStep(
                        id = "weak",
                        title = "Weak guess",
                        filePath = "src/Handler.kt",
                        startLine = 2,
                        endLine = 4,
                        explanation = "A guessed range.",
                        whyIncluded = "The model inferred it.",
                        uncertain = true,
                    ),
                    FlowStep(
                        id = "grounded",
                        title = "Grounded symbol",
                        filePath = "src/Handler.kt",
                        symbol = "handleRequest",
                        startLine = 1,
                        endLine = 1,
                        explanation = "The concrete handler method.",
                        whyIncluded = "It is the direct method call target.",
                        evidence = listOf(
                            EvidenceItem(kind = "usage", label = "Direct call site")
                        ),
                    ),
                ),
            )

            assertEquals(1, validated.size)
            assertEquals("grounded", validated.single().id)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate flow map synthesizes a grounded path when edges are missing`() {
        val root = Files.createTempDirectory("step-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(
                sourceDir.resolve("Pipeline.kt"),
                """
                class Pipeline {
                    fun start() {
                        process()
                    }

                    fun process() {
                        finish()
                    }

                    fun finish() {}
                }
                """.trimIndent(),
            )

            val validator = StepValidator(root.toString())
            val validated = validator.validate(
                FlowMap(
                    summary = "Simple pipeline.",
                    steps = listOf(
                        FlowStep(
                            id = "start",
                            title = "Start",
                            filePath = "src/Pipeline.kt",
                            symbol = "start",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Starts the pipeline.",
                            whyIncluded = "This is the user-visible entrypoint.",
                        ),
                        FlowStep(
                            id = "process",
                            title = "Process",
                            filePath = "src/Pipeline.kt",
                            symbol = "process",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Continues the work.",
                            whyIncluded = "It is the next important hop.",
                        ),
                        FlowStep(
                            id = "finish",
                            title = "Finish",
                            filePath = "src/Pipeline.kt",
                            symbol = "finish",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Terminates the pipeline.",
                            whyIncluded = "The path ends here.",
                        ),
                    ),
                ),
            )

            assertEquals("start", validated.entryStepId)
            assertEquals(listOf("finish"), validated.terminalStepIds)
            assertEquals(2, validated.edges.size)
            assertTrue(validated.edges.all { it.kind == "implied_order" })
            assertTrue(validated.edges.all { it.uncertain })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate clamps annotations and edge call sites to the current step`() {
        val root = Files.createTempDirectory("step-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(
                sourceDir.resolve("Handler.kt"),
                """
                class Handler {
                    fun handleRequest() {
                        helper()
                    }

                    fun helper() {}
                }
                """.trimIndent(),
            )

            val validator = StepValidator(root.toString())
            val validated = validator.validate(
                FlowMap(
                    summary = "Handler path.",
                    entryStepId = "handle",
                    terminalStepIds = listOf("helper"),
                    steps = listOf(
                        FlowStep(
                            id = "handle",
                            title = "Handle request",
                            filePath = "src/Handler.kt",
                            symbol = "handleRequest",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Handles the request.",
                            whyIncluded = "This is the entrypoint.",
                            lineAnnotations = listOf(
                                LineAnnotation(startLine = 99, endLine = 101, text = "Out of bounds annotation"),
                            ),
                        ),
                        FlowStep(
                            id = "helper",
                            title = "Helper",
                            filePath = "src/Handler.kt",
                            symbol = "helper",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Helper body.",
                            whyIncluded = "The path reaches the helper.",
                        ),
                    ),
                    edges = listOf(
                        StepEdge(
                            id = "edge-1",
                            fromStepId = "handle",
                            toStepId = "helper",
                            kind = "call",
                            rationale = "helper() is invoked from handleRequest().",
                            callSiteFilePath = "src/Handler.kt",
                            callSiteStartLine = 50,
                            callSiteEndLine = 51,
                            evidence = listOf(EvidenceItem(kind = "symbol", label = "helper() call")),
                        ),
                    ),
                ),
            )

            val handleStep = validated.steps.first { it.id == "handle" }
            assertEquals(4, handleStep.lineAnnotations.single().startLine)
            assertEquals(4, handleStep.lineAnnotations.single().endLine)
            assertTrue(handleStep.uncertain)
            val edge = validated.edges.single()
            assertEquals(4, edge.callSiteStartLine)
            assertEquals(4, edge.callSiteEndLine)
            assertNotNull(edge.validationNote)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate sanitizes potential bug findings and keeps them step scoped`() {
        val root = Files.createTempDirectory("step-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(
                sourceDir.resolve("Handler.kt"),
                """
                class Handler {
                    fun handleRequest() {
                        helper()
                    }

                    fun helper() {}
                }
                """.trimIndent(),
            )

            val validated = StepValidator(root.toString()).validate(
                listOf(
                    FlowStep(
                        id = "handle",
                        title = "Handle request",
                        filePath = "src/Handler.kt",
                        symbol = "handleRequest",
                        startLine = 1,
                        endLine = 1,
                        explanation = "Handles the request.",
                        whyIncluded = "This is the entrypoint.",
                        potentialBugs = listOf(
                            RepositoryFinding(
                                id = "bug-1",
                                title = "Missing guard before helper call",
                                summary = "The helper call is unconditional and may run on invalid state.",
                                severity = "high",
                                affectedFiles = listOf(" "),
                                evidence = listOf(
                                    EvidenceItem(
                                        kind = "line_range",
                                        label = "unguarded helper call",
                                        startLine = 0,
                                        endLine = 99,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ).single()

            val finding = validated.potentialBugs.single()
            assertEquals(listOf("src/Handler.kt"), finding.affectedFiles)
            assertEquals(1, finding.evidence.single().startLine)
            assertEquals(7, finding.evidence.single().endLine)
            assertTrue(finding.uncertain)
            assertTrue(validated.validationNote!!.contains("Potential bug"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
