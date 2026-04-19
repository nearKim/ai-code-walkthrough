package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.Timer

class LoadingCard : JPanel(BorderLayout()) {

    private val spinner = JBLabel(AnimatedIcon.Default.INSTANCE)
    private val statusLabel = JBLabel("Working on it...")
    private val elapsedLabel = JBLabel("0.0s")

    private var startMs: Long = 0
    private val timer: Timer = Timer(500) {
        val seconds = (System.currentTimeMillis() - startMs) / 1000.0
        elapsedLabel.text = "%.1fs".format(seconds)
    }

    init {
        border = JBUI.Borders.empty(16)

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        top.add(spinner)
        top.add(statusLabel)
        add(top, BorderLayout.NORTH)

        val bottom = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        bottom.add(elapsedLabel)
        add(bottom, BorderLayout.CENTER)
    }

    fun startLoading() {
        startMs = System.currentTimeMillis()
        elapsedLabel.text = "0.0s"
        timer.start()
    }

    fun stopLoading() {
        timer.stop()
    }

    fun setStatus(line: String) {
        statusLabel.text = line.takeIf { it.isNotBlank() } ?: "Working on it..."
    }
}
