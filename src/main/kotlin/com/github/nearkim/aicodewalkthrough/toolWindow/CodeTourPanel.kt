package com.github.nearkim.aicodewalkthrough.toolwindow

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FeaturePath
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.RecentWalkthrough
import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.EditorContextFormatter
import com.github.nearkim.aicodewalkthrough.service.EditorContextService
import com.github.nearkim.aicodewalkthrough.service.LlmProviderService
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.github.nearkim.aicodewalkthrough.util.FlowMapMarkdownExporter
import com.github.nearkim.aicodewalkthrough.util.RepositoryReviewMarkdownExporter
import com.github.nearkim.aicodewalkthrough.util.FlowStepMetaFormatter
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

private enum class ReviewMode(
    val displayName: String,
    val placeholder: String,
    val intro: String,
) {
    UNDERSTAND("Understand", "Ask how this code works...", "Explain the codebase clearly and trace execution."),
    REVIEW("Review", "Review this code for risks, bugs, and test gaps...", "Focus on correctness, regressions, and actionable review notes."),
    TRACE("Trace", "Trace the execution path or call chain...", "Follow symbols and execution order with minimal speculation."),
    RISK("Risk", "Highlight blast radius and invariants...", "Analyze what can break, who depends on it, and why."),
    COMMENT("Comment", "Draft a review comment for the current code...", "Compose concise, evidence-backed review comments."),
}

private enum class CommentStyle(val displayName: String, val leadIn: String) {
    QUESTION("Question", "Can you"),
    CONCERN("Concern", "I think"),
    SUGGESTION("Suggestion", "Consider"),
    APPROVAL("Approval", "Looks good because"),
}

private enum class StepFilter(val displayName: String) {
    ALL("All"),
    FINDINGS("Findings"),
    UNCERTAIN("Uncertain"),
    BROKEN("Broken"),
    TEST_GAPS("Tests"),
}

class CodeTourPanel(private val project: Project, private val scope: CoroutineScope) :
    JPanel(BorderLayout()),
    TourSessionService.TourSessionListener {

    private val sessionService = project.service<TourSessionService>()
    private val settings get() = project.service<CodeTourSettings>()
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private var errorBanner: JBLabel? = null
    private var errorBannerPanel: JPanel? = null
    private var questionTextArea: JBTextArea? = null
    private var modeButtonGroup: ButtonGroup? = null
    private var modeButtons = mutableMapOf<ReviewMode, JToggleButton>()
    private var contextChipPanel: JPanel? = null
    private var inputQuickActionPanel: JPanel? = null

    // Loading card components
    private var progressLog: JTextArea? = null
    private var progressLogScrollPane: JBScrollPane? = null
    private var loadingStatusLabel: JBLabel? = null
    private var elapsedLabel: JBLabel? = null
    private var loadingStartTime: Long = 0
    private var elapsedTimerThread: Thread? = null

    // Overview card components
    private var overviewPanel: JPanel? = null
    private var summaryLabel: JBLabel? = null
    private var stepListModel: DefaultListModel<FlowStep>? = null
    private var stepList: JBList<FlowStep>? = null
    private var followUpField: JBTextField? = null
    private var overviewContentPanel: JPanel? = null
    private var clarificationPanel: JPanel? = null
    private var clarificationLabel: JBLabel? = null
    private var clarificationField: JBTextField? = null
    private var metadataBar: JPanel? = null
    private var metadataLabel: JBLabel? = null
    private var overviewInsightsLabel: JBLabel? = null
    private var overviewFilterButtons = mutableMapOf<StepFilter, JToggleButton>()
    private var overviewGlobalNotesText: JTextArea? = null
    private var overviewSelectionTitle: JBLabel? = null
    private var overviewSelectionMeta: JBLabel? = null
    private var overviewSelectionBody: JTextArea? = null
    private var previewSelectedButton: JButton? = null
    private var startTourButton: JButton? = null
    private var copyMarkdownButton: JButton? = null
    private var overviewTabs: JTabbedPane? = null
    private var overviewExplainText: JTextArea? = null
    private var overviewEvidenceText: JTextArea? = null
    private var overviewRiskText: JTextArea? = null
    private var overviewCommentText: JTextArea? = null
    private var overviewTestsText: JTextArea? = null
    private var commentTabIndex: Int = -1
    private var commentStyleCombo: JComboBox<CommentStyle>? = null
    private var commentTitleLabel: JBLabel? = null
    private var commentStatusLabel: JBLabel? = null
    private var draftCommentButton: JButton? = null
    private var copyCommentButton: JButton? = null
    private var refreshCommentButton: JButton? = null

    // Tour active card components
    private var tourActivePanel: JPanel? = null
    private var tourStepHeader: JBLabel? = null
    private var tourStepFilePath: JBLabel? = null
    private var tourStepExplanation: JTextArea? = null
    private var tourWhySection: JPanel? = null
    private var tourWhyText: JTextArea? = null
    private var tourUncertainLabel: JBLabel? = null
    private var tourFollowUpField: JBTextField? = null
    private var tourAskButton: JButton? = null
    private var tourStepAnswerStatus: JBLabel? = null
    private var tourStepAnswerText: JTextArea? = null
    private var tourStepAnswerEvidenceText: JTextArea? = null

    // Status indicator
    private var statusDot: JBLabel? = null
    private var providerCombo: JComboBox<AiProvider>? = null
    private var currentMode: ReviewMode = ReviewMode.UNDERSTAND
    private var selectedStepSnapshot: FlowStep? = null
    private var currentStepFilter: StepFilter = StepFilter.ALL
    private var recentWalkthroughsPanel: JPanel? = null
    private var repoReviewButton: JButton? = null
    private var openRepoReviewButton: JButton? = null

    // Repository review card components
    private var repoReviewPanel: JPanel? = null
    private var repoReviewSummaryLabel: JBLabel? = null
    private var repoReviewMetadataLabel: JBLabel? = null
    private var repoReviewStaleLabel: JBLabel? = null
    private var repoFeatureListModel: DefaultListModel<RepositoryFeature>? = null
    private var repoFeatureList: JBList<RepositoryFeature>? = null
    private var repoFeatureTitleLabel: JBLabel? = null
    private var repoFeatureMetaLabel: JBLabel? = null
    private var repoFeatureBodyText: JTextArea? = null
    private var repoFeatureFindingsText: JTextArea? = null
    private var repoFeaturePathsModel: DefaultListModel<FeaturePath>? = null
    private var repoFeaturePathsList: JBList<FeaturePath>? = null
    private var repoPathDescriptionText: JTextArea? = null
    private var repoCrossCuttingText: JTextArea? = null
    private var startFeatureWalkthroughButton: JButton? = null
    private var refreshRepoReviewButton: JButton? = null
    private var copyRepoReviewButton: JButton? = null

    // Command history
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var historyDraft = ""

    init {
        cardPanel.add(createInputCard(), CARD_INPUT)
        cardPanel.add(createLoadingCard(), CARD_LOADING)
        cardPanel.add(createOverviewCard(), CARD_OVERVIEW)
        cardPanel.add(createRepositoryReviewCard(), CARD_REPO_REVIEW)
        cardPanel.add(createTourActiveCard(), CARD_TOUR_ACTIVE)

        add(cardPanel, BorderLayout.CENTER)

        sessionService.addListener(this)
        showCard(sessionService.state)
        checkProviderStatus()
    }

    override fun onStateChanged(state: TourState) {
        showCard(state)
    }

    override fun onStepChanged(stepIndex: Int, step: FlowStep) {
        refreshTourActiveCard(stepIndex, step)
    }

    override fun onStepAnswerChanged(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {
        refreshTourStepAnswer(answer, loading, errorMessage)
    }

    override fun onRecentWalkthroughsChanged(items: List<RecentWalkthrough>) {
        refreshInputCard()
    }

    override fun onRepositoryReviewChanged(snapshot: RepositoryReviewSnapshot?) {
        refreshInputCard()
        refreshRepositoryReviewCard()
    }

    override fun onProgressLine(line: String) {
        loadingStatusLabel?.text = line
        progressLog?.let { log ->
            log.append("$line\n")
            log.caretPosition = log.document.length
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        sessionService.removeListener(this)
        stopElapsedTimer()
    }

    private fun showCard(state: TourState) {
        applyFeatureVisibility()
        when (state) {
            TourState.INPUT -> {
                stopElapsedTimer()
                showErrorBannerIfNeeded()
                refreshInputCard()
                cardLayout.show(cardPanel, CARD_INPUT)
            }
            TourState.LOADING -> {
                clearErrorBanner()
                progressLog?.text = ""
                loadingStatusLabel?.text = "Waiting for provider progress..."
                startElapsedTimer()
                cardLayout.show(cardPanel, CARD_LOADING)
            }
            TourState.OVERVIEW -> {
                stopElapsedTimer()
                clearErrorBanner()
                refreshOverviewCard()
                cardLayout.show(cardPanel, CARD_OVERVIEW)
            }
            TourState.REPO_REVIEW -> {
                stopElapsedTimer()
                clearErrorBanner()
                refreshRepositoryReviewCard()
                cardLayout.show(cardPanel, CARD_REPO_REVIEW)
            }
            TourState.TOUR_ACTIVE -> {
                stopElapsedTimer()
                clearErrorBanner()
                cardLayout.show(cardPanel, CARD_TOUR_ACTIVE)
            }
        }
    }

    private fun applyFeatureVisibility() {
        val state = settings.state
        progressLogScrollPane?.isVisible = state.showRawProgressLog
        loadingStatusLabel?.isVisible = true

        val commentComposerEnabled = state.enableCommentComposer
        draftCommentButton?.isVisible = commentComposerEnabled
        copyCommentButton?.isVisible = commentComposerEnabled
        commentStyleCombo?.isEnabled = commentComposerEnabled
        overviewCommentText?.isEditable = commentComposerEnabled
        if (overviewTabs != null && commentTabIndex in 0 until overviewTabs!!.tabCount) {
            overviewTabs!!.setEnabledAt(commentTabIndex, commentComposerEnabled)
            if (!commentComposerEnabled && overviewTabs!!.selectedIndex == commentTabIndex) {
                overviewTabs!!.selectedIndex = 0
            }
        }
    }

    // ── Input Card ──────────────────────────────────────────────────────

    private fun createInputCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        val topPanel = JPanel(BorderLayout())

        topPanel.add(createModeAndProviderHeader(), BorderLayout.NORTH)
        topPanel.add(createStatusRow(), BorderLayout.CENTER)
        topPanel.add(createErrorBannerPanel(), BorderLayout.SOUTH)

        panel.add(topPanel, BorderLayout.NORTH)

        // Center: prompt editor
        questionTextArea = JBTextArea(4, 0).apply {
            emptyText.setText(currentMode.placeholder)
            lineWrap = true
            wrapStyleWord = true
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val navigated = when (e.keyCode) {
                        KeyEvent.VK_UP -> historyUp(this@apply, requireAtStart = true)
                        KeyEvent.VK_DOWN -> historyDown(this@apply, requireAtEnd = true)
                        else -> false
                    }
                    if (navigated) e.consume()
                }
            })
        }
        panel.add(JBScrollPane(questionTextArea!!), BorderLayout.CENTER)

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        recentWalkthroughsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 0, 8, 0)
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
        bottomPanel.add(recentWalkthroughsPanel)

        val suggestionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 0, 4, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        val suggestionsTitle = JBLabel("Try asking:").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
        }
        suggestionsPanel.add(suggestionsTitle)
        suggestionsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val suggestions = listOf(
            "How does the main entry point work?",
            "What's the request/response lifecycle?",
            "How is configuration loaded and applied?",
            "What are the key abstractions and how do they relate?",
        )
        val chipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        for (suggestion in suggestions) {
            chipsPanel.add(createSuggestionChip(suggestion))
        }
        suggestionsPanel.add(chipsPanel)
        bottomPanel.add(suggestionsPanel)

        val quickPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        quickPanel.add(createContextChipRow(), BorderLayout.CENTER)
        quickPanel.add(createInputActionRow(), BorderLayout.SOUTH)
        bottomPanel.add(quickPanel)

        panel.add(bottomPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun createModeAndProviderHeader(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.add(createModeChipRow())
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(createProviderRow())
        return panel
    }

    private fun createModeChipRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        modeButtonGroup = ButtonGroup()
        row.add(JBLabel("Mode:"))
        ReviewMode.entries.forEach { mode ->
            val button = JToggleButton(mode.displayName).apply {
                isFocusable = false
                font = JBUI.Fonts.smallFont()
                addActionListener { selectMode(mode) }
            }
            modeButtonGroup?.add(button)
            modeButtons[mode] = button
            row.add(button)
        }
        selectMode(currentMode, updatePromptPlaceholder = true)
        return row
    }

    private fun createProviderRow(): JPanel {
        val providerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        providerPanel.add(JBLabel("Provider:"))
        providerCombo = JComboBox(AiProvider.entries.toTypedArray()).apply {
            selectedItem = project.service<CodeTourSettings>().state.provider
            toolTipText = "Select which AI provider maps the walkthrough. CLI providers are required for grounded repo analysis."
            addActionListener {
                val selected = selectedItem as? AiProvider ?: return@addActionListener
                project.service<CodeTourSettings>().state.providerId = selected.id
                checkProviderStatus()
            }
        }
        providerPanel.add(providerCombo!!)
        return providerPanel
    }

    private fun createStatusRow(): JPanel {
        val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        statusDot = JBLabel(AllIcons.General.InspectionsOK).apply {
            text = "Checking provider..."
            foreground = UIUtil.getLabelDisabledForeground()
            font = JBUI.Fonts.smallFont()
        }
        statusPanel.add(statusDot!!)
        return statusPanel
    }

    private fun createErrorBannerPanel(): JPanel {
        errorBannerPanel = JPanel(BorderLayout())
        errorBanner = JBLabel().apply {
            icon = AllIcons.General.Error
            isVisible = false
            border = JBUI.Borders.empty(6)
        }
        errorBannerPanel!!.add(errorBanner!!, BorderLayout.CENTER)
        errorBannerPanel!!.isVisible = false
        return errorBannerPanel!!
    }

    private fun createContextChipRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(2, 0, 0, 0)
        }
        row.add(JBLabel("Context:").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        })
        row.add(createPromptChip("Current file") { applyPromptPreset(ReviewMode.UNDERSTAND, "Explain the current file and its role in the codebase.") })
        row.add(createPromptChip("Selection") { applyPromptPreset(ReviewMode.REVIEW, "Review the current selection for risks, regressions, and missing tests.") })
        row.add(createPromptChip("Trace flow") { applyPromptPreset(ReviewMode.TRACE, "Trace the execution path through the current code and identify key call sites.") })
        row.add(createPromptChip("Write comment") { applyPromptPreset(ReviewMode.COMMENT, "Draft a concise review comment for the current code.") })
        return row
    }

    private fun createInputActionRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
        }
        repoReviewButton = JButton("Thorough Repo Review").apply {
            toolTipText = "Run a repository-wide review using symbolic analysis and store feature slices for later walkthroughs."
            addActionListener { sessionService.startRepositoryReview() }
            row.add(this)
        }
        openRepoReviewButton = JButton("Open Last Repo Review").apply {
            addActionListener { sessionService.restoreStoredRepositoryReview() }
            row.add(this)
        }
        JButton("Review Current File").apply {
            addActionListener { applyPromptPreset(ReviewMode.REVIEW, "Review the current file for bugs, regressions, missing tests, and comment-worthy issues.") }
            row.add(this)
        }
        JButton("Explain Current Symbol").apply {
            addActionListener { applyPromptPreset(ReviewMode.UNDERSTAND, "Explain the current editor context and how the code works.") }
            row.add(this)
        }
        JButton("Write Review Comment").apply {
            addActionListener { applyPromptPreset(ReviewMode.COMMENT, "Draft a review comment for the current cursor or selection.") }
            row.add(this)
        }
        JButton("Map Flow").apply {
            addActionListener { submitCurrentPrompt() }
            row.add(this)
        }
        return row
    }

    private fun createPromptChip(text: String, onClick: () -> Unit): JPanel {
        val chip = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(2, 8),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val label = JBLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        }
        chip.add(label)
        chip.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                onClick()
            }

            override fun mouseEntered(e: MouseEvent) {
                chip.background = JBUI.CurrentTheme.ActionButton.hoverBackground()
            }

            override fun mouseExited(e: MouseEvent) {
                chip.background = null
            }
        })
        return chip
    }

    private fun selectMode(mode: ReviewMode, updatePromptPlaceholder: Boolean = false) {
        currentMode = mode
        modeButtons[mode]?.isSelected = true
        modeButtons.filterKeys { it != mode }.values.forEach { it.isSelected = false }
        if (updatePromptPlaceholder) {
            questionTextArea?.emptyText?.text = mode.placeholder
        }
    }

    private fun applyPromptPreset(mode: ReviewMode, question: String) {
        selectMode(mode, updatePromptPlaceholder = true)
        questionTextArea?.text = question
        questionTextArea?.caretPosition = questionTextArea?.text?.length ?: 0
        questionTextArea?.requestFocusInWindow()
    }

    private fun submitCurrentPrompt() {
        val question = questionTextArea?.text?.trim().orEmpty()
        if (question.isEmpty()) return
        addToHistory(question)
        sessionService.startMapping(
            question = buildPromptWithContext(question, currentMode),
            queryContext = currentEditorQueryContext(),
            featureScope = null,
        )
    }

    private fun buildPromptWithContext(
        question: String,
        mode: ReviewMode = currentMode,
        extraContext: String? = null,
    ): String {
        val editorContext = collectEditorContext()
        return buildString {
            appendLine("Mode: ${mode.displayName}")
            appendLine(mode.intro)
            if (editorContext.isNotBlank()) {
                appendLine()
                appendLine("IDE context:")
                appendLine(editorContext)
            }
            if (!extraContext.isNullOrBlank()) {
                appendLine()
                appendLine("Selected step context:")
                appendLine(extraContext)
            }
            appendLine()
            appendLine("User request:")
            append(question)
        }
    }

    private fun collectEditorContext(): String {
        val context = project.service<EditorContextService>().currentContext() ?: return ""
        return EditorContextFormatter.toPanelText(context)
    }

    private fun currentEditorQueryContext() =
        project.service<EditorContextService>().currentContext()?.toQueryContext()

    private fun refreshInputCard() {
        openRepoReviewButton?.isEnabled = sessionService.currentRepositoryReview != null
        val container = recentWalkthroughsPanel ?: return
        container.removeAll()

        val items = sessionService.recentWalkthroughs
        container.isVisible = items.isNotEmpty()
        if (items.isEmpty()) {
            container.revalidate()
            container.repaint()
            return
        }

        val title = JBLabel("Recent walkthroughs").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
        }
        container.add(title)
        container.add(Box.createVerticalStrut(JBUI.scale(4)))

        items.forEach { item ->
            container.add(createRecentWalkthroughRow(item))
            container.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        container.revalidate()
        container.repaint()
    }

    private fun createRecentWalkthroughRow(item: RecentWalkthrough): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(6, 8),
            )
            alignmentX = LEFT_ALIGNMENT
        }

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val title = JBLabel(item.displayTitle.truncateForDisplay(72)).apply {
            font = JBUI.Fonts.label().asBold()
            alignmentX = LEFT_ALIGNMENT
        }
        val meta = buildList {
            add(item.mode.displayName)
            add(item.flowMap.steps.size.toString() + " steps")
            item.metadata?.fileCount?.let { add("$it files") }
        }.joinToString("  ·  ")
        val summary = JBLabel("<html>${escapeHtml(item.flowMap.summary.truncateForDisplay(140))}</html>").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
        }
        val metaLabel = JBLabel(meta).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
        }
        textPanel.add(title)
        textPanel.add(metaLabel)
        textPanel.add(summary)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
        }
        buttonPanel.add(JButton("Open").apply {
            addActionListener { sessionService.restoreRecentWalkthrough(item.id) }
        })
        val startLabel = if (item.followUpContext?.activeStepId != null) "Resume Tour" else "Start Tour"
        buttonPanel.add(JButton(startLabel).apply {
            addActionListener { sessionService.restoreRecentWalkthrough(item.id, startTour = true) }
        })

        row.add(textPanel, BorderLayout.CENTER)
        row.add(buttonPanel, BorderLayout.EAST)
        return row
    }

    private fun createSuggestionChip(text: String): JPanel {
        val chip = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(2, 8),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val label = JBLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        }
        chip.add(label)
        chip.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                questionTextArea?.text = text
            }
            override fun mouseEntered(e: MouseEvent) {
                chip.background = JBUI.CurrentTheme.ActionButton.hoverBackground()
            }
            override fun mouseExited(e: MouseEvent) {
                chip.background = null
            }
        })
        return chip
    }

    // ── Loading Card ────────────────────────────────────────────────────

    private fun createLoadingCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Header with spinner and elapsed time
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        headerPanel.add(
            JBLabel("AI is exploring your codebase...", AnimatedIcon.Default(), SwingConstants.LEFT),
            BorderLayout.WEST,
        )
        elapsedLabel = JBLabel("0s").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }
        headerPanel.add(elapsedLabel!!, BorderLayout.EAST)
        panel.add(headerPanel, BorderLayout.NORTH)

        loadingStatusLabel = JBLabel("Waiting for provider progress...").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        headerPanel.add(loadingStatusLabel!!, BorderLayout.SOUTH)

        // Live progress log
        progressLog = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("JetBrains Mono", JBUI.Fonts.smallFont().size)
            foreground = UIUtil.getLabelDisabledForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
        }
        progressLogScrollPane = JBScrollPane(progressLog!!).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(),
            )
        }
        panel.add(progressLogScrollPane!!, BorderLayout.CENTER)

        // Cancel button
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
        }
        val cancelButton = JButton("Cancel").apply {
            addActionListener { sessionService.cancelRequest() }
        }
        buttonPanel.add(cancelButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun startElapsedTimer() {
        loadingStartTime = System.currentTimeMillis()
        stopElapsedTimer()
        elapsedTimerThread = Thread.ofVirtual().start {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(1000)
                    val elapsed = (System.currentTimeMillis() - loadingStartTime) / 1000
                    javax.swing.SwingUtilities.invokeLater {
                        elapsedLabel?.text = "${elapsed}s"
                    }
                }
            } catch (_: InterruptedException) {
                // Timer stopped
            }
        }
    }

    private fun stopElapsedTimer() {
        elapsedTimerThread?.interrupt()
        elapsedTimerThread = null
    }

    // ── Overview Card ───────────────────────────────────────────────────

    private fun createOverviewCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        // Metadata bar
        metadataBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            isVisible = false
        }
        metadataLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }
        metadataBar!!.add(metadataLabel!!)

        // Flow map content
        overviewContentPanel = JPanel(BorderLayout()).apply {
            summaryLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
            }
            overviewInsightsLabel = JBLabel().apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.empty(0, 0, 8, 0)
            }
            overviewGlobalNotesText = createOverviewTextArea().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                isVisible = false
            }

            val topSection = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }
            topSection.add(metadataBar!!)
            topSection.add(summaryLabel!!)
            topSection.add(overviewInsightsLabel!!)
            topSection.add(createOverviewFilterRow())
            topSection.add(overviewGlobalNotesText!!)
            add(topSection, BorderLayout.NORTH)

            stepListModel = DefaultListModel()
            stepList = JBList(stepListModel!!).apply {
                cellRenderer = FlowStepCellRenderer()
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                addListSelectionListener {
                    if (!it.valueIsAdjusting) {
                        refreshSelectedStepDetails()
                    }
                }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) {
                            startTourFromSelection()
                        }
                    }
                })
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ENTER) {
                            startTourFromSelection()
                            e.consume()
                        }
                    }
                })
            }
            add(JBScrollPane(stepList!!), BorderLayout.CENTER)

            val bottomSection = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

            val selectedStepPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border()),
                    JBUI.Borders.empty(8),
                )
                alignmentX = LEFT_ALIGNMENT
            }
            overviewSelectionTitle = JBLabel("Selected step").apply {
                font = JBUI.Fonts.label().asBold()
                alignmentX = LEFT_ALIGNMENT
            }
            overviewSelectionMeta = JBLabel().apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.empty(2, 0, 6, 0)
                alignmentX = LEFT_ALIGNMENT
            }
            overviewSelectionBody = JTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = JBUI.Fonts.smallFont()
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty()
                alignmentX = LEFT_ALIGNMENT
            }
            selectedStepPanel.add(overviewSelectionTitle!!)
            selectedStepPanel.add(overviewSelectionMeta!!)
            selectedStepPanel.add(overviewSelectionBody!!)
            overviewTabs = createOverviewTabsPanel()
            selectedStepPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            selectedStepPanel.add(overviewTabs!!)
            bottomSection.add(Box.createVerticalStrut(JBUI.scale(8)))
            bottomSection.add(selectedStepPanel)

            val startTourPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.empty(8, 0, 0, 0)
            }
            startTourButton = JButton("Start Tour").apply {
                addActionListener { startTourFromSelection() }
            }
            previewSelectedButton = JButton("Preview Selected").apply {
                addActionListener { previewSelectedStep() }
            }
            draftCommentButton = JButton("Draft Comment").apply {
                addActionListener { draftCommentForSelection() }
            }
            copyCommentButton = JButton("Copy Comment").apply {
                addActionListener { copyCommentDraft() }
            }
            copyMarkdownButton = JButton("Copy Markdown").apply {
                addActionListener { copyFlowMapMarkdown() }
            }
            startTourPanel.add(startTourButton!!)
            startTourPanel.add(previewSelectedButton!!)
            startTourPanel.add(draftCommentButton!!)
            startTourPanel.add(copyCommentButton!!)
            startTourPanel.add(copyMarkdownButton!!)
            bottomSection.add(startTourPanel)

            val hintLabel = JBLabel("Double-click a step to jump into the tour from there.").apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.empty(4, 0, 0, 0)
                alignmentX = LEFT_ALIGNMENT
            }
            bottomSection.add(hintLabel)

            val followUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
            followUpPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
            followUpField = JBTextField().apply field@{
                emptyText.setText("Ask a follow-up...")
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        val navigated = when (e.keyCode) {
                            KeyEvent.VK_UP -> historyUp(this@field, requireAtStart = false)
                            KeyEvent.VK_DOWN -> historyDown(this@field, requireAtEnd = false)
                            else -> false
                        }
                        if (navigated) e.consume()
                    }
                })
                addActionListener { submitOverviewFollowUp() }
            }
            followUpPanel.add(followUpField!!, BorderLayout.CENTER)
            val sendButton = JButton("Send").apply {
                addActionListener { submitOverviewFollowUp() }
            }
            followUpPanel.add(sendButton, BorderLayout.EAST)
            bottomSection.add(followUpPanel)

            add(bottomSection, BorderLayout.SOUTH)
        }

        // Clarification content
        clarificationPanel = JPanel(BorderLayout()).apply {
            clarificationLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
            }
            add(clarificationLabel!!, BorderLayout.NORTH)

            val answerPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
            answerPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
            clarificationField = JBTextField().apply clarification@{
                addKeyListener(object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        val navigated = when (e.keyCode) {
                            KeyEvent.VK_UP -> historyUp(this@clarification, requireAtStart = false)
                            KeyEvent.VK_DOWN -> historyDown(this@clarification, requireAtEnd = false)
                            else -> false
                        }
                        if (navigated) e.consume()
                    }
                })
                addActionListener { submitClarificationAnswer() }
            }
            answerPanel.add(clarificationField!!, BorderLayout.CENTER)
            val replyButton = JButton("Reply").apply {
                addActionListener { submitClarificationAnswer() }
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

            updateMetadataBar(sessionService.lastMetadata)
            updateOverviewInsights(flowMap.steps)
            updateOverviewGlobalNotes(flowMap)
            rebuildOverviewStepList()

            panel.removeAll()
            panel.add(overviewContentPanel!!, BorderLayout.CENTER)
        }

        panel.revalidate()
        panel.repaint()
    }

    // ── Repository Review Card ─────────────────────────────────────────

    private fun createRepositoryReviewCard(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        repoReviewSummaryLabel = JBLabel().apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            verticalAlignment = SwingConstants.TOP
        }
        repoReviewMetadataLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        repoReviewStaleLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        header.add(repoReviewSummaryLabel!!)
        header.add(repoReviewMetadataLabel!!)
        header.add(repoReviewStaleLabel!!)
        panel.add(header, BorderLayout.NORTH)

        val split = JPanel(BorderLayout(JBUI.scale(8), 0))
        repoFeatureListModel = DefaultListModel()
        repoFeatureList = JBList(repoFeatureListModel!!).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = RepositoryFeatureCellRenderer()
            addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    refreshRepositoryFeatureDetails()
                }
            }
        }
        split.add(JBScrollPane(repoFeatureList!!).apply {
            preferredSize = java.awt.Dimension(JBUI.scale(240), JBUI.scale(400))
        }, BorderLayout.WEST)

        val details = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        repoFeatureTitleLabel = JBLabel("Select a feature").apply {
            font = JBUI.Fonts.label().asBold()
            alignmentX = LEFT_ALIGNMENT
        }
        repoFeatureMetaLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(2, 0, 6, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        repoFeatureBodyText = createOverviewTextArea().apply {
            alignmentX = LEFT_ALIGNMENT
        }
        repoFeatureFindingsText = createOverviewTextArea().apply {
            border = BorderFactory.createTitledBorder("Findings")
            alignmentX = LEFT_ALIGNMENT
        }
        repoCrossCuttingText = createOverviewTextArea().apply {
            border = BorderFactory.createTitledBorder("Cross-cutting review notes")
            alignmentX = LEFT_ALIGNMENT
        }
        repoFeaturePathsModel = DefaultListModel()
        repoFeaturePathsList = JBList(repoFeaturePathsModel!!).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = FeaturePathCellRenderer()
            addListSelectionListener {
                if (!it.valueIsAdjusting) {
                    refreshRepositoryPathDetails()
                }
            }
        }
        repoPathDescriptionText = createOverviewTextArea().apply {
            border = BorderFactory.createTitledBorder("Selected path")
            alignmentX = LEFT_ALIGNMENT
        }

        details.add(repoFeatureTitleLabel!!)
        details.add(repoFeatureMetaLabel!!)
        details.add(repoFeatureBodyText!!)
        details.add(Box.createVerticalStrut(JBUI.scale(8)))
        details.add(repoCrossCuttingText!!)
        details.add(Box.createVerticalStrut(JBUI.scale(8)))
        details.add(repoFeatureFindingsText!!)
        details.add(Box.createVerticalStrut(JBUI.scale(8)))
        details.add(JBScrollPane(repoFeaturePathsList!!).apply {
            border = BorderFactory.createTitledBorder("Recommended bounded walkthrough paths")
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(180))
        })
        details.add(Box.createVerticalStrut(JBUI.scale(8)))
        details.add(repoPathDescriptionText!!)
        split.add(JBScrollPane(details), BorderLayout.CENTER)
        panel.add(split, BorderLayout.CENTER)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }
        startFeatureWalkthroughButton = JButton("Start Bounded Walkthrough").apply {
            addActionListener { startSelectedFeatureWalkthrough() }
        }
        refreshRepoReviewButton = JButton("Refresh Repo Review").apply {
            addActionListener { sessionService.startRepositoryReview() }
        }
        copyRepoReviewButton = JButton("Copy Review Markdown").apply {
            addActionListener { copyRepositoryReviewMarkdown() }
        }
        actions.add(startFeatureWalkthroughButton!!)
        actions.add(refreshRepoReviewButton!!)
        actions.add(copyRepoReviewButton!!)
        panel.add(actions, BorderLayout.SOUTH)

        repoReviewPanel = panel
        return panel
    }

    private fun refreshRepositoryReviewCard() {
        val snapshot = sessionService.currentRepositoryReview
        val panel = repoReviewPanel ?: return
        if (snapshot == null) {
            repoReviewSummaryLabel?.text = "<html><b>No stored repository review</b><br>Run a thorough repo review from the input card.</html>"
            repoReviewMetadataLabel?.text = ""
            repoReviewStaleLabel?.text = ""
            repoFeatureListModel?.clear()
            refreshRepositoryFeatureDetails()
            panel.revalidate()
            panel.repaint()
            return
        }

        repoReviewSummaryLabel?.text = "<html>${escapeHtml(snapshot.summary)}</html>"
        repoReviewMetadataLabel?.text = buildList {
            add("${snapshot.features.size} feature slices")
            add("${snapshot.crossCuttingFindings.size} cross-cutting findings")
            snapshot.providerLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("  ·  ")
        repoReviewStaleLabel?.text = if (sessionService.repositoryReviewIsStale()) {
            "Stored review is stale relative to the current repository."
        } else {
            "Stored review matches the current repository fingerprint."
        }
        repoCrossCuttingText?.text = if (snapshot.crossCuttingFindings.isEmpty()) {
            "No cross-cutting findings were returned."
        } else {
            snapshot.crossCuttingFindings.joinToString("\n\n") { finding ->
                "[${finding.severity}] ${finding.title}\n${finding.summary}"
            }
        }

        val model = repoFeatureListModel ?: return
        model.clear()
        snapshot.features.forEach(model::addElement)
        if (model.size > 0 && repoFeatureList?.selectedIndex !in 0 until model.size) {
            repoFeatureList?.selectedIndex = 0
        }
        refreshRepositoryFeatureDetails()
        panel.revalidate()
        panel.repaint()
    }

    private fun refreshRepositoryFeatureDetails() {
        val feature = repoFeatureList?.selectedValue
        if (feature == null) {
            repoFeatureTitleLabel?.text = "Select a feature"
            repoFeatureMetaLabel?.text = ""
            repoFeatureBodyText?.text = ""
            repoFeatureFindingsText?.text = ""
            repoFeaturePathsModel?.clear()
            refreshRepositoryPathDetails()
            return
        }

        repoFeatureTitleLabel?.text = feature.name
        repoFeatureMetaLabel?.text = buildList {
            add("${feature.filePaths.size} files")
            add("${feature.findings.size} findings")
            feature.category?.takeIf { it.isNotBlank() }?.let { add(it) }
            feature.overallRisk?.takeIf { it.isNotBlank() }?.let { add("risk: $it") }
            if (feature.uncertain) add("uncertain")
        }.joinToString("  ·  ")
        repoFeatureBodyText?.text = buildString {
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
        repoFeatureFindingsText?.text = if (feature.findings.isEmpty()) {
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

        val pathModel = repoFeaturePathsModel ?: return
        pathModel.clear()
        feature.paths.forEach(pathModel::addElement)
        if (pathModel.size > 0) {
            repoFeaturePathsList?.selectedIndex = 0
        }
        refreshRepositoryPathDetails()
    }

    private fun refreshRepositoryPathDetails() {
        val path = repoFeaturePathsList?.selectedValue
        repoPathDescriptionText?.text = if (path == null) {
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
        startFeatureWalkthroughButton?.isEnabled = path != null && path.broken.not()
    }

    private fun startSelectedFeatureWalkthrough() {
        val feature = repoFeatureList?.selectedValue ?: return
        val path = repoFeaturePathsList?.selectedValue ?: return
        sessionService.startFeatureWalkthrough(feature.id, path.id)
    }

    private fun copyRepositoryReviewMarkdown() {
        val snapshot = sessionService.currentRepositoryReview ?: return
        val markdown = RepositoryReviewMarkdownExporter.build(snapshot)
        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        WindowManager.getInstance().getStatusBar(project)?.info = "Repository review copied to clipboard"
    }

    private fun updateMetadataBar(metadata: ResponseMetadata?) {
        if (metadata == null) {
            metadataBar?.isVisible = false
            return
        }

        val parts = mutableListOf<String>()

        val seconds = metadata.durationMs / 1000.0
        parts.add("%.1fs".format(seconds))

        parts.add("${metadata.stepCount} steps")
        parts.add("${metadata.fileCount} files")
        parts.add("${metadata.numTurns} turns")

        if (metadata.costUsd != null) {
            parts.add("$%.4f".format(metadata.costUsd))
        }

        metadataLabel?.text = parts.joinToString("  ·  ")
        metadataBar?.isVisible = true
    }

    private fun updateOverviewInsights(steps: List<FlowStep>) {
        val uncertainCount = steps.count { it.uncertain }
        val brokenCount = steps.count { it.broken }
        val parts = mutableListOf(
            "${steps.size} mapped steps",
            "${steps.map { it.filePath }.distinct().size} files",
        )
        if (uncertainCount > 0) {
            parts.add("$uncertainCount uncertain")
        }
        if (brokenCount > 0) {
            parts.add("$brokenCount needs repair")
        }
        overviewInsightsLabel?.text = parts.joinToString("  ·  ")
    }

    private fun createOverviewFilterRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        val group = ButtonGroup()
        row.add(JBLabel("Filter:").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        })
        StepFilter.entries.forEach { filter ->
            val button = JToggleButton(filter.displayName).apply {
                isFocusable = false
                font = JBUI.Fonts.smallFont()
                addActionListener { selectStepFilter(filter) }
            }
            group.add(button)
            overviewFilterButtons[filter] = button
            row.add(button)
        }
        selectStepFilter(currentStepFilter, rebuild = false)
        return row
    }

    private fun selectStepFilter(filter: StepFilter, rebuild: Boolean = true) {
        currentStepFilter = filter
        overviewFilterButtons.forEach { (entryFilter, button) ->
            button.isSelected = entryFilter == filter
        }
        if (rebuild) {
            rebuildOverviewStepList()
        }
    }

    private fun rebuildOverviewStepList() {
        val flowMap = sessionService.currentFlowMap ?: return
        val filteredSteps = filterSteps(flowMap)
        stepListModel?.clear()
        filteredSteps.forEach { stepListModel?.addElement(it) }
        updateOverviewFilterLabels(flowMap)
        ensureOverviewSelection()
        refreshSelectedStepDetails()
    }

    private fun filterSteps(flowMap: FlowMap): List<FlowStep> {
        return flowMap.steps.filter { step ->
            when (currentStepFilter) {
                StepFilter.ALL -> true
                StepFilter.FINDINGS -> step.broken ||
                    step.severity?.let { it.equals("high", true) || it.equals("medium", true) } == true ||
                    step.evidence.isNotEmpty() ||
                    !step.testGap.isNullOrBlank() ||
                    !step.suggestedAction.isNullOrBlank()
                StepFilter.UNCERTAIN -> step.uncertain
                StepFilter.BROKEN -> step.broken
                StepFilter.TEST_GAPS -> !step.testGap.isNullOrBlank()
            }
        }
    }

    private fun updateOverviewFilterLabels(flowMap: FlowMap) {
        val counts = mapOf(
            StepFilter.ALL to flowMap.steps.size,
            StepFilter.FINDINGS to flowMap.steps.count { step ->
                step.broken ||
                    step.severity?.let { it.equals("high", true) || it.equals("medium", true) } == true ||
                    step.evidence.isNotEmpty() ||
                    !step.testGap.isNullOrBlank() ||
                    !step.suggestedAction.isNullOrBlank()
            },
            StepFilter.UNCERTAIN to flowMap.steps.count { it.uncertain },
            StepFilter.BROKEN to flowMap.steps.count { it.broken },
            StepFilter.TEST_GAPS to flowMap.steps.count { !it.testGap.isNullOrBlank() },
        )
        overviewFilterButtons.forEach { (filter, button) ->
            button.text = "${filter.displayName} (${counts[filter] ?: 0})"
        }
    }

    private fun updateOverviewGlobalNotes(flowMap: FlowMap) {
        val sections = buildList {
            val entryStepTitle = flowMap.steps.firstOrNull { it.id == flowMap.entryStepId }?.title
            val terminalStepTitles = flowMap.terminalStepIds.mapNotNull { terminalId ->
                flowMap.steps.firstOrNull { it.id == terminalId }?.title
            }
            val pathOverview = buildList {
                flowMap.entryStepId?.let { entryId ->
                    add("Entrypoint: ${entryStepTitle ?: entryId}")
                }
                if (terminalStepTitles.isNotEmpty()) {
                    add("Path ends at: ${terminalStepTitles.joinToString(", ")}")
                }
                if (flowMap.edges.isNotEmpty()) {
                    add("Validated hops: ${flowMap.edges.size}")
                }
            }.joinToString("\n")
            if (pathOverview.isNotBlank()) {
                add("Execution path:\n$pathOverview")
            }
            flowMap.reviewSummary?.takeIf { it.isNotBlank() }?.let {
                add("Review summary:\n$it")
            }
            flowMap.overallRisk?.takeIf { it.isNotBlank() }?.let {
                add("Overall risk:\n$it")
            }
            flowMap.analysisTrace?.let { trace ->
                val traceLines = buildList {
                    trace.entrypointReason?.takeIf { it.isNotBlank() }?.let { add("Entrypoint reason: $it") }
                    trace.pathEndReason?.takeIf { it.isNotBlank() }?.let { add("Path end reason: $it") }
                    if (trace.semanticToolsUsed.isNotEmpty()) {
                        add("Semantic tools: ${trace.semanticToolsUsed.joinToString(", ")}")
                    }
                    if (trace.delegatedAgents.isNotEmpty()) {
                        add("Delegated analysis: ${trace.delegatedAgents.joinToString(" | ")}")
                    }
                }
                if (traceLines.isNotEmpty()) {
                    add("Grounding trace:\n${traceLines.joinToString("\n")}")
                }
            }
            if (flowMap.suggestedTests.isNotEmpty()) {
                val suggested = flowMap.suggestedTests.joinToString("\n") { test ->
                    val hint = test.fileHint?.takeIf { it.isNotBlank() }?.let { " (${it})" }.orEmpty()
                    "- ${test.title}$hint: ${test.description}"
                }
                add("Suggested tests:\n$suggested")
            }
        }

        val text = sections.joinToString("\n\n")
        overviewGlobalNotesText?.text = text
        overviewGlobalNotesText?.isVisible = text.isNotBlank()
    }

    private fun ensureOverviewSelection() {
        val list = stepList ?: return
        val model = stepListModel ?: return
        if (model.size == 0) return

        if (list.selectedIndex in 0 until model.size) {
            return
        }

        val preferredIndex = (0 until model.size).firstOrNull { !model.getElementAt(it).broken } ?: 0
        list.selectedIndex = preferredIndex
        list.ensureIndexIsVisible(preferredIndex)
    }

    private fun refreshSelectedStepDetails() {
        val step = selectedStepIndex()?.let { stepListModel?.getElementAt(it) }
        if (step == null) {
            overviewSelectionTitle?.text = "Selected step"
            overviewSelectionMeta?.text = "Choose a step to preview it in the editor."
            overviewSelectionBody?.text = ""
            selectedStepSnapshot = null
            updateOverviewDetailTabs(null)
            updateOverviewSelectionActions(null)
            return
        }

        selectedStepSnapshot = step
        overviewSelectionTitle?.text = when {
            step.broken -> "${step.title} (needs repair)"
            step.uncertain -> "${step.title} (uncertain)"
            else -> step.title
        }

        val metaParts = mutableListOf(
            step.filePath,
            "L${step.startLine}-L${step.endLine}",
        )
        step.symbol?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
        step.stepType?.takeIf { it.isNotBlank() }?.let { metaParts.add("type: $it") }
        step.importance?.takeIf { it.isNotBlank() }?.let { metaParts.add("importance: $it") }
        step.severity?.takeIf { it.isNotBlank() }?.let { metaParts.add("severity: $it") }
        (step.confidence?.takeIf { it.isNotBlank() } ?: if (step.uncertain) "uncertain" else null)?.let {
            metaParts.add("confidence: $it")
        }
        step.riskType?.takeIf { it.isNotBlank() }?.let { metaParts.add("risk: $it") }
        if (sessionService.isEntryStep(step.id)) {
            metaParts.add("entrypoint")
        }
        if (sessionService.isTerminalStep(step.id)) {
            metaParts.add("terminal")
        }
        overviewSelectionMeta?.text = metaParts.joinToString("  ·  ")

        val detailLines = mutableListOf(step.explanation)
        detailLines += ""
        detailLines += "Why this step matters:"
        detailLines += step.whyIncluded
        buildHopSummary(step)?.let { hopSummary ->
            detailLines += ""
            detailLines += "Path grounding:"
            detailLines += hopSummary
        }
        if (step.lineAnnotations.isNotEmpty()) {
            detailLines += ""
            detailLines += "Annotations:"
            detailLines += step.lineAnnotations.map { annotation ->
                val range = if (annotation.startLine == annotation.endLine) {
                    "L${annotation.startLine}"
                } else {
                    "L${annotation.startLine}-L${annotation.endLine}"
                }
                "$range: ${annotation.text}"
            }
        }
        if (step.breakReason != null) {
            detailLines += ""
            detailLines += "Repair note:"
            detailLines += step.breakReason
        }
        if (step.validationNote != null) {
            detailLines += ""
            detailLines += "Grounding note:"
            detailLines += step.validationNote
        }
        step.suggestedAction?.takeIf { it.isNotBlank() }?.let {
            detailLines += ""
            detailLines += "Suggested action:"
            detailLines += it
        }
        overviewSelectionBody?.text = detailLines.joinToString("\n")

        updateOverviewDetailTabs(step)
        updateOverviewSelectionActions(step)
    }

    private fun updateOverviewSelectionActions(step: FlowStep?) {
        val enabled = step != null && !step.broken
        val commentComposerEnabled = settings.state.enableCommentComposer
        previewSelectedButton?.isEnabled = enabled
        startTourButton?.isEnabled = enabled
        copyMarkdownButton?.isEnabled = sessionService.currentFlowMap != null
        draftCommentButton?.isEnabled = commentComposerEnabled && enabled
        copyCommentButton?.isEnabled = commentComposerEnabled && enabled && !overviewCommentText?.text.isNullOrBlank()
    }

    private fun createOverviewTabsPanel(): JTabbedPane {
        val tabs = JTabbedPane()

        overviewExplainText = createOverviewTextArea()
        overviewEvidenceText = createOverviewTextArea()
        overviewRiskText = createOverviewTextArea()
        overviewTestsText = createOverviewTextArea()
        tabs.addTab("Explain", JBScrollPane(overviewExplainText!!))
        tabs.addTab("Evidence", JBScrollPane(overviewEvidenceText!!))
        tabs.addTab("Risk", JBScrollPane(overviewRiskText!!))
        commentTabIndex = tabs.tabCount
        tabs.addTab("Comment", createCommentComposerPanel())
        tabs.addTab("Tests", JBScrollPane(overviewTestsText!!))

        commentStyleCombo?.addActionListener {
            selectedStepSnapshot?.let { draftCommentForSelection(it, forceRefresh = true) }
        }
        return tabs
    }

    private fun createOverviewTextArea(editable: Boolean = false): JTextArea {
        return JTextArea().apply {
            isEditable = editable
            lineWrap = true
            wrapStyleWord = true
            font = if (editable) JBUI.Fonts.label() else JBUI.Fonts.smallFont()
            background = if (editable) UIUtil.getPanelBackground() else JBUI.CurrentTheme.ToolWindow.background()
            border = JBUI.Borders.empty()
        }
    }

    private fun createCommentComposerPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(4, 0, 0, 0)

        val header = JPanel(BorderLayout())
        commentTitleLabel = JBLabel("Comment composer").apply {
            font = JBUI.Fonts.label().asBold()
        }
        commentStatusLabel = JBLabel("Draft a review comment from the selected step.").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }
        header.add(commentTitleLabel!!, BorderLayout.NORTH)
        header.add(commentStatusLabel!!, BorderLayout.SOUTH)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 0, 6, 0)
        }
        controls.add(JBLabel("Style:"))
        commentStyleCombo = JComboBox(CommentStyle.entries.toTypedArray()).apply {
            toolTipText = "Choose the tone of the review comment"
        }
        controls.add(commentStyleCombo!!)
        controls.add(JButton("Draft").apply {
            addActionListener { draftCommentForSelection(forceRefresh = true) }
        })
        controls.add(JButton("Copy").apply {
            addActionListener { copyCommentDraft() }
        })
        controls.add(JButton("Use Step Evidence").apply {
            addActionListener { selectedStepSnapshot?.let { draftCommentForSelection(it, forceRefresh = true) } }
        })

        overviewCommentText = createOverviewTextArea(editable = true).apply {
            text = ""
            toolTipText = "A review comment draft will appear here."
        }

        val body = JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.NORTH)
            add(JBScrollPane(overviewCommentText!!), BorderLayout.CENTER)
        }

        panel.add(header, BorderLayout.NORTH)
        panel.add(body, BorderLayout.CENTER)
        return panel
    }

    private fun updateOverviewDetailTabs(step: FlowStep?) {
        if (step == null) {
            overviewExplainText?.text = ""
            overviewEvidenceText?.text = ""
            overviewRiskText?.text = ""
            overviewTestsText?.text = ""
            overviewCommentText?.text = ""
            commentStatusLabel?.text = "Select a step to populate the review tabs."
            return
        }

        overviewExplainText?.text = buildExplainText(step)
        overviewEvidenceText?.text = buildEvidenceText(step)
        overviewRiskText?.text = buildRiskText(step)
        overviewTestsText?.text = buildTestsText(step)
        overviewCommentText?.text = buildCommentDraft(step, commentStyleCombo?.selectedItem as? CommentStyle)
        commentStatusLabel?.text = if (step.broken) {
            "This step needs repair before it can be toured."
        } else if (step.validationNote != null) {
            "This step was adjusted during validation; keep the comment grounded to the shown range."
        } else if (step.uncertain) {
            "This step is inferred; the comment draft should stay cautious."
        } else {
            "Draft grounded in the selected step."
        }
    }

    private fun draftCommentForSelection(step: FlowStep? = selectedStepSnapshot, forceRefresh: Boolean = false) {
        if (!settings.state.enableCommentComposer) return
        if (step == null || step.broken) return
        val commentStyle = commentStyleCombo?.selectedItem as? CommentStyle
        if (forceRefresh || overviewCommentText?.text.isNullOrBlank()) {
            overviewCommentText?.text = buildCommentDraft(step, commentStyle)
        }
        copyCommentButton?.isEnabled = !overviewCommentText?.text.isNullOrBlank()
    }

    private fun copyCommentDraft() {
        val text = overviewCommentText?.text?.trim().orEmpty()
        if (text.isEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        WindowManager.getInstance().getStatusBar(project)?.info = "Comment draft copied to clipboard"
    }

    private fun buildExplainText(step: FlowStep): String = buildString {
        appendLine(step.explanation)
        appendLine()
        appendLine("Why this step matters:")
        append(step.whyIncluded)
        val roleLine = buildList {
            step.stepType?.takeIf { it.isNotBlank() }?.let { add("type: $it") }
            step.importance?.takeIf { it.isNotBlank() }?.let { add("importance: $it") }
            if (sessionService.isEntryStep(step.id)) add("entrypoint")
            if (sessionService.isTerminalStep(step.id)) add("terminal")
        }.joinToString("  ·  ")
        if (roleLine.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("Path role: $roleLine")
        }
        step.validationNote?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine()
            appendLine("Grounding note: $it")
        }
        if (step.symbol != null) {
            appendLine()
            appendLine()
            appendLine("Symbol: ${step.symbol}")
        }
    }

    private fun buildEvidenceText(step: FlowStep): String = buildString {
        appendLine("File: ${step.filePath}")
        appendLine("Range: L${step.startLine}-L${step.endLine}")
        step.symbol?.takeIf { it.isNotBlank() }?.let {
            appendLine("Symbol: $it")
        }
        step.validationNote?.takeIf { it.isNotBlank() }?.let {
            appendLine("Grounding note: $it")
        }
        val incoming = sessionService.incomingHops(step.id)
        val outgoing = sessionService.outgoingHops(step.id)
        if (incoming.isNotEmpty() || outgoing.isNotEmpty()) {
            appendLine()
            appendLine("Path hops:")
            incoming.forEach { edge -> appendLine("- From: ${formatHop(edge, showTarget = false)}") }
            outgoing.forEach { edge -> appendLine("- To: ${formatHop(edge, showTarget = true)}") }
        }
        if (step.evidence.isNotEmpty()) {
            appendLine()
            appendLine("Grounding evidence:")
            step.evidence.forEach { evidence ->
                val location = buildList {
                    evidence.filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    evidence.startLine?.let { start ->
                        add(
                            if (evidence.endLine != null && evidence.endLine != start) {
                                "L$start-L${evidence.endLine}"
                            } else {
                                "L$start"
                            },
                        )
                    }
                }.joinToString(":")
                val details = listOfNotNull(
                    evidence.kind.takeIf { it.isNotBlank() },
                    location.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                appendLine("- ${evidence.label}${if (details.isNotBlank()) " ($details)" else ""}")
                evidence.text?.takeIf { it.isNotBlank() }?.let { appendLine("  $it") }
            }
        }
        if (step.lineAnnotations.isNotEmpty()) {
            appendLine()
            appendLine("Line annotations:")
            step.lineAnnotations.forEach { annotation ->
                val range = if (annotation.startLine == annotation.endLine) {
                    "L${annotation.startLine}"
                } else {
                    "L${annotation.startLine}-L${annotation.endLine}"
                }
                appendLine("- $range: ${annotation.text}")
            }
        }
    }

    private fun buildRiskText(step: FlowStep): String = buildString {
        if (step.broken) {
            appendLine("Status: needs repair")
            step.breakReason?.let {
                appendLine("Repair note: $it")
            }
            return@buildString
        }
        appendLine("Risk signal: ${step.severity ?: if (step.uncertain) "uncertain" else "directly traced"}")
        step.validationNote?.takeIf { it.isNotBlank() }?.let {
            appendLine("Grounding note: $it")
        }
        step.riskType?.takeIf { it.isNotBlank() }?.let {
            appendLine("Risk type: $it")
        }
        step.confidence?.takeIf { it.isNotBlank() }?.let {
            appendLine("Confidence: $it")
        }
        buildHopSummary(step)?.let {
            appendLine()
            appendLine("Path grounding:")
            appendLine(it)
        }
        appendLine()
        appendLine("Why this is in the tour:")
        appendLine(step.whyIncluded)
        step.suggestedAction?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Suggested action:")
            appendLine(it)
        }
        step.testGap?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Test gap:")
            appendLine(it)
        }
        if (step.evidence.isNotEmpty()) {
            appendLine()
            appendLine("Evidence count: ${step.evidence.size}")
        }
    }

    private fun buildTestsText(step: FlowStep): String = buildString {
        val flowMap = sessionService.currentFlowMap
        val matchingSuggestedTests = flowMap?.suggestedTests.orEmpty().filter { test ->
            val hint = test.fileHint?.trim().orEmpty()
            hint.isEmpty() || step.filePath.contains(hint)
        }
        if (step.testGap.isNullOrBlank() && matchingSuggestedTests.isEmpty()) {
            appendLine("No explicit test guidance was returned for this step.")
            appendLine("Use this step for targeted regression coverage if you change it.")
            return@buildString
        }
        step.testGap?.takeIf { it.isNotBlank() }?.let {
            appendLine("Step-specific test gap:")
            appendLine(it)
        }
        if (matchingSuggestedTests.isNotEmpty()) {
            if (isNotEmpty()) {
                appendLine()
            }
            appendLine("Suggested tests:")
            matchingSuggestedTests.forEach { test ->
                val hint = test.fileHint?.takeIf { it.isNotBlank() }?.let { " (${it})" }.orEmpty()
                appendLine("- ${test.title}$hint: ${test.description}")
            }
        }
    }

    private fun buildHopSummary(step: FlowStep): String? {
        val incoming = sessionService.incomingHops(step.id)
        val preferredOutgoing = sessionService.preferredNextHop(step.id, visitedStepIds = emptySet())
        val summaryLines = buildList {
            if (incoming.isEmpty() && sessionService.isEntryStep(step.id)) {
                add("This is the grounded entrypoint for the traced path.")
            } else {
                incoming.firstOrNull()?.let { add("Arrives from ${formatHop(it, showTarget = false)}") }
            }
            preferredOutgoing?.let { add("Next important hop is ${formatHop(it, showTarget = true)}") }
            if (preferredOutgoing == null && sessionService.isTerminalStep(step.id)) {
                add("The validated path terminates at this step.")
            }
        }
        return summaryLines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun formatHop(edge: StepEdge, showTarget: Boolean): String {
        val flowMap = sessionService.currentFlowMap
        val peerStepId = if (showTarget) edge.toStepId else edge.fromStepId
        val peerTitle = flowMap?.steps?.firstOrNull { it.id == peerStepId }?.title ?: peerStepId
        val callSite = edge.callSiteStartLine?.let { start ->
            val lineRange = if (edge.callSiteEndLine != null && edge.callSiteEndLine != start) {
                "L$start-L${edge.callSiteEndLine}"
            } else {
                "L$start"
            }
            listOfNotNull(edge.callSiteFilePath, lineRange).joinToString(":")
        }
        val details = buildList {
            edge.kind.takeIf { it.isNotBlank() }?.let { add(it) }
            edge.importance?.takeIf { it.isNotBlank() }?.let { add("importance: $it") }
            callSite?.takeIf { it.isNotBlank() }?.let { add(it) }
            edge.callSiteLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (edge.uncertain) add("uncertain")
        }.joinToString("  ·  ")
        return if (details.isNotBlank()) {
            "$peerTitle ($details)"
        } else {
            peerTitle
        }
    }

    private fun buildCommentDraft(step: FlowStep, style: CommentStyle? = null): String = buildString {
        val selectedStyle = style ?: CommentStyle.CONCERN
        val preferredType = when (selectedStyle) {
            CommentStyle.QUESTION -> "question"
            CommentStyle.CONCERN -> "concern"
            CommentStyle.SUGGESTION -> "suggestion"
            CommentStyle.APPROVAL -> "praise"
        }
        val preferredTone = when (selectedStyle) {
            CommentStyle.QUESTION, CommentStyle.CONCERN -> "neutral"
            CommentStyle.SUGGESTION -> "direct"
            CommentStyle.APPROVAL -> "friendly"
        }
        val groundedDraft = step.commentDrafts.firstOrNull { draft ->
            draft.type.equals(preferredType, ignoreCase = true) &&
                draft.tone.equals(preferredTone, ignoreCase = true)
        } ?: step.commentDrafts.firstOrNull { draft ->
            draft.type.equals(preferredType, ignoreCase = true)
        } ?: step.commentDrafts.firstOrNull()
        if (groundedDraft != null) {
            append(groundedDraft.text.trim())
            return@buildString
        }
        val subject = step.symbol?.takeIf { it.isNotBlank() } ?: step.title
        append(selectedStyle.leadIn)
        append(" we tighten the behavior in `")
        append(subject)
        append("`?")
        appendLine()
        appendLine()
        appendLine("This step points at `${step.filePath}`:${step.startLine}-${step.endLine}.")
        appendLine("Why it matters: ${step.whyIncluded}")
        if (step.uncertain) {
            appendLine("Confidence is low here, so the comment should stay cautious.")
        }
        if (step.lineAnnotations.isNotEmpty()) {
            appendLine()
            appendLine("Evidence:")
            step.lineAnnotations.take(2).forEach { annotation ->
                appendLine("- ${annotation.text}")
            }
        }
        when (selectedStyle) {
            CommentStyle.QUESTION -> append("Could you clarify the intended behavior here?")
            CommentStyle.CONCERN -> append("I think this deserves a quick regression check and a test before merge.")
            CommentStyle.SUGGESTION -> append("Consider adding a test or a guard for the risky path.")
            CommentStyle.APPROVAL -> append("Looks good because the intent is clear and the scope is well contained.")
        }
    }

    private fun createOverviewListActions(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(6, 0, 0, 0)
            add(JButton("Preview").apply { addActionListener { previewSelectedStep() } })
            add(JButton("Start Tour").apply { addActionListener { startTourFromSelection() } })
            add(JButton("Draft Comment").apply { addActionListener { draftCommentForSelection() } })
        }
    }

    private fun createSelectedStepHeader(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        panel.add(overviewSelectionTitle!!)
        panel.add(overviewSelectionMeta!!)
        panel.add(overviewSelectionBody!!)
        return panel
    }

    private fun createOverviewFooterActions(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
            add(JButton("Preview Selected").apply { addActionListener { previewSelectedStep() } })
            add(JButton("Start Tour").apply { addActionListener { startTourFromSelection() } })
            add(JButton("Copy Markdown").apply { addActionListener { copyFlowMapMarkdown() } })
        }
    }

    private fun createFollowUpRow(): JPanel {
        val followUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
        followUpPanel.border = JBUI.Borders.empty(8, 0, 0, 0)
        followUpField = JBTextField().apply field@{
            emptyText.setText("Ask a follow-up...")
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val navigated = when (e.keyCode) {
                        KeyEvent.VK_UP -> historyUp(this@field, requireAtStart = false)
                        KeyEvent.VK_DOWN -> historyDown(this@field, requireAtEnd = false)
                        else -> false
                    }
                    if (navigated) e.consume()
                }
            })
            addActionListener { submitOverviewFollowUp() }
        }
        followUpPanel.add(followUpField!!, BorderLayout.CENTER)
        followUpPanel.add(JButton("Send").apply {
            addActionListener { submitOverviewFollowUp() }
        }, BorderLayout.EAST)
        return followUpPanel
    }

    private fun selectedStepIndex(): Int? =
        stepList?.selectedIndex?.takeIf { it >= 0 }

    private fun selectedStepOriginalIndex(): Int? {
        val selected = selectedStepSnapshot ?: return null
        return sessionService.currentFlowMap?.steps?.indexOfFirst { it.id == selected.id }?.takeIf { it >= 0 }
    }

    private fun previewSelectedStep() {
        selectedStepOriginalIndex()?.let { sessionService.previewStep(it) }
    }

    private fun startTourFromSelection() {
        selectedStepOriginalIndex()?.let { sessionService.startTour(it) }
    }

    private fun copyFlowMapMarkdown() {
        val flowMap = sessionService.currentFlowMap ?: return
        val markdown = FlowMapMarkdownExporter.build(
            question = sessionService.followUpContext?.originalQuestion ?: sessionService.currentQuestion,
            flowMap = flowMap,
            metadata = sessionService.lastMetadata,
            activeStepId = sessionService.followUpContext?.activeStepId,
        )
        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        WindowManager.getInstance().getStatusBar(project)?.info = "Walkthrough copied to clipboard"
    }

    private fun submitOverviewFollowUp() {
        submitFieldText(followUpField) { text ->
            sessionService.submitFollowUp(
                buildPromptWithContext(text, currentMode, selectedStepContext()),
                queryContext = selectedOverviewQueryContext(),
            )
        }
    }

    private fun submitTourFollowUp() {
        submitFieldText(tourFollowUpField) { text ->
            sessionService.askAboutCurrentStep(text)
        }
    }

    private fun submitClarificationAnswer() {
        submitFieldText(clarificationField) { sessionService.answerClarification(it) }
    }

    private fun selectedStepContext(): String? {
        val step = selectedStepSnapshot ?: return null
        return buildString {
            appendLine("File: ${step.filePath}")
            appendLine("Lines: L${step.startLine}-L${step.endLine}")
            step.symbol?.takeIf { it.isNotBlank() }?.let {
                appendLine("Symbol: $it")
            }
            step.stepType?.takeIf { it.isNotBlank() }?.let {
                appendLine("Type: $it")
            }
            step.importance?.takeIf { it.isNotBlank() }?.let {
                appendLine("Importance: $it")
            }
            appendLine("Explanation: ${step.explanation}")
            appendLine("Why included: ${step.whyIncluded}")
            buildHopSummary(step)?.let {
                appendLine("Path grounding:")
                appendLine(it)
            }
        }.trim()
    }

    private fun selectedOverviewQueryContext(): QueryContext? {
        val step = selectedStepSnapshot
        return if (step != null) {
            QueryContext(
                filePath = step.filePath,
                symbol = step.symbol,
                selectionStartLine = step.startLine,
                selectionEndLine = step.endLine,
                featureScope = sessionService.currentFeatureScope,
            )
        } else {
            currentEditorQueryContext()
        }
    }

    private fun submitFieldText(field: JBTextField?, onSubmit: (String) -> Unit) {
        val text = field?.text?.trim().orEmpty()
        if (text.isEmpty()) return
        addToHistory(text)
        onSubmit(text)
        field?.text = ""
    }

    // ── Tour Active Card ──────────────────────────────────────────────

    private fun createTourActiveCard(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        tourStepHeader = JBLabel().apply {
            font = JBUI.Fonts.label().asBold()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        contentPanel.add(tourStepHeader!!)

        tourUncertainLabel = JBLabel("(uncertain)").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
        contentPanel.add(tourUncertainLabel!!)

        tourStepFilePath = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        contentPanel.add(tourStepFilePath!!)

        tourStepExplanation = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.label()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(tourStepExplanation!!)

        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        val whyToggle = JBLabel("Why this step?").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(whyToggle)

        tourWhyText = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
        }
        tourWhySection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 0, 0)
            add(tourWhyText!!, BorderLayout.CENTER)
            isVisible = false
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(tourWhySection!!)

        whyToggle.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                tourWhySection!!.isVisible = !tourWhySection!!.isVisible
                tourWhySection!!.revalidate()
                tourWhySection!!.repaint()
            }
        })

        panel.add(JBScrollPane(contentPanel), BorderLayout.CENTER)

        val footerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val contextualFollowUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }
        tourFollowUpField = JBTextField().apply field@{
            emptyText.setText("Ask about this step...")
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    val navigated = when (e.keyCode) {
                        KeyEvent.VK_UP -> historyUp(this@field, requireAtStart = false)
                        KeyEvent.VK_DOWN -> historyDown(this@field, requireAtEnd = false)
                        else -> false
                    }
                    if (navigated) e.consume()
                }
            })
            addActionListener { submitTourFollowUp() }
        }
        contextualFollowUpPanel.add(tourFollowUpField!!, BorderLayout.CENTER)
        tourAskButton = JButton("Ask").apply {
            addActionListener { submitTourFollowUp() }
        }
        contextualFollowUpPanel.add(tourAskButton!!, BorderLayout.EAST)
        footerPanel.add(contextualFollowUpPanel)

        val stepAnswerPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8),
            )
        }
        tourStepAnswerStatus = JBLabel("Ask a targeted question about this step without leaving the tour.").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        stepAnswerPanel.add(tourStepAnswerStatus!!, BorderLayout.NORTH)
        tourStepAnswerText = createOverviewTextArea().apply {
            text = "The answer will stay scoped to the current symbol and its important lines."
        }
        tourStepAnswerEvidenceText = createOverviewTextArea().apply {
            text = "Evidence and important lines will appear here when available."
        }
        val answerTabs = JTabbedPane().apply {
            addTab("Answer", JBScrollPane(tourStepAnswerText!!))
            addTab("Evidence", JBScrollPane(tourStepAnswerEvidenceText!!))
        }
        stepAnswerPanel.add(answerTabs, BorderLayout.CENTER)
        footerPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        footerPanel.add(stepAnswerPanel)

        val navPanel = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }
        navPanel.add(JButton("< Prev").apply {
            addActionListener { sessionService.prevStep() }
        })
        navPanel.add(JButton("Skip").apply {
            addActionListener { sessionService.skipStep() }
        })
        navPanel.add(JButton("Next >").apply {
            addActionListener { sessionService.nextStep() }
        })
        navPanel.add(JButton("Stop Tour").apply {
            addActionListener { sessionService.stopTour() }
        })
        footerPanel.add(navPanel)
        panel.add(footerPanel, BorderLayout.SOUTH)

        tourActivePanel = panel
        return panel
    }

    private fun refreshTourActiveCard(stepIndex: Int, step: FlowStep) {
        val totalSteps = sessionService.currentFlowMap?.steps?.size ?: 0
        tourStepHeader?.text = "Step ${stepIndex + 1}/$totalSteps \u2014 ${step.title}"
        tourStepFilePath?.text = buildList {
            add(step.filePath)
            step.stepType?.takeIf { it.isNotBlank() }?.let { add("type: $it") }
            step.importance?.takeIf { it.isNotBlank() }?.let { add("importance: $it") }
            if (sessionService.isEntryStep(step.id)) add("entrypoint")
            if (sessionService.isTerminalStep(step.id)) add("terminal")
        }.joinToString("  ·  ")
        tourStepExplanation?.text = step.explanation
        tourWhyText?.text = listOfNotNull(
            step.whyIncluded,
            buildHopSummary(step)?.let { "\nPath grounding:\n$it" },
        ).joinToString("\n")
        tourWhySection?.isVisible = false
        tourUncertainLabel?.isVisible = step.uncertain
        tourFollowUpField?.toolTipText = "Ask a follow-up about ${step.title}"
        refreshTourStepAnswer(
            answer = sessionService.currentStepAnswer,
            loading = sessionService.stepAnswerLoading,
            errorMessage = sessionService.stepAnswerError,
        )

        tourActivePanel?.revalidate()
        tourActivePanel?.repaint()
    }

    private fun refreshTourStepAnswer(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {
        tourAskButton?.isEnabled = !loading
        tourFollowUpField?.isEnabled = !loading
        when {
            loading -> {
                tourStepAnswerStatus?.text = "Analyzing the current symbol..."
                tourStepAnswerText?.text = "Working through the current step without remapping the whole tour."
                tourStepAnswerEvidenceText?.text = "Evidence will appear here once the answer is ready."
            }
            !errorMessage.isNullOrBlank() -> {
                tourStepAnswerStatus?.text = errorMessage
                tourStepAnswerText?.text = "The step answer failed. Refine the question or try again."
                tourStepAnswerEvidenceText?.text = ""
            }
            answer != null -> {
                tourStepAnswerStatus?.text = buildStepAnswerStatus(answer)
                tourStepAnswerText?.text = buildStepAnswerText(answer)
                tourStepAnswerEvidenceText?.text = buildStepAnswerEvidenceText(answer)
            }
            else -> {
                tourStepAnswerStatus?.text = "Ask a targeted question about this step without leaving the tour."
                tourStepAnswerText?.text = "The answer will stay scoped to the current symbol and its important lines."
                tourStepAnswerEvidenceText?.text = "Evidence and important lines will appear here when available."
            }
        }
    }

    private fun buildStepAnswerStatus(answer: StepAnswer): String {
        val parts = mutableListOf("Step-scoped answer")
        (answer.confidence?.takeIf { it.isNotBlank() } ?: if (answer.uncertain) "uncertain" else null)?.let {
            parts.add("confidence: $it")
        }
        if (answer.evidence.isNotEmpty()) {
            parts.add("${answer.evidence.size} evidence points")
        }
        return parts.joinToString("  ·  ")
    }

    private fun buildStepAnswerText(answer: StepAnswer): String = buildString {
        appendLine(answer.answer)
        answer.whyItMatters?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Why it matters:")
            appendLine(it)
        }
        if (answer.importantLines.isNotEmpty()) {
            appendLine()
            appendLine("Important lines:")
            answer.importantLines.forEach { annotation ->
                val range = if (annotation.startLine == annotation.endLine) {
                    "L${annotation.startLine}"
                } else {
                    "L${annotation.startLine}-L${annotation.endLine}"
                }
                appendLine("- $range: ${annotation.text}")
            }
        }
    }.trim()

    private fun buildStepAnswerEvidenceText(answer: StepAnswer): String {
        if (answer.evidence.isEmpty() && answer.importantLines.isEmpty()) {
            return "No additional grounding details were returned for this answer."
        }
        return buildString {
            if (answer.evidence.isNotEmpty()) {
                appendLine("Evidence:")
                answer.evidence.forEach { evidence ->
                    val location = buildList {
                        evidence.filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
                        evidence.startLine?.let { start ->
                            add(
                                if (evidence.endLine != null && evidence.endLine != start) {
                                    "L$start-L${evidence.endLine}"
                                } else {
                                    "L$start"
                                },
                            )
                        }
                    }.joinToString(":")
                    val details = listOfNotNull(
                        evidence.kind.takeIf { it.isNotBlank() },
                        location.takeIf { it.isNotBlank() },
                    ).joinToString("  ·  ")
                    appendLine("- ${evidence.label}${if (details.isNotBlank()) " ($details)" else ""}")
                    evidence.text?.takeIf { it.isNotBlank() }?.let { appendLine("  $it") }
                }
            }
            if (answer.importantLines.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Important lines:")
                answer.importantLines.forEach { annotation ->
                    val range = if (annotation.startLine == annotation.endLine) {
                        "L${annotation.startLine}"
                    } else {
                        "L${annotation.startLine}-L${annotation.endLine}"
                    }
                    appendLine("- $range: ${annotation.text}")
                }
            }
        }.trim()
    }

    // ── Provider Status ─────────────────────────────────────────────────

    private fun checkProviderStatus() {
        scope.launch {
            val providerService = project.service<LlmProviderService>()
            val status = providerService.checkAvailability()
            javax.swing.SwingUtilities.invokeLater {
                repoReviewButton?.isEnabled = status.available && providerService.supportsRepositoryReview()
                when {
                    status.available && status.walkthroughSupported -> {
                        statusDot?.icon = AllIcons.General.InspectionsOK
                        statusDot?.text = status.message
                        statusDot?.foreground = JBColor.namedColor(
                            "Label.successForeground",
                            JBColor(0x3D8F58, 0x499C54),
                        )
                    }
                    status.available -> {
                        statusDot?.icon = AllIcons.General.WarningDialog
                        statusDot?.text = status.message
                        statusDot?.foreground = JBColor.namedColor(
                            "Label.warningForeground",
                            JBColor(0xA36A00, 0xD8A657),
                        )
                    }
                    else -> {
                        statusDot?.icon = AllIcons.General.Error
                        statusDot?.text = status.message
                        statusDot?.foreground = JBColor.namedColor(
                            "Label.errorForeground",
                            JBColor.RED,
                        )
                    }
                }
            }
        }
    }

    // ── Shared ───────────────────────────────────────────────────────────

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

    private fun String.truncateForDisplay(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars - 3).trimEnd() + "..."

    private class FlowStepCellRenderer : ListCellRenderer<FlowStep> {
        override fun getListCellRendererComponent(
            list: JList<out FlowStep>,
            value: FlowStep,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val title = SimpleColoredComponent().apply {
                ipad = JBUI.insets(0, 0, 2, 0)
                isOpaque = false
            }
            val metaLabel = JBLabel(FlowStepMetaFormatter.format(value)).apply {
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

            val stepNumber = "${index + 1}. "
            if (value.broken) {
                title.append(stepNumber, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                title.append(
                    value.title,
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getLabelDisabledForeground()),
                )
                title.append("  needs repair", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            } else {
                title.append(stepNumber, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                title.append(value.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (value.uncertain) {
                    title.append(" (uncertain)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
                if (value.validationNote != null) {
                    title.append(" [adjusted]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            panel.add(title, BorderLayout.NORTH)
            panel.add(metaLabel, BorderLayout.SOUTH)
            return panel
        }
    }

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

    // ── Command History ──────────────────────────────────────────────────

    private fun addToHistory(text: String) {
        if (history.isEmpty() || history.last() != text) {
            history.add(text)
        }
        historyIndex = history.size
        historyDraft = ""
    }

    /** Navigate to the previous history entry. Returns true if navigation occurred. */
    private fun historyUp(field: javax.swing.text.JTextComponent, requireAtStart: Boolean): Boolean {
        if (history.isEmpty() || historyIndex == 0) return false
        if (requireAtStart && field.caretPosition != 0) return false
        if (historyIndex == history.size) {
            historyDraft = field.text
        }
        historyIndex--
        field.text = history[historyIndex]
        field.caretPosition = 0
        return true
    }

    /** Navigate to the next history entry (or restore draft). Returns true if navigation occurred. */
    private fun historyDown(field: javax.swing.text.JTextComponent, requireAtEnd: Boolean): Boolean {
        if (historyIndex >= history.size) return false
        if (requireAtEnd && field.caretPosition != field.document.length) return false
        historyIndex++
        val newText = if (historyIndex < history.size) history[historyIndex] else historyDraft
        field.text = newText
        field.caretPosition = newText.length
        return true
    }

    companion object {
        private const val CARD_INPUT = "INPUT"
        private const val CARD_LOADING = "LOADING"
        private const val CARD_OVERVIEW = "OVERVIEW"
        private const val CARD_REPO_REVIEW = "REPO_REVIEW"
        private const val CARD_TOUR_ACTIVE = "TOUR_ACTIVE"
    }
}
