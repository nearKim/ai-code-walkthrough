package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CodexCliService(project: Project) : Disposable, LlmProvider {

    private val settings = project.service<CodeTourSettings>()
    private val delegate = CodexCliProvider(
        projectRoot = java.nio.file.Path.of(requireNotNull(project.basePath) { "Project base path is not available" }),
        settings = settings::toWalkthroughSettings,
    )

    override val provider: AiProvider
        get() = delegate.provider
    override val capabilities: ProviderCapabilities
        get() = delegate.capabilities

    override suspend fun query(
        prompt: String,
        promptKind: PromptKind,
        onProgress: ((String) -> Unit)?,
    ): ProviderResponse = delegate.query(prompt, promptKind, onProgress)

    override suspend fun checkAvailability(): ProviderStatus = delegate.checkAvailability()

    override fun cancel() = delegate.cancel()

    override fun dispose() = cancel()
}
