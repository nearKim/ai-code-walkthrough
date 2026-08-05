package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.application.prompt.PromptEnvelopeFactory
import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisTrace
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FeatureScopeContext
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.LlmResponse
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

data class MappingResult(
    val response: LlmResponse,
    val metadata: ResponseMetadata?,
)

data class StepAnswerResult(
    val answer: StepAnswer,
    val metadata: ResponseMetadata?,
)

class WalkthroughEngine(
    private val projectRoot: Path,
    private val settings: () -> WalkthroughSettings,
    private val analysisRoot: Path = MechanicalSymbolAnalyzer.defaultAnalysisRoot(),
    private val providerFor: (AiProvider) -> LlmProvider,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val activeProvider = AtomicReference<LlmProvider?>()
    private val latestSymbolInventory = AtomicReference<MechanicalSymbolInventory?>()

    suspend fun mapFlow(
        question: String,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        providerOverride: AiProvider? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<MappingResult> = withProvider(providerOverride) { provider ->
        onProgress?.invoke("Checking for a mechanical symbol analyzer...")
        val symbolInventory = MechanicalSymbolAnalyzer.analyze(projectRoot, json, analysisRoot)
            ?: throw IllegalStateException("Only Python target repositories are currently supported.")
        latestSymbolInventory.set(symbolInventory)
        onProgress?.invoke(
            if (symbolInventory.cacheHit) {
                "Reused persisted Python analysis for ${symbolInventory.filesScanned} files and ${symbolInventory.symbolCount} symbols."
            } else {
                "Indexed ${symbolInventory.filesScanned} Python files and ${symbolInventory.symbolCount} symbols with Python AST."
            },
        )
        val prompt = PromptEnvelopeFactory.buildWalkthroughPrompt(
            question = question,
            mode = mode,
            maxSteps = settings().maxSteps,
            queryContext = queryContext,
            followUpContext = followUpContext,
            featureScope = featureScope,
            providerCapabilities = provider.capabilities,
            json = json,
            mechanicalSymbolInventory = symbolInventory.payload,
        )
        val providerResponse = provider.query(prompt, PromptKind.WALKTHROUGH, onProgress)
        val response = decodeResponse(providerResponse.content)
        val validatedResponse = if (response.type == "flow_map" && response.steps != null) {
            val flowMap = response.toFlowMap()?.copy(mode = mode.id)
                ?: return@withProvider Result.failure(
                    IllegalStateException("Unexpected flow map response from LLM"),
                )
            val mechanicalArchitecture = decodeMechanicalArchitecture(symbolInventory.payload)
                ?: return@withProvider Result.failure(
                    IllegalStateException("Python analyzer omitted verified architecture"),
                )
            val validated = StepValidator(projectRoot.toString(), mechanicalArchitecture).validate(flowMap)
            val enrichedInventory = MechanicalSymbolAnalyzer.enrich(
                inventory = symbolInventory,
                projectRoot = projectRoot,
                steps = validated.steps.filterNot(FlowStep::broken),
                semanticToolResults = providerResponse.toolResults,
                json = json,
                analysisRoot = analysisRoot,
            )
            latestSymbolInventory.set(enrichedInventory)
            val verified = addToolEvidence(validated, enrichedInventory, providerResponse.toolResults)
            response.copy(
                mode = mode.id,
                summary = verified.architecture?.systemPurpose ?: response.summary,
                steps = verified.steps,
                architecture = verified.architecture,
                diagramSections = verified.diagramSections,
                learningPath = verified.learningPath,
                entryStepId = verified.entryStepId,
                terminalStepIds = verified.terminalStepIds,
                edges = verified.edges,
                analysisTrace = verified.analysisTrace,
            )
        } else {
            response
        }
        Result.success(MappingResult(validatedResponse, buildMetadata(providerResponse.metadata, validatedResponse)))
    }

    suspend fun answerStepQuestion(
        question: String,
        step: FlowStep,
        mode: AnalysisMode = AnalysisMode.UNDERSTAND,
        queryContext: QueryContext? = null,
        followUpContext: FollowUpContext? = null,
        featureScope: FeatureScopeContext? = null,
        providerOverride: AiProvider? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Result<StepAnswerResult> = withProvider(providerOverride) { provider ->
        val prompt = PromptEnvelopeFactory.buildStepQuestionPrompt(
            question = question,
            step = step,
            mode = mode,
            queryContext = queryContext,
            followUpContext = followUpContext,
            featureScope = featureScope,
            providerCapabilities = provider.capabilities,
            json = json,
        )
        val providerResponse = provider.query(prompt, PromptKind.WALKTHROUGH, onProgress)
        val answer = decodeResponse(providerResponse.content).toStepAnswer()
            ?: return@withProvider Result.failure(IllegalStateException("Unexpected response from LLM"))
        Result.success(
            StepAnswerResult(
                answer = sanitizeStepAnswer(answer, step),
                metadata = providerResponse.metadata,
            ),
        )
    }

    /**
     * Owns the provider lifecycle and the failure mapping both entry points share: capability check,
     * cancellation handle, and turning provider faults into a failed [Result] without losing an
     * in-flight coroutine cancellation.
     */
    private suspend fun <T> withProvider(
        providerOverride: AiProvider?,
        block: suspend (LlmProvider) -> Result<T>,
    ): Result<T> {
        val provider = providerFor(providerOverride ?: settings().provider)
        return try {
            requireRepoGroundedWalkthroughSupport(provider)
            activeProvider.set(provider)
            block(provider)
        } catch (error: SerializationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Failed to parse response: ${error.message}", error))
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Provider I/O error: ${error.message}", error))
        } catch (error: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(IllegalStateException("Unexpected provider error: ${error.message}", error))
        } finally {
            activeProvider.compareAndSet(provider, null)
        }
    }

    suspend fun checkAvailability(provider: AiProvider): ProviderStatus = providerFor(provider).checkAvailability()

    suspend fun mechanicalSymbolInventory(): JsonObject? {
        val cached = latestSymbolInventory.get()
        if (cached != null) return cached.payload
        return MechanicalSymbolAnalyzer.analyze(projectRoot, json, analysisRoot)
            ?.also(latestSymbolInventory::set)
            ?.payload
    }

    fun cancel() {
        activeProvider.getAndSet(null)?.cancel()
    }

    private fun decodeResponse(content: String): LlmResponse {
        val cleaned = JsonResponseSanitizer.extractTopLevelObject(content)
        return json.decodeFromString(cleaned)
    }

    private fun decodeMechanicalArchitecture(payload: JsonObject): CodebaseArchitecture? {
        val element = payload["architecture"] ?: return null
        return runCatching { json.decodeFromJsonElement<CodebaseArchitecture>(element) }.getOrNull()
    }

    private fun addToolEvidence(
        flowMap: FlowMap,
        inventory: MechanicalSymbolInventory,
        semanticToolResults: List<ProviderToolResult>,
    ): FlowMap {
        val pathAnalysis = inventory.payload["path_analysis"]?.jsonObject
        val pathResults = pathAnalysis?.get("results")?.jsonArray.orEmpty().map { it.jsonObject }
        val steps = flowMap.steps.map { step ->
            val result = pathResults.firstOrNull { item ->
                item["file_path"]?.jsonPrimitive?.contentOrNull == step.filePath &&
                    item["symbol"]?.jsonPrimitive?.contentOrNull?.substringAfterLast('.') ==
                    step.symbol?.substringAfterLast('.')
            } ?: return@map step
            val status = result["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val text = result["output"]?.jsonPrimitive?.contentOrNull
                ?: result["detail"]?.jsonPrimitive?.contentOrNull
                ?: "CrossHair result: $status."
            step.copy(
                evidence = step.evidence + EvidenceItem(
                    kind = "path_analysis",
                    label = "CrossHair $status",
                    filePath = step.filePath,
                    startLine = step.startLine,
                    endLine = step.endLine,
                    text = text,
                ),
            )
        }
        val completedPathAnalysis = pathResults.any {
            it["status"]?.jsonPrimitive?.contentOrNull in setOf("partial_coverage", "unknown", "timeout", "unsupported")
        }
        val actualTools = buildList {
            add(inventory.tool)
            addAll(semanticToolResults.map(ProviderToolResult::name))
            if (completedPathAnalysis) add("crosshair")
        }.distinct()
        val toolNotes = buildList {
            add(
                if (inventory.cacheHit) {
                    "Reused persisted analysis after verifying the current Python source fingerprint."
                } else {
                    "Persisted this analysis using the current Python source fingerprint."
                },
            )
            if (semanticToolResults.isNotEmpty()) {
                add("Captured ${semanticToolResults.size} Serena semantic tool result(s) from the provider stream.")
            }
            val pathStatus = pathAnalysis?.get("status")?.jsonPrimitive?.contentOrNull
            if (pathStatus != null) {
                val resultStatuses = pathResults.groupingBy {
                    it["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                }.eachCount()
                add(
                    if (resultStatuses.isEmpty()) {
                        "CrossHair path analysis status: $pathStatus."
                    } else {
                        "CrossHair path results: " + resultStatuses.entries.joinToString { "${it.key} (${it.value})" } + "."
                    },
                )
            }
        }
        val architecture = flowMap.architecture?.copy(
            coverageNotes = (flowMap.architecture.coverageNotes + toolNotes).distinct(),
        )
        return flowMap.copy(
            steps = steps,
            architecture = architecture,
            analysisTrace = (flowMap.analysisTrace ?: AnalysisTrace()).copy(
                semanticToolsUsed = actualTools,
                delegatedAgents = emptyList(),
            ),
        )
    }

    private fun buildMetadata(metadata: ResponseMetadata?, response: LlmResponse): ResponseMetadata? {
        val durationMs = metadata?.durationMs ?: return null
        val steps = response.steps.orEmpty()
        return ResponseMetadata(
            durationMs = durationMs,
            costUsd = metadata.costUsd,
            numTurns = metadata.numTurns,
            stepCount = steps.size,
            fileCount = steps.map(FlowStep::filePath).distinct().size,
        )
    }

    private fun sanitizeStepAnswer(answer: StepAnswer, step: FlowStep): StepAnswer {
        val validator = StepValidator(projectRoot.toString())
        return answer.copy(
            importantLines = answer.importantLines.mapNotNull { annotation ->
                val start = annotation.startLine.coerceIn(step.startLine, step.endLine)
                val end = annotation.endLine.coerceIn(step.startLine, step.endLine)
                annotation.copy(startLine = start, endLine = end).takeIf { start <= end }
            },
            evidence = validator.sanitizeEvidenceItems(answer.evidence, step.filePath),
        )
    }

    private fun requireRepoGroundedWalkthroughSupport(provider: LlmProvider) {
        if (!provider.capabilities.supportsRepoGroundedWalkthrough) {
            throw IllegalStateException(
                "${provider.provider.displayName} cannot safely inspect the local repository. " +
                    "Use Codex CLI or Claude CLI for grounded walkthroughs.",
            )
        }
    }
}
