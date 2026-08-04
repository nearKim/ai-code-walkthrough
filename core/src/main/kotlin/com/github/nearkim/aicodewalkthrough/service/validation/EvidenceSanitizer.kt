package com.github.nearkim.aicodewalkthrough.service.validation

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.service.ProjectFiles

/** Clamps evidence locations into real project files, dropping anything that cannot be resolved. */
internal class EvidenceSanitizer(private val projectFiles: ProjectFiles) {

    fun sanitize(
        evidence: List<EvidenceItem>,
        defaultFilePath: String,
        validationNotes: MutableList<String>,
    ): ValidationResult<List<EvidenceItem>> {
        var changed = false
        val sanitized = evidence.mapNotNull { item ->
            val explicitPath = item.filePath?.takeIf { it.isNotBlank() }
            if (explicitPath == null && item.startLine == null) return@mapNotNull item

            val targetPath = explicitPath ?: defaultFilePath
            val normalizedPath = projectFiles.normalizeExisting(targetPath, requireRegularFile = true)
            val lines = normalizedPath?.let(projectFiles::readLines)
            if (normalizedPath == null || lines == null || (item.startLine != null && lines.isEmpty())) {
                changed = true
                validationNotes += "Dropped evidence ${item.label.quote()} because it does not resolve to a project file."
                return@mapNotNull null
            }
            if (item.startLine == null) {
                return@mapNotNull item.copy(filePath = normalizedPath)
            }

            val fileLineCount = lines.size
            val start = item.startLine.coerceIn(1, fileLineCount)
            val requestedEnd = item.endLine ?: item.startLine
            val end = requestedEnd.coerceIn(start, fileLineCount)
            if (start != item.startLine || end != requestedEnd) {
                changed = true
                validationNotes += "Clamped evidence ${item.label.quote()} to L$start-L$end."
            }
            item.copy(
                filePath = normalizedPath,
                startLine = start,
                endLine = end,
            )
        }
        return ValidationResult(sanitized, changed)
    }
}
