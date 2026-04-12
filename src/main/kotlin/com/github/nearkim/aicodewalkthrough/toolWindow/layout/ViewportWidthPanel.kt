package com.github.nearkim.aicodewalkthrough.toolwindow.layout

import com.intellij.util.ui.JBUI
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Scrollable panel that always tracks the viewport width so child components can wrap instead of
 * forcing horizontal scrolling inside narrow tool windows.
 */
class ViewportWidthPanel : JPanel(), Scrollable {

    override fun getPreferredScrollableViewportSize() = preferredSize

    override fun getScrollableUnitIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = JBUI.scale(24)

    override fun getScrollableBlockIncrement(
        visibleRect: Rectangle,
        orientation: Int,
        direction: Int,
    ): Int = maxOf(visibleRect.height - JBUI.scale(24), JBUI.scale(24))

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}
