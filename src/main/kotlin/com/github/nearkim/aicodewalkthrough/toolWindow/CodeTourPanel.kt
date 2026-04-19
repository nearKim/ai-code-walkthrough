package com.github.nearkim.aicodewalkthrough.toolwindow

import com.github.nearkim.aicodewalkthrough.editor.EditorDecorationController
import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.model.AnalysisMode
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.RecentWalkthrough
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import com.github.nearkim.aicodewalkthrough.model.TourState
import com.github.nearkim.aicodewalkthrough.service.TourSessionService
import com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings
import com.github.nearkim.aicodewalkthrough.toolwindow.cards.InputCard
import com.github.nearkim.aicodewalkthrough.toolwindow.cards.LoadingCard
import com.github.nearkim.aicodewalkthrough.toolwindow.cards.OverviewCard
import com.github.nearkim.aicodewalkthrough.toolwindow.cards.TourActiveCard
import com.github.nearkim.aicodewalkthrough.util.FlowMapMarkdownExporter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JPanel

class CodeTourPanel(
    private val project: Project,
    @Suppress("unused") private val scope: CoroutineScope,
) : JPanel(BorderLayout()), TourSessionService.TourSessionListener {

    private val session = project.service<TourSessionService>()
    private val settings get() = project.service<CodeTourSettings>()

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)

    private val input = InputCard(project, onSubmit = ::handleSubmit)
    private val loading = LoadingCard()
    private val overview = OverviewCard(
        onStartTour = { session.startTour() },
        onPreviewStep = { session.previewStep(it) },
        onCopyMarkdown = ::copyMarkdown,
    )
    private val tour = TourActiveCard(
        onPrev = { session.prevStep() },
        onNext = { session.nextStep() },
        onStop = { session.stopTour() },
        onGoToCode = ::goToCurrentStep,
        onAskFollowUp = { session.askAboutCurrentStep(it) },
    )

    init {
        cards.add(input, CARD_INPUT)
        cards.add(loading, CARD_LOADING)
        cards.add(overview, CARD_OVERVIEW)
        cards.add(tour, CARD_TOUR)
        add(cards, BorderLayout.CENTER)

        session.addListener(this)
        showCard(session.state)
    }

    override fun onStateChanged(state: TourState) {
        ApplicationManager.getApplication().invokeLater { showCard(state) }
    }

    override fun onStepChanged(stepIndex: Int, step: FlowStep) {
        ApplicationManager.getApplication().invokeLater {
            val total = session.currentFlowMap?.steps?.size ?: 0
            tour.setStep(stepIndex, total, step)
        }
    }

    override fun onStepAnswerChanged(answer: StepAnswer?, loading: Boolean, errorMessage: String?) {
        ApplicationManager.getApplication().invokeLater {
            tour.setAnswer(answer, loading, errorMessage)
        }
    }

    override fun onProgressLines(lines: List<String>) {
        val last = lines.lastOrNull() ?: return
        ApplicationManager.getApplication().invokeLater {
            loading.setStatus(last)
        }
    }

    override fun onRecentWalkthroughsChanged(items: List<RecentWalkthrough>) {
        // UI no longer surfaces recent walkthroughs.
    }

    private fun showCard(state: TourState) {
        when (state) {
            TourState.INPUT -> {
                loading.stopLoading()
                session.errorMessage?.let { input.showError(it) }
                cardLayout.show(cards, CARD_INPUT)
            }
            TourState.LOADING -> {
                loading.startLoading()
                loading.setStatus("Mapping walkthrough...")
                cardLayout.show(cards, CARD_LOADING)
            }
            TourState.OVERVIEW -> {
                loading.stopLoading()
                overview.setFlowMap(session.currentFlowMap, settings.state.provider.displayName)
                cardLayout.show(cards, CARD_OVERVIEW)
            }
            TourState.TOUR_ACTIVE -> {
                loading.stopLoading()
                val flow = session.currentFlowMap
                val index = session.currentStepIndex
                val step = flow?.steps?.getOrNull(index)
                if (flow != null && step != null) {
                    tour.setStep(index, flow.steps.size, step)
                    tour.setAnswer(session.currentStepAnswer, session.stepAnswerLoading, session.stepAnswerError)
                }
                cardLayout.show(cards, CARD_TOUR)
            }
        }
    }

    private fun handleSubmit(prompt: String, mode: AnalysisMode, provider: AiProvider) {
        settings.loadState(settings.state.copy(providerId = provider.id))
        session.startMapping(prompt, mode)
    }

    private fun copyMarkdown() {
        val flow = session.currentFlowMap ?: return
        val markdown = FlowMapMarkdownExporter.build(
            question = session.currentQuestion,
            flowMap = flow,
            metadata = session.lastMetadata,
            activeStepId = flow.steps.getOrNull(session.currentStepIndex)?.id,
        )
        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
    }

    private fun goToCurrentStep() {
        val flow = session.currentFlowMap ?: return
        val step = flow.steps.getOrNull(session.currentStepIndex) ?: return
        val basePath = project.basePath ?: return
        val vf = LocalFileSystem.getInstance()
            .findFileByNioFile(Path.of(basePath).resolve(step.filePath)) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        val line = (step.startLine - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        editor.caretModel.moveToOffset(document.getLineStartOffset(line))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        project.service<EditorDecorationController>().showStep(
            step = step,
            stepIndex = session.currentStepIndex,
            totalSteps = flow.steps.size,
            nextStep = null,
            nextEdge = null,
        )
    }

    companion object {
        private const val CARD_INPUT = "INPUT"
        private const val CARD_LOADING = "LOADING"
        private const val CARD_OVERVIEW = "OVERVIEW"
        private const val CARD_TOUR = "TOUR"
    }
}
