package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
