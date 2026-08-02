package com.github.nearkim.aicodewalkthrough.util

import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowStepMetaFormatterTest {

    @Test
    fun `format includes key review signals`() {
        val meta = FlowStepMetaFormatter.format(
            FlowStep(
                id = "step-1",
                title = "Handle request",
                filePath = "src/App.kt",
                startLine = 12,
                endLine = 30,
                explanation = "Entry point.",
                whyIncluded = "The request starts here.",
                severity = "high",
                confidence = "medium",
                riskType = "regression",
                evidence = listOf(EvidenceItem(kind = "usage", label = "call site")),
                suggestedAction = "Add a null guard.",
                testGap = "Missing request normalization coverage.",
            ),
        )

        assertEquals(
            "src/App.kt  ·  L12-L30  ·  severity: high  ·  confidence: medium  ·  risk: regression  ·  evidence: 1  ·  test gap  ·  action",
            meta,
        )
    }

    @Test
    fun `format marks broken steps`() {
        val meta = FlowStepMetaFormatter.format(
            FlowStep(
                id = "step-2",
                title = "Missing helper",
                filePath = "src/Helper.kt",
                startLine = 40,
                endLine = 44,
                explanation = "This step could not be grounded.",
                whyIncluded = "The model inferred it as the next hop.",
                broken = true,
            ),
        )

        assertEquals("src/Helper.kt  ·  L40-L44  ·  needs repair", meta)
    }

}
