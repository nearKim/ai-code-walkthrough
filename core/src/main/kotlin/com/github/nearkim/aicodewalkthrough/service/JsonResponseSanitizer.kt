package com.github.nearkim.aicodewalkthrough.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object JsonResponseSanitizer {

    private val json = Json { ignoreUnknownKeys = true }

    fun extractTopLevelObject(text: String): String {
        val normalized = text.trim().removePrefix(UTF8_BOM).trim()
        if (normalized.isEmpty()) return normalized
        if (isJsonObject(normalized)) return normalized

        var startIndex = normalized.indexOf('{')
        while (startIndex >= 0) {
            extractBalancedObject(normalized, startIndex)?.let { candidate ->
                if (isJsonObject(candidate)) {
                    return candidate
                }
            }
            startIndex = normalized.indexOf('{', startIndex + 1)
        }

        return normalized
    }

    private fun isJsonObject(candidate: String): Boolean {
        return try {
            json.parseToJsonElement(candidate) is JsonObject
        } catch (_: Exception) {
            false
        }
    }

    private fun extractBalancedObject(text: String, startIndex: Int): String? {
        var depth = 0
        var inString = false
        var escaping = false

        for (index in startIndex until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaping -> escaping = false
                    ch == '\\' -> escaping = true
                    ch == '"' -> inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return text.substring(startIndex, index + 1)
                    }
                    if (depth < 0) {
                        return null
                    }
                }
            }
        }

        return null
    }

    private const val UTF8_BOM = "\uFEFF"
}
