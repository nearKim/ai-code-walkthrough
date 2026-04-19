package com.github.nearkim.aicodewalkthrough.editor

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EditorDecorationControllerTest : BasePlatformTestCase() {

    private val sample = """
        class Sample {
            fun entry() {
                helper()
            }
            fun helper() {
                return
            }
        }
    """.trimIndent()

    fun `test decorate adds header, summary, annotations, background, and next-hop marker`() {
        myFixture.configureByText("Sample.kt", sample)
        val controller = project.service<EditorDecorationController>()
        val editor = myFixture.editor

        val step = step(
            id = "s1",
            startLine = 2,
            endLine = 4,
            lineAnnotations = listOf(
                LineAnnotation(startLine = 3, endLine = 3, text = "calls helper"),
                LineAnnotation(startLine = 4, endLine = 4, text = "closes entry"),
            ),
        )
        val nextStep = step(id = "s2", title = "helper", startLine = 5, endLine = 7)
        val edge = StepEdge(
            id = "e1",
            fromStepId = "s1",
            toStepId = "s2",
            kind = "call",
            rationale = "entry calls helper",
            callSiteFilePath = step.filePath,
            callSiteStartLine = 3,
            callSiteEndLine = 3,
        )

        controller.decorate(editor, step, stepIndex = 0, totalSteps = 2, nextStep = nextStep, nextEdge = edge)

        val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength) +
            editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength)
        // 1 header + 1 summary (block elements) + 2 after-line annotations
        assertEquals("inlay count", 4, inlays.size)

        val highlighters = editor.markupModel.allHighlighters
        // 1 background highlighter + 1 next-hop highlighter
        assertEquals("highlighter count", 2, highlighters.size)

        controller.clearDecorations()
        assertEquals(
            "all inlays disposed",
            0,
            editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size +
                editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength).size,
        )
        assertEquals("all highlighters disposed", 0, editor.markupModel.allHighlighters.size)
    }

    fun `test decorate without edge omits next-hop highlighter and symbol fallback finds none`() {
        myFixture.configureByText("Sample.kt", sample)
        val controller = project.service<EditorDecorationController>()
        val editor = myFixture.editor

        val step = step(id = "s1", startLine = 2, endLine = 4, lineAnnotations = emptyList())

        controller.decorate(editor, step, stepIndex = 0, totalSteps = 1, nextStep = null, nextEdge = null)

        // Only the background highlighter exists.
        assertEquals("one background highlighter only", 1, editor.markupModel.allHighlighters.size)
    }

    fun `test decorate skips broken step entirely`() {
        myFixture.configureByText("Sample.kt", sample)
        val controller = project.service<EditorDecorationController>()
        val editor = myFixture.editor

        val step = step(id = "s1", startLine = 2, endLine = 4, broken = true)

        controller.decorate(editor, step, stepIndex = 0, totalSteps = 1, nextStep = null, nextEdge = null)

        val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength) +
            editor.inlayModel.getAfterLineEndElementsInRange(0, editor.document.textLength)
        assertEquals("no inlays for broken step", 0, inlays.size)
        assertEquals("no highlighters for broken step", 0, editor.markupModel.allHighlighters.size)
    }

    private fun step(
        id: String,
        title: String = id,
        startLine: Int,
        endLine: Int,
        lineAnnotations: List<LineAnnotation> = emptyList(),
        broken: Boolean = false,
    ): FlowStep = FlowStep(
        id = id,
        title = title,
        filePath = "Sample.kt",
        startLine = startLine,
        endLine = endLine,
        explanation = "explanation for $id",
        whyIncluded = "why $id",
        lineAnnotations = lineAnnotations,
        broken = broken,
    )
}
