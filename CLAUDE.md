# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the plugin
./gradlew build

# Run in a sandboxed IDE instance (primary development workflow)
./gradlew runIde

# Run tests
./gradlew test

# Verify plugin compatibility
./gradlew verifyPlugin

# Check (compile + test + verify)
./gradlew check
```

There are also pre-configured run configurations in `.run/` for the IDE.

## Architecture

This is an **IntelliJ Platform Plugin** (targeting IDEA 2025.2+) that teaches an unfamiliar repository from architecture to implementation. It builds a grounded component map and staged learning path, then steps the user through validated execution-path stops in the editor.

The core principle is:

- The model proposes the walkthrough path.
- The plugin validates that path before rendering.
- The IDE only highlights validated step ranges and validated next-hop previews.

### Data flow

```
User question (CodeTourPanel)
  → TourSessionService.startMapping()
    → FlowPlannerService.mapFlow()
      → LlmProviderService.requireRepoGroundedWalkthroughSupport()
      → currentProvider().query()                     # grounded walkthroughs require CLI providers
      → parse JSON into LlmResponse / FlowMap
      → StepValidator.validate(flowMap)              # validates architecture, curriculum, steps, evidence, and edges
    → TourSessionService.handleMappingResult()
      → transitions to OVERVIEW
  → user clicks "Start Tour"
    → TourSessionService.startTour()
      → prefers validated StepEdge hops for navigation
      → EditorDecorationController.showStep()        # current-step highlight + next-hop preview
  → user asks about current step
    → TourSessionService.askAboutCurrentStep()
      → FlowPlannerService.answerStepQuestion()
      → step-scoped answer + evidence shown in the tour panel
```

### State machine (`TourState`)

`INPUT → LOADING → OVERVIEW → TOUR_ACTIVE`

`TourSessionService` owns the state and notifies observers via `TourSessionListener`. All UI transitions go through this service. `CodeTourPanel` implements `TourSessionListener` and drives the `CardLayout`.

### Provider and grounding rules

- Repo-grounded walkthroughs must use a provider that can inspect the local repository. Today that means `Codex CLI` or `Claude CLI`.
- API-only providers remain configurable, but walkthrough planning rejects them for grounded repo analysis.
- When Claude CLI MCP support is enabled, semantic tools such as `find_symbol`, `get_symbols_overview`, and `find_referencing_symbols` should be preferred over raw file reads whenever they materially improve grounding.

### Response contract

`flow_map` responses now represent an execution path, not just a flat list of related files.

Important `FlowMap` fields:

- `architecture`: system purpose, validated components and relationships, cross-cutting concerns, and coverage notes
- `learning_path`: ordered curriculum stages referencing architecture components and walkthrough steps
- `steps`: validated `FlowStep` items
- `entry_step_id`: explicit path entrypoint
- `terminal_step_ids`: explicit path endpoints
- `edges`: `StepEdge` transitions between important hops
- `analysis_trace`: optional grounding metadata such as semantic tools used or delegated-analysis notes

Important `FlowStep` fields:

- `symbol`
- `step_type`: `entrypoint|method|class|module|branch|async_hop|sink`
- `importance`: `high|medium|low`
- `line_annotations`: only important sub-regions inside the validated step range

Important `StepEdge` fields:

- `from_step_id` / `to_step_id`
- `kind`: `call|branch|async_hop|instantiation|data_flow|return|implied_order`
- `call_site_*`: exact next-hop location when grounded
- `evidence`: hop-level grounding data

`step_answer` responses stay scoped to the current validated step and should explain the whole symbol first, then call out only the important lines.

### Key classes

| Class | Role |
|---|---|
| `TourSessionService` | Central session coordinator. Owns state, recent walkthroughs, current step, path-aware history, and step-answer state |
| `FlowPlannerService` | Prompt construction, provider capability checks, JSON parsing, validation |
| `LlmProviderService` | Provider selection and grounded-walkthrough capability enforcement |
| `ClaudeCliService` / `CodexCliService` | CLI-backed providers that can inspect the local repo |
| `StepValidator` | Validates `FlowMap` objects end-to-end: steps, annotations, evidence, edges, entrypoints, terminals |
| `EditorDecorationController` | Highlights validated step ranges and previews the next hop using validated callsites when possible |
| `CodeTourPanel` | Tool window UI for input, overview, active tour, and step-scoped follow-up questions |
| `CodeTourSettings` | Persistent per-project settings for provider selection, timeouts, MCP config, and UI toggles |

### Models (`model/` package)

- `FlowMap`
- `CodebaseArchitecture`, `ArchitectureComponent`, `ComponentRelationship`
- `LearningStage`
- `FlowStep`
- `StepEdge`
- `AnalysisTrace`
- `LineAnnotation`
- `EvidenceItem`
- `StepAnswer`
- `LlmResponse`
- `TourState`, `FollowUpContext`, `ClarificationExchange`

### Validation behavior

`StepValidator` is the main anti-hallucination layer.

It currently:

- rejects component anchors and evidence paths outside the project
- filters invalid architecture relationships and learning-stage references
- re-anchors steps to real symbols when possible
- clamps ranges into real files
- clamps `line_annotations` into the validated step range
- clamps evidence locations
- validates `StepEdge` callsite ranges against the source step
- synthesizes `implied_order` edges when the model omitted edges
- resolves `entry_step_id` and `terminal_step_ids` from validated structure

It does not perform full language-aware call resolution by itself. Strongest results come from providers that already used semantic navigation before returning JSON.

### Navigation and rendering behavior

- Learn mode accepts a blank prompt for a whole-codebase architecture and curriculum.
- The overview presents architecture before the staged learning path.
- The active tour shows the current learning-stage goal/checkpoint and uses validated outgoing edges first when picking the next step.
- If no usable edge exists, it falls back to the next non-broken step in list order.
- The editor preview highlights the validated next-hop `call_site_*` range when available.
- If no callsite exists, the preview falls back to a symbol-name match inside the current step.
- The tour panel includes a scoped follow-up text input for the current step. That request is intentionally local to the current validated symbol/range and does not remap the entire repo.
- Tool window text areas are line-wrapped to fit the panel.

### IntelliJ service registration

All major services are `@Service(Service.Level.PROJECT)` and declared in `plugin.xml`. Retrieve them via `project.service<Foo>()`. Actions in `action/` delegate immediately to `TourSessionService`.

### Threading model

- Provider calls run on `Dispatchers.IO` inside `TourSessionService.scope`
- UI and listener callbacks are posted with `ApplicationManager.getApplication().invokeLater { }`

### Testing

There is an active `src/test/` tree. Current tests cover validator behavior, markdown export, and step metadata formatting. Run `./gradlew test` after changing the response contract, validator, provider capability logic, or walkthrough rendering.
