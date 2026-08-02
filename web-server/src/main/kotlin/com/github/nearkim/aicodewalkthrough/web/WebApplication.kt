package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import com.github.nearkim.aicodewalkthrough.service.ProjectFiles
import com.github.nearkim.aicodewalkthrough.service.WalkthroughEngine
import com.github.nearkim.aicodewalkthrough.util.FlowMapMarkdownExporter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondResource
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.serialization.kotlinx.json.json
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

data class WebDependencies(
    val session: WebSession,
    val settings: WebSettingsStore,
    val engine: WalkthroughEngine,
    val projectFiles: ProjectFiles,
)

fun Application.configureWebApplication(dependencies: WebDependencies) {
    val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
    install(ContentNegotiation) { json(json) }
    install(SSE)
    install(StatusPages) {
        exception<BadRequestException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid request"))
        }
        exception<SerializationException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(error.message ?: "Invalid JSON"))
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.host().lowercase() !in LOOPBACK_HOSTS) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Only loopback hosts are allowed."))
            finish()
        }
    }

    routing {
        get("/api/session") {
            call.respond(dependencies.session.snapshot())
        }

        get("/api/providers") {
            val statuses = coroutineScope {
                AiProvider.entries.map { provider ->
                    async {
                        val status = dependencies.engine.checkAvailability(provider)
                        ProviderStatusResponse(
                            id = provider.id,
                            name = provider.displayName,
                            available = status.available && status.walkthroughSupported,
                            message = status.message,
                        )
                    }
                }.awaitAll()
            }
            call.respond(statuses)
        }

        get("/api/settings") {
            call.respond(SettingsResponse(dependencies.settings.get()))
        }

        put("/api/settings") {
            if (!call.requireJson()) return@put
            val updated = try {
                dependencies.settings.save(call.receive<WalkthroughSettings>())
            } catch (error: IOException) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Could not save settings: ${error.message}"))
                return@put
            }
            call.respond(SettingsResponse(updated))
        }

        post("/api/mapping") {
            if (!call.requireJson()) return@post
            val request = call.receive<MappingRequest>()
            val mode = AnalysisMode.entries.firstOrNull { it.id == request.mode }
            if (mode == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown analysis mode: ${request.mode}"))
                return@post
            }
            val provider = AiProvider.entries.firstOrNull { it.id == request.provider }
            if (provider == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown provider: ${request.provider}"))
                return@post
            }
            val question = mode.resolveQuestion(request.question)
            if (question == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A question is required for ${mode.displayName}."))
                return@post
            }
            call.respond(HttpStatusCode.Accepted, dependencies.session.startMapping(question, mode, provider))
        }

        delete("/api/mapping") {
            call.respond(dependencies.session.cancelMapping())
        }

        post("/api/tour") {
            if (!call.requireJson()) return@post
            val request = call.receive<TourRequest>()
            dependencies.session.tour(request.action, request.stepId).fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Invalid tour action")) },
            )
        }

        post("/api/step-answer") {
            if (!call.requireJson()) return@post
            val question = call.receive<StepAnswerRequest>().question.trim()
            if (question.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A step question is required."))
                return@post
            }
            dependencies.session.answerCurrentStep(question).fold(
                onSuccess = { call.respond(it) },
                onFailure = { call.respond(HttpStatusCode.BadRequest, ErrorResponse(it.message ?: "Could not answer step")) },
            )
        }

        get("/api/source") {
            val requested = call.request.queryParameters["path"]
            if (requested.isNullOrBlank() || requested.isAbsolutePath()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("A relative project path is required."))
                return@get
            }
            readSource(dependencies.projectFiles, requested).fold(
                onSuccess = { call.respond(it) },
                onFailure = { error ->
                    val status = if (error is SourceTooLargeException) {
                        HttpStatusCode.PayloadTooLarge
                    } else {
                        HttpStatusCode.NotFound
                    }
                    call.respond(status, ErrorResponse(error.message ?: "Source file is unavailable"))
                },
            )
        }

        get("/api/export") {
            val snapshot = dependencies.session.snapshot()
            val flowMap = snapshot.flowMap
            if (flowMap == null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("No walkthrough is available to export."))
                return@get
            }
            call.respondText(
                FlowMapMarkdownExporter.build(
                    question = snapshot.question,
                    flowMap = flowMap,
                    metadata = snapshot.metadata,
                    activeStepId = snapshot.displayedStep?.id,
                ),
                ContentType.parse("text/markdown; charset=UTF-8"),
            )
        }

        sse("/api/events") {
            send(ServerSentEvent(event = "session", data = json.encodeToString(dependencies.session.snapshot())))
            dependencies.session.events.collect { event ->
                send(ServerSentEvent(event = event.name, data = event.data))
            }
        }

        get("/") {
            call.respondResource("web/index.html")
        }
        staticResources("/assets", "web/assets")
    }
}

private suspend fun ApplicationCall.requireJson(): Boolean {
    if (request.contentType().withoutParameters() == ContentType.Application.Json) return true
    respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("Content-Type must be application/json."))
    return false
}

private fun String.isAbsolutePath(): Boolean = try {
    Path.of(this).isAbsolute
} catch (_: InvalidPathException) {
    true
}

private fun readSource(projectFiles: ProjectFiles, requested: String): Result<SourceResponse> {
    val resolved = projectFiles.resolveExisting(requested, requireRegularFile = true)
        ?: return Result.failure(IllegalArgumentException("Source file does not exist inside the repository."))
    val size = Files.size(resolved)
    if (size > MAX_SOURCE_BYTES) {
        return Result.failure(SourceTooLargeException("Source file exceeds the 5 MiB browser limit."))
    }
    val bytes = Files.readAllBytes(resolved)
    if (bytes.any { it == 0.toByte() }) {
        return Result.failure(IllegalArgumentException("Binary files cannot be displayed."))
    }
    val content = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        return Result.failure(IllegalArgumentException("Only UTF-8 source files can be displayed."))
    }
    val normalized = projectFiles.normalizeExisting(requested, requireRegularFile = true) ?: requested
    return Result.success(SourceResponse(normalized, content))
}

private class SourceTooLargeException(message: String) : IllegalArgumentException(message)

private const val MAX_SOURCE_BYTES = 5L * 1024L * 1024L
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")
