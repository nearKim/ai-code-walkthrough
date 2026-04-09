package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CodeTourConfigurable(private val project: Project) : Configurable {

    private lateinit var providerCombo: JComboBox<AiProvider>
    private lateinit var codexCliPathField: JBTextField
    private lateinit var claudePathField: JBTextField
    private lateinit var openAiModelField: JBTextField
    private lateinit var claudeApiModelField: JBTextField
    private lateinit var geminiModelField: JBTextField
    private lateinit var openAiApiKeyField: JPasswordField
    private lateinit var claudeApiKeyField: JPasswordField
    private lateinit var geminiApiKeyField: JPasswordField
    private lateinit var openAiKeyStatusLabel: JBLabel
    private lateinit var claudeApiKeyStatusLabel: JBLabel
    private lateinit var geminiKeyStatusLabel: JBLabel
    private lateinit var clearOpenAiApiKeyCheckBox: JBCheckBox
    private lateinit var clearClaudeApiKeyCheckBox: JBCheckBox
    private lateinit var clearGeminiApiKeyCheckBox: JBCheckBox
    private lateinit var requestTimeoutSpinner: JSpinner
    private lateinit var maxStepsSpinner: JSpinner
    private lateinit var enableMcpCheckBox: JBCheckBox
    private lateinit var mcpConfigPathField: JBTextField
    private lateinit var codexPanel: JPanel
    private lateinit var claudeCliPanel: JPanel
    private lateinit var openAiPanel: JPanel
    private lateinit var claudeApiPanel: JPanel
    private lateinit var geminiPanel: JPanel
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AI Code Walkthrough"

    override fun createComponent(): JComponent {
        providerCombo = JComboBox(AiProvider.entries.toTypedArray()).apply {
            addActionListener { refreshProviderSections() }
        }

        codexCliPathField = JBTextField()
        claudePathField = JBTextField()
        openAiModelField = JBTextField()
        claudeApiModelField = JBTextField()
        geminiModelField = JBTextField()
        openAiApiKeyField = JPasswordField()
        claudeApiKeyField = JPasswordField()
        geminiApiKeyField = JPasswordField()
        openAiKeyStatusLabel = JBLabel()
        claudeApiKeyStatusLabel = JBLabel()
        geminiKeyStatusLabel = JBLabel()
        clearOpenAiApiKeyCheckBox = JBCheckBox("Clear stored API key")
        clearClaudeApiKeyCheckBox = JBCheckBox("Clear stored API key")
        clearGeminiApiKeyCheckBox = JBCheckBox("Clear stored API key")
        requestTimeoutSpinner = JSpinner(SpinnerNumberModel(120, 10, 600, 10))
        maxStepsSpinner = JSpinner(SpinnerNumberModel(20, 1, 100, 1))

        enableMcpCheckBox = JBCheckBox("Enable MCP semantic navigation for Claude CLI").apply {
            toolTipText = "Uses semantic tools (find_symbol, get_symbols_overview, find_referencing_symbols) " +
                "for more accurate step detection. Requires an MCP server such as Serena."
        }
        mcpConfigPathField = JBTextField().apply {
            emptyText.setText("Leave blank to use global Claude MCP settings")
            toolTipText = "Optional path to a custom MCP config JSON. Empty = inherit from ~/.claude/settings.json"
        }
        enableMcpCheckBox.addChangeListener {
            mcpConfigPathField.isEnabled = enableMcpCheckBox.isSelected
        }

        codexPanel = providerSection(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Codex CLI path:", codexCliPathField)
                .addComponent(JBLabel("Codex CLI uses your local Codex login or API-key setup."))
                .panel,
        )
        claudeCliPanel = providerSection(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Claude CLI path:", claudePathField)
                .addComponent(enableMcpCheckBox)
                .addLabeledComponent("MCP config path (optional):", mcpConfigPathField)
                .panel,
        )
        openAiPanel = providerSection(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Model:", openAiModelField)
                .addLabeledComponent("API key:", openAiApiKeyField)
                .addComponent(openAiKeyStatusLabel)
                .addComponent(clearOpenAiApiKeyCheckBox)
                .addComponent(JBLabel("Leave the field blank to keep the current key. Stored in the IDE Password Safe, not in codeTourSettings.xml."))
                .panel,
        )
        claudeApiPanel = providerSection(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Model:", claudeApiModelField)
                .addLabeledComponent("API key:", claudeApiKeyField)
                .addComponent(claudeApiKeyStatusLabel)
                .addComponent(clearClaudeApiKeyCheckBox)
                .addComponent(JBLabel("Leave the field blank to keep the current key. Stored in the IDE Password Safe, not in codeTourSettings.xml."))
                .panel,
        )
        geminiPanel = providerSection(
            FormBuilder.createFormBuilder()
                .addLabeledComponent("Model:", geminiModelField)
                .addLabeledComponent("API key:", geminiApiKeyField)
                .addComponent(geminiKeyStatusLabel)
                .addComponent(clearGeminiApiKeyCheckBox)
                .addComponent(JBLabel("Leave the field blank to keep the current key. Stored in the IDE Password Safe, not in codeTourSettings.xml."))
                .panel,
        )

        val providerSettingsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(codexPanel)
            add(claudeCliPanel)
            add(openAiPanel)
            add(claudeApiPanel)
            add(geminiPanel)
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Request timeout (seconds):", requestTimeoutSpinner)
            .addLabeledComponent("Max steps:", maxStepsSpinner)
            .addSeparator()
            .addComponent(providerSettingsPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = project.service<CodeTourSettings>().state
        return providerCombo.selectedItem != settings.provider ||
            codexCliPathField.text != settings.codexCliPath ||
            claudePathField.text != settings.claudePath ||
            openAiModelField.text != settings.openAiModel ||
            claudeApiModelField.text != settings.claudeApiModel ||
            geminiModelField.text != settings.geminiModel ||
            requestTimeoutSpinner.value as Int != settings.requestTimeout ||
            maxStepsSpinner.value as Int != settings.maxSteps ||
            enableMcpCheckBox.isSelected != settings.enableMcp ||
            mcpConfigPathField.text != settings.mcpConfigPath ||
            openAiApiKeyField.password.isNotEmpty() ||
            claudeApiKeyField.password.isNotEmpty() ||
            geminiApiKeyField.password.isNotEmpty() ||
            clearOpenAiApiKeyCheckBox.isSelected ||
            clearClaudeApiKeyCheckBox.isSelected ||
            clearGeminiApiKeyCheckBox.isSelected
    }

    override fun apply() {
        val openAiKeyReplacement = String(openAiApiKeyField.password).trim()
        val claudeApiKeyReplacement = String(claudeApiKeyField.password).trim()
        val geminiApiKeyReplacement = String(geminiApiKeyField.password).trim()
        val openAiConfigured = if (clearOpenAiApiKeyCheckBox.isSelected) false else openAiKeyReplacement.isNotEmpty() || project.service<CodeTourSettings>().state.openAiApiKeyConfigured
        val claudeConfigured = if (clearClaudeApiKeyCheckBox.isSelected) false else claudeApiKeyReplacement.isNotEmpty() || project.service<CodeTourSettings>().state.claudeApiKeyConfigured
        val geminiConfigured = if (clearGeminiApiKeyCheckBox.isSelected) false else geminiApiKeyReplacement.isNotEmpty() || project.service<CodeTourSettings>().state.geminiApiKeyConfigured

        val settings = project.service<CodeTourSettings>()
        settings.loadState(
            CodeTourSettings.State(
                providerId = (providerCombo.selectedItem as AiProvider).id,
                codexCliPath = codexCliPathField.text.trim(),
                claudePath = claudePathField.text.trim(),
                openAiModel = openAiModelField.text.trim(),
                claudeApiModel = claudeApiModelField.text.trim(),
                geminiModel = geminiModelField.text.trim(),
                openAiApiKeyConfigured = openAiConfigured,
                claudeApiKeyConfigured = claudeConfigured,
                geminiApiKeyConfigured = geminiConfigured,
                requestTimeout = requestTimeoutSpinner.value as Int,
                maxSteps = maxStepsSpinner.value as Int,
                enableMcp = enableMcpCheckBox.isSelected,
                mcpConfigPath = mcpConfigPathField.text.trim(),
            ),
        )

        val secrets = project.service<ProviderSecretsService>()
        when {
            clearOpenAiApiKeyCheckBox.isSelected -> secrets.setApiKey(AiProvider.CHATGPT_API, null)
            openAiKeyReplacement.isNotEmpty() -> secrets.setApiKey(AiProvider.CHATGPT_API, openAiKeyReplacement)
        }
        when {
            clearClaudeApiKeyCheckBox.isSelected -> secrets.setApiKey(AiProvider.CLAUDE_API, null)
            claudeApiKeyReplacement.isNotEmpty() -> secrets.setApiKey(AiProvider.CLAUDE_API, claudeApiKeyReplacement)
        }
        when {
            clearGeminiApiKeyCheckBox.isSelected -> secrets.setApiKey(AiProvider.GEMINI_API, null)
            geminiApiKeyReplacement.isNotEmpty() -> secrets.setApiKey(AiProvider.GEMINI_API, geminiApiKeyReplacement)
        }
    }

    override fun reset() {
        val settings = project.service<CodeTourSettings>().state
        providerCombo.selectedItem = settings.provider
        codexCliPathField.text = settings.codexCliPath
        claudePathField.text = settings.claudePath
        openAiModelField.text = settings.openAiModel
        claudeApiModelField.text = settings.claudeApiModel
        geminiModelField.text = settings.geminiModel
        openAiApiKeyField.text = ""
        claudeApiKeyField.text = ""
        geminiApiKeyField.text = ""
        clearOpenAiApiKeyCheckBox.isSelected = false
        clearClaudeApiKeyCheckBox.isSelected = false
        clearGeminiApiKeyCheckBox.isSelected = false
        requestTimeoutSpinner.value = settings.requestTimeout
        maxStepsSpinner.value = settings.maxSteps
        enableMcpCheckBox.isSelected = settings.enableMcp
        mcpConfigPathField.text = settings.mcpConfigPath
        mcpConfigPathField.isEnabled = settings.enableMcp
        updateStoredKeyStatusLabels(settings)
        refreshProviderSections()
    }

    private fun refreshProviderSections() {
        val provider = providerCombo.selectedItem as? AiProvider ?: AiProvider.CLAUDE_CLI
        codexPanel.isVisible = provider == AiProvider.CODEX_CLI
        claudeCliPanel.isVisible = provider == AiProvider.CLAUDE_CLI
        openAiPanel.isVisible = provider == AiProvider.CHATGPT_API
        claudeApiPanel.isVisible = provider == AiProvider.CLAUDE_API
        geminiPanel.isVisible = provider == AiProvider.GEMINI_API
        enableMcpCheckBox.isEnabled = provider == AiProvider.CLAUDE_CLI
        mcpConfigPathField.isEnabled = provider == AiProvider.CLAUDE_CLI && enableMcpCheckBox.isSelected
        panel?.revalidate()
        panel?.repaint()
    }

    private fun providerSection(content: JPanel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 0)
            add(content)
        }
    }

    private fun updateStoredKeyStatusLabels(settings: CodeTourSettings.State) {
        openAiKeyStatusLabel.text = keyStatusText(settings.openAiApiKeyConfigured)
        claudeApiKeyStatusLabel.text = keyStatusText(settings.claudeApiKeyConfigured)
        geminiKeyStatusLabel.text = keyStatusText(settings.geminiApiKeyConfigured)
    }

    private fun keyStatusText(configured: Boolean): String {
        return if (configured) {
            "A key is currently stored in Password Safe."
        } else {
            "No key is currently stored."
        }
    }
}
