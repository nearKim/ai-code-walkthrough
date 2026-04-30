package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.Timer

private const val MAX_LOG_LINES = 500

class LoadingCard(
    private val onStop: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val spinner = JBLabel(AnimatedIcon.Default.INSTANCE)
    private val statusLabel = JBLabel("Working on it...")
    private val elapsedLabel = JBLabel("0.0s")
    private val stopButton = JButton("Stop").apply {
        addActionListener { onStop() }
    }

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(6, 8)
    }
    private val logScroll = JBScrollPane(logArea).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(200, 240)
    }

    private val lineBuffer = ArrayDeque<String>()
    private var startMs: Long = 0
    private val timer: Timer = Timer(500) {
        val seconds = (System.currentTimeMillis() - startMs) / 1000.0
        elapsedLabel.text = "%.1fs".format(seconds)
    }

    init {
        border = JBUI.Borders.empty(6, 8)

        add(buildTopBar(), BorderLayout.NORTH)
        add(logScroll, BorderLayout.CENTER)
    }

    private fun buildTopBar(): JPanel {
        val bar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val statusGroup = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        statusGroup.add(spinner)
        statusGroup.add(statusLabel)
        statusGroup.add(elapsedLabel)
        bar.add(statusGroup)
        bar.add(Box.createHorizontalGlue())
        bar.add(stopButton)
        return bar
    }

    fun startLoading() {
        startMs = System.currentTimeMillis()
        elapsedLabel.text = "0.0s"
        resetLog()
        stopButton.isEnabled = true
        timer.start()
    }

    fun stopLoading() {
        timer.stop()
        stopButton.isEnabled = false
    }

    fun setStatus(line: String) {
        statusLabel.text = line.takeIf { it.isNotBlank() } ?: "Working on it..."
    }

    fun appendLog(lines: List<String>) {
        if (lines.isEmpty()) return
        lines.forEach { lineBuffer.addLast(it) }
        while (lineBuffer.size > MAX_LOG_LINES) lineBuffer.removeFirst()
        logArea.text = lineBuffer.joinToString("\n")
        logArea.caretPosition = logArea.document.length
    }

    fun resetLog() {
        lineBuffer.clear()
        logArea.text = ""
    }
}
