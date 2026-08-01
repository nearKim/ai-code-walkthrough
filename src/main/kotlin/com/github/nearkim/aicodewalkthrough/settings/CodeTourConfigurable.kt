package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.ProviderModelCatalog
import com.github.nearkim.aicodewalkthrough.model.ProviderModelOption
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CodeTourConfigurable(private val project: Project) : Configurable {

    private lateinit var providerCombo: JComboBox<AiProvider>
    private lateinit var codexCliPathField: JBTextField
    private lateinit var codexModelCombo: JComboBox<ProviderModelOption>
    private lateinit var codexReasoningCombo: JComboBox<String>
    private lateinit var claudePathField: JBTextField
    private lateinit var claudeModelCombo: JComboBox<ProviderModelOption>
    private lateinit var claudeEffortCombo: JComboBox<String>
    private lateinit var maxStepsSpinner: JSpinner
    private lateinit var enableMcpCheckBox: JBCheckBox
    private lateinit var mcpConfigPathField: JBTextField
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AI Code Walkthrough"

    override fun createComponent(): JComponent {
        providerCombo = JComboBox(AiProvider.entries.toTypedArray())
        codexCliPathField = JBTextField()
        codexModelCombo = JComboBox(ProviderModelCatalog.codexModels.toTypedArray()).apply {
            toolTipText = "Grounded walkthroughs use the GPT-5.6 Sol model."
        }
        codexReasoningCombo = JComboBox(ProviderModelCatalog.codexReasoningEfforts.toTypedArray()).apply {
            toolTipText = "Ultra uses maximum reasoning with subagent delegation; Max uses maximum single-agent reasoning."
        }
        claudePathField = JBTextField()
        claudeModelCombo = JComboBox(ProviderModelCatalog.claudeModels.toTypedArray()).apply {
            toolTipText = "Claude Code resolves the fable and opus aliases to the supported Claude 5 models."
        }
        claudeEffortCombo = JComboBox(CLAUDE_EFFORT_OPTIONS).apply {
            toolTipText = "Claude --effort level; blank = use CLI default. 'max' = maximum thinking."
        }
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

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Max steps:", maxStepsSpinner)
            .addComponent(JBLabel("Repository analysis runs until completion; use Stop to cancel it."))
            .addSeparator()
            .addLabeledComponent("Codex CLI path:", codexCliPathField)
            .addLabeledComponent("Codex model:", codexModelCombo)
            .addLabeledComponent("Codex reasoning effort:", codexReasoningCombo)
            .addComponent(JBLabel("Codex CLI uses your local Codex login or API-key setup and supports grounded repo walkthroughs."))
            .addSeparator()
            .addLabeledComponent("Claude CLI path:", claudePathField)
            .addLabeledComponent("Claude model:", claudeModelCombo)
            .addLabeledComponent("Claude effort (thinking):", claudeEffortCombo)
            .addComponent(enableMcpCheckBox)
            .addLabeledComponent("MCP config path (optional):", mcpConfigPathField)
            .addComponent(JBLabel("Claude CLI is the other grounded walkthrough option; enable MCP for tighter symbol tracing."))
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = project.service<CodeTourSettings>().state
        return providerCombo.selectedItem != settings.provider ||
            codexCliPathField.text != settings.codexCliPath ||
            (codexModelCombo.selectedItem as ProviderModelOption).id != settings.codexModel ||
            (codexReasoningCombo.selectedItem as String) != settings.codexReasoningEffort ||
            claudePathField.text != settings.claudePath ||
            (claudeModelCombo.selectedItem as ProviderModelOption).id != settings.claudeModel ||
            (claudeEffortCombo.selectedItem as String) != settings.claudeEffort ||
            maxStepsSpinner.value as Int != settings.maxSteps ||
            enableMcpCheckBox.isSelected != settings.enableMcp ||
            mcpConfigPathField.text != settings.mcpConfigPath
    }

    override fun apply() {
        val settings = project.service<CodeTourSettings>()
        settings.loadState(
            CodeTourSettings.State(
                providerId = (providerCombo.selectedItem as AiProvider).id,
                codexCliPath = codexCliPathField.text.trim(),
                codexModel = (codexModelCombo.selectedItem as ProviderModelOption).id,
                codexReasoningEffort = (codexReasoningCombo.selectedItem as String).trim(),
                claudePath = claudePathField.text.trim(),
                claudeModel = (claudeModelCombo.selectedItem as ProviderModelOption).id,
                claudeEffort = (claudeEffortCombo.selectedItem as String).trim(),
                maxSteps = maxStepsSpinner.value as Int,
                enableMcp = enableMcpCheckBox.isSelected,
                mcpConfigPath = mcpConfigPathField.text.trim(),
            ),
        )
    }

    override fun reset() {
        val settings = project.service<CodeTourSettings>().state
        providerCombo.selectedItem = settings.provider
        codexCliPathField.text = settings.codexCliPath
        codexModelCombo.selectedItem = ProviderModelCatalog.codexOption(settings.codexModel)
        codexReasoningCombo.selectedItem =
            ProviderModelCatalog.normalizeCodexReasoningEffort(settings.codexReasoningEffort)
        claudePathField.text = settings.claudePath
        claudeModelCombo.selectedItem = ProviderModelCatalog.claudeOption(settings.claudeModel)
        claudeEffortCombo.selectedItem = settings.claudeEffort
            .takeIf { it in CLAUDE_EFFORT_OPTIONS } ?: ""
        maxStepsSpinner.value = settings.maxSteps
        enableMcpCheckBox.isSelected = settings.enableMcp
        mcpConfigPathField.text = settings.mcpConfigPath
        mcpConfigPathField.isEnabled = settings.enableMcp
    }

    companion object {
        private val CLAUDE_EFFORT_OPTIONS = arrayOf("", "low", "medium", "high", "xhigh", "max")
    }
}
