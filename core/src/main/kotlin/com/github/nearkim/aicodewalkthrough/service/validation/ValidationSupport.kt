package com.github.nearkim.aicodewalkthrough.service.validation

internal data class ValidationResult<T>(
    val value: T,
    val changed: Boolean,
)

internal fun String.orNullIfBlank(): String? = takeIf { it.isNotBlank() }

internal fun List<String>.cleanTextItems(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct()

internal fun String.quote(): String = "\"$this\""
