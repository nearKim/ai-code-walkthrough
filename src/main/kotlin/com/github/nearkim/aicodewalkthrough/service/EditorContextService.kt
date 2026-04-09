package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.EditorContextSnapshot
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class EditorContextService(private val project: Project) {

    fun currentContext(): EditorContextSnapshot? {
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.selectedTextEditor?.let { editor ->
            return contextFor(editor)
        }
        return editorManager.selectedFiles.firstOrNull()?.let(::contextFor)
    }

    fun contextFor(editor: Editor): EditorContextSnapshot? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val document = editor.document
        val caretOffset = editor.caretModel.offset.coerceIn(0, document.textLength)
        val caretLine = document.getLineNumber(caretOffset) + 1

        val selectedText = editor.selectionModel.selectedText
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::truncate)

        val hasSelection = editor.selectionModel.hasSelection()
        val selectionStartLine = if (hasSelection) {
            document.getLineNumber(editor.selectionModel.selectionStart.coerceAtLeast(0)) + 1
        } else {
            null
        }
        val selectionEndLine = if (hasSelection) {
            val endOffset = (editor.selectionModel.selectionEnd - 1).coerceIn(0, document.textLength)
            document.getLineNumber(endOffset) + 1
        } else {
            null
        }

        return EditorContextSnapshot(
            filePath = relativePath(file.path) ?: file.path,
            fileName = file.name,
            caretLine = caretLine,
            selectionStartLine = selectionStartLine,
            selectionEndLine = selectionEndLine,
            selectedText = selectedText,
            symbolName = findSymbolName(document, caretOffset),
        )
    }

    private fun contextFor(file: VirtualFile): EditorContextSnapshot =
        EditorContextSnapshot(
            filePath = relativePath(file.path) ?: file.path,
            fileName = file.name,
        )

    private fun findSymbolName(document: Document, caretOffset: Int): String? {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return null
        val element = psiFile.findElementAt(caretOffset) ?: return null
        val named = PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
        return named?.name?.takeIf { it.isNotBlank() }
    }

    private fun relativePath(absolutePath: String): String? {
        val basePath = project.basePath ?: return null
        return try {
            val base = Path.of(basePath).toAbsolutePath().normalize()
            val file = Path.of(absolutePath).toAbsolutePath().normalize()
            base.relativize(file).toString().replace('\\', '/')
        } catch (_: Exception) {
            null
        }
    }

    private fun truncate(text: String, maxChars: Int = 4000): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n... [truncated]"
    }
}
