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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

class InputCard(
    project: Project,
    private val scope: CoroutineScope,
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

    private val modeCards = mutableMapOf<AnalysisMode, JPanel>()

    private val promptArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.setText("Ask about this codebase...")
        margin = JBUI.insets(8, 10)
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
    private var historyIndex = 0
    private var savedDraft: String = ""
    private var selectedMode = AnalysisMode.UNDERSTAND

    init {
        border = JBUI.Borders.empty(6, 8)
        add(buildContent(), BorderLayout.CENTER)

        promptArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        e.consume()
                        submit()
                    }
                    e.keyCode == KeyEvent.VK_UP && promptArea.caretPosition == 0 -> {
                        if (history.isNotEmpty() && historyIndex > 0) {
                            if (historyIndex == history.size) {
                                savedDraft = promptArea.text.orEmpty()
                            }
                            historyIndex -= 1
                            promptArea.text = history[historyIndex]
                            e.consume()
                        }
                    }
                    e.keyCode == KeyEvent.VK_DOWN && promptArea.caretPosition == promptArea.document.length -> {
                        if (history.isNotEmpty() && historyIndex < history.size) {
                            historyIndex += 1
                            promptArea.text = if (historyIndex == history.size) savedDraft else history[historyIndex]
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
        updateModeSelection()
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
        savedDraft = ""
        val provider = providerCombo.selectedItem as? AiProvider ?: AiProvider.CLAUDE_CLI
        onSubmit(prompt, selectedMode, provider)
    }

    private fun buildContent(): JPanel {
        val root = JPanel(BorderLayout())

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            errorBanner.alignmentX = LEFT_ALIGNMENT
            errorBanner.maximumSize = Dimension(Int.MAX_VALUE, errorBanner.preferredSize.height.coerceAtLeast(24))
            add(errorBanner)
            val modeSelector = buildModeSelector().apply { alignmentX = LEFT_ALIGNMENT }
            add(modeSelector)
        }
        root.add(topPanel, BorderLayout.NORTH)

        val promptScroll = com.intellij.ui.components.JBScrollPane(promptArea).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                BorderFactory.createEmptyBorder(),
            )
            minimumSize = Dimension(0, 80)
        }
        root.add(promptScroll, BorderLayout.CENTER)

        val bottomPanel = buildProviderRow().apply {
            border = JBUI.Borders.emptyTop(6)
        }
        root.add(bottomPanel, BorderLayout.SOUTH)

        return root
    }

    private fun buildModeSelector(): JPanel {
        val row = JPanel(GridLayout(1, AnalysisMode.entries.size, 6, 0)).apply {
            border = JBUI.Borders.emptyBottom(6)
        }
        AnalysisMode.entries.forEach { mode ->
            val card = buildModeCard(mode)
            modeCards[mode] = card
            row.add(card)
        }
        return row
    }

    private fun buildModeCard(mode: AnalysisMode): JPanel {
        val titleLabel = JBLabel(mode.displayName).apply {
            font = font.deriveFont(Font.BOLD)
        }
        val descLabel = JBLabel("<html>${mode.description}</html>").apply {
            foreground = JBColor(Color(100, 100, 100), Color(150, 150, 150))
            font = font.deriveFont(font.size - 1f)
        }
        val exampleLabel = JBLabel("<html>${mode.example}</html>").apply {
            foreground = JBColor(Color(130, 130, 130), Color(120, 120, 120))
            font = font.deriveFont(Font.ITALIC, font.size - 1.5f)
            border = JBUI.Borders.emptyTop(3)
        }
        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8, 10),
            )
            isOpaque = true
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(titleLabel)
            add(descLabel)
            add(exampleLabel)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    selectedMode = mode
                    updateModeSelection()
                }
            })
        }
        return card
    }

    private fun updateModeSelection() {
        val selectedBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(70, 130, 210), Color(90, 150, 230)), 2),
            JBUI.Borders.empty(7, 9),
        )
        val defaultBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border()),
            JBUI.Borders.empty(8, 10),
        )
        val selectedBg = JBColor(Color(235, 244, 255), Color(40, 55, 75))
        modeCards.forEach { (mode, card) ->
            if (mode == selectedMode) {
                card.border = selectedBorder
                card.background = selectedBg
            } else {
                card.border = defaultBorder
                card.background = JBColor.PanelBackground
            }
        }
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

    private fun refreshProviderStatus() {
        val provider = providerCombo.selectedItem as? AiProvider ?: return
        providerStatusDot.foreground = JBColor.GRAY
        providerStatusDot.toolTipText = "Checking ${provider.displayName}..."
        submitButton.isEnabled = true
        scope.launch {
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
