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
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Service(Service.Level.PROJECT)
class GeminiApiService(project: Project) : JsonHttpProviderSupport(project) {

    override val provider: AiProvider = AiProvider.GEMINI_API
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsRepoGroundedWalkthrough = false,
    )

    override suspend fun query(
        prompt: String,
        promptKind: PromptKind,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = io {
        val apiKey = secrets.getApiKey(provider)
            ?: throw IllegalStateException("Gemini API key is not configured")
        onProgress?.invoke("Sending request to ${provider.displayName}...")

        val requestBody = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", PromptContract.buildSystemPrompt(promptKind, enableSemanticTools = false)) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
            })
        }

        val model = URLEncoder.encode(settings.state.geminiModel, StandardCharsets.UTF_8)
        val apiKeyParam = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKeyParam"))
            .timeout(Duration.ofSeconds(settings.state.requestTimeout.toLong()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Gemini API error ${response.statusCode()}: ${response.body().take(400)}")
        }

        val payload = json.parseToJsonElement(response.body()).jsonObject
        val content = (payload["candidates"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.let { it as? JsonArray }
            ?.firstNotNullOfOrNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.content?.trim()
            }
            ?: throw IllegalStateException("Gemini API returned no text content")

        ProviderResponse(content = content)
    }

    override suspend fun checkAvailability(): ProviderStatus = io {
        val configured = secrets.hasApiKey(provider)
        val message = if (configured) {
            "Gemini API configured (${settings.state.geminiModel})"
        } else {
            "Gemini API key missing"
        }
        ProviderStatus(provider, configured, message)
    }
}
