package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
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
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

private const val VIEW_STEP = "STEP"
private const val VIEW_ANSWER = "ANSWER"
private const val MAX_PUNCH_LENGTH = 200

class TourActiveCard(
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onStop: () -> Unit,
    private val onGoToCode: () -> Unit,
    private val onAskFollowUp: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val headerLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, font.size + 2f)
    }
    private val subtitleLabel = JBLabel(" ").apply {
        foreground = mutedForeground()
    }
    private val goToCodeLink = linkLabel("Go to code") { onGoToCode() }

    private val prevButton = JButton("\u25C0 Prev")
    private val nextButton = JButton("Next \u25B6")
    private val stopButton = JButton("Stop")

    private val stepPunch = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, font.size + 1f)
    }
    private val stepDetail = readOnlyText()
    private val annotationsHeader = JBLabel("Important lines:").apply {
        foreground = mutedForeground()
    }
    private val annotationsList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val stepView = buildStepView()

    private val backToStepLink = linkLabel("\u2190 Back to step") { showStepView() }
    private val answerPunch = JBLabel(" ").apply {
        font = font.deriveFont(Font.BOLD, font.size + 1f)
    }
    private val answerBody = createHtmlPane()
    private val whyItMattersLabel = JBLabel(" ").apply {
        font = font.deriveFont(Font.ITALIC)
        foreground = mutedForeground()
    }
    private val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        isOpaque = false
        add(JBLabel(AnimatedIcon.Default.INSTANCE))
        add(JBLabel("Thinking\u2026").apply { foreground = mutedForeground() })
        isVisible = false
    }
    private val answerView = buildAnswerView()

    private val bodyCards = CardLayout()
    private val body = JPanel(bodyCards).apply {
        add(stepView, VIEW_STEP)
        add(answerView, VIEW_ANSWER)
    }

    private val followUpField = JBTextField().apply {
        emptyText.setText("Ask about this step...")
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(4, 6),
        )
    }

    private var currentAnswer: StepAnswer? = null

    init {
        border = JBUI.Borders.empty(6, 8)
        add(buildHeaderPanel(), BorderLayout.NORTH)
        add(JBScrollPane(body).apply {
            border = BorderFactory.createEmptyBorder()
        }, BorderLayout.CENTER)
        add(buildFollowUpStrip(), BorderLayout.SOUTH)

        prevButton.addActionListener { onPrev() }
        nextButton.addActionListener { onNext() }
        stopButton.addActionListener { onStop() }

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

        registerShortcuts()
    }

    fun setStep(stepIndex: Int, totalSteps: Int, step: FlowStep) {
        headerLabel.text = "Step ${stepIndex + 1}/$totalSteps \u00B7 ${step.title}"
        subtitleLabel.text = "${step.filePath}:${step.startLine}-${step.endLine}"
        populateStepView(step)
        currentAnswer = null
        answerPunch.text = " "
        answerBody.text = ""
        whyItMattersLabel.text = " "
        loadingPanel.isVisible = false
        showStepView()
    }

    fun setAnswer(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {
        when {
            loading -> {
                loadingPanel.isVisible = true
                answerPunch.text = " "
                answerBody.text = ""
                whyItMattersLabel.text = " "
                showAnswerView()
            }
            errorMessage != null -> {
                loadingPanel.isVisible = false
                answerPunch.text = " "
                answerBody.text = renderHtml("<p style='color:#cc4444;'>Error: ${escapeHtml(errorMessage)}</p>")
                whyItMattersLabel.text = " "
                showAnswerView()
            }
            answer != null -> {
                loadingPanel.isVisible = false
                currentAnswer = answer
                val (punch, rest) = splitPunch(answer.answer)
                answerPunch.text = punch
                answerBody.text = renderHtml(markdownToHtml(rest))
                answerBody.caretPosition = 0
                whyItMattersLabel.text = answer.whyItMatters
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "Why it matters: ${it.trim()}" }
                    ?: " "
                showAnswerView()
            }
            else -> {
                loadingPanel.isVisible = false
                currentAnswer = null
                showStepView()
            }
        }
    }

    fun clearAnswer() {
        currentAnswer = null
        answerPunch.text = " "
        answerBody.text = ""
        whyItMattersLabel.text = " "
        loadingPanel.isVisible = false
        showStepView()
    }

    private fun showStepView() = bodyCards.show(body, VIEW_STEP)
    private fun showAnswerView() = bodyCards.show(body, VIEW_ANSWER)

    private fun populateStepView(step: FlowStep) {
        stepPunch.text = step.whyIncluded.takeIf { it.isNotBlank() } ?: step.title
        stepDetail.text = step.detailedExplanation?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: step.explanation.trim()
        stepDetail.caretPosition = 0

        annotationsList.removeAll()
        val annotations = step.lineAnnotations.filter { it.text.isNotBlank() }
        annotationsHeader.isVisible = annotations.isNotEmpty()
        annotationsList.isVisible = annotations.isNotEmpty()
        annotations.forEach { annotation ->
            annotationsList.add(buildAnnotationRow(annotation))
        }
        annotationsList.revalidate()
        annotationsList.repaint()
    }

    private fun buildStepView(): JPanel {
        val panel = columnPanel()
        panel.add(stepPunch.alignLeft())
        panel.add(Box.createVerticalStrut(6))
        panel.add(stepDetail.alignLeft())
        panel.add(Box.createVerticalStrut(8))
        panel.add(annotationsHeader.alignLeft())
        panel.add(Box.createVerticalStrut(2))
        panel.add(annotationsList.alignLeft())
        panel.add(Box.createVerticalGlue())
        return panel
    }

    private fun buildAnswerView(): JPanel {
        val panel = columnPanel()
        panel.add(backToStepLink.alignLeft())
        panel.add(Box.createVerticalStrut(6))
        panel.add(loadingPanel.alignLeft())
        panel.add(answerPunch.alignLeft())
        panel.add(Box.createVerticalStrut(6))
        panel.add(answerBody.alignLeft())
        panel.add(Box.createVerticalStrut(6))
        panel.add(whyItMattersLabel.alignLeft())
        panel.add(Box.createVerticalGlue())
        return panel
    }

    private fun buildAnnotationRow(annotation: LineAnnotation): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            isOpaque = false
        }
        val lineLabel = JBLabel("L${annotation.startLine}").apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            foreground = mutedForeground()
        }
        row.add(lineLabel)
        row.add(JBLabel(annotation.text.trim()))
        // Cap vertical growth *after* children are added so preferredSize.height is accurate;
        // otherwise BoxLayout Y_AXIS stretches the row to fill and hides later rows.
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun buildHeaderPanel(): JPanel {
        val titleRow = JPanel(BorderLayout()).apply {
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(headerLabel)
                add(subtitleLabel)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                add(goToCodeLink)
            }, BorderLayout.EAST)
        }
        val navRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(prevButton)
            add(nextButton)
            add(stopButton)
        }
        val wrapper = JPanel(BorderLayout())
        wrapper.add(titleRow, BorderLayout.NORTH)
        wrapper.add(navRow, BorderLayout.SOUTH)
        wrapper.border = JBUI.Borders.emptyBottom(4)
        return wrapper
    }

    private fun buildFollowUpStrip(): JPanel {
        val strip = JPanel(BorderLayout())
        strip.border = JBUI.Borders.emptyTop(4)
        strip.add(followUpField, BorderLayout.CENTER)
        return strip
    }

    private fun registerShortcuts() {
        val inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = actionMap
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "codeTour.prev")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "codeTour.next")
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "codeTour.backToStep")
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
        actionMap.put("codeTour.backToStep", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (!followUpField.isFocusOwner) showStepView()
            }
        })
    }

    private fun columnPanel(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(4, 2)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun readOnlyText(): JTextArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun linkLabel(text: String, onClick: () -> Unit): JBLabel =
        JBLabel(text).apply {
            foreground = JBColor(Color(60, 110, 190), Color(130, 170, 225))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { onClick() }
            })
        }

    private fun mutedForeground(): JBColor =
        JBColor(Color(120, 120, 120), Color(160, 160, 160))

    private fun splitPunch(text: String): Pair<String, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return " " to ""
        val breakIndex = listOf(
            trimmed.indexOf("\n\n"),
            trimmed.indexOf('\n'),
            trimmed.indexOf(". ").let { if (it >= 0) it + 1 else -1 },
        ).filter { it in 1..MAX_PUNCH_LENGTH }.minOrNull()
        return if (breakIndex != null) {
            trimmed.substring(0, breakIndex).trim() to trimmed.substring(breakIndex).trim()
        } else if (trimmed.length <= MAX_PUNCH_LENGTH) {
            trimmed to ""
        } else {
            " " to trimmed
        }
    }

    private fun <T : JComponent> T.alignLeft(): T {
        alignmentX = Component.LEFT_ALIGNMENT
        return this
    }

    private fun createHtmlPane(): JEditorPane = JEditorPane().apply {
        contentType = "text/html"
        isEditable = false
        isOpaque = false
        border = BorderFactory.createEmptyBorder()
        alignmentX = Component.LEFT_ALIGNMENT
        val kit = HTMLEditorKit()
        val ss = StyleSheet()
        val fg = JBColor.foreground()
        val fgHex = String.format("#%02x%02x%02x", fg.red, fg.green, fg.blue)
        val codeBg = JBColor(Color(245, 245, 245), Color(45, 45, 45))
        val codeBgHex = String.format("#%02x%02x%02x", codeBg.red, codeBg.green, codeBg.blue)
        ss.addRule("body { font-family: sans-serif; font-size: 12px; color: $fgHex; margin: 0; padding: 0; }")
        ss.addRule("code { font-family: monospace; background: $codeBgHex; padding: 1px 4px; }")
        ss.addRule("pre { font-family: monospace; background: $codeBgHex; padding: 8px; }")
        ss.addRule("ul, ol { margin: 4px 0 4px 16px; padding: 0; }")
        ss.addRule("li { margin: 2px 0; }")
        ss.addRule("p { margin: 4px 0; }")
        kit.styleSheet = ss
        editorKit = kit
    }

    private fun renderHtml(bodyHtml: String): String =
        "<html><body>$bodyHtml</body></html>"

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun markdownToHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inCodeBlock = false
        var inList = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                if (inCodeBlock) {
                    sb.append("</pre>")
                    inCodeBlock = false
                } else {
                    if (inList) { sb.append("</ul>"); inList = false }
                    sb.append("<pre>")
                    inCodeBlock = true
                }
                i++
                continue
            }
            if (inCodeBlock) {
                sb.append(escapeHtml(line)).append("\n")
                i++
                continue
            }
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (inList) { sb.append("</ul>"); inList = false }
                i++
                continue
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) { sb.append("<ul>"); inList = true }
                sb.append("<li>").append(inlineFormat(escapeHtml(trimmed.substring(2)))).append("</li>")
                i++
                continue
            }
            val numberedMatch = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
            if (numberedMatch != null) {
                if (!inList) { sb.append("<ul>"); inList = true }
                sb.append("<li>").append(inlineFormat(escapeHtml(numberedMatch.groupValues[2]))).append("</li>")
                i++
                continue
            }
            if (inList) { sb.append("</ul>"); inList = false }
            sb.append("<p>").append(inlineFormat(escapeHtml(trimmed))).append("</p>")
            i++
        }
        if (inCodeBlock) sb.append("</pre>")
        if (inList) sb.append("</ul>")
        return sb.toString()
    }

    private fun inlineFormat(escaped: String): String {
        var result = escaped
        result = Regex("`([^`]+)`").replace(result) { "<code>${it.groupValues[1]}</code>" }
        result = Regex("\\*\\*([^*]+)\\*\\*").replace(result) { "<b>${it.groupValues[1]}</b>" }
        result = Regex("\\*([^*]+)\\*").replace(result) { "<i>${it.groupValues[1]}</i>" }
        return result
    }
}
