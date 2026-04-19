package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke

class TourActiveCard(
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onStop: () -> Unit,
    private val onGoToCode: () -> Unit,
    private val onAskFollowUp: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val headerLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
    }
    private val subtitleLabel = JBLabel(" ").apply {
        foreground = JBColor(Color(120, 120, 120), Color(160, 160, 160))
    }
    private val goToCodeLink = JBLabel("Go to code").apply {
        foreground = JBColor(Color(60, 110, 190), Color(130, 170, 225))
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val prevButton = JButton("\u25C0 Prev")
    private val nextButton = JButton("Next \u25B6")
    private val stopButton = JButton("Stop")

    private val followUpField = JBTextField().apply {
        emptyText.setText("Ask about this step...")
    }
    private val answerArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6)
    }
    private val answerScroll = JBScrollPane(answerArea).apply {
        preferredSize = java.awt.Dimension(200, 140)
    }
    private val statusLabel = JBLabel(" ").apply {
        foreground = JBColor(Color(120, 120, 120), Color(160, 160, 160))
    }

    init {
        border = JBUI.Borders.empty(10)
        layout = BorderLayout()
        add(buildHeaderPanel(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)

        prevButton.addActionListener { onPrev() }
        nextButton.addActionListener { onNext() }
        stopButton.addActionListener { onStop() }
        goToCodeLink.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { onGoToCode() }
        })

        followUpField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val text = followUpField.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        onAskFollowUp(text)
                        followUpField.text = ""
                    }
                    e.consume()
                }
            }
        })

        registerArrowShortcuts()
    }

    fun setStep(stepIndex: Int, totalSteps: Int, step: FlowStep) {
        headerLabel.text = "Step ${stepIndex + 1}/$totalSteps · ${step.title}"
        subtitleLabel.text = "${step.filePath}:${step.startLine}-${step.endLine}"
        clearAnswer()
    }

    fun setAnswer(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {
        when {
            loading -> {
                statusLabel.text = "Loading..."
                answerArea.text = ""
            }
            errorMessage != null -> {
                statusLabel.text = "Error: $errorMessage"
                answerArea.text = ""
            }
            answer != null -> {
                statusLabel.text = "Done"
                answerArea.text = buildString {
                    append(answer.answer.trim())
                    answer.whyItMatters?.takeIf { it.isNotBlank() }?.let {
                        append("\n\nWhy it matters: ")
                        append(it.trim())
                    }
                }
                answerArea.caretPosition = 0
            }
            else -> {
                statusLabel.text = " "
                answerArea.text = ""
            }
        }
    }

    fun clearAnswer() {
        statusLabel.text = " "
        answerArea.text = ""
    }

    private fun buildHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        val left = JPanel()
        left.layout = BoxLayout(left, BoxLayout.Y_AXIS)
        left.add(headerLabel)
        left.add(subtitleLabel)
        panel.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        right.add(goToCodeLink)
        panel.add(right, BorderLayout.EAST)

        val navRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4))
        navRow.add(prevButton)
        navRow.add(nextButton)
        navRow.add(stopButton)

        val wrapper = JPanel(BorderLayout())
        wrapper.add(panel, BorderLayout.NORTH)
        wrapper.add(navRow, BorderLayout.SOUTH)
        return wrapper
    }

    private fun buildCenter(): JPanel {
        val center = JPanel(BorderLayout())
        val followUpPanel = JPanel(BorderLayout())
        followUpField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(4, 6),
        )
        followUpPanel.add(followUpField, BorderLayout.NORTH)
        followUpPanel.add(Box.createVerticalStrut(6), BorderLayout.CENTER)
        center.add(followUpPanel, BorderLayout.NORTH)
        center.add(answerScroll, BorderLayout.CENTER)
        center.add(statusLabel, BorderLayout.SOUTH)
        return center
    }

    private fun registerArrowShortcuts() {
        val inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = actionMap
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "codeTour.prev")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "codeTour.next")
        actionMap.put("codeTour.prev", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (!followUpField.isFocusOwner) onPrev()
            }
        })
        actionMap.put("codeTour.next", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (!followUpField.isFocusOwner) onNext()
            }
        })
    }
}
