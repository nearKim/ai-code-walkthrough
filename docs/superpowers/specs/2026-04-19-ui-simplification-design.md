# UI Simplification & Code-Panel Focus — Design

## Goal

Reshape the plugin into a minimal, step-by-step code-review walkthrough tool. The tool window is a thin controller for input and navigation; the **editor (code panel)** carries the explanation content through ephemeral inlays.

Remove features that distract from this core flow. Target a ~70% reduction in `CodeTourPanel` LOC and clean deletion of the parallel repository-review product.

## Non-goals

- Language-aware call resolution beyond what `StepValidator` already does.
- Replacing the provider/grounding architecture (CLI providers stay the source of truth).
- Adding new modes or new prompt types.
- Multi-file side-by-side tour UI.

## User Flow

1. User opens tool window → **Input card**: picks a mode (Understand / Review / Trace), types a prompt, selects a CLI provider, clicks *Start walkthrough*.
2. **Loading card**: spinner + single status line.
3. **Overview card**: summary line + numbered step list + `Start tour` / `Preview selected` / `Copy as Markdown`.
4. **Tour Active card**: thin controller (Prev/Next/Stop, per-step follow-up Q&A). All step explanation content renders in the **editor**:
   - Header inlay (step N/M · title · file:lines · nav hint)
   - Summary inlay (2–4 lines of prose: "what does this do")
   - Per-line annotation inlays on important lines only
   - Current-step background highlight
   - Next-step call-site marker (bold underline + gutter arrow + tooltip)
5. User presses Next / Prev to walk steps. Inlays clear and rebuild per step — "ephemeral" by construction. Inlays also clear if the user edits the highlighted range.

## Scope: What Stays, What Goes

### Keep (with edits)

| Component | Role | Changes |
|---|---|---|
| `TourSessionService` | State owner | Remove `REPO_REVIEW` state/paths, remove clarification exchanges, keep path-aware history and per-step Q&A |
| `FlowPlannerService` | Prompt + validate | Keep `mapFlow`, `answerStepQuestion`; remove repo-review prompt paths |
| `StepValidator` | Anti-hallucination | No changes |
| `LlmProviderService` | Provider selection | Constrain available providers to CLI-only in the dropdown |
| `ClaudeCliService`, `CodexCliService` | CLI providers | No changes |
| `EditorDecorationController` | Editor surfaces | **Rewritten** to produce 3-inlay stack + line annotations + next-hop marker |
| Domain models (`FlowMap`, `FlowStep`, `StepEdge`, `LineAnnotation`, `EvidenceItem`, `StepAnswer`, `LlmResponse`, `TourState`) | Data contract | Drop `RepositoryReview*` and `RepositoryFinding` models |

### Delete

**Services & infra:**
- `RepositoryReviewPlannerService`, `RepositoryReviewValidator`, `ReviewArtifactStore`
- `ClaudeApiService`, `GeminiApiService`, `OpenAiApiService`, `JsonHttpProviderSupport` *(verify no other call sites before deletion)*

**UI:**
- `toolwindow/review/RepositoryReviewCard.kt`
- `CARD_REPO_REVIEW` card and all repo-review UI in `CodeTourPanel`
- Overview card: filter chips, tab panel (explain/evidence/risk/comment/tests), comment composer, global-notes section, grounding trace section, potential-bug warning panels, context chips, quick-action grid, recent walkthroughs panel, suggestion chips
- Loading card: progress log textarea
- Tour Active card: evidence list, risk tab, comment composer, tests tab, clarification exchange UI

**Actions:**
- `action/ReviewRepositoryAction.kt`
- `action/ReviewCurrentFileAction.kt`

**Modes:**
- `ReviewMode.RISK`, `ReviewMode.COMMENT`
- Corresponding `AnalysisMode.RISK`, `AnalysisMode.COMMENT` (if no other consumers)

**Models:**
- `model/RepositoryReview.kt` (RepositoryReviewSnapshot, RepositoryFinding, etc.)

**Settings:** entries tied to repo review and progress-log visibility if no longer referenced.

**Plugin.xml:** registrations for deleted services, actions, and toolwindow contributions.

## Tool Window Composition

Refactor `CodeTourPanel` (3257 lines) into a thin root + per-card files:

```
toolwindow/
  CodeTourPanel.kt           (~150 lines) — root panel, CardLayout, listener registration
  CodeTourToolWindowFactory.kt (unchanged)
  cards/
    InputCard.kt             (~200 lines)
    LoadingCard.kt           (~60 lines)
    OverviewCard.kt          (~200 lines)
    TourActiveCard.kt        (~200 lines)
  layout/                    (existing helpers: ViewportWidthPanel, WrapLayout, WrappingTextArea)
```

Target total: ~850 lines (from ~3600). Each card is a self-contained `JPanel` subclass with a single public API: `fun bind(session: TourSessionService)` (or constructor injection) plus the `TourSessionListener` callbacks it needs.

### Input card

- **Row 1 — Mode chips:** single-select toggle group `Understand | Review | Trace`. One-line helper text under the chips that updates on selection.
- **Row 2 — Prompt:** `JBTextArea`, 4 rows, line-wrapped, mode-specific placeholder. Enter submits, Shift+Enter newline, ↑/↓ walks history.
- **Row 3 — Provider + submit:** `[Provider ▾]` dropdown (Claude CLI, Codex CLI only) with status dot (green available / red unavailable; tooltip shows path or error). `[Start walkthrough]` primary button.
- **Error banner:** single dismissible label above Row 1, shown only after a failed run. Auto-clears when the prompt is edited.

### Loading card

- Animated spinner + elapsed timer + one-line status label. No progress log.

### Overview card

- Summary line: `N steps · entrypoint: <title> · <provider>`.
- `JBList<FlowStep>`: numbered rows; primary text = step title; secondary line = `<file>:<startLine>`; small step-type chip.
- Action row: `[ Start tour ]` (primary), `[ Preview selected ]`, `[ Copy as Markdown ]`.

### Tour Active card

- Header: `Step N/M · <title>` + muted `<file>:<start>-<end>` + `Go to code` link (recenters editor).
- Nav row: `[ ◀ Prev ] [ Next ▶ ] [ Stop ]`. Arrow-key bindings when the panel has focus.
- Follow-up: `JBTextField` (placeholder `Ask about this step...`), Enter submits `sessionService.askAboutCurrentStep()`. Answer area: read-only `JTextArea` inside a scroll pane, cleared on step change. Small status label (`Loading…` / `Done` / `Error: …`).

## Editor Decoration Redesign

`EditorDecorationController.showStep()` produces, in order:

1. **Header inlay** — block element above `step.startLine`. Single line: `Step N/M · <symbol> · <file>:<start>-<end>` with right-aligned hint `← prev   → next   esc to stop`. Left accent bar colored by severity (neutral / accent / warning / danger). Height ≈ one editor line + padding.
2. **Summary inlay** — block element directly below the header. 2–4 wrapped lines of the model's step explanation in a smaller italic, muted color. Text width follows editor viewport width. Wrap layout cached per `(width, text)`.
3. **Line annotation inlays** — after-line inlays on each line in `FlowStep.line_annotations`, rendered right of the line content. Format: `  ← <annotation>`. Muted accent color, no background fill.
4. **Current-step background highlight** — existing range highlighter on `startLine..endLine`, tinted softer so inlay text reads cleanly on top.
5. **Next-step marker** — if the outgoing `StepEdge` has a validated `call_site_*` range inside the current step range: bold underline on that range + gutter arrow icon + tooltip `Next: <nextStep.title>`. Fallback: first symbol-name match inside the current range (existing behavior).

### Ephemeral behavior

- `clearDecorations()` runs on every `showStep()` call, on Prev/Next/Stop, on tool-window close, and on a `DocumentListener` edit within the highlighted range.
- All inlay renderers dispose their highlighters and inlays on controller `dispose()`.

### Performance rules

- `showStep()` runs on EDT but only calls markup-model / inlay-model APIs — no file I/O.
- Snippet reads (when building candidate lines) stay off the EDT.
- Wrap layout is cached per renderer, keyed on `(width, text)`; rebuilt only in `calcHeightInPixels` when width changes, not on every `paint()`.
- No per-repaint allocations inside `paint()`; Graphics state is restored via `g2.create()` / `dispose()`.
- Highlighter layers: `HighlighterLayer.SELECTION - 1` for step background, `HighlighterLayer.SELECTION` for next-hop marker (existing).

## Data Flow

```
InputCard submit
  → TourSessionService.startMapping(prompt, mode, provider)
    → FlowPlannerService.mapFlow() → LlmResponse → StepValidator.validate()
    → state = OVERVIEW
  → OverviewCard renders step list
  → user clicks Start tour / Preview
    → TourSessionService.startTour() / previewStep()
      → EditorDecorationController.showStep(step, next, edge)
         → header + summary + line annotations + current-highlight + next-hop marker
      → state = TOUR_ACTIVE
  → Prev/Next (buttons or arrow keys)
    → sessionService.nextStep() / prevStep() → showStep()
  → follow-up submit (TourActiveCard)
    → sessionService.askAboutCurrentStep(question)
    → FlowPlannerService.answerStepQuestion()
    → TourActiveCard renders StepAnswer
```

State machine: `INPUT → LOADING → OVERVIEW → TOUR_ACTIVE`. No `REPO_REVIEW`.

## Error Handling

- Provider unavailable (CLI missing): input card status dot red, tooltip shows the error, submit button disabled.
- Mapping failure: state → INPUT, error banner shown with the message from `FlowPlannerService`.
- Validation failure: same as above; banner distinguishes `validation` vs `auth` vs `provider` errors.
- Step-answer failure: TourActiveCard status label shows `Error: <message>`; other step state untouched.

## Testing

- Keep: `StepValidator` tests, `FlowMapMarkdownExporter` tests, `FlowStepMetaFormatter` tests, `JsonResponseSanitizerTest`.
- Delete: tests for repo review, comment composer, step filters (if any exist).
- **Add:** `EditorDecorationControllerTest` using `BasePlatformTestCase`:
  - Given a `FlowStep` with N `line_annotations` and an outgoing validated `StepEdge`, assert the editor has 1 header block inlay + 1 summary block inlay + N after-line inlays + 1 range highlighter for the step background + 1 highlighter for the next-hop marker.
  - Given a step with no outgoing edge, assert no next-hop highlighter is added.
  - Given a step with `broken = true`, assert `showStep` returns without adding decorations.
- Run `./gradlew check` after the refactor.

## Risk / Rollback

- Delete is the main risk. Before deleting each file, search for references (`Grep` for class name) to catch any hidden consumer.
- Keep the branch `feature/ux-improvements` as the working branch; each logical step (card extraction, decoration rewrite, repo-review removal) is a separate commit so partial rollback is cheap.
- Manual verification via `./gradlew runIde` after the refactor: confirm Understand / Review / Trace modes each produce a valid walkthrough and the three-inlay stack renders correctly in both light and dark themes.
