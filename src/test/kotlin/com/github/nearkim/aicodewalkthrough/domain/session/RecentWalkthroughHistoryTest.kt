package com.github.nearkim.aicodewalkthrough.domain.session

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext
import com.github.nearkim.aicodewalkthrough.model.RecentWalkthrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentWalkthroughHistoryTest {

    @Test
    fun `remember deduplicates matching walkthroughs and caps history size`() {
        val history = RecentWalkthroughHistory(maxSize = 2)

        history.remember(walkthrough(id = "1", question = "Explain A", summary = "A"))
        history.remember(walkthrough(id = "2", question = "Explain B", summary = "B"))
        history.remember(walkthrough(id = "3", question = "Explain A", summary = "A"))

        val snapshot = history.snapshot()
        assertEquals(listOf("3", "2"), snapshot.map { it.id })
    }

    @Test
    fun `update follow up rewrites the matching walkthrough in place`() {
        val history = RecentWalkthroughHistory()
        history.remember(walkthrough(id = "1", question = "Explain A", summary = "A"))

        val updated = history.updateFollowUp(
            id = "1",
            followUpContext = FollowUpContext(
                originalQuestion = "Explain A",
                previousFlowMap = flowMap("A"),
                activeStepId = "step-2",
            ),
        )

        assertTrue(updated)
        assertEquals("step-2", history.find("1")?.followUpContext?.activeStepId)
        assertNull(history.find("missing"))
    }

    private fun walkthrough(id: String, question: String, summary: String): RecentWalkthrough {
        return RecentWalkthrough(
            id = id,
            displayTitle = question,
            question = question,
            mode = AnalysisMode.UNDERSTAND,
            flowMap = flowMap(summary),
        )
    }

    private fun flowMap(summary: String): FlowMap {
        return FlowMap(
            summary = summary,
            steps = emptyList(),
        )
    }
}
