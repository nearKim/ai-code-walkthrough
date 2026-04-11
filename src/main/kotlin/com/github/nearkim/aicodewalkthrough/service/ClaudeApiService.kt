package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.PROJECT)
class ClaudeApiService(project: Project) : JsonHttpProviderSupport(project) {

    override val provider: AiProvider = AiProvider.CLAUDE_API
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsRepoGroundedWalkthrough = false,
    )

    override suspend fun query(
        prompt: String,
        promptKind: PromptKind,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = io {
        val apiKey = secrets.getApiKey(provider)
            ?: throw IllegalStateException("Claude API key is not configured")
        onProgress?.invoke("Sending request to ${provider.displayName}...")

        val requestBody = buildJsonObject {
            put("model", settings.state.claudeApiModel)
            put("max_tokens", 4096)
            put("system", PromptContract.buildSystemPrompt(promptKind, enableSemanticTools = false))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(settings.state.requestTimeout.toLong()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Claude API error ${response.statusCode()}: ${response.body().take(400)}")
        }

        val payload = json.parseToJsonElement(response.body()).jsonObject
        val content = (payload["content"] as? JsonArray)
            ?.firstNotNullOfOrNull { item ->
                val obj = item.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "text") {
                    obj["text"]?.jsonPrimitive?.content?.trim()
                } else {
                    null
                }
            }
            ?: throw IllegalStateException("Claude API returned no text content")

        ProviderResponse(content = content)
    }

    override suspend fun checkAvailability(): ProviderStatus = io {
        val configured = secrets.hasApiKey(provider)
        val message = if (configured) {
            "Claude API configured (${settings.state.claudeApiModel})"
        } else {
            "Claude API key missing"
        }
        ProviderStatus(provider, configured, message)
    }
}
