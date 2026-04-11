package com.github.nearkim.aicodewalkthrough.toolwindow

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewModeTest {

    @Test
    fun `review modes round-trip with analysis modes`() {
        AnalysisMode.entries.forEach { mode ->
            val reviewMode = ReviewMode.fromAnalysisMode(mode)
            assertEquals(mode, reviewMode.toAnalysisMode())
        }
    }
}
