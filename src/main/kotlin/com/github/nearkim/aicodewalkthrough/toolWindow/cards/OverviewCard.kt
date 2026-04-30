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
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

private const val SUMMARY_TRUNCATE_LENGTH = 120

class OverviewCard(
    private val onStartTour: () -> Unit,
    private val onPreviewStep: (FlowStep) -> Unit,
    private val onCopyMarkdown: () -> Unit,
) : JPanel(BorderLayout()) {

    private val questionLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, font.size + 1f)
    }
    private val metaLabel = JBLabel(" ").apply {
        foreground = JBColor(Color(120, 120, 120), Color(160, 160, 160))
        font = font.deriveFont(font.size - 1f)
    }
    private val summaryLabel = JBLabel(" ").apply {
        foreground = JBColor(Color(80, 80, 80), Color(180, 180, 180))
    }
    private val toggleLink = JBLabel("Show more").apply {
        foreground = JBColor(Color(60, 110, 190), Color(130, 170, 225))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(font.size - 1f)
    }

    private var fullSummary = ""
    private var summaryExpanded = false

    private val listModel = DefaultListModel<FlowStep>()
    private val stepList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = StepRenderer()
    }
    private val startTourButton = JButton("Start tour").apply { isDefaultCapable = true }
    private val previewButton = JButton("Preview selected")
    private val copyButton = JButton("Copy as Markdown")

    init {
        border = JBUI.Borders.empty(6, 8)

        add(buildHeader(), BorderLayout.NORTH)
        add(JBScrollPane(stepList).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
        }, BorderLayout.CENTER)

        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            border = JBUI.Borders.emptyTop(4)
        }
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
        toggleLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                summaryExpanded = !summaryExpanded
                applySummaryText()
            }
        })
    }

    fun setFlowMap(flowMap: FlowMap?, providerName: String, question: String? = null) {
        listModel.clear()
        if (flowMap == null) {
            questionLabel.text = " "
            summaryLabel.text = " "
            metaLabel.text = " "
            toggleLink.isVisible = false
            startTourButton.isEnabled = false
            previewButton.isEnabled = false
            copyButton.isEnabled = false
            return
        }
        questionLabel.text = question?.takeIf { it.isNotBlank() } ?: "Walkthrough"
        fullSummary = flowMap.summary
        summaryExpanded = false
        applySummaryText()
        val entryTitle = flowMap.steps.firstOrNull { it.id == flowMap.entryStepId }?.title
            ?: flowMap.steps.firstOrNull()?.title
            ?: "—"
        metaLabel.text = "${flowMap.steps.size} steps · $entryTitle · $providerName"
        flowMap.steps.forEach { listModel.addElement(it) }
        startTourButton.isEnabled = flowMap.steps.any { !it.broken }
        previewButton.isEnabled = flowMap.steps.isNotEmpty()
        copyButton.isEnabled = true
        if (flowMap.steps.isNotEmpty()) stepList.selectedIndex = 0
    }

    private fun buildHeader(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }
        val topRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(questionLabel)
            add(metaLabel)
        }
        panel.add(topRow, BorderLayout.NORTH)

        val summaryRow = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
        }
        summaryRow.add(summaryLabel, BorderLayout.CENTER)
        summaryRow.add(toggleLink, BorderLayout.SOUTH)
        panel.add(summaryRow, BorderLayout.CENTER)
        return panel
    }

    private fun applySummaryText() {
        val needsTruncation = fullSummary.length > SUMMARY_TRUNCATE_LENGTH
        if (summaryExpanded || !needsTruncation) {
            summaryLabel.text = "<html>${escapeHtml(fullSummary)}</html>"
            toggleLink.text = "Show less"
            toggleLink.isVisible = needsTruncation
        } else {
            val truncated = fullSummary.take(SUMMARY_TRUNCATE_LENGTH).let {
                val lastSpace = it.lastIndexOf(' ')
                if (lastSpace > SUMMARY_TRUNCATE_LENGTH / 2) it.substring(0, lastSpace) else it
            }
            summaryLabel.text = "<html>${escapeHtml(truncated)}\u2026</html>"
            toggleLink.text = "Show more"
            toggleLink.isVisible = true
        }
        revalidate()
        repaint()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class StepRenderer : JPanel(BorderLayout()), ListCellRenderer<FlowStep> {

        private val indexLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(0, 4, 0, 6)
        }
        private val titleLabel = JBLabel()
        private val subtitleLabel = JBLabel().apply {
            foreground = JBColor(Color(120, 120, 120), Color(160, 160, 160))
            font = font.deriveFont(font.size - 1f)
        }
        private val typeChip = JBLabel().apply {
            border = JBUI.Borders.empty(1, 5)
            isOpaque = true
            font = font.deriveFont(font.size - 1.5f)
        }

        init {
            border = JBUI.Borders.empty(4, 6)
            val textPanel = JPanel(BorderLayout(0, 1))
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
            subtitleLabel.text = formatPath(value.filePath, value.startLine)
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

        private fun formatPath(filePath: String, startLine: Int): String {
            val name = filePath.substringAfterLast('/')
            val dir = filePath.substringBeforeLast('/', "").substringAfterLast('/')
            return if (dir.isNotEmpty()) "$dir/$name:$startLine" else "$name:$startLine"
        }
    }
}
