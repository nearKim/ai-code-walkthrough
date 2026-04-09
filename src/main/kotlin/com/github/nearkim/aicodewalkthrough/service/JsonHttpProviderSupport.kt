package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.github.nearkim.aicodewalkthrough.settings.ProviderSecretsService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.http.HttpClient
import java.time.Duration

abstract class JsonHttpProviderSupport(
    protected val project: Project,
) : LlmProvider {

    protected val settings get() = project.service<CodeTourSettings>()
    protected val secrets get() = project.service<ProviderSecretsService>()
    protected val json = Json { ignoreUnknownKeys = true }
    protected val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    protected fun buildUsageMetadata(
        promptTokens: Int? = null,
        completionTokens: Int? = null,
    ): ResponseMetadata? {
        if (promptTokens == null && completionTokens == null) return null
        return ResponseMetadata(
            durationMs = 0,
            costUsd = null,
            numTurns = 1,
            stepCount = 0,
            fileCount = 0,
        )
    }

    protected suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override fun cancel() {
        // java.net.http synchronous calls are not cancellable through this abstraction.
    }
}
