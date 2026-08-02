# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

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

# Run the localhost web client for one repository
./gradlew :web-server:run --args="/path/to/repository"

# Run browser-client checks
cd web
npm test
npm run test:e2e
```

There are also pre-configured run configurations in `.run/` for the IDE.

## Architecture

This repository contains an **IntelliJ Platform Plugin** (targeting IDEA 2025.2+) and a **localhost web client**. Both teach an unfamiliar repository from architecture to implementation using the same grounded component map, staged learning path, validation, and path navigation.

The core principle is:

- The model proposes a path.
- The shared `core` module validates and repairs that path.
- The IDE and browser only render validated locations and validated next-hop previews.

The modules are:

- `core/` — provider commands, prompt contracts, response models, validation, navigation, and Markdown export; no IntelliJ dependency
- root plugin — Swing tool window and IntelliJ editor integration
- `web-server/` — loopback Ktor server, in-memory session, settings, and repository-contained source API
- `web/` — React/Mantine controls and a read-only Monaco editor with temporary line explanations

### Data flow

```
User question (CodeTourPanel)
  → TourSessionService.startMapping()
    → FlowPlannerService.mapFlow()
      → LlmProviderService.requireRepoGroundedWalkthroughSupport()
      → currentProvider().query()                     # CLI providers only for grounded walkthroughs
      → parse JSON into LlmResponse / FlowMap
      → StepValidator.validate(flowMap)              # validates architecture, curriculum, steps, evidence, edges
    → TourSessionService.handleMappingResult()
      → transitions to OVERVIEW
  → user clicks "Start Tour"
    → TourSessionService.startTour()
      → navigates by validated StepEdge hops first, list order second
      → EditorDecorationController.showStep()        # highlights current step + next-hop callsite/symbol
  → user asks about current step
    → TourSessionService.askAboutCurrentStep()
      → FlowPlannerService.answerStepQuestion()
      → step-scoped answer + evidence shown in tour panel

Browser request (App)
  → WebApplication route
    → WebSession
      → WalkthroughEngine
        → CLI provider → parse and validate shared FlowMap
    → session events over SSE
  → CodePane fetches repository-contained source and renders validated highlights/inlays
```

### State machine (`TourState`)

`INPUT → LOADING → OVERVIEW → TOUR_ACTIVE`

`TourSessionService` owns plugin state and notifies `TourSessionListener`. `WebSession` owns the equivalent single-process browser state and emits snapshots over SSE.

### Grounding and provider rules

- Grounded walkthroughs require a provider that can inspect the local repository. In practice, that means `Codex CLI` or `Claude CLI`.
- Claude CLI can be augmented with MCP semantic navigation (`find_symbol`, `get_symbols_overview`, `find_referencing_symbols`) for tighter symbol and edge grounding.

### Response contract

The walkthrough contract is no longer just an ordered list of files. A `flow_map` now contains:

- `architecture`: system purpose, validated `ArchitectureComponent` anchors, grounded `ComponentRelationship` edges, cross-cutting concerns, and honest coverage notes
- `learning_path`: ordered `LearningStage` items that connect architecture components to concrete walkthrough steps and checkpoints
- `steps`: validated `FlowStep` items
- `entry_step_id`: explicit entrypoint for the traced path
- `terminal_step_ids`: validated path endpoints
- `edges`: `StepEdge` transitions between important hops
- `analysis_trace`: optional trace metadata such as semantic tools used or delegated-analysis notes

Important step fields:

- `symbol`: target method/class/module when applicable
- `step_type`: `entrypoint|method|class|module|branch|async_hop|sink`
- `importance`: `high|medium|low`
- `line_annotations`: only important sub-regions within the validated step range

Important edge fields:

- `from_step_id` / `to_step_id`
- `kind`: `call|branch|async_hop|instantiation|data_flow|return|implied_order`
- `call_site_*`: exact next-hop location when the model can ground it
- `evidence`: hop-level grounding data

### Key classes

| Class | Role |
|---|---|
| `TourSessionService` | Central session/state coordinator. Tracks walkthrough state, current step, step-answer state, and path-aware navigation history |
| `WalkthroughEngine` | Shared prompt, provider capability, parsing, validation, and step-answer pipeline |
| `FlowPlannerService` | Thin IntelliJ adapter over `WalkthroughEngine` |
| `LlmProviderService` | Chooses provider implementations and blocks unsafe providers for grounded walkthroughs |
| `ClaudeCliService` / `CodexCliService` | CLI-backed providers that can inspect the local repo |
| `StepValidator` | Validates `FlowMap` objects: re-anchors symbols, clamps annotations/evidence, validates/synthesizes edges, resolves entry/terminal steps |
| `EditorDecorationController` | Applies highlights and inlays, and previews the next hop using validated callsites when available |
| `CodeTourPanel` | Swing tool window UI for input, overview, active tour, and step-scoped follow-up questions |
| `ArchitecturePanel` | Architecture-first overview of validated components, relationships, cross-cutting concerns, and coverage gaps |
| `CodeTourSettings` | Persistent per-project settings for provider selection, model selection, MCP config, and UI toggles |
| `WebSession` / `WebApplication` | Browser state machine and loopback HTTP/SSE boundary |
| `App` / `CodePane` | Browser split view and read-only Monaco source decorations |

### Models (`core/.../model/` package)

- `FlowMap` — validated walkthrough path
- `CodebaseArchitecture` / `ArchitectureComponent` / `ComponentRelationship` — grounded system structure
- `LearningStage` — curriculum stage referencing validated components and steps
- `FlowStep` — one rendered walkthrough step
- `StepEdge` — validated transition between steps
- `AnalysisTrace` — optional grounding metadata
- `LineAnnotation` / `EvidenceItem` / `StepAnswer` — detailed rendering and follow-up answer models
- `LlmResponse` — raw model response wrapper
- `TourState` / `FollowUpContext` / `ClarificationExchange` — session state

### Validation behavior

`StepValidator` is the main anti-hallucination layer.

It currently:

- rejects architecture anchors and evidence paths that escape the project
- removes nonexistent component anchors and relationships to invalid components
- filters learning-stage component/step references and assigns otherwise-unassigned validated steps
- re-anchors step ranges to real symbol locations when possible
- clamps step ranges to the file
- clamps `line_annotations` into the validated step range
- clamps evidence line ranges to real files
- validates `StepEdge` callsite ranges against the source step
- synthesizes `implied_order` edges when the model omitted edges entirely
- resolves `entry_step_id` and `terminal_step_ids` from validated steps/edges

It does not do full language-aware call resolution. For now, symbol lookup is still text-based plus brace scanning unless the provider used semantic tools before returning the JSON.

### Tour navigation and UI behavior

- Learn mode can be started with a blank prompt to request a whole-codebase curriculum.
- The overview opens on architecture first, then advances to the staged learning path.
- The active tour shows the current learning-stage goal and checkpoint and prefers validated outgoing `StepEdge` hops when choosing the next step.
- If no valid outgoing hop exists, it falls back to the next non-broken step in list order.
- The editor preview highlights the next hop's validated `call_site_*` range when available.
- If no callsite is available, the preview falls back to a symbol-name match inside the current step.
- The tour panel includes a scoped follow-up input for the current step. That request uses the current validated step as context and does not remap the whole repo.
- All user-visible text areas in the tool window are line-wrapped to fit the panel.

### IntelliJ service registration

All major services are `@Service(Service.Level.PROJECT)` and declared in `plugin.xml`. Retrieve them via `project.service<Foo>()`. Actions in `action/` delegate immediately to `TourSessionService`.

### Threading model

- Provider requests run on `Dispatchers.IO` inside `TourSessionService.scope`
- Repository-analysis requests have no wall-clock deadline and run until the CLI exits or the user presses Stop
- All UI updates and listener callbacks are posted through `ApplicationManager.getApplication().invokeLater { }`
- The web server uses a coroutine-backed `WebSession`; each process owns one repository and one active mapping request.

### Testing

Tests live under `core/src/test`, `src/test`, `web-server/src/test`, `web/src`, and `web/e2e`. Run `./gradlew test`, `npm test`, and `npm run test:e2e` after changing the shared contract or either walkthrough renderer.
