package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EditorContextSnapshot

object EditorContextFormatter {

    fun toPanelText(context: EditorContextSnapshot): String =
        buildString {
            appendLine("Current file: ${context.filePath}")
            context.caretLine?.let { appendLine("Caret line: $it") }
            context.selectionStartLine?.let { start ->
                val end = context.selectionEndLine ?: start
                appendLine("Selection range: L$start-L$end")
            }
            context.symbolName?.takeIf { it.isNotBlank() }?.let {
                appendLine("Symbol: $it")
            }
            context.selectedText?.takeIf { it.isNotBlank() }?.let {
                appendLine("Selected text:")
                appendLine(it)
            }
        }.trimEnd()

    fun toBulletLines(context: EditorContextSnapshot): List<String> = buildList {
        add("- file_path: ${context.filePath}")
        add("- file_name: ${context.fileName}")
        context.caretLine?.let { add("- caret_line: $it") }
        context.selectionStartLine?.let { start ->
            val end = context.selectionEndLine ?: start
            add("- selection_range: L$start-L$end")
        }
        context.symbolName?.takeIf { it.isNotBlank() }?.let { add("- symbol: $it") }
        context.selectedText?.takeIf { it.isNotBlank() }?.let {
            add("- selection_text:")
            add("```text")
            add(it)
            add("```")
        }
    }
}
