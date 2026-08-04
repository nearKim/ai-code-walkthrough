package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.ArchitectureComponent
import com.github.nearkim.aicodewalkthrough.model.ArchitectureResponsibility
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.ComponentRelationship
import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LearningStage
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
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
    fun `validate grounds architecture relationships and learning stages`() {
        val root = Files.createTempDirectory("architecture-validator")
        try {
            val sourceDir = root.resolve("src")
            Files.createDirectories(sourceDir)
            Files.writeString(sourceDir.resolve("App.kt"), "fun start() = runService()\n")
            Files.writeString(sourceDir.resolve("Service.kt"), "fun runService() = Unit\n")

            val validated = StepValidator(root.toString()).validate(
                FlowMap(
                    mode = "understand",
                    summary = "Application delegates to a service.",
                    architecture = CodebaseArchitecture(
                        systemPurpose = "Demonstrate an application boundary.",
                        components = listOf(
                            ArchitectureComponent(
                                id = "application",
                                name = "Application",
                                kind = "application",
                                responsibility = "Owns the entrypoint.",
                                responsibilities = listOf(
                                    ArchitectureResponsibility(
                                        id = "start-application",
                                        title = "Start the application",
                                        description = "Transfers control from the entrypoint to the service.",
                                        evidence = listOf(
                                            EvidenceItem(
                                                kind = "function",
                                                label = "start",
                                                filePath = "src/App.kt",
                                                startLine = 1,
                                            ),
                                        ),
                                        collaboratorComponentIds = listOf("service", "invented"),
                                        relationshipIds = listOf("application-service", "application-invented"),
                                    ),
                                    ArchitectureResponsibility(
                                        id = "invented-responsibility",
                                        title = "Invent behavior",
                                        description = "This mapping is not grounded.",
                                        evidence = listOf(
                                            EvidenceItem(
                                                kind = "class",
                                                label = "Missing",
                                                filePath = "src/Missing.kt",
                                                startLine = 1,
                                            ),
                                        ),
                                        collaboratorComponentIds = listOf("invented"),
                                    ),
                                ),
                                keyPaths = listOf("src/App.kt", "../outside.kt"),
                                evidence = listOf(
                                    EvidenceItem(
                                        kind = "symbol",
                                        label = "start",
                                        filePath = "src/App.kt",
                                        startLine = 1,
                                    ),
                                ),
                            ),
                            ArchitectureComponent(
                                id = "service",
                                name = "Service",
                                kind = "domain",
                                responsibility = "Runs the core behavior.",
                                keyPaths = listOf("src/Service.kt"),
                            ),
                            ArchitectureComponent(
                                id = "invented",
                                name = "Invented",
                                kind = "integration",
                                responsibility = "Does not exist.",
                                keyPaths = listOf("src/Missing.kt"),
                            ),
                        ),
                        relationships = listOf(
                            ComponentRelationship(
                                id = "application-service",
                                fromComponentId = "application",
                                toComponentId = "service",
                                kind = "calls",
                                description = "The entrypoint calls the service.",
                                evidence = listOf(
                                    EvidenceItem(
                                        kind = "reference",
                                        label = "runService call",
                                        filePath = "src/App.kt",
                                        startLine = 1,
                                    ),
                                ),
                            ),
                            ComponentRelationship(
                                id = "application-invented",
                                fromComponentId = "application",
                                toComponentId = "invented",
                                kind = "calls",
                                description = "This target is not grounded.",
                            ),
                        ),
                    ),
                    learningPath = listOf(
                        LearningStage(
                            id = "orientation",
                            title = "Orientation",
                            goal = "Understand the application boundary.",
                            componentIds = listOf("application", "invented"),
                            stepIds = listOf("start", "missing"),
                        ),
                        LearningStage(
                            id = "core",
                            title = "Core behavior",
                            goal = "Follow the service call.",
                            componentIds = listOf("service"),
                            stepIds = listOf("start", "service"),
                        ),
                    ),
                    steps = listOf(
                        FlowStep(
                            id = "start",
                            title = "Start",
                            filePath = "src/App.kt",
                            symbol = "start",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Starts the app.",
                            whyIncluded = "It is the entrypoint.",
                        ),
                        FlowStep(
                            id = "service",
                            title = "Run service",
                            filePath = "src/Service.kt",
                            symbol = "runService",
                            startLine = 1,
                            endLine = 1,
                            explanation = "Runs the service.",
                            whyIncluded = "It owns the core behavior.",
                        ),
                    ),
                ),
            )

            val architecture = validated.architecture ?: error("Expected a validated architecture")
            assertEquals(listOf("application", "service"), architecture.components.map { it.id })
            val application = architecture.components.first()
            assertEquals(listOf("src/App.kt"), application.keyPaths)
            assertTrue(application.uncertain)
            val responsibility = application.responsibilities.single()
            assertEquals(listOf("service"), responsibility.collaboratorComponentIds)
            assertEquals(listOf("application-service"), responsibility.relationshipIds)
            assertEquals("src/App.kt", responsibility.evidence.single().filePath)
            assertTrue(responsibility.uncertain)
            assertEquals(1, architecture.relationships.size)
            assertEquals("application-service", architecture.relationships.single().id)
            assertEquals(
                listOf("start", "service"),
                validated.learningPath.flatMap { it.stepIds },
            )
            assertEquals(listOf("application"), validated.learningPath.first().componentIds)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate rejects a step path that escapes the project`() {
        val root = Files.createTempDirectory("step-validator-root")
        val outside = Files.createTempFile("step-validator-outside", ".kt")
        try {
            Files.writeString(outside, "fun outside() = Unit\n")
            val escapedPath = root.relativize(outside).toString()

            val validated = StepValidator(root.toString()).validate(
                listOf(
                    FlowStep(
                        id = "outside",
                        title = "Outside",
                        filePath = escapedPath,
                        symbol = "outside",
                        startLine = 1,
                        endLine = 1,
                        explanation = "Must not be read.",
                        whyIncluded = "The model returned an unsafe path.",
                    ),
                ),
            ).single()

            assertTrue(validated.broken)
            assertTrue(validated.breakReason!!.contains("File not found"))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `mechanical architecture replaces fabricated architecture and code claims`() {
        val root = Files.createTempDirectory("step-validator-mechanical")
        Files.writeString(
            root.resolve("app.py"),
            "class Runner:\n    def run(self):\n        return 1\n",
        )
        val classEvidence = EvidenceItem("class", "Runner", "app.py", 1, 3, "Owns run().")
        val methodEvidence = EvidenceItem("method", "Runner.run", "app.py", 2, 3, "Contains 1 return statement.")
        val mechanical = CodebaseArchitecture(
            systemPurpose = "Verified Python structure.",
            components = listOf(
                ArchitectureComponent(
                    id = "python-root",
                    name = "Root modules",
                    responsibility = "Contains one class.",
                    responsibilities = listOf(
                        ArchitectureResponsibility(
                            id = "runner",
                            title = "Owns run().",
                            description = "Owns run().",
                            evidence = listOf(classEvidence, methodEvidence),
                        ),
                    ),
                    keyPaths = listOf("app.py"),
                    keySymbols = listOf("Runner"),
                    evidence = listOf(classEvidence),
                ),
            ),
        )
        val fabricated = CodebaseArchitecture(
            systemPurpose = "Invented purpose.",
            components = listOf(
                ArchitectureComponent(
                    id = "ghost",
                    name = "Ghost service",
                    responsibility = "Does something unsupported.",
                    keyPaths = listOf("app.py"),
                ),
            ),
        )

        val validated = StepValidator(root.toString(), mechanical).validate(
            FlowMap(
                summary = "Invented summary.",
                architecture = fabricated,
                steps = listOf(
                    FlowStep(
                        id = "real",
                        title = "Invented behavior",
                        filePath = "app.py",
                        symbol = "Runner.run",
                        startLine = 1,
                        endLine = 1,
                        explanation = "Invented explanation.",
                        whyIncluded = "Teach the verified method.",
                    ),
                    FlowStep(
                        id = "ghost",
                        title = "Ghost behavior",
                        filePath = "app.py",
                        symbol = "Ghost.run",
                        startLine = 1,
                        endLine = 1,
                        explanation = "Invented explanation.",
                        whyIncluded = "The model invented it.",
                    ),
                ),
            ),
        )

        assertEquals(listOf("Root modules"), validated.architecture!!.components.map { it.name })
        val real = validated.steps.first { it.id == "real" }
        assertEquals("Runner.run", real.title)
        assertEquals("Contains 1 return statement.", real.explanation)
        assertEquals("verified", real.confidence)
        assertTrue(validated.steps.first { it.id == "ghost" }.broken)
    }

}
