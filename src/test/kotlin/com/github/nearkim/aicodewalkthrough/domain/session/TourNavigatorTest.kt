package com.github.nearkim.aicodewalkthrough.domain.session

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TourNavigatorTest {

    private val navigator = TourNavigator()

    @Test
    fun `preferred next hop favors grounded higher-importance edge`() {
        val flowMap = FlowMap(
            summary = "Flow",
            steps = listOf(step("s1"), step("s2"), step("s3")),
            edges = listOf(
                edge("e1", from = "s1", to = "s2", importance = "medium", uncertain = true, evidenceCount = 1),
                edge("e2", from = "s1", to = "s3", importance = "high", uncertain = false, evidenceCount = 2),
            ),
        )

        val next = navigator.preferredNextHop(flowMap, "s1", emptySet())

        assertEquals("e2", next?.id)
    }

    @Test
    fun `find preferred next navigable step skips visited and broken targets`() {
        val flowMap = FlowMap(
            summary = "Flow",
            steps = listOf(step("s1"), step("s2", broken = true), step("s3")),
            edges = listOf(
                edge("e1", from = "s1", to = "s2", importance = "high"),
                edge("e2", from = "s1", to = "s3", importance = "medium"),
            ),
        )

        val nextIndex = navigator.findPreferredNextNavigableStepIndex(flowMap, fromIndex = 0, visitedStepIds = emptySet())
        val missing = navigator.findPreferredNextNavigableStepIndex(flowMap, fromIndex = 2, visitedStepIds = setOf("s3"))

        assertEquals(2, nextIndex)
        assertNull(missing)
    }

    private fun step(id: String, broken: Boolean = false): FlowStep {
        return FlowStep(
            id = id,
            title = id,
            filePath = "src/$id.kt",
            startLine = 1,
            endLine = 3,
            explanation = "Explain $id",
            whyIncluded = "Needed",
            broken = broken,
        )
    }

    private fun edge(
        id: String,
        from: String,
        to: String,
        importance: String,
        uncertain: Boolean = false,
        evidenceCount: Int = 0,
    ): StepEdge {
        return StepEdge(
            id = id,
            fromStepId = from,
            toStepId = to,
            kind = "call",
            rationale = "call",
            importance = importance,
            uncertain = uncertain,
            evidence = List(evidenceCount) { index ->
                EvidenceItem(kind = "note", label = "e$index")
            },
        )
    }
}
