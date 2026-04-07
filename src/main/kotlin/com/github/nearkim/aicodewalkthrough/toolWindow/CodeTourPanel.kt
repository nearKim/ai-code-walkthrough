package com.github.nearkim.aicodewalkthrough.toolwindow

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

class CodeTourPanel(private val project: Project) :
    JPanel(BorderLayout()),
    TourSessionService.TourSessionListener {

    private val sessionService = project.service<TourSessionService>()
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private var errorBanner: JBLabel? = null
    private var errorBannerPanel: JPanel? = null

    init {
        cardPanel.add(createInputCard(), CARD_INPUT)
        cardPanel.add(createLoadingCard(), CARD_LOADING)
        cardPanel.add(createOverviewCard(), CARD_OVERVIEW)

        add(cardPanel, BorderLayout.CENTER)

        sessionService.addListener(this)
        showCard(sessionService.state)
    }

    override fun onStateChanged(state: TourState) {
        showCard(state)
    }

    override fun removeNotify() {
        super.removeNotify()
        sessionService.removeListener(this)
    }

    private fun showCard(state: TourState) {
        when (state) {
            TourState.INPUT -> {
                showErrorBannerIfNeeded()
                cardLayout.show(cardPanel, CARD_INPUT)
            }
            TourState.LOADING -> {
                clearErrorBanner()
                cardLayout.show(cardPanel, CARD_LOADING)
            }
            TourState.OVERVIEW -> {
                clearErrorBanner()
                refreshOverviewCard()
                cardLayout.show(cardPanel, CARD_OVERVIEW)
            }
            TourState.TOUR_ACTIVE -> {
                clearErrorBanner()
                cardLayout.show(cardPanel, CARD_OVERVIEW)
            }
        }
    }

    private fun createInputCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        errorBannerPanel = JPanel(BorderLayout())
        errorBanner = JBLabel().apply {
            icon = AllIcons.General.Error
            isVisible = false
            border = JBUI.Borders.empty(6)
        }
        errorBannerPanel!!.add(errorBanner!!, BorderLayout.CENTER)
        errorBannerPanel!!.isVisible = false
        panel.add(errorBannerPanel!!, BorderLayout.NORTH)

        val textArea = JBTextArea(4, 0).apply {
            emptyText.setText("Ask about your codebase...")
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val mapFlowButton = JButton("Map Flow").apply {
            addActionListener {
                val question = textArea.text.trim()
                if (question.isNotEmpty()) {
                    sessionService.startMapping(question)
                }
            }
        }
        buttonPanel.add(mapFlowButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createLoadingCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        val centerPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        centerPanel.add(JBLabel("Mapping flow...", AnimatedIcon.Default(), SwingConstants.LEFT))
        panel.add(centerPanel, BorderLayout.CENTER)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val cancelButton = JButton("Cancel").apply {
            addActionListener { sessionService.cancelRequest() }
        }
        buttonPanel.add(cancelButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private var overviewPanel: JPanel? = null
    private var summaryLabel: JBLabel? = null
    private var stepListModel: DefaultListModel<FlowStep>? = null
    private var stepList: JBList<FlowStep>? = null
    private var followUpField: JBTextField? = null
    private var overviewContentPanel: JPanel? = null
    private var clarificationPanel: JPanel? = null
    private var clarificationLabel: JBLabel? = null
    private var clarificationField: JBTextField? = null

    private fun createOverviewCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        overviewContentPanel = JPanel(BorderLayout()).apply {
            summaryLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
            }
            add(summaryLabel!!, BorderLayout.NORTH)

            stepListModel = DefaultListModel()
            stepList = JBList(stepListModel!!).apply {
                cellRenderer = FlowStepCellRenderer()
            }
            add(JBScrollPane(stepList!!), BorderLayout.CENTER)

            val followUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
            followUpPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
            followUpField = JBTextField().apply {
                emptyText.setText("Ask a follow-up...")
            }
            followUpPanel.add(followUpField!!, BorderLayout.CENTER)
            val sendButton = JButton("Send").apply {
                addActionListener {
                    val text = followUpField!!.text.trim()
                    if (text.isNotEmpty()) {
                        sessionService.submitFollowUp(text)
                        followUpField!!.text = ""
                    }
                }
            }
            followUpPanel.add(sendButton, BorderLayout.EAST)
            add(followUpPanel, BorderLayout.SOUTH)
        }

        clarificationPanel = JPanel(BorderLayout()).apply {
            clarificationLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
            }
            add(clarificationLabel!!, BorderLayout.NORTH)

            val answerPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
            answerPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
            clarificationField = JBTextField()
            answerPanel.add(clarificationField!!, BorderLayout.CENTER)
            val replyButton = JButton("Reply").apply {
                addActionListener {
                    val text = clarificationField!!.text.trim()
                    if (text.isNotEmpty()) {
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
            panel.removeAll()
            panel.add(overviewContentPanel!!, BorderLayout.CENTER)
        }

        panel.revalidate()
        panel.repaint()
    }

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

    companion object {
        private const val CARD_INPUT = "INPUT"
        private const val CARD_LOADING = "LOADING"
        private const val CARD_OVERVIEW = "OVERVIEW"
    }
}
