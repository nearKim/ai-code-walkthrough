package com.github.nearkim.aicodewalkthrough.service.validation

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.service.ProjectFiles

/**
 * Anchors each step to a real symbol range in a real file, clamping annotations and evidence into
 * that range. In mechanical mode the persisted Python AST fact wins over the model's claims.
 */
internal class StepAnchorValidator(
    private val projectFiles: ProjectFiles,
    private val evidenceSanitizer: EvidenceSanitizer,
    private val mechanicalEvidence: List<EvidenceItem>?,
) {

    fun validate(steps: List<FlowStep>): List<FlowStep> = deduplicate(steps.map(::validateStep))

    private fun validateStep(step: FlowStep): FlowStep {
        if (mechanicalEvidence != null) return validateMechanicalStep(step, mechanicalEvidence)

        val lines = projectFiles.readLines(step.filePath)
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
                clampRange(step.startLine, step.endLine, fileLineCount, validationNotes)
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

        val evidenceResult = evidenceSanitizer.sanitize(step.evidence, step.filePath, validationNotes)
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

    private fun validateMechanicalStep(step: FlowStep, mechanicalEvidence: List<EvidenceItem>): FlowStep {
        val normalizedPath = projectFiles.normalizeExisting(step.filePath, requireRegularFile = true)
            ?: return step.copy(broken = true, breakReason = "File not found: ${step.filePath}")
        val candidates = mechanicalEvidence.filter { it.filePath == normalizedPath }
        val requestedSymbol = step.symbol?.removeSuffix("()")
        val matched = when {
            requestedSymbol != null -> candidates.firstOrNull {
                val candidate = it.label.removeSuffix("()")
                candidate == requestedSymbol || requestedSymbol.endsWith(".$candidate")
            } ?: candidates.firstOrNull {
                !requestedSymbol.contains('.') &&
                    it.label.substringAfterLast('.').removeSuffix("()") == requestedSymbol.substringAfterLast('.')
                }
            else -> candidates
                .filter { evidence ->
                    val start = evidence.startLine ?: return@filter false
                    val end = evidence.endLine ?: start
                    step.startLine in start..end
                }
                .minByOrNull { evidence -> (evidence.endLine ?: evidence.startLine!!) - evidence.startLine!! }
        } ?: return step.copy(
            broken = true,
            breakReason = "No current Python AST fact matches ${step.symbol ?: step.filePath}.",
        )

        val start = matched.startLine!!
        val end = matched.endLine ?: start
        val explanation = matched.text?.takeIf(String::isNotBlank)
            ?: "Verified ${matched.kind} ${matched.label}."
        return step.copy(
            title = matched.label,
            filePath = matched.filePath!!,
            symbol = matched.label,
            startLine = start,
            endLine = end,
            explanation = explanation,
            detailedExplanation = null,
            whyIncluded = "Verified Python ${matched.kind} selected for this learning route.",
            stepType = matched.kind,
            uncertain = false,
            lineAnnotations = emptyList(),
            severity = null,
            confidence = "verified",
            riskType = null,
            evidence = listOf(matched),
            suggestedAction = null,
            testGap = null,
            commentDrafts = emptyList(),
            validationNote = "Replaced model-authored code claims with the persisted Python AST fact.",
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

    private fun findSymbolLine(lines: List<String>, symbol: String): Int? {
        for ((index, line) in lines.withIndex()) {
            for (prefix in SYMBOL_PATTERNS) {
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

    private companion object {
        val SYMBOL_PATTERNS = listOf("def ", "class ", "fun ", "function ")
    }
}
