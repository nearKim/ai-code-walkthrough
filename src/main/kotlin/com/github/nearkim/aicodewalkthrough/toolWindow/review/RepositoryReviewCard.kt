package com.github.nearkim.aicodewalkthrough.toolwindow.review

import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

class RepositoryReviewCard(
    private val isSnapshotStale: () -> Boolean,
    private val onStartFeatureWalkthrough: (featureId: String, pathId: String) -> Unit,
    private val onRefreshReview: () -> Unit,
    private val onCopyMarkdown: () -> Unit,
) {

    private val repoReviewSummaryLabel = JBLabel()
    private val repoReviewMetadataLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelDisabledForeground()
        border = JBUI.Borders.empty(0, 0, 4, 0)
    }
    private val repoReviewStaleLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelDisabledForeground()
        border = JBUI.Borders.empty(0, 0, 8, 0)
    }
    private val repoFeatureListModel = DefaultListModel<RepositoryFeature>()
    private val repoFeatureList = JBList(repoFeatureListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RepositoryFeatureCellRenderer()
        addListSelectionListener {
            if (!it.valueIsAdjusting) {
                refreshRepositoryFeatureDetails()
            }
        }
    }
    private val repoFeatureTitleLabel = JBLabel("Select a feature").apply {
        font = JBUI.Fonts.label().asBold()
    }
    private val repoFeatureMetaLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelDisabledForeground()
        border = JBUI.Borders.empty(2, 0, 6, 0)
    }
    private val repoFeatureBodyText = createTextArea()
    private val repoFeatureFindingsText = createTextArea().apply {
        border = BorderFactory.createTitledBorder("Findings")
    }
    private val repoFeaturePathsModel = DefaultListModel<FeaturePath>()
    private val repoFeaturePathsList = JBList(repoFeaturePathsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = FeaturePathCellRenderer()
        addListSelectionListener {
            if (!it.valueIsAdjusting) {
                refreshRepositoryPathDetails()
            }
        }
    }
    private val repoPathDescriptionText = createTextArea().apply {
        border = BorderFactory.createTitledBorder("Selected path")
    }
    private val repoCrossCuttingText = createTextArea().apply {
        border = BorderFactory.createTitledBorder("Cross-cutting review notes")
    }
    private val startFeatureWalkthroughButton = JButton("Start Bounded Walkthrough").apply {
        addActionListener {
            val feature = repoFeatureList.selectedValue ?: return@addActionListener
            val path = repoFeaturePathsList.selectedValue ?: return@addActionListener
            onStartFeatureWalkthrough(feature.id, path.id)
        }
    }

    val panel: JPanel = createPanel()

    fun refresh(snapshot: RepositoryReviewSnapshot?) {
        if (snapshot == null) {
            repoReviewSummaryLabel.text = "<html><b>No stored repository review</b><br>Run a thorough repo review from the input card.</html>"
            repoReviewMetadataLabel.text = ""
            repoReviewStaleLabel.text = ""
            repoFeatureListModel.clear()
            refreshRepositoryFeatureDetails()
            panel.revalidate()
            panel.repaint()
            return
        }

        repoReviewSummaryLabel.text = "<html>${escapeHtml(snapshot.summary)}</html>"
        repoReviewMetadataLabel.text = buildList {
            add("${snapshot.features.size} feature slices")
            add("${snapshot.crossCuttingFindings.size} cross-cutting findings")
            snapshot.providerLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")
        repoReviewStaleLabel.text = if (isSnapshotStale()) {
            "Stored review is stale relative to the current repository."
        } else {
            "Stored review matches the current repository fingerprint."
        }
        repoCrossCuttingText.text = if (snapshot.crossCuttingFindings.isEmpty()) {
            "No cross-cutting findings were returned."
        } else {
            snapshot.crossCuttingFindings.joinToString("\n\n") { finding ->
                "[${finding.severity}] ${finding.title}\n${finding.summary}"
            }
        }

        repoFeatureListModel.clear()
        snapshot.features.forEach(repoFeatureListModel::addElement)
        if (repoFeatureListModel.size > 0 && repoFeatureList.selectedIndex !in 0 until repoFeatureListModel.size) {
            repoFeatureList.selectedIndex = 0
        }
        refreshRepositoryFeatureDetails()
        panel.revalidate()
        panel.repaint()
    }

    private fun createPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)

            val header = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            repoReviewSummaryLabel.border = JBUI.Borders.empty(0, 0, 6, 0)
            repoReviewSummaryLabel.verticalAlignment = SwingConstants.TOP
            header.add(repoReviewSummaryLabel)
            header.add(repoReviewMetadataLabel)
            header.add(repoReviewStaleLabel)
            add(header, BorderLayout.NORTH)

            val split = JPanel(BorderLayout(JBUI.scale(8), 0))
            split.add(
                JBScrollPane(repoFeatureList).apply {
                    preferredSize = java.awt.Dimension(JBUI.scale(240), JBUI.scale(400))
                },
                BorderLayout.WEST,
            )

            val details = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            details.add(repoFeatureTitleLabel)
            details.add(repoFeatureMetaLabel)
            details.add(repoFeatureBodyText)
            details.add(Box.createVerticalStrut(JBUI.scale(8)))
            details.add(repoCrossCuttingText)
            details.add(Box.createVerticalStrut(JBUI.scale(8)))
            details.add(repoFeatureFindingsText)
            details.add(Box.createVerticalStrut(JBUI.scale(8)))
            details.add(
                JBScrollPane(repoFeaturePathsList).apply {
                    border = BorderFactory.createTitledBorder("Recommended bounded walkthrough paths")
                    alignmentX = JPanel.LEFT_ALIGNMENT
                    maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(180))
                },
            )
            details.add(Box.createVerticalStrut(JBUI.scale(8)))
            details.add(repoPathDescriptionText)

            split.add(JBScrollPane(details), BorderLayout.CENTER)
            add(split, BorderLayout.CENTER)

            val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.empty(8, 0, 0, 0)
            }
            actions.add(startFeatureWalkthroughButton)
            actions.add(JButton("Refresh Repo Review").apply { addActionListener { onRefreshReview() } })
            actions.add(JButton("Copy Review Markdown").apply { addActionListener { onCopyMarkdown() } })
            add(actions, BorderLayout.SOUTH)
        }
    }

    private fun refreshRepositoryFeatureDetails() {
        val feature = repoFeatureList.selectedValue
        if (feature == null) {
            repoFeatureTitleLabel.text = "Select a feature"
            repoFeatureMetaLabel.text = ""
            repoFeatureBodyText.text = ""
            repoFeatureFindingsText.text = ""
            repoFeaturePathsModel.clear()
            refreshRepositoryPathDetails()
            return
        }

        repoFeatureTitleLabel.text = feature.name
        repoFeatureMetaLabel.text = buildList {
            add("${feature.filePaths.size} files")
            add("${feature.findings.size} findings")
            feature.category?.takeIf { it.isNotBlank() }?.let { add(it) }
            feature.overallRisk?.takeIf { it.isNotBlank() }?.let { add("risk: $it") }
            if (feature.uncertain) add("uncertain")
        }.joinToString("  ·  ")
        repoFeatureBodyText.text = buildString {
            appendLine(feature.summary)
            feature.businessValue?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Business value:")
                appendLine(it)
            }
            feature.whyThisMatters?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Why this matters:")
                appendLine(it)
            }
            if (feature.entrypoints.isNotEmpty()) {
                appendLine()
                appendLine("Entrypoints:")
                feature.entrypoints.forEach { entry ->
                    val symbol = entry.symbol?.takeIf { it.isNotBlank() }?.let { " :: $it" }.orEmpty()
                    appendLine("- ${entry.filePath}$symbol")
                }
            }
            if (feature.filePaths.isNotEmpty()) {
                appendLine()
                appendLine("Owned files:")
                feature.filePaths.take(8).forEach { appendLine("- $it") }
                if (feature.filePaths.size > 8) {
                    appendLine("- ... and ${feature.filePaths.size - 8} more")
                }
            }
            feature.validationNote?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Grounding note:")
                appendLine(it)
            }
        }.trim()
        repoFeatureFindingsText.text = if (feature.findings.isEmpty()) {
            "No feature-specific findings were returned."
        } else {
            feature.findings.joinToString("\n\n") { finding ->
                buildString {
                    append("[${finding.severity}] ${finding.title}\n")
                    append(finding.summary)
                    finding.suggestedAction?.takeIf { it.isNotBlank() }?.let { append("\nAction: $it") }
                    finding.testGap?.takeIf { it.isNotBlank() }?.let { append("\nTest gap: $it") }
                }
            }
        }

        repoFeaturePathsModel.clear()
        feature.paths.forEach(repoFeaturePathsModel::addElement)
        if (repoFeaturePathsModel.size > 0) {
            repoFeaturePathsList.selectedIndex = 0
        }
        refreshRepositoryPathDetails()
    }

    private fun refreshRepositoryPathDetails() {
        val path = repoFeaturePathsList.selectedValue
        repoPathDescriptionText.text = if (path == null) {
            "Select a recommended path to start a bounded walkthrough."
        } else {
            buildString {
                appendLine(path.description)
                appendLine()
                appendLine("Suggested mode: ${AnalysisMode.fromId(path.defaultMode).displayName}")
                path.entryFilePath?.takeIf { it.isNotBlank() }?.let { appendLine("Entry file: $it") }
                path.entrySymbol?.takeIf { it.isNotBlank() }?.let { appendLine("Entry symbol: $it") }
                if (path.filePaths.isNotEmpty()) {
                    appendLine()
                    appendLine("Bounded files:")
                    path.filePaths.take(8).forEach { appendLine("- $it") }
                }
                if (path.supportingSymbols.isNotEmpty()) {
                    appendLine()
                    appendLine("Supporting symbols:")
                    path.supportingSymbols.forEach { appendLine("- $it") }
                }
                if (path.boundaryNotes.isNotEmpty()) {
                    appendLine()
                    appendLine("Boundary notes:")
                    path.boundaryNotes.forEach { appendLine("- $it") }
                }
                appendLine()
                appendLine("Prompt seed:")
                append(path.promptSeed)
                path.validationNote?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    appendLine()
                    appendLine("Grounding note:")
                    append(it)
                }
            }.trim()
        }
        startFeatureWalkthroughButton.isEnabled = path != null && path.broken.not()
    }

    private fun createTextArea(): JTextArea {
        return JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            foreground = UIUtil.getLabelForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
            alignmentX = JPanel.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class RepositoryFeatureCellRenderer : ListCellRenderer<RepositoryFeature> {
        override fun getListCellRendererComponent(
            list: JList<out RepositoryFeature>,
            value: RepositoryFeature,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val title = SimpleColoredComponent().apply {
                isOpaque = false
            }
            val metaLabel = JBLabel(
                buildList {
                    add("${value.filePaths.size} files")
                    add("${value.findings.size} findings")
                    value.overallRisk?.takeIf { it.isNotBlank() }?.let { add("risk: $it") }
                }.joinToString("  ·  "),
            ).apply {
                font = JBUI.Fonts.smallFont()
            }
            val panel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 6)
                isOpaque = true
            }
            if (isSelected) {
                panel.background = list.selectionBackground
                title.foreground = list.selectionForeground
                metaLabel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                title.foreground = list.foreground
                metaLabel.foreground = UIUtil.getLabelDisabledForeground()
            }
            title.append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.uncertain) {
                title.append(" (uncertain)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
            panel.add(title, BorderLayout.NORTH)
            panel.add(metaLabel, BorderLayout.SOUTH)
            return panel
        }
    }

    private class FeaturePathCellRenderer : ListCellRenderer<FeaturePath> {
        override fun getListCellRendererComponent(
            list: JList<out FeaturePath>,
            value: FeaturePath,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val title = SimpleColoredComponent().apply {
                isOpaque = false
            }
            val metaLabel = JBLabel(
                buildList {
                    add(AnalysisMode.fromId(value.defaultMode).displayName)
                    if (value.filePaths.isNotEmpty()) add("${value.filePaths.size} files")
                    if (value.uncertain) add("uncertain")
                }.joinToString("  ·  "),
            ).apply {
                font = JBUI.Fonts.smallFont()
            }
            val panel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 6)
                isOpaque = true
            }
            if (isSelected) {
                panel.background = list.selectionBackground
                title.foreground = list.selectionForeground
                metaLabel.foreground = list.selectionForeground
            } else {
                panel.background = list.background
                title.foreground = list.foreground
                metaLabel.foreground = UIUtil.getLabelDisabledForeground()
            }
            title.append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (value.broken) {
                title.append(" (unavailable)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            panel.add(title, BorderLayout.NORTH)
            panel.add(metaLabel, BorderLayout.SOUTH)
            return panel
        }
    }
}
