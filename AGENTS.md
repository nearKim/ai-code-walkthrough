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
```

There are also pre-configured run configurations in `.run/` for the IDE.

## Architecture

This is an **IntelliJ Platform Plugin** (targeting IDEA 2025.2+) that answers natural-language questions about a repo by generating a grounded execution-path walkthrough, then guiding the user through that path in the editor.

The core principle is:

- The model proposes a path.
- The plugin validates and repairs that path.
- The IDE only renders validated locations and validated next-hop previews.

### Data flow

```
User question (CodeTourPanel)
  → TourSessionService.startMapping()
    → FlowPlannerService.mapFlow()
      → LlmProviderService.requireRepoGroundedWalkthroughSupport()
      → currentProvider().query()                     # CLI providers only for grounded walkthroughs
      → parse JSON into LlmResponse / FlowMap
      → StepValidator.validate(flowMap)              # validates steps, annotations, evidence, edges
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
```

### State machine (`TourState`)

`INPUT → LOADING → OVERVIEW → TOUR_ACTIVE`

`TourSessionService` owns the state and notifies observers via `TourSessionListener`. All UI transitions go through this service. `CodeTourPanel` implements `TourSessionListener` and drives the `CardLayout`.

### Grounding and provider rules

- Grounded walkthroughs require a provider that can inspect the local repository. In practice, that means `Codex CLI` or `Claude CLI`.
- API-only providers are available in settings, but the walkthrough planner rejects them for repo-grounded analysis to avoid hallucinating over missing code context.
- Claude CLI can be augmented with MCP semantic navigation (`find_symbol`, `get_symbols_overview`, `find_referencing_symbols`) for tighter symbol and edge grounding.

### Response contract

The walkthrough contract is no longer just an ordered list of files. A `flow_map` now contains:

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
| `FlowPlannerService` | Builds prompts, enforces provider capability checks, parses responses, and runs validation |
| `LlmProviderService` | Chooses provider implementations and blocks unsafe providers for grounded walkthroughs |
| `ClaudeCliService` / `CodexCliService` | CLI-backed providers that can inspect the local repo |
| `StepValidator` | Validates `FlowMap` objects: re-anchors symbols, clamps annotations/evidence, validates/synthesizes edges, resolves entry/terminal steps |
| `EditorDecorationController` | Applies highlights and inlays, and previews the next hop using validated callsites when available |
| `CodeTourPanel` | Swing tool window UI for input, overview, active tour, and step-scoped follow-up questions |
| `CodeTourSettings` | Persistent per-project settings for provider selection, timeouts, MCP config, and UI toggles |

### Models (`model/` package)

- `FlowMap` — validated walkthrough path
- `FlowStep` — one rendered walkthrough step
- `StepEdge` — validated transition between steps
- `AnalysisTrace` — optional grounding metadata
- `LineAnnotation` / `EvidenceItem` / `StepAnswer` — detailed rendering and follow-up answer models
- `LlmResponse` — raw model response wrapper
- `TourState` / `FollowUpContext` / `ClarificationExchange` — session state

### Validation behavior

`StepValidator` is the main anti-hallucination layer.

It currently:

- re-anchors step ranges to real symbol locations when possible
- clamps step ranges to the file
- clamps `line_annotations` into the validated step range
- clamps evidence line ranges to real files
- validates `StepEdge` callsite ranges against the source step
- synthesizes `implied_order` edges when the model omitted edges entirely
- resolves `entry_step_id` and `terminal_step_ids` from validated steps/edges

It does not do full language-aware call resolution. For now, symbol lookup is still text-based plus brace scanning unless the provider used semantic tools before returning the JSON.

### Tour navigation and UI behavior

- The active tour prefers validated outgoing `StepEdge` hops when choosing the next step.
- If no valid outgoing hop exists, it falls back to the next non-broken step in list order.
- The editor preview highlights the next hop's validated `call_site_*` range when available.
- If no callsite is available, the preview falls back to a symbol-name match inside the current step.
- The tour panel includes a scoped follow-up input for the current step. That request uses the current validated step as context and does not remap the whole repo.
- All user-visible text areas in the tool window are line-wrapped to fit the panel.

### IntelliJ service registration

All major services are `@Service(Service.Level.PROJECT)` and declared in `plugin.xml`. Retrieve them via `project.service<Foo>()`. Actions in `action/` delegate immediately to `TourSessionService`.

### Threading model

- Provider requests run on `Dispatchers.IO` inside `TourSessionService.scope`
- All UI updates and listener callbacks are posted through `ApplicationManager.getApplication().invokeLater { }`

### Testing

There is an active `src/test/` tree. Current tests cover validator behavior, markdown export, and step metadata formatting. Run `./gradlew test` after changing the response contract, validator, or walkthrough rendering.
