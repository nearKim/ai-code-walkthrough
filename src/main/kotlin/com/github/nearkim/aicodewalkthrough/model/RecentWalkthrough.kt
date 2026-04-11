package com.github.nearkim.aicodewalkthrough.model

data class RecentWalkthrough(
    val id: String,
    val displayTitle: String,
    val question: String,
    val mode: AnalysisMode,
    val flowMap: FlowMap,
    val queryContext: QueryContext? = null,
    val followUpContext: FollowUpContext? = null,
    val featureScope: FeatureScopeContext? = null,
    val metadata: ResponseMetadata? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
)
