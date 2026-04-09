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
                val newStartLine = matchLine + 1
                val symbolEndLine = findSymbolEndLine(lines, matchLine)
                val newEndLine = (symbolEndLine + 1).coerceAtMost(fileLineCount)
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

    // Scan forward from symbolLine tracking brace depth to find the closing } of the symbol body.
    // Returns symbolLine itself if no opening brace is found (e.g. single-expression fun).
    private fun findSymbolEndLine(lines: List<String>, symbolLine: Int): Int {
        var depth = 0
        var foundOpenBrace = false
        for (i in symbolLine until lines.size) {
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> { depth++; foundOpenBrace = true }
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
        val seen = mutableSetOf<Triple<String, Int, Int>>()
        return steps.filter { step ->
            seen.add(Triple(step.filePath, step.startLine, step.endLine))
        }
    }
}
