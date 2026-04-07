package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import java.nio.file.Files
import java.nio.file.Path

class StepValidator(private val projectBasePath: String) {

    private val symbolPatterns = listOf("def ", "class ", "fun ", "function ")

    fun validate(steps: List<FlowStep>): List<FlowStep> {
        val validated = steps.map { step -> validateStep(step) }
        return deduplicate(validated)
    }

    private fun validateStep(step: FlowStep): FlowStep {
        val filePath = Path.of(projectBasePath).resolve(step.filePath)

        if (!Files.exists(filePath)) {
            return step.copy(broken = true, breakReason = "File not found: ${step.filePath}")
        }

        val lines = Files.readAllLines(filePath)
        val fileLineCount = lines.size

        if (step.symbol != null) {
            val matchLine = findSymbolLine(lines, step.symbol)
            if (matchLine != null) {
                val originalRangeSize = (step.endLine - step.startLine).coerceAtLeast(0)
                val newStartLine = matchLine + 1
                val newEndLine = (matchLine + 1 + originalRangeSize).coerceAtMost(fileLineCount)
                return step.copy(startLine = newStartLine, endLine = newEndLine)
            }
        }

        if (step.startLine > fileLineCount && step.endLine > fileLineCount) {
            return step.copy(
                broken = true,
                breakReason = "Line range ${step.startLine}-${step.endLine} is outside file (${fileLineCount} lines)",
            )
        }

        val clampedStart = step.startLine.coerceIn(1, fileLineCount)
        val clampedEnd = step.endLine.coerceIn(clampedStart, fileLineCount)
        return step.copy(startLine = clampedStart, endLine = clampedEnd)
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

    private fun deduplicate(steps: List<FlowStep>): List<FlowStep> {
        val seen = mutableSetOf<Triple<String, Int, Int>>()
        return steps.filter { step ->
            seen.add(Triple(step.filePath, step.startLine, step.endLine))
        }
    }
}
