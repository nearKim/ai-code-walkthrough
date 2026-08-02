package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class FlowPlannerService(project: Project) {

    private val providerService = project.service<LlmProviderService>()
    private val settings = project.service<CodeTourSettings>()
    private val engine = WalkthroughEngine(
        projectRoot = Path.of(requireNotNull(project.basePath) { "Project base path is not available" }),
        settings = settings::toWalkthroughSettings,
        providerFor = providerService::providerFor,
    )

    suspend fun mapFlow(
        question: String,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<MappingResult> = engine.mapFlow(
        question = question,
        mode = mode,
        queryContext = queryContext,
        followUpContext = followUpContext,
        featureScope = featureScope,
        onProgress = onProgress,
    )

    suspend fun answerStepQuestion(
        question: String,
        step: FlowStep,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<StepAnswerResult> = engine.answerStepQuestion(
        question = question,
        step = step,
        mode = mode,
        queryContext = queryContext,
        followUpContext = followUpContext,
        featureScope = featureScope,
        onProgress = onProgress,
    )

    fun cancel() = engine.cancel()
}
