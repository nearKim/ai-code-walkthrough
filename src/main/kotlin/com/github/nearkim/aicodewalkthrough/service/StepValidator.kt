package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import java.nio.file.Files
import java.nio.file.Path

class StepValidator(private val projectBasePath: String) {

    private val symbolPatterns = listOf("def ", "class ", "fun ", "function ")
    private val fileCache = mutableMapOf<Path, List<String>>()

    fun validate(flowMap: FlowMap): FlowMap {
        val validatedSteps = validate(flowMap.steps)
        val stepById = validatedSteps.associateBy { it.id }
        val validatedEdges = validateEdges(flowMap.edges, validatedSteps, stepById)
        val entryStepId = resolveEntryStepId(flowMap.entryStepId, validatedSteps, validatedEdges)
        val terminalStepIds = resolveTerminalStepIds(flowMap.terminalStepIds, validatedSteps, validatedEdges)

        return flowMap.copy(
            steps = validatedSteps,
            entryStepId = entryStepId,
            terminalStepIds = terminalStepIds,
            edges = validatedEdges,
        )
    }

    fun validate(steps: List<FlowStep>): List<FlowStep> {
        val validated = steps.map { step -> validateStep(step) }
        return deduplicate(validated)
    }

    private fun validateStep(step: FlowStep): FlowStep {
        val lines = readFileLines(step.filePath)
            ?: return step.copy(broken = true, breakReason = "File not found: ${step.filePath}")

        val fileLineCount = lines.size
        if (fileLineCount == 0) {
            return step.copy(broken = true, breakReason = "File is empty: ${step.filePath}")
        }
        val validationNotes = mutableListOf<String>()
        var downgradeConfidence = false

        val (validatedStart, validatedEnd) = if (!step.symbol.isNullOrBlank()) {
            val matchLine = findSymbolLine(lines, step.symbol)
            if (matchLine != null) {
                val newStartLine = matchLine + 1
                val symbolEndLine = findSymbolEndLine(lines, matchLine)
                val newEndLine = (symbolEndLine + 1).coerceAtMost(fileLineCount)
                if (newStartLine != step.startLine || newEndLine != step.endLine) {
                    validationNotes += "Re-anchored to symbol ${step.symbol} at L$newStartLine-L$newEndLine."
                }
                newStartLine to newEndLine
            } else {
                validationNotes += "Symbol ${step.symbol} was not found in ${step.filePath}; kept the requested line range."
                downgradeConfidence = true
                clampRange(step.startLine, step.endLine, fileLineCount, validationNotes).also {
                    if (it.first != step.startLine || it.second != step.endLine) {
                        downgradeConfidence = true
                    }
                }
            }
        } else {
            clampRange(step.startLine, step.endLine, fileLineCount, validationNotes).also {
                if (it.first != step.startLine || it.second != step.endLine) {
                    downgradeConfidence = true
                }
            }
        }

        if (validatedStart > fileLineCount && validatedEnd > fileLineCount) {
            return step.copy(
                broken = true,
                breakReason = "Line range ${step.startLine}-${step.endLine} is outside file ($fileLineCount lines)",
            )
        }

        val annotationResult = sanitizeAnnotations(
            step.lineAnnotations,
            validatedStart,
            validatedEnd,
            validationNotes,
        )
        if (annotationResult.changed) downgradeConfidence = true

        val evidenceResult = sanitizeEvidence(step.evidence, step.filePath, validationNotes)
        if (evidenceResult.changed) downgradeConfidence = true

        return step.copy(
            startLine = validatedStart,
            endLine = validatedEnd,
            lineAnnotations = annotationResult.value,
            evidence = evidenceResult.value,
            uncertain = step.uncertain || downgradeConfidence,
            confidence = if (downgradeConfidence) "uncertain" else step.confidence,
            validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
        )
    }

    private fun clampRange(
        startLine: Int,
        endLine: Int,
        fileLineCount: Int,
        validationNotes: MutableList<String>,
    ): Pair<Int, Int> {
        if (startLine > fileLineCount && endLine > fileLineCount) {
            return startLine to endLine
        }
        val clampedStart = startLine.coerceIn(1, fileLineCount)
        val clampedEnd = endLine.coerceIn(clampedStart, fileLineCount)
        if (clampedStart != startLine || clampedEnd != endLine) {
            validationNotes += "Clamped the range to L$clampedStart-L$clampedEnd to fit the file."
        }
        return clampedStart to clampedEnd
    }

    private fun sanitizeAnnotations(
        annotations: List<LineAnnotation>,
        stepStartLine: Int,
        stepEndLine: Int,
        validationNotes: MutableList<String>,
    ): ValidationResult<List<LineAnnotation>> {
        var changed = false
        val sanitized = annotations.mapNotNull { annotation ->
            val start = annotation.startLine.coerceIn(stepStartLine, stepEndLine)
            val end = annotation.endLine.coerceIn(stepStartLine, stepEndLine)
            if (start > end) {
                changed = true
                validationNotes += "Dropped an annotation that fell outside L$stepStartLine-L$stepEndLine."
                null
            } else {
                if (start != annotation.startLine || end != annotation.endLine) {
                    changed = true
                    validationNotes += "Clamped annotation ${annotation.text.take(40).quote()} to L$start-L$end."
                }
                annotation.copy(startLine = start, endLine = end)
            }
        }
        return ValidationResult(sanitized, changed)
    }

    private fun sanitizeEvidence(
        evidence: List<EvidenceItem>,
        defaultFilePath: String,
        validationNotes: MutableList<String>,
    ): ValidationResult<List<EvidenceItem>> {
        var changed = false
        val sanitized = evidence.map { item ->
            val targetPath = item.filePath?.takeIf { it.isNotBlank() } ?: defaultFilePath
            val lines = readFileLines(targetPath)
            if (lines == null || item.startLine == null) {
                item
            } else {
                val fileLineCount = lines.size
                val start = item.startLine.coerceIn(1, fileLineCount)
                val requestedEnd = item.endLine ?: item.startLine
                val end = requestedEnd.coerceIn(start, fileLineCount)
                if (start != item.startLine || end != requestedEnd) {
                    changed = true
                    validationNotes += "Clamped evidence ${item.label.quote()} to L$start-L$end."
                }
                item.copy(
                    filePath = targetPath,
                    startLine = start,
                    endLine = end,
                )
            }
        }
        return ValidationResult(sanitized, changed)
    }

    private fun validateEdges(
        edges: List<StepEdge>,
        steps: List<FlowStep>,
        stepById: Map<String, FlowStep>,
    ): List<StepEdge> {
        val sourceEdges = if (edges.isEmpty()) synthesizeSequentialEdges(steps) else edges
        return deduplicateEdges(
            sourceEdges.mapNotNull { edge -> validateEdge(edge, stepById) },
        )
    }

    private fun validateEdge(edge: StepEdge, stepById: Map<String, FlowStep>): StepEdge? {
        val fromStep = stepById[edge.fromStepId] ?: return null
        val toStep = stepById[edge.toStepId] ?: return null
        if (fromStep.broken || toStep.broken || fromStep.id == toStep.id) return null

        val validationNotes = mutableListOf<String>()
        var downgradeConfidence = edge.uncertain

        val callSiteFilePath = edge.callSiteFilePath?.takeIf { it.isNotBlank() } ?: fromStep.filePath
        var callSiteStartLine = edge.callSiteStartLine
        var callSiteEndLine = edge.callSiteEndLine

        if (callSiteStartLine == null || callSiteEndLine == null) {
            downgradeConfidence = true
            validationNotes += "Missing explicit call-site lines for hop ${edge.id}; the next preview may fall back to symbol matching."
        } else if (callSiteFilePath == fromStep.filePath) {
            val clampedStart = callSiteStartLine.coerceIn(fromStep.startLine, fromStep.endLine)
            val clampedEnd = callSiteEndLine.coerceIn(clampedStart, fromStep.endLine)
            if (clampedStart != callSiteStartLine || clampedEnd != callSiteEndLine) {
                downgradeConfidence = true
                validationNotes += "Clamped edge ${edge.id} to the source step range L$clampedStart-L$clampedEnd."
            }
            callSiteStartLine = clampedStart
            callSiteEndLine = clampedEnd
        } else {
            downgradeConfidence = true
            validationNotes += "Edge ${edge.id} points to a call site outside ${fromStep.filePath}; the IDE preview stays step-scoped."
        }

        if (edge.evidence.isEmpty()) {
            downgradeConfidence = true
            validationNotes += "Edge ${edge.id} has no grounding evidence."
        }

        return edge.copy(
            callSiteFilePath = callSiteFilePath,
            callSiteStartLine = callSiteStartLine,
            callSiteEndLine = callSiteEndLine,
            uncertain = downgradeConfidence,
            validationNote = validationNotes.joinToString(" ").orNullIfBlank(),
        )
    }

    private fun synthesizeSequentialEdges(steps: List<FlowStep>): List<StepEdge> {
        val navigableSteps = steps.filterNot { it.broken }
        if (navigableSteps.size < 2) return emptyList()

        return navigableSteps.zipWithNext().mapIndexed { index, (fromStep, toStep) ->
            StepEdge(
                id = "derived-edge-${index + 1}",
                fromStepId = fromStep.id,
                toStepId = toStep.id,
                kind = "implied_order",
                rationale = "Synthesized from the returned step order because no explicit path edges were provided.",
                importance = toStep.importance,
                uncertain = true,
            )
        }
    }

    private fun resolveEntryStepId(
        requestedEntryStepId: String?,
        steps: List<FlowStep>,
        edges: List<StepEdge>,
    ): String? {
        val navigableSteps = steps.filterNot { it.broken }
        if (navigableSteps.isEmpty()) return null

        requestedEntryStepId?.takeIf { candidate ->
            navigableSteps.any { it.id == candidate }
        }?.let { return it }

        val incomingTargets = edges.map { it.toStepId }.toSet()
        return navigableSteps.firstOrNull { it.id !in incomingTargets }?.id
            ?: navigableSteps.first().id
    }

    private fun resolveTerminalStepIds(
        requestedTerminalStepIds: List<String>,
        steps: List<FlowStep>,
        edges: List<StepEdge>,
    ): List<String> {
        val navigableSteps = steps.filterNot { it.broken }
        if (navigableSteps.isEmpty()) return emptyList()

        val validRequested = requestedTerminalStepIds.filter { candidate ->
            navigableSteps.any { it.id == candidate }
        }
        val outgoingSources = edges.map { it.fromStepId }.toSet()
        val inferred = navigableSteps.filter { it.id !in outgoingSources }.map { it.id }
        val merged = (validRequested + inferred).distinct()
        return if (merged.isNotEmpty()) merged else listOf(navigableSteps.last().id)
    }

    private fun findSymbolLine(lines: List<String>, symbol: String): Int? {
        for ((index, line) in lines.withIndex()) {
            for (prefix in symbolPatterns) {
                if (line.contains("$prefix$symbol")) return index
            }
        }
        for ((index, line) in lines.withIndex()) {
            if (line.contains(symbol)) return index
        }
        return null
    }

    // Scan forward from symbolLine tracking brace depth to find the closing } of the symbol body.
    // Returns symbolLine itself if no opening brace is found (e.g. single-expression fun).
    private fun findSymbolEndLine(lines: List<String>, symbolLine: Int): Int {
        var depth = 0
        var foundOpenBrace = false
        for (i in symbolLine until lines.size) {
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++
                        foundOpenBrace = true
                    }
                    '}' -> if (foundOpenBrace) {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return if (foundOpenBrace) lines.size - 1 else symbolLine
    }

    private fun readFileLines(relativePath: String): List<String>? {
        val resolvedPath = Path.of(projectBasePath).resolve(relativePath).normalize()
        return fileCache.getOrPut(resolvedPath) {
            if (!Files.exists(resolvedPath)) {
                return null
            }
            Files.readAllLines(resolvedPath)
        }
    }

    private fun deduplicate(steps: List<FlowStep>): List<FlowStep> {
        return steps
            .groupBy { Triple(it.filePath, it.startLine, it.endLine) }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<FlowStep> { if (it.broken) 0 else 1 }
                        .thenBy { if (it.uncertain) 0 else 1 }
                        .thenBy { if (it.symbol.isNullOrBlank()) 0 else 1 }
                        .thenBy { it.evidence.size },
                ) ?: duplicates.first()
            }
    }

    private fun deduplicateEdges(edges: List<StepEdge>): List<StepEdge> {
        return edges
            .groupBy { Triple(it.fromStepId, it.toStepId, it.kind) }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<StepEdge> { if (it.broken) 0 else 1 }
                        .thenBy { if (it.uncertain) 0 else 1 }
                        .thenBy { it.evidence.size }
                        .thenBy { if (it.callSiteStartLine == null) 0 else 1 },
                ) ?: duplicates.first()
            }
    }

    private fun String.orNullIfBlank(): String? = takeIf { it.isNotBlank() }

    private fun String.quote(): String = "\"$this\""

    private data class ValidationResult<T>(
        val value: T,
        val changed: Boolean,
    )
}
