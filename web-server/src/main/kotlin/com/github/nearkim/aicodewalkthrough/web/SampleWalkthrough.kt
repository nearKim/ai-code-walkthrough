package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.ArchitectureComponent
import com.github.nearkim.aicodewalkthrough.model.ArchitectureResponsibility
import com.github.nearkim.aicodewalkthrough.model.CodebaseArchitecture
import com.github.nearkim.aicodewalkthrough.model.ComponentRelationship
import com.github.nearkim.aicodewalkthrough.model.EvidenceItem
import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.model.LearningStage
import com.github.nearkim.aicodewalkthrough.model.LineAnnotation

internal object SampleWalkthrough {
    const val sourcePath = ".walkthrough-sample.ts"
    const val question = "Sample result — illustrative only"

    val source = """
        /**
         * This source exists only to demonstrate the walkthrough UI.
         * It is not part of the repository and no repository analysis produced it.
         */

        type SampleRequest = { focus: string };

        export class WalkthroughSample {
          mapSystem(request: SampleRequest) {
            const components = ['entrypoint', 'behavior', 'evidence'];
            return { request, components };
          }

          traceBehavior(map: ReturnType<WalkthroughSample['mapSystem']>) {
            const branch = map.request.focus ? 'focused' : 'default';
            return { branch, state: 'mapped' };
          }

          verifyEvidence(trace: ReturnType<WalkthroughSample['traceBehavior']>) {
            return trace.branch === 'focused' ? 'exact source evidence' : 'coverage note';
          }
        }
    """.trimIndent().trim() + "\n"

    fun flowMap(): FlowMap {
        val sampleClass = evidence(
            kind = "class",
            label = "WalkthroughSample",
            startLine = 8,
            endLine = 22,
            text = "Illustrates a class whose methods are grouped with their responsibilities.",
        )
        val systemEvidence = evidence(
            kind = "method",
            label = "WalkthroughSample.mapSystem",
            startLine = 9,
            endLine = 12,
            text = "Illustrates how a mapped entry point is shown with a source anchor.",
        )
        val behaviorEvidence = evidence(
            kind = "method",
            label = "WalkthroughSample.traceBehavior",
            startLine = 14,
            endLine = 17,
            text = "Illustrates a branch and state transition in a behavior path.",
        )
        val verificationEvidence = evidence(
            kind = "method",
            label = "WalkthroughSample.verifyEvidence",
            startLine = 19,
            endLine = 21,
            text = "Illustrates source-backed evidence and an explicit coverage note.",
        )
        return FlowMap(
            mode = "understand",
            summary = "An instant illustrative result showing the architecture map, staged route, and source preview. No repository analysis was run.",
            architecture = CodebaseArchitecture(
                systemPurpose = "Demonstrate how an analyzed repository appears in the walkthrough UI.",
                components = listOf(
                    component(
                        id = "sample-system",
                        name = "System map",
                        kind = "entrypoint",
                        responsibility = "Shows the entry point of a staged walkthrough.",
                        title = "Orient the reader",
                        description = "Starts with a concise map before following implementation details.",
                        evidence = listOf(sampleClass, systemEvidence),
                    ),
                    component(
                        id = "sample-behavior",
                        name = "Behavior path",
                        kind = "application",
                        responsibility = "Shows branches, state changes, and side effects on a representative path.",
                        title = "Trace one behavior",
                        description = "Shows the concrete branch and state transition a real review would explain.",
                        evidence = listOf(sampleClass, behaviorEvidence),
                    ),
                    component(
                        id = "sample-evidence",
                        name = "Evidence check",
                        kind = "shared",
                        responsibility = "Shows how source evidence and coverage limits accompany conclusions.",
                        title = "Verify the conclusion",
                        description = "Keeps tool output and source evidence separate from unverified inference.",
                        evidence = listOf(sampleClass, verificationEvidence),
                    ),
                ),
                relationships = listOf(
                    ComponentRelationship(
                        id = "sample-system-behavior",
                        fromComponentId = "sample-system",
                        toComponentId = "sample-behavior",
                        kind = "calls",
                        description = "Illustrates moving from system orientation to a behavior path.",
                        evidence = listOf(systemEvidence),
                    ),
                    ComponentRelationship(
                        id = "sample-behavior-evidence",
                        fromComponentId = "sample-behavior",
                        toComponentId = "sample-evidence",
                        kind = "depends_on",
                        description = "Illustrates checking behavior claims against source evidence.",
                        evidence = listOf(verificationEvidence),
                    ),
                ),
                crossCuttingConcerns = listOf(
                    "Real walkthroughs retain exact source locations for every substantive claim.",
                ),
                coverageNotes = listOf(
                    "No provider, subagent, AST, semantic, or symbolic-execution tool ran for this sample.",
                    "The displayed source is virtual and exists only to demonstrate the renderer.",
                ),
            ),
            learningPath = listOf(
                LearningStage(
                    id = "sample-orientation",
                    title = "System orientation",
                    goal = "See how the architecture map introduces a real walkthrough.",
                    componentIds = listOf("sample-system"),
                    stepIds = listOf("sample-map"),
                    checkpoint = "You can identify where the rendered route begins.",
                ),
                LearningStage(
                    id = "sample-behavior-stage",
                    title = "Behavior path",
                    goal = "See how a representative branch and state transition are presented.",
                    componentIds = listOf("sample-behavior"),
                    stepIds = listOf("sample-behavior"),
                    checkpoint = "You can distinguish a branch from its resulting state.",
                ),
                LearningStage(
                    id = "sample-evidence-stage",
                    title = "Evidence and limits",
                    goal = "See how source evidence and analysis boundaries remain visible.",
                    componentIds = listOf("sample-evidence"),
                    stepIds = listOf("sample-evidence"),
                    checkpoint = "You can tell a verified source claim from a coverage limit.",
                ),
            ),
            entryStepId = "sample-map",
            terminalStepIds = listOf("sample-evidence"),
            steps = listOf(
                FlowStep(
                    id = "sample-map",
                    title = "Map the system",
                    filePath = sourcePath,
                    symbol = "WalkthroughSample.mapSystem",
                    startLine = 9,
                    endLine = 12,
                    explanation = "A walkthrough begins with a concise system map and a source anchor.",
                    detailedExplanation = "This illustrative stop shows the same code preview and line annotations used by a grounded result. It does not describe this repository.",
                    whyIncluded = "It demonstrates the first overview-to-code transition.",
                    stepType = "entrypoint",
                    importance = "high",
                    lineAnnotations = listOf(LineAnnotation(10, 10, "Illustrative component inventory.")),
                    evidence = listOf(systemEvidence),
                ),
                FlowStep(
                    id = "sample-behavior",
                    title = "Trace a behavior",
                    filePath = sourcePath,
                    symbol = "WalkthroughSample.traceBehavior",
                    startLine = 14,
                    endLine = 17,
                    explanation = "Representative paths call out branches, state changes, and their consequences.",
                    detailedExplanation = "A real behavior search would use repository evidence, delegated exploration when available, and any verified path-sensitive results. This stop only demonstrates the presentation.",
                    whyIncluded = "It demonstrates a branch-focused code stop.",
                    stepType = "branch",
                    importance = "high",
                    lineAnnotations = listOf(LineAnnotation(15, 15, "Illustrative branch predicate.")),
                    evidence = listOf(behaviorEvidence),
                ),
                FlowStep(
                    id = "sample-evidence",
                    title = "Check the evidence",
                    filePath = sourcePath,
                    symbol = "WalkthroughSample.verifyEvidence",
                    startLine = 19,
                    endLine = 21,
                    explanation = "Grounded results pair source locations with explicit limits on what was checked.",
                    detailedExplanation = "The system notes tab makes coverage limits visible beside the architecture and learning path. This sample has no analysis provenance because no analysis ran.",
                    whyIncluded = "It demonstrates how evidence and uncertainty stay visible in the result.",
                    stepType = "sink",
                    importance = "medium",
                    lineAnnotations = listOf(LineAnnotation(20, 20, "Illustrative evidence outcome.")),
                    evidence = listOf(verificationEvidence),
                ),
            ),
        )
    }

    private fun component(
        id: String,
        name: String,
        kind: String,
        responsibility: String,
        title: String,
        description: String,
        evidence: List<EvidenceItem>,
    ) = ArchitectureComponent(
        id = id,
        name = name,
        kind = kind,
        responsibility = responsibility,
        responsibilities = listOf(
            ArchitectureResponsibility(
                id = "$id-responsibility",
                title = title,
                description = description,
                evidence = evidence,
            ),
        ),
        keyPaths = listOf(sourcePath),
        keySymbols = evidence.map { it.label },
        evidence = evidence,
    )

    private fun evidence(kind: String, label: String, startLine: Int, endLine: Int, text: String) = EvidenceItem(
        kind = kind,
        label = label,
        filePath = sourcePath,
        startLine = startLine,
        endLine = endLine,
        text = text,
    )
}
