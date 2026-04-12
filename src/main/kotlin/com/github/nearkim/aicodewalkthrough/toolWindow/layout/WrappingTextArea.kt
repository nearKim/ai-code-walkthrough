package com.github.nearkim.aicodewalkthrough.toolwindow.layout

import com.intellij.ui.components.JBTextArea
import java.awt.Dimension

/**
 * Lightweight wrapping text area that recomputes its preferred height from the available width.
 */
class WrappingTextArea : JBTextArea() {

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (!lineWrap) return base

        val availableWidth = when {
            width > 0 -> width
            parent?.width ?: 0 > 0 -> parent.width
            else -> 0
        }
        if (availableWidth <= 0) return base

        val wrappedWidth = maxOf(availableWidth - insets.left - insets.right, 1)
        setSize(wrappedWidth, Short.MAX_VALUE.toInt())
        val wrapped = super.getPreferredSize()
        return Dimension(wrappedWidth + insets.left + insets.right, wrapped.height)
    }
}
