package com.github.nearkim.aicodewalkthrough.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class CodeTourConfigurable(private val project: Project) : Configurable {

    private lateinit var claudePathField: JBTextField
    private lateinit var requestTimeoutSpinner: JSpinner
    private lateinit var maxStepsSpinner: JSpinner
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "AI Code Walkthrough"

    override fun createComponent(): JComponent {
        claudePathField = JBTextField()
        requestTimeoutSpinner = JSpinner(SpinnerNumberModel(120, 10, 600, 10))
        maxStepsSpinner = JSpinner(SpinnerNumberModel(20, 1, 100, 1))

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Claude CLI path:", claudePathField)
            .addLabeledComponent("Request timeout (seconds):", requestTimeoutSpinner)
            .addLabeledComponent("Max steps:", maxStepsSpinner)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = project.getService(CodeTourSettings::class.java).state
        return claudePathField.text != settings.claudePath ||
            requestTimeoutSpinner.value as Int != settings.requestTimeout ||
            maxStepsSpinner.value as Int != settings.maxSteps
    }

    override fun apply() {
        val settings = project.getService(CodeTourSettings::class.java)
        settings.loadState(
            CodeTourSettings.State(
                claudePath = claudePathField.text,
                requestTimeout = requestTimeoutSpinner.value as Int,
                maxSteps = maxStepsSpinner.value as Int,
            )
        )
    }

    override fun reset() {
        val settings = project.getService(CodeTourSettings::class.java).state
        claudePathField.text = settings.claudePath
        requestTimeoutSpinner.value = settings.requestTimeout
        maxStepsSpinner.value = settings.maxSteps
    }
}
