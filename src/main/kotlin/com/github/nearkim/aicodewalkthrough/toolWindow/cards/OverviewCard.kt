package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

class OverviewCard(
    private val onStartTour: () -> Unit,
    private val onPreviewStep: (FlowStep) -> Unit,
    private val onCopyMarkdown: () -> Unit,
) : JPanel(BorderLayout()) {

    private val summaryLabel = JBLabel(" ")
    private val listModel = DefaultListModel<FlowStep>()
    private val stepList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = StepRenderer()
    }
    private val startTourButton = JButton("Start tour").apply { isDefaultCapable = true }
    private val previewButton = JButton("Preview selected")
    private val copyButton = JButton("Copy as Markdown")

    init {
        border = JBUI.Borders.empty(10)

        add(summaryLabel, BorderLayout.NORTH)
        add(JBScrollPane(stepList), BorderLayout.CENTER)

        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        actionRow.add(startTourButton)
        actionRow.add(previewButton)
        actionRow.add(copyButton)
        add(actionRow, BorderLayout.SOUTH)

        startTourButton.addActionListener { onStartTour() }
        previewButton.addActionListener {
            val selected = stepList.selectedValue ?: return@addActionListener
            onPreviewStep(selected)
        }
        copyButton.addActionListener { onCopyMarkdown() }
    }

    fun setFlowMap(flowMap: FlowMap?, providerName: String) {
        listModel.clear()
        if (flowMap == null) {
            summaryLabel.text = " "
            startTourButton.isEnabled = false
            previewButton.isEnabled = false
            copyButton.isEnabled = false
            return
        }
        val entryTitle = flowMap.steps.firstOrNull { it.id == flowMap.entryStepId }?.title
            ?: flowMap.steps.firstOrNull()?.title
            ?: "—"
        summaryLabel.text = "${flowMap.steps.size} steps · entrypoint: $entryTitle · $providerName"
        flowMap.steps.forEach { listModel.addElement(it) }
        startTourButton.isEnabled = flowMap.steps.any { !it.broken }
        previewButton.isEnabled = flowMap.steps.isNotEmpty()
        copyButton.isEnabled = true
        if (flowMap.steps.isNotEmpty()) stepList.selectedIndex = 0
    }

    private class StepRenderer : JPanel(BorderLayout()), ListCellRenderer<FlowStep> {

        private val indexLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(0, 4, 0, 8)
        }
        private val titleLabel = JBLabel()
        private val subtitleLabel = JBLabel().apply {
            foreground = JBColor(Color(120, 120, 120), Color(160, 160, 160))
        }
        private val typeChip = JBLabel().apply {
            border = JBUI.Borders.empty(2, 6)
            isOpaque = true
        }

        init {
            border = JBUI.Borders.empty(6, 8)
            val textPanel = JPanel(BorderLayout())
            textPanel.isOpaque = false
            textPanel.add(titleLabel, BorderLayout.NORTH)
            textPanel.add(subtitleLabel, BorderLayout.SOUTH)
            add(indexLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
            val east = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            east.isOpaque = false
            east.add(typeChip)
            add(east, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out FlowStep>?,
            value: FlowStep?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            value ?: return this
            indexLabel.text = "${index + 1}."
            titleLabel.text = value.title
            subtitleLabel.text = "${value.filePath}:${value.startLine}"
            val type = value.stepType?.takeIf { it.isNotBlank() } ?: value.importance ?: ""
            typeChip.text = if (type.isNotBlank()) type else ""
            typeChip.isVisible = type.isNotBlank()
            typeChip.background = JBColor(Color(235, 240, 246), Color(55, 65, 80))
            typeChip.foreground = JBColor(Color(90, 100, 120), Color(170, 180, 200))

            background = if (isSelected) list?.selectionBackground else list?.background
            foreground = if (isSelected) list?.selectionForeground else list?.foreground
            titleLabel.foreground = foreground
            indexLabel.foreground = foreground
            return this
        }
    }
}
