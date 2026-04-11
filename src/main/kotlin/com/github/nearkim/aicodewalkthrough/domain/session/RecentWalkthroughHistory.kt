package com.github.nearkim.aicodewalkthrough.domain.session

import com.github.nearkim.aicodewalkthrough.model.RecentWalkthrough
import com.github.nearkim.aicodewalkthrough.model.FollowUpContext

class RecentWalkthroughHistory(private val maxSize: Int = 5) {

    private val items = ArrayDeque<RecentWalkthrough>()
    private var currentId: String? = null

    fun snapshot(): List<RecentWalkthrough> = items.toList()
    fun items(): List<RecentWalkthrough> = snapshot()

    fun find(id: String): RecentWalkthrough? = items.firstOrNull { it.id == id }

    fun restore(id: String): RecentWalkthrough? {
        val snapshot = find(id) ?: return null
        currentId = snapshot.id
        return snapshot
    }

    fun clearCurrentSelection() {
        currentId = null
    }

    fun remember(item: RecentWalkthrough): String {
        items.removeAll {
            it.question == item.question && it.mode == item.mode && it.flowMap.summary == item.flowMap.summary
        }
        items.addFirst(item)
        currentId = item.id
        while (items.size > maxSize) {
            items.removeLast()
        }
        return item.id
    }

    fun updateFollowUp(id: String, followUpContext: FollowUpContext?): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val existing = items.removeAt(index)
        items.add(index, existing.copy(followUpContext = followUpContext))
        return true
    }

    fun updateCurrentFollowUp(followUpContext: FollowUpContext?): Boolean {
        val activeId = currentId ?: return false
        return updateFollowUp(activeId, followUpContext)
    }

    fun resolveStartIndex(snapshot: RecentWalkthrough): Int {
        val activeStepId = snapshot.followUpContext?.activeStepId ?: return 0
        return snapshot.flowMap.steps.indexOfFirst { it.id == activeStepId }.takeIf { it >= 0 } ?: 0
    }
}
