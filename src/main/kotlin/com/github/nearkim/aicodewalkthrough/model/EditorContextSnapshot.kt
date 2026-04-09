package com.github.nearkim.aicodewalkthrough.model

data class EditorContextSnapshot(
    val filePath: String,
    val fileName: String,
    val caretLine: Int? = null,
    val selectionStartLine: Int? = null,
    val selectionEndLine: Int? = null,
    val selectedText: String? = null,
    val symbolName: String? = null,
) {
    fun toQueryContext(invokedFromCursor: Boolean = false): QueryContext =
        QueryContext(
            filePath = filePath,
            symbol = symbolName,
            selectionStartLine = selectionStartLine,
            selectionEndLine = selectionEndLine,
            selectedText = selectedText,
            invokedFromCursor = invokedFromCursor,
        )
}
