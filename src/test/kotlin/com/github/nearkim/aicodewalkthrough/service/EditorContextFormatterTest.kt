package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EditorContextSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContextFormatterTest {

    @Test
    fun `panel text includes shared editor details`() {
        val context = EditorContextSnapshot(
            filePath = "src/App.kt",
            fileName = "App.kt",
            caretLine = 21,
            selectionStartLine = 18,
            selectionEndLine = 22,
            selectedText = "handler(request)",
            symbolName = "handleRequest",
        )

        val text = EditorContextFormatter.toPanelText(context)

        assertTrue(text.contains("Current file: src/App.kt"))
        assertTrue(text.contains("Caret line: 21"))
        assertTrue(text.contains("Selection range: L18-L22"))
        assertTrue(text.contains("Symbol: handleRequest"))
        assertTrue(text.contains("Selected text:\nhandler(request)"))
    }

    @Test
    fun `snapshot converts to query context for grounding`() {
        val context = EditorContextSnapshot(
            filePath = "src/App.kt",
            fileName = "App.kt",
            selectionStartLine = 5,
            selectionEndLine = 8,
            selectedText = "selected code",
            symbolName = "run",
        )

        val queryContext = context.toQueryContext(invokedFromCursor = true)

        assertEquals("src/App.kt", queryContext.filePath)
        assertEquals("run", queryContext.symbol)
        assertEquals(5, queryContext.selectionStartLine)
        assertEquals(8, queryContext.selectionEndLine)
        assertEquals("selected code", queryContext.selectedText)
        assertEquals(true, queryContext.invokedFromCursor)
    }
}
