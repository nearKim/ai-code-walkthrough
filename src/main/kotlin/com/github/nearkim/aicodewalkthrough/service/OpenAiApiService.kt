package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
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
class OpenAiApiService(project: Project) : JsonHttpProviderSupport(project) {

    override val provider: AiProvider = AiProvider.CHATGPT_API
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsRepoGroundedWalkthrough = false,
    )

    override suspend fun query(
        prompt: String,
        promptKind: PromptKind,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = io {
        val apiKey = secrets.getApiKey(provider)
            ?: throw IllegalStateException("OpenAI API key is not configured")
        onProgress?.invoke("Sending request to ${provider.displayName}...")

        val requestBody = buildJsonObject {
            put("model", settings.state.openAiModel)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", PromptContract.buildSystemPrompt(promptKind, enableSemanticTools = false))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = HttpRequest.newBuilder()
            .uri(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(settings.state.requestTimeout.toLong()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("OpenAI API error ${response.statusCode()}: ${response.body().take(400)}")
        }

        val payload = json.parseToJsonElement(response.body()).jsonObject
        val content = payload["choices"]
            ?.jsonObjectOrNullAt(0)
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?: throw IllegalStateException("OpenAI API returned no assistant content")

        ProviderResponse(content = content)
    }

    override suspend fun checkAvailability(): ProviderStatus = io {
        val configured = secrets.hasApiKey(provider)
        val message = if (configured) {
            "ChatGPT API configured (${settings.state.openAiModel})"
        } else {
            "ChatGPT API key missing"
        }
        ProviderStatus(provider, configured, message)
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNullAt(index: Int): kotlinx.serialization.json.JsonObject? {
    val array = this as? kotlinx.serialization.json.JsonArray ?: return null
    return array.getOrNull(index)?.jsonObject
}
