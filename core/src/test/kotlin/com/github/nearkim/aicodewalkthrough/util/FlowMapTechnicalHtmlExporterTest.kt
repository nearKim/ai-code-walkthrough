package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.ArchitectureComponent
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.ComponentRelationship
import com.github.nearkim.aicodewalkthrough.model.DiagramSection
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowMapTechnicalHtmlExporterTest {

    @Test
    fun `build renders grounded system component and feature views`() {
        val flowMap = FlowMap(
            summary = "The request crosses an authenticated application boundary.",
            architecture = CodebaseArchitecture(
                systemName = "Example service",
                systemPurpose = "Authenticate requests before serving protected data.",
                components = listOf(
                    component("api", "HTTP API", "Accepts requests."),
                    component("identity", "Identity service", "Validates credentials."),
                ),
                relationships = listOf(
                    ComponentRelationship(
                        id = "api-identity",
                        fromComponentId = "api",
                        toComponentId = "identity",
                        kind = "calls",
                        description = "The API asks the identity service to validate credentials.",
                    ),
                ),
            ),
            diagramSections = listOf(
                DiagramSection(
                    id = "system-overview",
                    title = "System overview",
                    componentIds = listOf("api", "identity"),
                ),
                DiagramSection(
                    id = "component-map",
                    title = "Component map",
                    componentIds = listOf("api", "identity"),
                ),
                DiagramSection(
                    id = "authentication",
                    title = "Authentication",
                    summary = "Follow credential validation into the protected request path.",
                    componentIds = listOf("api", "identity"),
                    stepIds = listOf("validate"),
                ),
            ),
            steps = listOf(
                FlowStep(
                    id = "validate",
                    title = "Validate credentials",
                    filePath = "src/auth.py",
                    symbol = "validate_credentials",
                    startLine = 11,
                    endLine = 19,
                    explanation = "Checks the supplied credential before the protected path continues.",
                    whyIncluded = "It is the authentication boundary.",
                ),
            ),
        )

        val html = FlowMapTechnicalHtmlExporter.build("How does auth work?", flowMap, null)

        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("System overview"))
        assertTrue(html.contains("Component map"))
        assertTrue(html.contains("Authentication"))
        assertTrue(html.contains("src/auth.py:11-19"))
        assertTrue(html.contains("marker-end=\"url(#arrow)\""))
        assertTrue(html.contains("<a href=\"#feature-0\">Authentication</a>"))
        assertTrue(!html.contains("<a href=\"#feature-0\">System overview</a>"))
    }

    @Test
    fun `build escapes source derived text`() {
        val html = FlowMapTechnicalHtmlExporter.build(
            question = "<script>",
            flowMap = FlowMap(
                summary = "<unsafe & summary>",
                steps = listOf(
                    FlowStep(
                        id = "step",
                        title = "<unsafe>",
                        filePath = "src/<unsafe>.py",
                        startLine = 1,
                        endLine = 1,
                        explanation = "<unsafe>",
                        whyIncluded = "Needed.",
                    ),
                ),
            ),
            metadata = null,
        )

        assertTrue(html.contains("&lt;unsafe &amp; summary&gt;"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(!html.contains("<script>"))
    }

    private fun component(id: String, name: String, responsibility: String) = ArchitectureComponent(
        id = id,
        name = name,
        kind = "application",
        responsibility = responsibility,
        keyPaths = listOf("src/$id.py"),
    )
}
