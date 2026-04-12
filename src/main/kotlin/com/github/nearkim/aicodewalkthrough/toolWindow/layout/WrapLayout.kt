package com.github.nearkim.aicodewalkthrough.toolwindow.layout

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * FlowLayout that reports wrapped preferred height correctly inside narrow containers.
 */
class WrapLayout(
    align: Int = FlowLayout.LEFT,
    hgap: Int = 5,
    vgap: Int = 5,
) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, preferred = false)
        minimum.width -= hgap + 1
        return minimum
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val horizontalInsetsAndGap = target.insets.left + target.insets.right + (hgap * 2)
            val maxWidth = availableWidth(target, horizontalInsetsAndGap)

            var dimension = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (index in 0 until target.componentCount) {
                val component = target.getComponent(index)
                if (!component.isVisible) continue

                val size = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth + size.width > maxWidth) {
                    addRow(dimension, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }

                if (rowWidth != 0) {
                    rowWidth += hgap
                }
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }

            addRow(dimension, rowWidth, rowHeight)
            dimension.width += horizontalInsetsAndGap
            dimension.height += target.insets.top + target.insets.bottom + (vgap * 2)

            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
            if (scrollPane != null && target.isValid) {
                dimension.width -= hgap + 1
            }

            return dimension
        }
    }

    private fun availableWidth(target: Container, horizontalInsetsAndGap: Int): Int {
        val targetWidth = when {
            target.width > 0 -> target.width
            target.parent?.width ?: 0 > 0 -> target.parent.width
            else -> Int.MAX_VALUE
        }
        return maxOf(targetWidth - horizontalInsetsAndGap, 1)
    }

    private fun addRow(dimension: Dimension, rowWidth: Int, rowHeight: Int) {
        dimension.width = maxOf(dimension.width, rowWidth)
        if (dimension.height > 0) {
            dimension.height += vgap
        }
        dimension.height += rowHeight
    }
}
