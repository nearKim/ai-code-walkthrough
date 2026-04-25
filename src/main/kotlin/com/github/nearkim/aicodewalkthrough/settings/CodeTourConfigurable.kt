package com.github.nearkim.aicodewalkthrough.settings

import com.github.nearkim.aicodewalkthrough.model.AiProvider
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
    private lateinit var codexModelField: JBTextField
    private lateinit var codexReasoningCombo: JComboBox<String>
    private lateinit var claudePathField: JBTextField
    private lateinit var claudeModelField: JBTextField
    private lateinit var claudeEffortCombo: JComboBox<String>
    private lateinit var requestTimeoutSpinner: JSpinner
    private lateinit var maxStepsSpinner: JSpinner
    private lateinit var enableMcpCheckBox: JBCheckBox
    private lateinit var mcpConfigPathField: JBTextField
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AI Code Walkthrough"

    override fun createComponent(): JComponent {
        providerCombo = JComboBox(AiProvider.entries.toTypedArray())
        codexCliPathField = JBTextField()
        codexModelField = JBTextField().apply {
            emptyText.setText("Leave blank to use Codex CLI default")
            toolTipText = "Codex model id, e.g. gpt-5.5, gpt-5-codex, o3"
        }
        codexReasoningCombo = JComboBox(CODEX_REASONING_OPTIONS).apply {
            toolTipText = "Maps to -c model_reasoning_effort=<value>; blank = use Codex default"
        }
        claudePathField = JBTextField()
        claudeModelField = JBTextField().apply {
            emptyText.setText("Leave blank to use Claude CLI default")
            toolTipText = "Claude model id or alias, e.g. claude-opus-4-7, opus, sonnet"
        }
        claudeEffortCombo = JComboBox(CLAUDE_EFFORT_OPTIONS).apply {
            toolTipText = "Claude --effort level; blank = use CLI default. 'max' = maximum thinking."
        }
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

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Provider:", providerCombo)
            .addLabeledComponent("Request timeout (seconds):", requestTimeoutSpinner)
            .addLabeledComponent("Max steps:", maxStepsSpinner)
            .addSeparator()
            .addLabeledComponent("Codex CLI path:", codexCliPathField)
            .addLabeledComponent("Codex model:", codexModelField)
            .addLabeledComponent("Codex reasoning effort:", codexReasoningCombo)
            .addComponent(JBLabel("Codex CLI uses your local Codex login or API-key setup and supports grounded repo walkthroughs."))
            .addSeparator()
            .addLabeledComponent("Claude CLI path:", claudePathField)
            .addLabeledComponent("Claude model:", claudeModelField)
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
            codexModelField.text != settings.codexModel ||
            (codexReasoningCombo.selectedItem as String) != settings.codexReasoningEffort ||
            claudePathField.text != settings.claudePath ||
            claudeModelField.text != settings.claudeModel ||
            (claudeEffortCombo.selectedItem as String) != settings.claudeEffort ||
            requestTimeoutSpinner.value as Int != settings.requestTimeout ||
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
                codexModel = codexModelField.text.trim(),
                codexReasoningEffort = (codexReasoningCombo.selectedItem as String).trim(),
                claudePath = claudePathField.text.trim(),
                claudeModel = claudeModelField.text.trim(),
                claudeEffort = (claudeEffortCombo.selectedItem as String).trim(),
                requestTimeout = requestTimeoutSpinner.value as Int,
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
        codexModelField.text = settings.codexModel
        codexReasoningCombo.selectedItem = settings.codexReasoningEffort
            .takeIf { it in CODEX_REASONING_OPTIONS } ?: ""
        claudePathField.text = settings.claudePath
        claudeModelField.text = settings.claudeModel
        claudeEffortCombo.selectedItem = settings.claudeEffort
            .takeIf { it in CLAUDE_EFFORT_OPTIONS } ?: ""
        requestTimeoutSpinner.value = settings.requestTimeout
        maxStepsSpinner.value = settings.maxSteps
        enableMcpCheckBox.isSelected = settings.enableMcp
        mcpConfigPathField.text = settings.mcpConfigPath
        mcpConfigPathField.isEnabled = settings.enableMcp
    }

    companion object {
        private val CLAUDE_EFFORT_OPTIONS = arrayOf("", "low", "medium", "high", "xhigh", "max")
        private val CODEX_REASONING_OPTIONS = arrayOf("", "minimal", "low", "medium", "high")
    }
}
