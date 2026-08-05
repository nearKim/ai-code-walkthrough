package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.github.nearkim.aicodewalkthrough.model.DiagramSection
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LearningStage
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.WrapLayout
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.WrappingTextArea
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
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
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

private const val SUMMARY_TRUNCATE_LENGTH = 120

class OverviewCard(
    private val onStartTour: () -> Unit,
    private val onStartSectionTour: (DiagramSection) -> Unit,
    private val onPreviewStep: (FlowStep) -> Unit,
    private val onCopyMarkdown: () -> Unit,
    private val onNewQuestion: () -> Unit,
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

    private val stageSelector = JComboBox<String>()
    private val sectionSelector = JComboBox<String>()
    private val listModel = DefaultListModel<FlowStep>()
    private val stepList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = StepRenderer()
    }
    private val architecturePanel = ArchitecturePanel()
    private val learningStageContext = WrappingTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        foreground = JBColor(Color(95, 95, 95), Color(175, 175, 175))
        border = JBUI.Borders.empty(6, 8)
    }
    private val diagramSectionContext = WrappingTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        foreground = JBColor(Color(95, 95, 95), Color(175, 175, 175))
        border = JBUI.Borders.empty(6, 8)
    }
    private val tabs = JBTabbedPane().apply {
        addTab("Architecture", architecturePanel)
        addTab("Learning path", buildLearningPathPanel())
        addTab("Feature walkthroughs", buildFeatureWalkthroughPanel())
    }
    private val continueButton = JButton("Continue to learning path \u2192")
    private val backButton = JButton("\u2190 Architecture")
    private val startTourButton = JButton("Start guided tour").apply { isDefaultCapable = true }
    private val startSectionTourButton = JButton("Walk this section")
    private val previewButton = JButton("Preview selected")
    private val copyButton = JButton("Copy as Markdown")
    private val newQuestionButton = JButton("New question")
    private var displayStages: List<LearningStage> = emptyList()
    private var displaySections: List<DiagramSection> = emptyList()
    private var stepsById: Map<String, FlowStep> = emptyMap()
    private var componentNamesById: Map<String, String> = emptyMap()
    private var boundFlowMap: FlowMap? = null

    init {
        border = JBUI.Borders.empty(6, 8)

        add(buildHeader(), BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
            border = JBUI.Borders.emptyTop(4)
        }
        actionRow.add(continueButton)
        actionRow.add(backButton)
        actionRow.add(startTourButton)
        actionRow.add(startSectionTourButton)
        actionRow.add(previewButton)
        actionRow.add(copyButton)
        actionRow.add(newQuestionButton)
        add(actionRow, BorderLayout.SOUTH)

        continueButton.addActionListener { tabs.selectedIndex = LEARNING_PATH_TAB }
        backButton.addActionListener { tabs.selectedIndex = ARCHITECTURE_TAB }
        startTourButton.addActionListener { onStartTour() }
        startSectionTourButton.addActionListener {
            displaySections.getOrNull(sectionSelector.selectedIndex)?.let(onStartSectionTour)
        }
        previewButton.addActionListener {
            val selected = stepList.selectedValue ?: return@addActionListener
            onPreviewStep(selected)
        }
        copyButton.addActionListener { onCopyMarkdown() }
        newQuestionButton.addActionListener { onNewQuestion() }
        toggleLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                summaryExpanded = !summaryExpanded
                applySummaryText()
            }
        })
        tabs.addChangeListener { updateSectionActions() }
        stageSelector.addActionListener { updateSelectedStage() }
        sectionSelector.addActionListener { updateSelectedDiagramSection() }
        stepList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) updatePreviewAction()
        }
        updateSectionActions()
    }

    fun setFlowMap(flowMap: FlowMap?, providerName: String, question: String? = null) {
        stepList.clearSelection()
        stageSelector.model = DefaultComboBoxModel()
        sectionSelector.model = DefaultComboBoxModel()
        listModel.clear()
        if (flowMap == null) {
            boundFlowMap = null
            architecturePanel.setArchitecture(null)
            displayStages = emptyList()
            displaySections = emptyList()
            stepsById = emptyMap()
            componentNamesById = emptyMap()
            questionLabel.text = " "
            summaryLabel.text = " "
            metaLabel.text = " "
            learningStageContext.text = ""
            diagramSectionContext.text = ""
            toggleLink.isVisible = false
            startTourButton.isEnabled = false
            startSectionTourButton.isEnabled = false
            previewButton.isEnabled = false
            copyButton.isEnabled = false
            tabs.setEnabledAt(ARCHITECTURE_TAB, false)
            tabs.setEnabledAt(FEATURE_WALKTHROUGHS_TAB, false)
            updateSectionActions()
            return
        }
        val isNewFlowMap = flowMap !== boundFlowMap
        boundFlowMap = flowMap
        questionLabel.text = question?.takeIf { it.isNotBlank() } ?: "Walkthrough"
        fullSummary = flowMap.summary
        summaryExpanded = false
        applySummaryText()
        val entryTitle = flowMap.steps.firstOrNull { it.id == flowMap.entryStepId }?.title
            ?: flowMap.steps.firstOrNull()?.title
            ?: "—"
        val componentCount = flowMap.architecture?.components?.size ?: 0
        displayStages = flowMap.learningPath.ifEmpty {
            if (flowMap.steps.isEmpty()) emptyList() else listOf(
                LearningStage(
                    id = "walkthrough",
                    title = "Walkthrough path",
                    goal = "Follow the validated code stops in order.",
                    stepIds = flowMap.steps.map { it.id },
                ),
            )
        }
        val stageCount = displayStages.size
        val sectionCount = flowMap.diagramSections.size
        metaLabel.text = buildList {
            if (componentCount > 0) add("$componentCount components")
            if (stageCount > 0) add("$stageCount stages")
            if (sectionCount > 0) add("$sectionCount sections")
            add("${flowMap.steps.size} steps")
            add(entryTitle)
            add(providerName)
        }.joinToString(" · ")
        architecturePanel.setArchitecture(flowMap.architecture)
        stepsById = flowMap.steps.associateBy { it.id }
        componentNamesById = flowMap.architecture?.components?.associate { it.id to it.name }.orEmpty()
        displaySections = flowMap.diagramSections
        stageSelector.model = DefaultComboBoxModel(
            displayStages.mapIndexed { index, stage ->
                "${index + 1}. ${stage.title} · ${stage.stepIds.size} stops"
            }.toTypedArray(),
        )
        sectionSelector.model = DefaultComboBoxModel(
            displaySections.mapIndexed { index, section ->
                "${index + 1}. ${section.title} · ${section.stepIds.size} stops"
            }.toTypedArray(),
        )
        tabs.setTitleAt(ARCHITECTURE_TAB, "Architecture${componentCount.takeIf { it > 0 }?.let { " ($it)" }.orEmpty()}")
        tabs.setTitleAt(LEARNING_PATH_TAB, "Learning path ($stageCount stages · ${flowMap.steps.size} stops)")
        tabs.setTitleAt(FEATURE_WALKTHROUGHS_TAB, "Feature walkthroughs (${displaySections.size} sections)")
        tabs.setEnabledAt(ARCHITECTURE_TAB, flowMap.architecture != null)
        tabs.setEnabledAt(FEATURE_WALKTHROUGHS_TAB, displaySections.isNotEmpty())
        startTourButton.isEnabled = flowMap.steps.any { !it.broken }
        copyButton.isEnabled = true
        if (displayStages.isNotEmpty()) {
            stageSelector.selectedIndex = 0
        } else {
            learningStageContext.text = "No validated code stops were returned."
            previewButton.isEnabled = false
        }
        if (displaySections.isNotEmpty()) {
            sectionSelector.selectedIndex = 0
        } else {
            startSectionTourButton.isEnabled = false
        }
        if (isNewFlowMap) {
            tabs.selectedIndex = if (flowMap.architecture != null) ARCHITECTURE_TAB else LEARNING_PATH_TAB
        }
        updateSelectedStage()
        updateSelectedDiagramSection()
        updateSectionActions()
    }

    private fun buildLearningPathPanel(): JPanel = JPanel(BorderLayout()).apply {
        val stagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 0, 4, 0)
            isOpaque = false
            add(JBLabel("Learning stages").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(0, 8, 3, 8)
            })
            add(stageSelector.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            add(learningStageContext.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(JBLabel("Code stops").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(4, 8, 3, 8)
            })
        }
        add(stagePanel, BorderLayout.NORTH)
        add(JBScrollPane(stepList).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
        }, BorderLayout.CENTER)
    }

    private fun buildFeatureWalkthroughPanel(): JPanel = JPanel(BorderLayout()).apply {
        val sectionPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 0, 4, 0)
            isOpaque = false
            add(JBLabel("Diagram sections").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(0, 8, 3, 8)
            })
            add(sectionSelector.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
        add(sectionPanel, BorderLayout.NORTH)
        add(JBScrollPane(diagramSectionContext).apply {
            border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
        }, BorderLayout.CENTER)
    }

    private fun updateSelectedStage() {
        val index = stageSelector.selectedIndex
        if (index < 0) return
        val stage = displayStages[index]
        val checkpoint = stage.checkpoint?.takeIf { it.isNotBlank() }?.let { "\nCheckpoint: $it" }.orEmpty()
        learningStageContext.text = "Stage ${index + 1}/${displayStages.size} · ${stage.title}\n${stage.goal}$checkpoint"
        listModel.clear()
        stage.stepIds.mapNotNull(stepsById::get).forEach(listModel::addElement)
        if (listModel.size > 0) stepList.selectedIndex = 0
        updatePreviewAction()
    }

    private fun updateSelectedDiagramSection() {
        val section = displaySections.getOrNull(sectionSelector.selectedIndex)
        if (section == null) {
            diagramSectionContext.text = "No validated feature sections were returned."
            startSectionTourButton.isEnabled = false
            return
        }
        val components = section.componentIds.mapNotNull(componentNamesById::get)
        val steps = section.stepIds.mapNotNull(stepsById::get)
        diagramSectionContext.text = buildList {
            add("Section ${sectionSelector.selectedIndex + 1}/${displaySections.size} · ${section.title}")
            section.summary?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
            if (components.isNotEmpty()) add("Components: ${components.joinToString(" · ")}")
            if (steps.isNotEmpty()) add("Code stops:\n${steps.joinToString("\n") { "• ${it.title} (${it.filePath}:${it.startLine})" }}")
        }.joinToString("\n\n")
        startSectionTourButton.isEnabled = steps.any { !it.broken }
        diagramSectionContext.caretPosition = 0
    }

    private fun updatePreviewAction() {
        previewButton.isEnabled = stepList.selectedValue?.broken == false
    }

    private fun updateSectionActions() {
        val showingArchitecture = tabs.selectedIndex == ARCHITECTURE_TAB && tabs.isEnabledAt(ARCHITECTURE_TAB)
        val showingFeatureWalkthroughs = tabs.selectedIndex == FEATURE_WALKTHROUGHS_TAB &&
            tabs.isEnabledAt(FEATURE_WALKTHROUGHS_TAB)
        continueButton.isVisible = showingArchitecture
        backButton.isVisible = !showingArchitecture && tabs.isEnabledAt(ARCHITECTURE_TAB)
        startTourButton.isVisible = !showingArchitecture && !showingFeatureWalkthroughs
        startSectionTourButton.isVisible = showingFeatureWalkthroughs
        previewButton.isVisible = !showingArchitecture && !showingFeatureWalkthroughs
        revalidate()
        repaint()
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

    private inner class StepRenderer : JPanel(BorderLayout()), ListCellRenderer<FlowStep> {

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

    companion object {
        private const val ARCHITECTURE_TAB = 0
        private const val LEARNING_PATH_TAB = 1
        private const val FEATURE_WALKTHROUGHS_TAB = 2
    }
}
