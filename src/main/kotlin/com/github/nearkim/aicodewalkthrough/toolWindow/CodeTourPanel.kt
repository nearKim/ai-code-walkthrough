package com.github.nearkim.aicodewalkthrough.toolwindow

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.QueryContext
import com.github.nearkim.aicodewalkthrough.model.RecentWalkthrough
import com.github.nearkim.aicodewalkthrough.model.RepositoryFinding
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepEdge
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.EditorContextFormatter
import com.github.nearkim.aicodewalkthrough.service.EditorContextService
import com.github.nearkim.aicodewalkthrough.service.LlmProviderService
import com.github.nearkim.aicodewalkthrough.service.ProviderStatus
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.ViewportWidthPanel
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.WrapLayout
import com.github.nearkim.aicodewalkthrough.toolwindow.layout.WrappingTextArea
import com.github.nearkim.aicodewalkthrough.util.FlowMapMarkdownExporter
import com.github.nearkim.aicodewalkthrough.util.FlowStepMetaFormatter
import com.github.nearkim.aicodewalkthrough.util.RepositoryReviewMarkdownExporter
import com.github.nearkim.aicodewalkthrough.toolwindow.review.RepositoryReviewCard
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
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.ListCellRenderer
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

internal enum class ReviewMode(
    val displayName: String,
    val placeholder: String,
    val intro: String,
    val helperText: String,
    val tooltipText: String,
) {
    UNDERSTAND(
        "Understand",
        "Ask how this code works...",
        "Explain the codebase clearly and trace execution.",
        "Explain what the code does, why it exists, and how the main pieces fit together.",
        "Use this when you want a clear explanation of behavior and structure.",
    ),
    REVIEW(
        "Review",
        "Review this code for risks, bugs, and test gaps...",
        "Focus on correctness, regressions, and actionable review notes.",
        "Look for bugs, regressions, unsafe assumptions, and missing tests.",
        "Use this when you want findings ordered by severity.",
    ),
    TRACE(
        "Trace",
        "Trace the execution path or call chain...",
        "Follow symbols and execution order with minimal speculation.",
        "Follow the important call chain or execution path through the code.",
        "Use this when you want to see how control or data moves step by step.",
    ),
    RISK(
        "Risk",
        "Highlight blast radius and invariants...",
        "Analyze what can break, who depends on it, and why.",
        "Estimate blast radius, invariants, and what could break if this area changes.",
        "Use this when you are planning a change and want impact analysis.",
    ),
    COMMENT(
        "Comment",
        "Draft a review comment for the current code...",
        "Compose concise, evidence-backed review comments.",
        "Draft a concise, evidence-backed code review comment for the selected area.",
        "Use this when you want review-ready comment text.",
    ),
    ;

    fun toAnalysisMode(): AnalysisMode = when (this) {
        UNDERSTAND -> AnalysisMode.UNDERSTAND
        REVIEW -> AnalysisMode.REVIEW
        TRACE -> AnalysisMode.TRACE
        RISK -> AnalysisMode.RISK
        COMMENT -> AnalysisMode.COMMENT
    }

    companion object {
        fun fromAnalysisMode(mode: AnalysisMode): ReviewMode = when (mode) {
            AnalysisMode.UNDERSTAND -> UNDERSTAND
            AnalysisMode.REVIEW -> REVIEW
            AnalysisMode.TRACE -> TRACE
            AnalysisMode.RISK -> RISK
            AnalysisMode.COMMENT -> COMMENT
        }
    }
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
    private var modeDescriptionText: JBTextArea? = null
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
    private var overviewScrollContent: JPanel? = null
    private var clarificationPanel: JPanel? = null
    private var clarificationLabel: JBLabel? = null
    private var clarificationField: JBTextField? = null
    private var metadataBar: JPanel? = null
    private var metadataLabel: JBLabel? = null
    private var overviewInsightsLabel: JBLabel? = null
    private var overviewFilterButtons = mutableMapOf<StepFilter, JToggleButton>()
    private var overviewGlobalNotesText: JTextArea? = null
    private var overviewSelectionTitle: JBLabel? = null
    private var overviewSelectionMeta: JBTextArea? = null
    private var overviewSelectionBody: JTextArea? = null
    private var overviewStepWarningPanel: JPanel? = null
    private var overviewStepWarningText: JTextArea? = null
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
    private var tourStepFilePath: JBTextArea? = null
    private var tourStepExplanation: JTextArea? = null
    private var tourStepWarningPanel: JPanel? = null
    private var tourStepWarningText: JTextArea? = null
    private var tourWhySection: JPanel? = null
    private var tourWhyText: JTextArea? = null
    private var tourUncertainLabel: JBLabel? = null
    private var tourFollowUpField: JBTextField? = null
    private var tourAskButton: JButton? = null
    private var tourStepAnswerStatus: JBLabel? = null
    private var tourStepAnswerWarningPanel: JPanel? = null
    private var tourStepAnswerWarningText: JTextArea? = null
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
    private var repositoryShortcutStatusText: JBTextArea? = null
    private var lastProviderStatus: ProviderStatus? = null
    private val repositoryReviewCard = RepositoryReviewCard(
        onStartFeatureWalkthrough = { featureId, pathId -> sessionService.startFeatureWalkthrough(featureId, pathId) },
        onRefreshReview = { sessionService.startRepositoryReview() },
        onCopyMarkdown = { copyRepositoryReviewMarkdown() },
    )
    private val progressLogLines = ArrayDeque<String>()

    // Command history
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private var historyDraft = ""
    private var listenerRegistered = false

    init {
        cardPanel.add(createInputCard(), CARD_INPUT)
        cardPanel.add(createLoadingCard(), CARD_LOADING)
        cardPanel.add(createOverviewCard(), CARD_OVERVIEW)
        cardPanel.add(createRepositoryReviewCard(), CARD_REPO_REVIEW)
        cardPanel.add(createTourActiveCard(), CARD_TOUR_ACTIVE)

        add(cardPanel, BorderLayout.CENTER)

        registerSessionListener()
        showCard(sessionService.state)
        checkProviderStatus()
    }

    override fun addNotify() {
        super.addNotify()
        registerSessionListener()
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
        onProgressLines(listOf(line))
    }

    override fun onProgressLines(lines: List<String>) {
        if (lines.isEmpty()) return
        loadingStatusLabel?.text = lines.last()
        if (!settings.state.showRawProgressLog) return

        lines.forEach { line ->
            progressLogLines.addLast(line)
        }
        while (progressLogLines.size > MAX_PROGRESS_LOG_LINES) {
            progressLogLines.removeFirst()
        }

        progressLog?.text = progressLogLines.joinToString(separator = "\n", postfix = "\n")
        progressLog?.caretPosition = progressLog?.document?.length ?: 0
    }

    override fun removeNotify() {
        super.removeNotify()
        unregisterSessionListener()
        stopElapsedTimer()
    }

    private fun showCard(state: TourState) {
        syncModeWithSession()
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
                resetProgressLog()
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
        val content = ViewportWidthPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            alignmentX = LEFT_ALIGNMENT
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }
        topPanel.add(createModeAndProviderHeader())
        topPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        topPanel.add(createStatusRow())
        topPanel.add(createErrorBannerPanel())
        content.add(topPanel)
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        // Center: prompt editor
        questionTextArea = WrappingTextArea().apply {
            rows = 4
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
        content.add(createInputSection(
            title = "Prompt",
            description = "Describe the behavior, review, risk, or execution path you want to inspect.",
            body = JBScrollPane(questionTextArea!!).apply {
                alignmentX = LEFT_ALIGNMENT
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(150))
                preferredSize = java.awt.Dimension(0, JBUI.scale(110))
                minimumSize = java.awt.Dimension(0, JBUI.scale(96))
            },
        ))
        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        recentWalkthroughsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 0, 8, 0)
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
        }
        bottomPanel.add(recentWalkthroughsPanel)

        val suggestions = listOf(
            "How does the main entry point work?",
            "What's the request/response lifecycle?",
            "How is configuration loaded and applied?",
            "What are the key abstractions and how do they relate?",
        )
        val chipsPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        for (suggestion in suggestions) {
            chipsPanel.add(createSuggestionChip(suggestion))
        }
        bottomPanel.add(createInputSection(
            title = "Examples",
            description = "Start from one of these prompts and adjust it to your question.",
            body = chipsPanel,
        ))
        bottomPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        bottomPanel.add(createInputSection(
            title = "Prompt Context",
            description = "Seed the prompt from the current file, symbol, or selection.",
            body = createContextChipRow(),
        ))
        bottomPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        bottomPanel.add(createQuickActionSection())

        content.add(bottomPanel)

        return JPanel(BorderLayout()).apply {
            add(
                JBScrollPane(content).apply {
                    border = JBUI.Borders.empty()
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun createModeAndProviderHeader(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }
        panel.add(createModeChipRow())
        panel.add(createModeDescriptionRow())
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))
        panel.add(createProviderRow())
        return panel
    }

    private fun createModeChipRow(): JPanel {
        val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(0, 0, 4, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        modeButtonGroup = ButtonGroup()
        row.add(JBLabel("Mode:"))
        ReviewMode.entries.forEach { mode ->
            val button = JToggleButton(mode.displayName).apply {
                isFocusable = false
                isOpaque = true
                isContentAreaFilled = true
                font = JBUI.Fonts.smallFont()
                toolTipText = mode.tooltipText
                addActionListener {
                    selectMode(mode, updatePromptPlaceholder = true)
                    questionTextArea?.requestFocusInWindow()
                }
            }
            modeButtonGroup?.add(button)
            modeButtons[mode] = button
            row.add(button)
        }
        selectMode(currentMode, updatePromptPlaceholder = true)
        return row
    }

    private fun createModeDescriptionRow(): JPanel {
        modeDescriptionText = createHelperTextArea().apply {
            border = JBUI.Borders.empty()
        }
        refreshModeDescription()
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(6, 8),
            )

            add(JBLabel("Selected Mode").apply {
                font = JBUI.Fonts.smallFont().asBold()
                foreground = UIUtil.getLabelDisabledForeground()
                alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 0, 2, 0)
            })
            add(modeDescriptionText!!)
        }
    }

    private fun createProviderRow(): JPanel {
        val providerPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            alignmentX = LEFT_ALIGNMENT
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
        val statusPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            alignmentX = LEFT_ALIGNMENT
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
        errorBannerPanel = JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
        }
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
        val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        row.add(createPromptChip("Current file") { applyPromptPreset(ReviewMode.UNDERSTAND, "Explain the current file and its role in the codebase.") })
        row.add(createPromptChip("Selection") { applyPromptPreset(ReviewMode.REVIEW, "Review the current selection for risks, regressions, and missing tests.") })
        row.add(createPromptChip("Trace flow") { applyPromptPreset(ReviewMode.TRACE, "Trace the execution path through the current code and identify key call sites.") })
        row.add(createPromptChip("Write comment") { applyPromptPreset(ReviewMode.COMMENT, "Draft a concise review comment for the current code.") })
        return row
    }

    private fun createQuickActionSection(): JPanel {
        repoReviewButton = createActionButton(
            text = "Run Repo Review",
            toolTip = "Run a repository-wide review using symbolic analysis and store feature slices for later walkthroughs.",
            onClick = { sessionService.startRepositoryReview() },
        )
        openRepoReviewButton = createActionButton(
            text = "Open Saved Review",
            toolTip = "Open the most recent saved repository review without re-running analysis.",
            onClick = { sessionService.restoreStoredRepositoryReview() },
        )
        val reviewCurrentFileButton = createActionButton(
            text = "Review File",
            toolTip = "Prefill a review-oriented prompt for the current file.",
            onClick = {
                applyPromptPreset(
                    ReviewMode.REVIEW,
                    "Review the current file for bugs, regressions, missing tests, and comment-worthy issues.",
                )
            },
        )
        val explainCurrentSymbolButton = createActionButton(
            text = "Explain Symbol",
            toolTip = "Prefill an explanation prompt for the current editor context.",
            onClick = { applyPromptPreset(ReviewMode.UNDERSTAND, "Explain the current editor context and how the code works.") },
        )
        val writeReviewCommentButton = createActionButton(
            text = "Draft Comment",
            toolTip = "Prefill a prompt to draft a review comment for the current cursor or selection.",
            onClick = { applyPromptPreset(ReviewMode.COMMENT, "Draft a review comment for the current cursor or selection.") },
        )
        val runPromptButton = createActionButton(
            text = "Run Prompt",
            toolTip = "Run the prompt above with the selected mode and current editor context.",
            onClick = { submitCurrentPrompt() },
        )

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        repositoryShortcutStatusText = createHelperTextArea().apply {
            border = JBUI.Borders.empty(4, 0, 0, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        content.add(
            createActionGroup(
                title = "Repository",
                description = "Audit the whole repo or reopen the last stored audit.",
                buttons = listOf(repoReviewButton!!, openRepoReviewButton!!),
                footer = repositoryShortcutStatusText,
            ),
        )
        content.add(Box.createVerticalStrut(JBUI.scale(8)))
        content.add(
            createActionGroup(
                title = "Current Editor",
                description = "Prefill the prompt from the active file, symbol, or selection.",
                buttons = listOf(reviewCurrentFileButton, explainCurrentSymbolButton, writeReviewCommentButton),
            ),
        )
        content.add(Box.createVerticalStrut(JBUI.scale(8)))
        content.add(
            createActionGroup(
                title = "Run",
                description = "Send the prompt above with the selected mode and current context.",
                buttons = listOf(runPromptButton),
            ),
        )

        return createInputSection(
            title = "Shortcuts",
            description = "Reopen saved work or prefill the prompt from the current editor.",
            body = content,
        )
    }

    private fun createActionGroup(
        title: String,
        description: String,
        buttons: List<JButton>,
        footer: JComponent? = null,
    ): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT

            add(JBLabel(title).apply {
                font = JBUI.Fonts.smallFont().asBold()
                alignmentX = LEFT_ALIGNMENT
            })
            add(createHelperTextArea().apply {
                text = description
                border = JBUI.Borders.empty(2, 0, 4, 0)
                alignmentX = LEFT_ALIGNMENT
            })
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                buttons.forEach(::add)
            })
            footer?.let { add(it) }
        }
    }

    private fun createActionButton(
        text: String,
        toolTip: String,
        onClick: () -> Unit,
    ): JButton {
        return JButton(text).apply {
            toolTipText = toolTip
            addActionListener { onClick() }
        }
    }

    private fun createInputSection(
        title: String,
        description: String? = null,
        body: JComponent,
    ): JPanel {
        body.alignmentX = LEFT_ALIGNMENT
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8),
            )

            add(JBLabel(title).apply {
                font = JBUI.Fonts.label().asBold()
                alignmentX = LEFT_ALIGNMENT
            })
            description?.takeIf { it.isNotBlank() }?.let { helper ->
                add(createHelperTextArea().apply {
                    text = helper
                    border = JBUI.Borders.empty(2, 0, 6, 0)
                    alignmentX = LEFT_ALIGNMENT
                })
            }
            add(body)
        }
    }

    private fun refreshRepositoryShortcutStatus() {
        val providerService = project.service<LlmProviderService>()
        val repoReviewStatus = when {
            lastProviderStatus == null -> "Run Repo Review: checking provider."
            lastProviderStatus?.available != true -> {
                "Run Repo Review: unavailable - ${lastProviderStatus?.message.orEmpty()}."
            }
            providerService.supportsRepositoryReview() -> "Run Repo Review: ready."
            else -> "Run Repo Review: requires Claude CLI with MCP semantic navigation enabled."
        }
        val savedReviewStatus = if (sessionService.currentRepositoryReview != null) {
            "Open Saved Review: ready."
        } else {
            "Open Saved Review: no saved repository review yet."
        }
        repositoryShortcutStatusText?.text = "$repoReviewStatus\n$savedReviewStatus"
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
        modeButtons.forEach { (entryMode, button) ->
            button.isSelected = entryMode == mode
        }
        refreshModeButtonStyles()
        refreshModeDescription()
        if (updatePromptPlaceholder) {
            questionTextArea?.emptyText?.setText(mode.placeholder)
        }
    }

    private fun refreshModeButtonStyles() {
        val selectedBackground = JBColor(0xE8F0FE, 0x2F5D8A)
        val selectedForeground = JBColor(0x1A73E8, 0xFFFFFF)
        val selectedBorder = JBColor(0x1A73E8, 0x78A9FF)
        val defaultBackground = UIUtil.getPanelBackground()
        val defaultForeground = UIUtil.getLabelForeground()
        val defaultBorder = JBColor.border()

        modeButtons.forEach { (entryMode, button) ->
            val selected = entryMode == currentMode
            button.background = if (selected) selectedBackground else defaultBackground
            button.foreground = if (selected) selectedForeground else defaultForeground
            button.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(if (selected) selectedBorder else defaultBorder, 1, true),
                JBUI.Borders.empty(6, 12),
            )
            button.revalidate()
            button.repaint()
        }
    }

    private fun refreshModeDescription() {
        modeDescriptionText?.text = currentMode.helperText
        modeDescriptionText?.toolTipText = currentMode.tooltipText
    }

    private fun createHelperTextArea(): JBTextArea {
        return WrappingTextArea().apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
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
            mode = currentMode.toAnalysisMode(),
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
        refreshRepositoryShortcutStatus()
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
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
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

        val buttonPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        buttonPanel.add(JButton("Open").apply {
            addActionListener { sessionService.restoreRecentWalkthrough(item.id) }
        })
        val startLabel = if (item.followUpContext?.activeStepId != null) "Resume Tour" else "Start Tour"
        buttonPanel.add(JButton(startLabel).apply {
            addActionListener { sessionService.restoreRecentWalkthrough(item.id, startTour = true) }
        })

        row.add(textPanel)
        row.add(Box.createVerticalStrut(JBUI.scale(6)))
        row.add(buttonPanel)
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
                questionTextArea?.caretPosition = text.length
                questionTextArea?.requestFocusInWindow()
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
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        panel.add(progressLogScrollPane!!, BorderLayout.CENTER)

        // Cancel button
        val buttonPanel = JPanel(WrapLayout(FlowLayout.RIGHT, JBUI.scale(6), JBUI.scale(6))).apply {
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
        overviewScrollContent = ViewportWidthPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        // Metadata bar
        metadataBar = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            isVisible = false
        }
        metadataLabel = JBLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
        }
        metadataBar!!.add(metadataLabel!!)

        // Flow map content
        overviewContentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT

            summaryLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
                alignmentX = LEFT_ALIGNMENT
            }
            overviewInsightsLabel = JBLabel().apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.empty(0, 0, 8, 0)
                alignmentX = LEFT_ALIGNMENT
            }
            overviewGlobalNotesText = createOverviewTextArea().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                isVisible = false
                alignmentX = LEFT_ALIGNMENT
            }

            val topSection = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
            }
            topSection.add(metadataBar!!)
            topSection.add(summaryLabel!!)
            topSection.add(overviewInsightsLabel!!)
            topSection.add(createOverviewFilterRow())
            topSection.add(overviewGlobalNotesText!!)
            add(topSection)
            add(Box.createVerticalStrut(JBUI.scale(8)))

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
            add(
                JBScrollPane(stepList!!).apply {
                    alignmentX = LEFT_ALIGNMENT
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    preferredSize = java.awt.Dimension(0, JBUI.scale(220))
                    minimumSize = java.awt.Dimension(0, JBUI.scale(120))
                    maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(320))
                },
            )

            val bottomSection = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
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
            overviewSelectionMeta = createHelperTextArea().apply {
                border = JBUI.Borders.empty(2, 0, 6, 0)
                alignmentX = LEFT_ALIGNMENT
            }
            overviewSelectionBody = WrappingTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = JBUI.Fonts.smallFont()
                background = UIUtil.getPanelBackground()
                border = JBUI.Borders.empty()
                alignmentX = LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            }
            createPotentialBugWarningPanel("Potential bugs detected").also { (panel, textArea) ->
                overviewStepWarningPanel = panel
                overviewStepWarningText = textArea
            }
            selectedStepPanel.add(overviewSelectionTitle!!)
            selectedStepPanel.add(overviewSelectionMeta!!)
            selectedStepPanel.add(overviewStepWarningPanel!!)
            selectedStepPanel.add(overviewSelectionBody!!)
            overviewTabs = createOverviewTabsPanel()
            overviewTabs!!.alignmentX = LEFT_ALIGNMENT
            selectedStepPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
            selectedStepPanel.add(overviewTabs!!)
            bottomSection.add(Box.createVerticalStrut(JBUI.scale(8)))
            bottomSection.add(selectedStepPanel)

            val startTourPanel = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply {
                border = JBUI.Borders.empty(8, 0, 0, 0)
                alignmentX = LEFT_ALIGNMENT
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

            val followUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                alignmentX = LEFT_ALIGNMENT
            }
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

            add(bottomSection)
        }

        // Clarification content
        clarificationPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            clarificationLabel = JBLabel().apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                verticalAlignment = SwingConstants.TOP
                alignmentX = LEFT_ALIGNMENT
            }
            add(clarificationLabel!!)

            val answerPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                alignmentX = LEFT_ALIGNMENT
            }
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
            add(answerPanel)
        }

        overviewScrollContent!!.add(overviewContentPanel!!)
        panel.add(
            JBScrollPane(overviewScrollContent!!).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
        overviewPanel = panel
        return panel
    }

    private fun refreshOverviewCard() {
        val panel = overviewPanel ?: return
        val scrollContent = overviewScrollContent ?: return
        val flowMap = sessionService.currentFlowMap
        val clarification = sessionService.clarificationQuestion

        if (clarification != null) {
            clarificationLabel!!.text = "<html><b>Clarification needed:</b><br>${escapeHtml(clarification)}</html>"
            scrollContent.removeAll()
            scrollContent.add(clarificationPanel!!)
        } else if (flowMap != null) {
            summaryLabel!!.text = "<html>${escapeHtml(flowMap.summary)}</html>"

            updateMetadataBar(sessionService.lastMetadata)
            updateOverviewInsights(flowMap.steps)
            updateOverviewGlobalNotes(flowMap)
            rebuildOverviewStepList()

            scrollContent.removeAll()
            scrollContent.add(overviewContentPanel!!)
        }

        scrollContent.revalidate()
        scrollContent.repaint()
        panel.revalidate()
        panel.repaint()
    }

    // ── Repository Review Card ─────────────────────────────────────────

    private fun createRepositoryReviewCard(): JPanel = repositoryReviewCard.panel

    private fun refreshRepositoryReviewCard() {
        repositoryReviewCard.refresh(sessionService.currentRepositoryReview, sessionService.repositoryReviewStale)
        sessionService.refreshRepositoryReviewStaleStatus()
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
        val bugCount = steps.sumOf { it.potentialBugs.size }
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
        if (bugCount > 0) {
            parts.add("$bugCount bug warnings")
        }
        overviewInsightsLabel?.text = parts.joinToString("  ·  ")
    }

    private fun createOverviewFilterRow(): JPanel {
        val row = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
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
                    step.potentialBugs.isNotEmpty() ||
                    step.evidence.isNotEmpty() ||
                    !step.testGap.isNullOrBlank() ||
                    !step.suggestedAction.isNullOrBlank()
                StepFilter.UNCERTAIN -> step.uncertain
                StepFilter.BROKEN -> step.broken
                StepFilter.TEST_GAPS -> !step.testGap.isNullOrBlank() ||
                    step.potentialBugs.any { !it.testGap.isNullOrBlank() }
            }
        }
    }

    private fun updateOverviewFilterLabels(flowMap: FlowMap) {
        val counts = mapOf(
            StepFilter.ALL to flowMap.steps.size,
            StepFilter.FINDINGS to flowMap.steps.count { step ->
                step.broken ||
                    step.severity?.let { it.equals("high", true) || it.equals("medium", true) } == true ||
                    step.potentialBugs.isNotEmpty() ||
                    step.evidence.isNotEmpty() ||
                    !step.testGap.isNullOrBlank() ||
                    !step.suggestedAction.isNullOrBlank()
            },
            StepFilter.UNCERTAIN to flowMap.steps.count { it.uncertain },
            StepFilter.BROKEN to flowMap.steps.count { it.broken },
            StepFilter.TEST_GAPS to flowMap.steps.count { step ->
                !step.testGap.isNullOrBlank() || step.potentialBugs.any { !it.testGap.isNullOrBlank() }
            },
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
            val potentialBugs = flowMap.steps.flatMap { step ->
                step.potentialBugs.map { finding -> step to finding }
            }
            if (potentialBugs.isNotEmpty()) {
                val summaryLine = "${potentialBugs.size} grounded warning" +
                    if (potentialBugs.size == 1) "" else "s" +
                    " across ${potentialBugs.map { it.first.id }.distinct().size} steps."
                val highlights = potentialBugs
                    .sortedWith(compareBy<Pair<FlowStep, RepositoryFinding>> { severityRank(it.second.severity) }.thenBy { it.second.title })
                    .take(5)
                    .joinToString("\n") { (step, finding) ->
                        "- [${finding.severity}] ${finding.title} (${step.title})"
                    }
                add("Auto bug discovery:\n$summaryLine\n$highlights")
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
            updatePotentialBugWarning(overviewStepWarningPanel, overviewStepWarningText, emptyList())
            selectedStepSnapshot = null
            updateOverviewDetailTabs(null)
            updateOverviewSelectionActions(null)
            return
        }

        selectedStepSnapshot = step
        val titleFlags = buildList {
            if (step.broken) add("needs repair")
            else if (step.uncertain) add("uncertain")
            if (step.potentialBugs.isNotEmpty()) {
                add("${step.potentialBugs.size} bug warning${if (step.potentialBugs.size == 1) "" else "s"}")
            }
        }
        overviewSelectionTitle?.text = if (titleFlags.isEmpty()) {
            step.title
        } else {
            "${step.title} (${titleFlags.joinToString(", ")})"
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
        if (step.potentialBugs.isNotEmpty()) {
            metaParts.add("bug warnings: ${step.potentialBugs.size}")
        }
        if (sessionService.isEntryStep(step.id)) {
            metaParts.add("entrypoint")
        }
        if (sessionService.isTerminalStep(step.id)) {
            metaParts.add("terminal")
        }
        overviewSelectionMeta?.text = metaParts.joinToString("  ·  ")
        updatePotentialBugWarning(overviewStepWarningPanel, overviewStepWarningText, step.potentialBugs)

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
        tabs.addTab("Explain", JBScrollPane(overviewExplainText!!).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
        tabs.addTab("Evidence", JBScrollPane(overviewEvidenceText!!).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
        tabs.addTab("Risk", JBScrollPane(overviewRiskText!!).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
        commentTabIndex = tabs.tabCount
        tabs.addTab("Comment", createCommentComposerPanel())
        tabs.addTab("Tests", JBScrollPane(overviewTestsText!!).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })

        commentStyleCombo?.addActionListener {
            selectedStepSnapshot?.let { draftCommentForSelection(it, forceRefresh = true) }
        }
        return tabs
    }

    private fun createOverviewTextArea(editable: Boolean = false): JTextArea {
        return WrappingTextArea().apply {
            isEditable = editable
            lineWrap = true
            wrapStyleWord = true
            font = if (editable) JBUI.Fonts.label() else JBUI.Fonts.smallFont()
            background = if (editable) UIUtil.getPanelBackground() else JBUI.CurrentTheme.ToolWindow.background()
            border = JBUI.Borders.empty()
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun createPotentialBugWarningPanel(title: String): Pair<JPanel, JTextArea> {
        val background = JBColor(0xFFF3F3, 0x402727)
        val borderColor = JBColor(0xE2B2B2, 0x7A4242)
        val foreground = JBColor.namedColor(
            "Label.errorForeground",
            JBColor(0x9F1B1B, 0xFF8A80),
        )
        val textArea = WrappingTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
            this.background = background
            this.foreground = foreground
            border = JBUI.Borders.empty()
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        val header = JBLabel(title, AllIcons.General.WarningDialog, SwingConstants.LEFT).apply {
            font = JBUI.Fonts.label().asBold()
            this.foreground = foreground
            border = JBUI.Borders.empty(0, 0, 4, 0)
        }
        val panel = JPanel(BorderLayout()).apply {
            isVisible = false
            alignmentX = LEFT_ALIGNMENT
            this.background = background
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor),
                JBUI.Borders.empty(8),
            )
            add(header, BorderLayout.NORTH)
            add(textArea, BorderLayout.CENTER)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        return panel to textArea
    }

    private fun updatePotentialBugWarning(panel: JPanel?, textArea: JTextArea?, findings: List<RepositoryFinding>) {
        val visible = findings.isNotEmpty()
        panel?.isVisible = visible
        textArea?.text = if (visible) buildPotentialBugSummaryText(findings) else ""
        panel?.revalidate()
        panel?.repaint()
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

        val controls = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply {
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
            add(JBScrollPane(overviewCommentText!!).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
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
        } else if (step.potentialBugs.isNotEmpty()) {
            "Potential bugs were detected in this step; keep the comment specific and evidence-backed."
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
        if (step.potentialBugs.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("Potential bugs:")
            appendLine(buildPotentialBugSummaryText(step.potentialBugs))
        }
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
        if (step.potentialBugs.isNotEmpty()) {
            appendLine()
            appendLine("Potential bug evidence:")
            appendLine(buildPotentialBugEvidenceText(step.potentialBugs))
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
        if (step.potentialBugs.isNotEmpty()) {
            appendLine()
            appendLine("Potential bugs:")
            appendLine(buildPotentialBugSummaryText(step.potentialBugs))
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
        val findingTestGaps = step.potentialBugs.mapNotNull { finding ->
            finding.testGap?.takeIf { it.isNotBlank() }?.let { finding to it }
        }
        if (step.testGap.isNullOrBlank() && matchingSuggestedTests.isEmpty() && findingTestGaps.isEmpty()) {
            appendLine("No explicit test guidance was returned for this step.")
            appendLine("Use this step for targeted regression coverage if you change it.")
            return@buildString
        }
        step.testGap?.takeIf { it.isNotBlank() }?.let {
            appendLine("Step-specific test gap:")
            appendLine(it)
        }
        if (findingTestGaps.isNotEmpty()) {
            if (isNotEmpty()) {
                appendLine()
            }
            appendLine("Potential bug test gaps:")
            findingTestGaps.forEach { (finding, gap) ->
                appendLine("- ${finding.title}: $gap")
            }
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
        val topBug = step.potentialBugs.minByOrNull { severityRank(it.severity) }
        val subject = step.symbol?.takeIf { it.isNotBlank() } ?: step.title
        if (topBug != null) {
            when (selectedStyle) {
                CommentStyle.QUESTION -> append("Can you clarify whether `${topBug.title}` is intentional in `")
                CommentStyle.CONCERN -> append("I think `")
                CommentStyle.SUGGESTION -> append("Consider tightening `")
                CommentStyle.APPROVAL -> append("Looks good overall, but I would still double-check `")
            }
            append(subject)
            append("`")
            when (selectedStyle) {
                CommentStyle.QUESTION -> append("?")
                CommentStyle.CONCERN -> append(" may be exposing `${topBug.title}`.")
                CommentStyle.SUGGESTION -> append("` around `${topBug.title}`.")
                CommentStyle.APPROVAL -> append("` around `${topBug.title}`.")
            }
            appendLine()
            appendLine()
            appendLine(topBug.summary)
            topBug.suggestedAction?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append("Suggested next step: ")
                append(it)
            }
            val primaryEvidence = topBug.evidence.firstOrNull()
            if (primaryEvidence != null) {
                appendLine()
                appendLine()
                append("Evidence: ")
                append(primaryEvidence.label)
                val location = buildList {
                    primaryEvidence.filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    primaryEvidence.startLine?.let { start ->
                        add(
                            if (primaryEvidence.endLine != null && primaryEvidence.endLine != start) {
                                "L$start-L${primaryEvidence.endLine}"
                            } else {
                                "L$start"
                            },
                        )
                    }
                }.joinToString(":")
                if (location.isNotBlank()) {
                    append(" (")
                    append(location)
                    append(")")
                }
                primaryEvidence.text?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }
            return@buildString
        }
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

    private fun buildPotentialBugSummaryText(findings: List<RepositoryFinding>): String = buildString {
        sortPotentialBugs(findings).forEachIndexed { index, finding ->
            if (index > 0) appendLine()
            appendLine("[${finding.severity}] ${finding.title}")
            appendLine(finding.summary)
            finding.riskType?.takeIf { it.isNotBlank() }?.let { appendLine("Risk type: $it") }
            finding.suggestedAction?.takeIf { it.isNotBlank() }?.let { appendLine("Suggested action: $it") }
            finding.testGap?.takeIf { it.isNotBlank() }?.let { appendLine("Test gap: $it") }
            val primaryEvidence = finding.evidence.firstOrNull()
            if (primaryEvidence != null) {
                val location = buildList {
                    primaryEvidence.filePath?.takeIf { it.isNotBlank() }?.let { add(it) }
                    primaryEvidence.startLine?.let { start ->
                        add(
                            if (primaryEvidence.endLine != null && primaryEvidence.endLine != start) {
                                "L$start-L${primaryEvidence.endLine}"
                            } else {
                                "L$start"
                            },
                        )
                    }
                }.joinToString(":")
                append("Evidence: ${primaryEvidence.label}")
                if (location.isNotBlank()) {
                    append(" ($location)")
                }
                primaryEvidence.text?.takeIf { it.isNotBlank() }?.let {
                    append(": $it")
                }
                appendLine()
            }
            if (finding.uncertain) {
                appendLine("Signal: inferred, review carefully before acting on it.")
            }
        }
    }.trim()

    private fun buildPotentialBugEvidenceText(findings: List<RepositoryFinding>): String = buildString {
        sortPotentialBugs(findings).forEachIndexed { index, finding ->
            if (index > 0) appendLine()
            appendLine("- [${finding.severity}] ${finding.title}")
            if (finding.evidence.isEmpty()) {
                appendLine("  No additional evidence was returned.")
            } else {
                finding.evidence.forEach { evidence ->
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
                    appendLine("  - ${evidence.label}${if (details.isNotBlank()) " ($details)" else ""}")
                    evidence.text?.takeIf { it.isNotBlank() }?.let { appendLine("    $it") }
                }
            }
        }
    }.trim()

    private fun sortPotentialBugs(findings: List<RepositoryFinding>): List<RepositoryFinding> =
        findings.sortedWith(compareBy<RepositoryFinding> { severityRank(it.severity) }.thenBy { it.title.lowercase() })

    private fun severityRank(severity: String?): Int = when (severity?.lowercase()) {
        "critical" -> 0
        "high" -> 1
        "medium" -> 2
        "low" -> 3
        "info" -> 4
        else -> 5
    }

    private fun createOverviewListActions(): JPanel {
        return JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
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
        return JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply {
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
                question = buildPromptWithContext(text, currentMode, selectedStepContext()),
                mode = currentMode.toAnalysisMode(),
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
        val contentPanel = ViewportWidthPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            alignmentX = LEFT_ALIGNMENT
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

        tourStepFilePath = createHelperTextArea().apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }
        contentPanel.add(tourStepFilePath!!)

        tourStepExplanation = WrappingTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.label()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        contentPanel.add(tourStepExplanation!!)
        createPotentialBugWarningPanel("Potential bugs detected").also { (panel, textArea) ->
            tourStepWarningPanel = panel
            tourStepWarningText = textArea
        }
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        contentPanel.add(tourStepWarningPanel!!)

        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))

        val whyToggle = JBLabel("Why this step?").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = LEFT_ALIGNMENT
        }
        contentPanel.add(whyToggle)

        tourWhyText = WrappingTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
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

        val contextualFollowUpPanel = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
            alignmentX = LEFT_ALIGNMENT
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
        contentPanel.add(contextualFollowUpPanel)

        val stepAnswerPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                JBUI.Borders.empty(8),
            )
            alignmentX = LEFT_ALIGNMENT
        }
        tourStepAnswerStatus = JBLabel("Ask a targeted question about this step without leaving the tour.").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getLabelDisabledForeground()
            border = JBUI.Borders.empty(0, 0, 6, 0)
        }
        createPotentialBugWarningPanel("Potential bugs in this answer").also { (panel, textArea) ->
            tourStepAnswerWarningPanel = panel
            tourStepAnswerWarningText = textArea
        }
        val stepAnswerHeader = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(tourStepAnswerStatus!!)
            add(tourStepAnswerWarningPanel!!)
        }
        stepAnswerPanel.add(stepAnswerHeader, BorderLayout.NORTH)
        tourStepAnswerText = createOverviewTextArea().apply {
            text = "The answer will stay scoped to the current symbol and its important lines."
        }
        tourStepAnswerEvidenceText = createOverviewTextArea().apply {
            text = "Evidence and important lines will appear here when available."
        }
        val answerTabs = JTabbedPane().apply {
            addTab("Answer", JBScrollPane(tourStepAnswerText!!).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            })
            addTab("Evidence", JBScrollPane(tourStepAnswerEvidenceText!!).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            })
        }
        val answerBody = JPanel(BorderLayout()).apply {
            add(answerTabs, BorderLayout.CENTER)
        }
        stepAnswerPanel.add(answerBody, BorderLayout.CENTER)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(8)))
        contentPanel.add(stepAnswerPanel)

        val navPanel = JPanel(WrapLayout(FlowLayout.CENTER, JBUI.scale(8), JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)
            alignmentX = LEFT_ALIGNMENT
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
        contentPanel.add(navPanel)

        panel.add(
            JBScrollPane(contentPanel).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )

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
            if (step.potentialBugs.isNotEmpty()) {
                add("bug warnings: ${step.potentialBugs.size}")
            }
            if (sessionService.isEntryStep(step.id)) add("entrypoint")
            if (sessionService.isTerminalStep(step.id)) add("terminal")
        }.joinToString("  ·  ")
        tourStepExplanation?.text = step.explanation
        updatePotentialBugWarning(tourStepWarningPanel, tourStepWarningText, step.potentialBugs)
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
                updatePotentialBugWarning(tourStepAnswerWarningPanel, tourStepAnswerWarningText, emptyList())
                tourStepAnswerText?.text = "Working through the current step without remapping the whole tour."
                tourStepAnswerEvidenceText?.text = "Evidence will appear here once the answer is ready."
            }
            !errorMessage.isNullOrBlank() -> {
                tourStepAnswerStatus?.text = errorMessage
                updatePotentialBugWarning(tourStepAnswerWarningPanel, tourStepAnswerWarningText, emptyList())
                tourStepAnswerText?.text = "The step answer failed. Refine the question or try again."
                tourStepAnswerEvidenceText?.text = ""
            }
            answer != null -> {
                tourStepAnswerStatus?.text = buildStepAnswerStatus(answer)
                updatePotentialBugWarning(tourStepAnswerWarningPanel, tourStepAnswerWarningText, answer.potentialBugs)
                tourStepAnswerText?.text = buildStepAnswerText(answer)
                tourStepAnswerEvidenceText?.text = buildStepAnswerEvidenceText(answer)
            }
            else -> {
                tourStepAnswerStatus?.text = "Ask a targeted question about this step without leaving the tour."
                updatePotentialBugWarning(tourStepAnswerWarningPanel, tourStepAnswerWarningText, emptyList())
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
        if (answer.potentialBugs.isNotEmpty()) {
            parts.add("${answer.potentialBugs.size} bug warning${if (answer.potentialBugs.size == 1) "" else "s"}")
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
        if (answer.potentialBugs.isNotEmpty()) {
            appendLine()
            appendLine("Potential bugs:")
            appendLine(buildPotentialBugSummaryText(answer.potentialBugs))
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
        if (answer.evidence.isEmpty() && answer.importantLines.isEmpty() && answer.potentialBugs.isEmpty()) {
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
            if (answer.potentialBugs.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Potential bug evidence:")
                appendLine(buildPotentialBugEvidenceText(answer.potentialBugs))
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
                lastProviderStatus = status
                repoReviewButton?.isEnabled = status.available && providerService.supportsRepositoryReview()
                refreshRepositoryShortcutStatus()
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

    private fun resetProgressLog() {
        progressLogLines.clear()
        progressLog?.text = ""
    }

    private fun registerSessionListener() {
        if (listenerRegistered) return
        sessionService.addListener(this)
        listenerRegistered = true
    }

    private fun unregisterSessionListener() {
        if (!listenerRegistered) return
        sessionService.removeListener(this)
        listenerRegistered = false
    }

    private fun syncModeWithSession() {
        val sessionMode = ReviewMode.fromAnalysisMode(sessionService.currentMode)
        if (sessionMode != currentMode) {
            selectMode(sessionMode, updatePromptPlaceholder = true)
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
                if (value.potentialBugs.isNotEmpty()) {
                    title.append(
                        "  ${value.potentialBugs.size} bug warning${if (value.potentialBugs.size == 1) "" else "s"}",
                        SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor.namedColor("Label.errorForeground", JBColor(0x9F1B1B, 0xFF8A80)),
                        ),
                    )
                }
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
        private const val MAX_PROGRESS_LOG_LINES = 250
        private const val CARD_INPUT = "INPUT"
        private const val CARD_LOADING = "LOADING"
        private const val CARD_OVERVIEW = "OVERVIEW"
        private const val CARD_REPO_REVIEW = "REPO_REVIEW"
        private const val CARD_TOUR_ACTIVE = "TOUR_ACTIVE"
    }
}
