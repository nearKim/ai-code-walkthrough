package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.service.LlmProvider
import com.github.nearkim.aicodewalkthrough.service.ProjectFiles
import com.github.nearkim.aicodewalkthrough.service.PromptKind
import com.github.nearkim.aicodewalkthrough.service.ProviderCapabilities
import com.github.nearkim.aicodewalkthrough.service.ProviderResponse
import com.github.nearkim.aicodewalkthrough.service.ProviderStatus
import com.github.nearkim.aicodewalkthrough.service.WalkthroughEngine
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class WebApplicationTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `maps a tour and only serves source inside the repository`() = testApplication {
        val root = temporary.newFolder("repository").toPath()
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.kt"), "fun main() {\n    start()\n}\n")
        Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"sample\"\n")
        Files.writeString(root.resolve("app.py"), "class Application:\n    def run(self):\n        return 1\n")
        val settings = WebSettingsStore(root.resolve("settings/settings.json"))
        val provider = FakeProvider()
        val engine = WalkthroughEngine(root, settings::get) { provider }
        val session = WebSession(root, settings, engine)
        application {
            configureWebApplication(WebDependencies(session, settings, engine, ProjectFiles(root)))
        }

        try {
            val sample = client.post("/api/sample")
            assertEquals(HttpStatusCode.OK, sample.status)
            val samplePayload = sample.bodyAsText()
            val sampleSnapshot = Json.decodeFromString<SessionSnapshot>(samplePayload)
            assertEquals("OVERVIEW", sampleSnapshot.state)
            assertEquals(SampleWalkthrough.question, sampleSnapshot.question)
            assertEquals("sample-map", sampleSnapshot.flowMap?.entryStepId)
            assertTrue(samplePayload.contains("WalkthroughSample.mapSystem"))
            val sampleSource = client.get("/api/source?path=${SampleWalkthrough.sourcePath}")
            assertEquals(HttpStatusCode.OK, sampleSource.status)
            assertTrue(sampleSource.bodyAsText().contains("This source exists only to demonstrate"))
            assertTrue(sampleSource.bodyAsText().contains("class WalkthroughSample"))

            val mapping = client.post("/api/mapping") {
                contentType(ContentType.Application.Json)
                setBody("""{"question":"How does it start?","mode":"understand","provider":"codex_cli"}""")
            }
            assertEquals(HttpStatusCode.Accepted, mapping.status)

            var snapshot = Json.decodeFromString<SessionSnapshot>(mapping.bodyAsText())
            withTimeout(5_000) {
                while (snapshot.state == "LOADING") {
                    delay(10)
                    snapshot = Json.decodeFromString(client.get("/api/session").bodyAsText())
                }
            }
            assertEquals("OVERVIEW", snapshot.state)
            assertEquals("entry", snapshot.flowMap?.entryStepId)

            val tour = client.post("/api/tour") {
                contentType(ContentType.Application.Json)
                setBody("""{"action":"start"}""")
            }
            val active = Json.decodeFromString<SessionSnapshot>(tour.bodyAsText())
            assertEquals("TOUR_ACTIVE", active.state)
            assertEquals("entry", active.displayedStep?.id)

            val source = client.get("/api/source?path=src%2FMain.kt")
            assertEquals(HttpStatusCode.OK, source.status)
            assertTrue(source.bodyAsText().contains("fun main()"))

            val virtualSourceOutsideSample = client.get("/api/source?path=${SampleWalkthrough.sourcePath}")
            assertEquals(HttpStatusCode.NotFound, virtualSourceOutsideSample.status)

            val symbols = client.get("/api/symbols")
            assertEquals(HttpStatusCode.OK, symbols.status)
            assertTrue(symbols.bodyAsText().contains("\"n\":\"Application\""))

            val escaped = client.get("/api/source?path=..%2Foutside.txt")
            assertEquals(HttpStatusCode.NotFound, escaped.status)

            val rebound = client.get("/api/source?path=src%2FMain.kt") {
                headers.append(HttpHeaders.Host, "example.test")
            }
            assertEquals(HttpStatusCode.Forbidden, rebound.status)

            val event = coroutineScope {
                val pending = async(start = CoroutineStart.UNDISPATCHED) {
                    session.events.first { it.name == "session" }
                }
                session.cancelMapping()
                pending.await()
            }
            assertFalse(event.data.contains("\"displayed_step\":null"))
        } finally {
            session.close()
        }
    }

    private class FakeProvider : LlmProvider {
        override val provider = AiProvider.CODEX_CLI
        override val capabilities = ProviderCapabilities(supportsRepoGroundedWalkthrough = true)

        override suspend fun query(
            prompt: String,
            promptKind: PromptKind,
            onProgress: ((String) -> Unit)?,
        ): ProviderResponse {
            onProgress?.invoke("Mapped entrypoint")
            return ProviderResponse(
                Json.encodeToString(
                    LlmResponse(
                        type = "flow_map",
                        summary = "Application entrypoint.",
                        entryStepId = "entry",
                        terminalStepIds = listOf("entry"),
                        steps = listOf(
                            FlowStep(
                                id = "entry",
                                title = "Start the program",
                                filePath = "src/Main.kt",
                                startLine = 1,
                                endLine = 3,
                                explanation = "The process starts here.",
                                whyIncluded = "This is the executable entrypoint.",
                                lineAnnotations = listOf(LineAnnotation(2, 2, "The first application call.")),
                            ),
                        ),
                    ),
                ),
            )
        }

        override suspend fun checkAvailability() = ProviderStatus(provider, true, "Available")

        override fun cancel() = Unit
    }
}
