package com.github.nearkim.aicodewalkthrough.toolwindow

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.ClaudeCliService
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

class CodeTourPanel(private val project: Project, private val scope: CoroutineScope) :
    JPanel(BorderLayout()),
    TourSessionService.TourSessionListener {

    private val sessionService = project.service<TourSessionService>()
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private var errorBanner: JBLabel? = null
    private var errorBannerPanel: JPanel? = null
    private var questionTextArea: JBTextArea? = null

    // Loading card components
    private var progressLog: JTextArea? = null
    private var elapsedLabel: JBLabel? = null
    private var loadingStartTime: Long = 0
    private var elapsedTimerThread: Thread? = null

    // Overview card components
    private var overviewPanel: JPanel? = null
    private var summaryLabel: JBLabel? = null
    private var stepListModel: DefaultListModel<FlowStep>? = null
    private var stepList: JBList<FlowStep>? = null
    private var followUpField: JBTextField? = null
    private var overviewContentPanel: JPanel? = null
    private var clarificationPanel: JPanel? = null
    private var clarificationLabel: JBLabel? = null
    private var clarificationField: JBTextField? = null
    private var metadataBar: JPanel? = null
    private var metadataLabel: JBLabel? = null

    // Tour active card components
    private var tourActivePanel: JPanel? = null
    private var tourStepHeader: JBLabel? = null
    private var tourStepFilePath: JBLabel? = null
    private var tourStepExplanation: JTextArea? = null
    private var tourWhySection: JPanel? = null
    private var tourWhyText: JTextArea? = null
    private var tourUncertainLabel: JBLabel? = null

    // Status indicator
    private var statusDot: JBLabel? = null

    // Command history
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var historyDraft = ""

    init {
        cardPanel.add(createInputCard(), CARD_INPUT)
        cardPanel.add(createLoadingCard(), CARD_LOADING)
        cardPanel.add(createOverviewCard(), CARD_OVERVIEW)
        cardPanel.add(createTourActiveCard(), CARD_TOUR_ACTIVE)

        add(cardPanel, BorderLayout.CENTER)

        sessionService.addListener(this)
        showCard(sessionService.state)
        checkCliStatus()
    }

    override fun onStateChanged(state: TourState) {
        showCard(state)
    }

    override fun onStepChanged(stepIndex: Int, step: FlowStep) {
        refreshTourActiveCard(stepIndex, step)
    }

    override fun onProgressLine(line: String) {
        progressLog?.let { log ->
            log.append("$line\n")
            log.caretPosition = log.document.length
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        sessionService.removeListener(this)
        stopElapsedTimer()
    }

    private fun showCard(state: TourState) {
        when (state) {
            TourState.INPUT -> {
                stopElapsedTimer()
                showErrorBannerIfNeeded()
                cardLayout.show(cardPanel, CARD_INPUT)
            }
            TourState.LOADING -> {
                clearErrorBanner()
                progressLog?.text = ""
                startElapsedTimer()
                cardLayout.show(cardPanel, CARD_LOADING)
            }
            TourState.OVERVIEW -> {
                stopElapsedTimer()
                clearErrorBanner()
                refreshOverviewCard()
                cardLayout.show(cardPanel, CARD_OVERVIEW)
            }
            TourState.TOUR_ACTIVE -> {
                stopElapsedTimer()
                clearErrorBanner()
                cardLayout.show(cardPanel, CARD_TOUR_ACTIVE)
            }
        }
    }

    // ── Input Card ──────────────────────────────────────────────────────

    private fun createInputCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Top: status indicator + error banner
        val topPanel = JPanel(BorderLayout())

        // CLI status row
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        statusDot = JBLabel(AllIcons.General.InspectionsOK).apply {
            text = "Checking claude..."
            foreground = UIUtil.getLabelDisabledForeground()
            font = JBUI.Fonts.smallFont()
        }
        statusPanel.add(statusDot!!)
        topPanel.add(statusPanel, BorderLayout.NORTH)

        // Error banner
        errorBannerPanel = JPanel(BorderLayout())
        errorBanner = JBLabel().apply {
            icon = AllIcons.General.Error
            isVisible = false
            border = JBUI.Borders.empty(6)
        }
        errorBannerPanel!!.add(errorBanner!!, BorderLayout.CENTER)
        errorBannerPanel!!.isVisible = false
        topPanel.add(errorBannerPanel!!, BorderLayout.SOUTH)

        panel.add(topPanel, BorderLayout.NORTH)

        // Center: text area
        questionTextArea = JBTextArea(4, 0).apply {
            emptyText.setText("Ask about your codebase...")
            lineWrap = true
            wrapStyleWord = true
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val navigated = when (e.keyCode) {
                        KeyEvent.VK_UP -> historyUp(this@apply, requireAtStart = true)
                        KeyEvent.VK_DOWN -> historyDown(this@apply, requireAtEnd = true)
                        else -> false
                    }
                    if (navigated) e.consume()
                }
            })
        }
        panel.add(JBScrollPane(questionTextArea!!), BorderLayout.CENTER)

        // Bottom: suggested prompts + button
        val bottomPanel = JPanel(BorderLayout())

        // Suggested prompts
        val suggestionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 0, 4, 0)
        }
        val suggestionsTitle = JBLabel("Try asking:").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
        }
        suggestionsPanel.add(suggestionsTitle)
        suggestionsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val suggestions = listOf(
            "How does the main entry point work?",
            "What's the request/response lifecycle?",
            "How is configuration loaded and applied?",
            "What are the key abstractions and how do they relate?",
        )
        val chipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        for (suggestion in suggestions) {
            chipsPanel.add(createSuggestionChip(suggestion))
        }
        suggestionsPanel.add(chipsPanel)
        bottomPanel.add(suggestionsPanel, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val mapFlowButton = JButton("Map Flow").apply {
            addActionListener {
                val question = questionTextArea!!.text.trim()
                if (question.isNotEmpty()) {
                    addToHistory(question)
                    sessionService.startMapping(question)
                }
            }
        }
        buttonPanel.add(mapFlowButton)
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH)

        panel.add(bottomPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun createSuggestionChip(text: String): JPanel {
        val chip = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(2, 8),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val label = JBLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        }
        chip.add(label)
        chip.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                questionTextArea?.text = text
            }
            override fun mouseEntered(e: MouseEvent) {
                chip.background = JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
            override fun mouseExited(e: MouseEvent) {
                chip.background = null
            }
        })
        return chip
    }

    // ── Loading Card ────────────────────────────────────────────────────

    private fun createLoadingCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Header with spinner and elapsed time
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        headerPanel.add(
            JBLabel("Claude is exploring your codebase...", AnimatedIcon.Default(), SwingConstants.LEFT),
            BorderLayout.WEST,
        )
        elapsedLabel = JBLabel("0s").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }
        headerPanel.add(elapsedLabel!!, BorderLayout.EAST)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Live progress log
        progressLog = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("JetBrains Mono", JBUI.Fonts.smallFont().size)
            foreground = UIUtil.getLabelDisabledForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
        }
        val logScrollPane = JBScrollPane(progressLog!!).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(),
            )
        }
        panel.add(logScrollPane, BorderLayout.CENTER)

        // Cancel button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
        }
        val cancelButton = JButton("Cancel").apply {
            addActionListener { sessionService.cancelRequest() }
        }
        buttonPanel.add(cancelButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun startElapsedTimer() {
        loadingStartTime = System.currentTimeMillis()
        stopElapsedTimer()
        elapsedTimerThread = Thread.ofVirtual().start {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    val elapsed = (System.currentTimeMillis() - loadingStartTime) / 1000
                    javax.swing.SwingUtilities.invokeLater {
                        elapsedLabel?.text = "${elapsed}s"
                    }
                }
            } catch (_: InterruptedException) {
                // Timer stopped
            }
        }
    }

    private fun stopElapsedTimer() {
        elapsedTimerThread?.interrupt()
        elapsedTimerThread = null
    }

    // ── Overview Card ───────────────────────────────────────────────────

    private fun createOverviewCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Metadata bar
        metadataBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            isVisible = false
        }
        metadataLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }
        metadataBar!!.add(metadataLabel!!)

        // Flow map content
        overviewContentPanel = JPanel(BorderLayout()).apply {
            summaryLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
            }

            val topSection = JPanel(BorderLayout())
            topSection.add(metadataBar!!, BorderLayout.NORTH)
            topSection.add(summaryLabel!!, BorderLayout.SOUTH)
            add(topSection, BorderLayout.NORTH)

            stepListModel = DefaultListModel()
            stepList = JBList(stepListModel!!).apply {
                cellRenderer = FlowStepCellRenderer()
            }
            add(JBScrollPane(stepList!!), BorderLayout.CENTER)

            val bottomSection = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val startTourPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                border = JBUI.Borders.empty(6, 0, 0, 0)
            }
            val startTourButton = JButton("Start Tour").apply {
                addActionListener { sessionService.startTour() }
            }
            startTourPanel.add(startTourButton)
            bottomSection.add(startTourPanel)

            val followUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
            followUpPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
            followUpField = JBTextField().apply field@{
                emptyText.setText("Ask a follow-up...")
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        val navigated = when (e.keyCode) {
                            KeyEvent.VK_UP -> historyUp(this@field, requireAtStart = false)
                            KeyEvent.VK_DOWN -> historyDown(this@field, requireAtEnd = false)
                            else -> false
                        }
                        if (navigated) e.consume()
                    }
                })
            }
            followUpPanel.add(followUpField!!, BorderLayout.CENTER)
            val sendButton = JButton("Send").apply {
                addActionListener {
                    val text = followUpField!!.text.trim()
                    if (text.isNotEmpty()) {
                        addToHistory(text)
                        sessionService.submitFollowUp(text)
                        followUpField!!.text = ""
                    }
                }
            }
            followUpPanel.add(sendButton, BorderLayout.EAST)
            bottomSection.add(followUpPanel)

            add(bottomSection, BorderLayout.SOUTH)
        }

        // Clarification content
        clarificationPanel = JPanel(BorderLayout()).apply {
            clarificationLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
            }
            add(clarificationLabel!!, BorderLayout.NORTH)

            val answerPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
            answerPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
            clarificationField = JBTextField().apply clarification@{
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        val navigated = when (e.keyCode) {
                            KeyEvent.VK_UP -> historyUp(this@clarification, requireAtStart = false)
                            KeyEvent.VK_DOWN -> historyDown(this@clarification, requireAtEnd = false)
                            else -> false
                        }
                        if (navigated) e.consume()
                    }
                })
            }
            answerPanel.add(clarificationField!!, BorderLayout.CENTER)
            val replyButton = JButton("Reply").apply {
                addActionListener {
                    val text = clarificationField!!.text.trim()
                    if (text.isNotEmpty()) {
                        addToHistory(text)
                        sessionService.answerClarification(text)
                        clarificationField!!.text = ""
                    }
                }
            }
            answerPanel.add(replyButton, BorderLayout.EAST)
            add(answerPanel, BorderLayout.SOUTH)
        }

        panel.add(overviewContentPanel!!, BorderLayout.CENTER)
        overviewPanel = panel
        return panel
    }

    private fun refreshOverviewCard() {
        val panel = overviewPanel ?: return
        val flowMap = sessionService.currentFlowMap
        val clarification = sessionService.clarificationQuestion

        if (clarification != null) {
            clarificationLabel!!.text = "<html><b>Clarification needed:</b><br>${escapeHtml(clarification)}</html>"
            panel.removeAll()
            panel.add(clarificationPanel!!, BorderLayout.CENTER)
        } else if (flowMap != null) {
            summaryLabel!!.text = "<html>${escapeHtml(flowMap.summary)}</html>"
            stepListModel!!.clear()
            flowMap.steps.forEach { stepListModel!!.addElement(it) }

            updateMetadataBar(sessionService.lastMetadata)

            panel.removeAll()
            panel.add(overviewContentPanel!!, BorderLayout.CENTER)
        }

        panel.revalidate()
        panel.repaint()
    }

    private fun updateMetadataBar(metadata: ResponseMetadata?) {
        if (metadata == null) {
            metadataBar?.isVisible = false
            return
        }

        val parts = mutableListOf<String>()

        val seconds = metadata.durationMs / 1000.0
        parts.add("%.1fs".format(seconds))

        parts.add("${metadata.stepCount} steps")
        parts.add("${metadata.fileCount} files")
        parts.add("${metadata.numTurns} turns")

        if (metadata.costUsd != null) {
            parts.add("$%.4f".format(metadata.costUsd))
        }

        metadataLabel?.text = parts.joinToString("  ·  ")
        metadataBar?.isVisible = true
    }

    // ── Tour Active Card ──────────────────────────────────────────────

    private fun createTourActiveCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        tourStepHeader = JBLabel().apply {
            font = JBUI.Fonts.label().asBold()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        contentPanel.add(tourStepHeader!!)

        tourUncertainLabel = JBLabel("(uncertain)").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
        contentPanel.add(tourUncertainLabel!!)

        tourStepFilePath = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        contentPanel.add(tourStepFilePath!!)

        tourStepExplanation = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.label()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(tourStepExplanation!!)

        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        val whyToggle = JBLabel("Why this step?").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(whyToggle)

        tourWhyText = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
        }
        tourWhySection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(tourWhyText!!, BorderLayout.CENTER)
            isVisible = false
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(tourWhySection!!)

        whyToggle.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                tourWhySection!!.isVisible = !tourWhySection!!.isVisible
                tourWhySection!!.revalidate()
                tourWhySection!!.repaint()
            }
        })

        panel.add(JBScrollPane(contentPanel), BorderLayout.CENTER)

        val navPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }
        navPanel.add(JButton("< Prev").apply {
            addActionListener { sessionService.prevStep() }
        })
        navPanel.add(JButton("Skip").apply {
            addActionListener { sessionService.skipStep() }
        })
        navPanel.add(JButton("Next >").apply {
            addActionListener { sessionService.nextStep() }
        })
        navPanel.add(JButton("Stop Tour").apply {
            addActionListener { sessionService.stopTour() }
        })
        panel.add(navPanel, BorderLayout.SOUTH)

        tourActivePanel = panel
        return panel
    }

    private fun refreshTourActiveCard(stepIndex: Int, step: FlowStep) {
        val totalSteps = sessionService.currentFlowMap?.steps?.size ?: 0
        tourStepHeader?.text = "Step ${stepIndex + 1}/$totalSteps \u2014 ${step.title}"
        tourStepFilePath?.text = step.filePath
        tourStepExplanation?.text = step.explanation
        tourWhyText?.text = step.whyIncluded
        tourWhySection?.isVisible = false
        tourUncertainLabel?.isVisible = step.uncertain

        tourActivePanel?.revalidate()
        tourActivePanel?.repaint()
    }

    // ── CLI Status ──────────────────────────────────────────────────────

    private fun checkCliStatus() {
        scope.launch {
            val status = project.service<ClaudeCliService>().checkAvailability()
            javax.swing.SwingUtilities.invokeLater {
                if (status.available) {
                    statusDot?.icon = AllIcons.General.InspectionsOK
                    statusDot?.text = status.versionOrError
                    statusDot?.foreground = JBColor.namedColor(
                        "Label.successForeground",
                        JBColor(0x3D8F58, 0x499C54),
                    )
                } else {
                    statusDot?.icon = AllIcons.General.Error
                    statusDot?.text = status.versionOrError
                    statusDot?.foreground = JBColor.namedColor(
                        "Label.errorForeground",
                        JBColor.RED,
                    )
                }
            }
        }
    }

    // ── Shared ───────────────────────────────────────────────────────────

    private fun showErrorBannerIfNeeded() {
        val message = sessionService.errorMessage
        if (message != null) {
            errorBanner!!.text = message
            errorBanner!!.isVisible = true
            errorBannerPanel!!.isVisible = true
            errorBannerPanel!!.background = JBUI.CurrentTheme.Notification.Error.BACKGROUND
            errorBannerPanel!!.border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(0, 0, 4, 0),
                JBUI.Borders.empty(4),
            )
        } else {
            clearErrorBanner()
        }
    }

    private fun clearErrorBanner() {
        errorBanner?.isVisible = false
        errorBannerPanel?.isVisible = false
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class FlowStepCellRenderer : ListCellRenderer<FlowStep> {
        override fun getListCellRendererComponent(
            list: JList<out FlowStep>,
            value: FlowStep,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val component = SimpleColoredComponent()
            component.ipad = JBUI.insets(4)
            component.isOpaque = true

            if (isSelected) {
                component.background = list.selectionBackground
                component.foreground = list.selectionForeground
            } else {
                component.background = list.background
                component.foreground = list.foreground
            }

            val stepNumber = "${index + 1}. "
            if (value.broken) {
                component.append(stepNumber, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                component.append(
                    value.title,
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getLabelDisabledForeground()),
                )
                component.append("  ${value.filePath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            } else {
                component.append(stepNumber, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                component.append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (value.uncertain) {
                    component.append(" (uncertain)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                component.append("  ${value.filePath}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            return component
        }
    }

    // ── Command History ──────────────────────────────────────────────────

    private fun addToHistory(text: String) {
        if (history.isEmpty() || history.last() != text) {
            history.add(text)
        }
        historyIndex = history.size
        historyDraft = ""
    }

    /** Navigate to the previous history entry. Returns true if navigation occurred. */
    private fun historyUp(field: javax.swing.text.JTextComponent, requireAtStart: Boolean): Boolean {
        if (history.isEmpty() || historyIndex == 0) return false
        if (requireAtStart && field.caretPosition != 0) return false
        if (historyIndex == history.size) {
            historyDraft = field.text
        }
        historyIndex--
        field.text = history[historyIndex]
        field.caretPosition = 0
        return true
    }

    /** Navigate to the next history entry (or restore draft). Returns true if navigation occurred. */
    private fun historyDown(field: javax.swing.text.JTextComponent, requireAtEnd: Boolean): Boolean {
        if (historyIndex >= history.size) return false
        if (requireAtEnd && field.caretPosition != field.document.length) return false
        historyIndex++
        val newText = if (historyIndex < history.size) history[historyIndex] else historyDraft
        field.text = newText
        field.caretPosition = newText.length
        return true
    }

    companion object {
        private const val CARD_INPUT = "INPUT"
        private const val CARD_LOADING = "LOADING"
        private const val CARD_OVERVIEW = "OVERVIEW"
        private const val CARD_TOUR_ACTIVE = "TOUR_ACTIVE"
    }
}
