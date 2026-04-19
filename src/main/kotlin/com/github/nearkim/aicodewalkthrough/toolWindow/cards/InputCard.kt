package com.github.nearkim.aicodewalkthrough.toolwindow.cards

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.service.LlmProviderService
import com.github.nearkim.aicodewalkthrough.service.ProviderStatus
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.UIManager

class InputCard(
    private val project: Project,
    private val onSubmit: (prompt: String, mode: AnalysisMode, provider: AiProvider) -> Unit,
) : JPanel(BorderLayout()) {

    private val providerService = project.service<LlmProviderService>()

    private val errorBanner = JBLabel().apply {
        isOpaque = true
        background = JBColor(Color(255, 247, 204), Color(92, 80, 40))
        foreground = JBColor(Color(80, 60, 20), Color(235, 215, 150))
        border = JBUI.Borders.empty(6, 10)
        isVisible = false
    }

    private val modeButtons = mutableMapOf<AnalysisMode, JToggleButton>()
    private val helperLabel = JBLabel().apply {
        foreground = JBColor(Color(120, 120, 120), Color(160, 160, 160))
        border = JBUI.Borders.empty(2, 4, 6, 4)
    }

    private val promptArea = JBTextArea(4, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(6),
        )
    }

    private val providerCombo = JComboBox(arrayOf(AiProvider.CLAUDE_CLI, AiProvider.CODEX_CLI))
    private val providerStatusDot = JBLabel("\u25CF").apply {
        foreground = JBColor.GRAY
        toolTipText = "Checking provider..."
    }
    private val submitButton = JButton("Start walkthrough").apply {
        isDefaultCapable = true
    }

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var selectedMode = AnalysisMode.UNDERSTAND
    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        border = JBUI.Borders.empty(10)
        add(buildContent(), BorderLayout.CENTER)

        promptArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        e.consume()
                        submit()
                    }
                    e.keyCode == KeyEvent.VK_UP && promptArea.caretPosition == 0 -> {
                        if (history.isNotEmpty()) {
                            historyIndex = (historyIndex - 1).coerceAtLeast(0)
                            promptArea.text = history[historyIndex]
                            e.consume()
                        }
                    }
                    e.keyCode == KeyEvent.VK_DOWN && promptArea.caretPosition == promptArea.document.length -> {
                        if (history.isNotEmpty() && historyIndex < history.size - 1) {
                            historyIndex += 1
                            promptArea.text = history[historyIndex]
                            e.consume()
                        }
                    }
                }
            }
        })

        promptArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = clearError()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = clearError()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })

        submitButton.addActionListener { submit() }

        providerCombo.addActionListener { refreshProviderStatus() }
        refreshProviderStatus()
        updateHelper()
    }

    fun showError(message: String) {
        errorBanner.text = message
        errorBanner.isVisible = true
        revalidate()
        repaint()
    }

    fun clearError() {
        errorBanner.isVisible = false
        errorBanner.text = ""
        revalidate()
        repaint()
    }

    private fun submit() {
        val prompt = promptArea.text?.trim().orEmpty()
        if (prompt.isEmpty()) {
            showError("Please enter a prompt before starting a walkthrough.")
            return
        }
        history.add(prompt)
        historyIndex = history.size
        val provider = providerCombo.selectedItem as? AiProvider ?: AiProvider.CLAUDE_CLI
        onSubmit(prompt, selectedMode, provider)
    }

    private fun buildContent(): JPanel {
        val root = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        root.add(errorBanner)
        root.add(buildModeRow())
        root.add(helperLabel)
        root.add(Box.createVerticalStrut(6))
        val promptScroll = com.intellij.ui.components.JBScrollPane(promptArea).apply {
            preferredSize = Dimension(200, 100)
            alignmentX = LEFT_ALIGNMENT
        }
        root.add(promptScroll)
        root.add(Box.createVerticalStrut(6))
        root.add(buildProviderRow())
        return root
    }

    private fun buildModeRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val group = ButtonGroup()
        AnalysisMode.entries.forEach { mode ->
            val btn = JToggleButton(mode.displayName).apply {
                horizontalAlignment = SwingConstants.CENTER
                addActionListener {
                    selectedMode = mode
                    updateHelper()
                }
            }
            if (mode == selectedMode) btn.isSelected = true
            group.add(btn)
            row.add(btn)
            modeButtons[mode] = btn
        }
        return row
    }

    private fun buildProviderRow(): JPanel {
        val row = JPanel(BorderLayout())
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        leftGroup.add(JBLabel("Provider:"))
        leftGroup.add(providerCombo)
        leftGroup.add(providerStatusDot)
        row.add(leftGroup, BorderLayout.WEST)
        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        rightGroup.add(submitButton)
        row.add(rightGroup, BorderLayout.EAST)
        return row
    }

    private fun updateHelper() {
        helperLabel.text = when (selectedMode) {
            AnalysisMode.UNDERSTAND -> "Explain what the code does and how the main pieces fit together."
            AnalysisMode.REVIEW -> "Focus on correctness, regressions, and actionable review notes."
            AnalysisMode.TRACE -> "Trace callers and callees along a concrete execution path."
        }
    }

    private fun refreshProviderStatus() {
        val provider = providerCombo.selectedItem as? AiProvider ?: return
        providerStatusDot.foreground = JBColor.GRAY
        providerStatusDot.toolTipText = "Checking ${provider.displayName}..."
        submitButton.isEnabled = true
        statusScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { providerService.checkAvailability(provider) }.getOrNull()
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                applyProviderStatus(status)
            }
        }
    }

    private fun applyProviderStatus(status: ProviderStatus?) {
        if (status == null) {
            providerStatusDot.foreground = JBColor.GRAY
            providerStatusDot.toolTipText = "Provider status unavailable."
            submitButton.isEnabled = true
            return
        }
        val ok = status.available && status.walkthroughSupported
        providerStatusDot.foreground = if (ok) {
            JBColor(Color(70, 160, 90), Color(110, 200, 130))
        } else {
            JBColor(Color(200, 80, 70), Color(235, 130, 120))
        }
        providerStatusDot.toolTipText = status.message
        submitButton.isEnabled = ok
    }
}
