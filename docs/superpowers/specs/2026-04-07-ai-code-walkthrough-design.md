# AI Code Walkthrough - JetBrains Plugin Design Spec

## Overview

A JetBrains IDE plugin that provides interactive, LLM-guided code walkthroughs. The user asks a question about the codebase (e.g., "How does the quicktest pipeline work?"), the plugin calls Claude to produce a structured "flow map" of the answer, and the user steps through the code tour file-by-file with editor highlights and inline explanations.

**Primary AI provider:** Claude CLI (`claude`) spawned as a subprocess.

**Target IDE:** Any JetBrains IDE (depends on `com.intellij.modules.platform` only). The plugin uses no language-specific PSI — Claude CLI handles code intelligence, and the plugin validates outputs via text search.

## Core Concepts

- **Flow map**: A structured, ordered list of code locations and explanations that answer the user's question. Produced by the LLM.
- **Tour**: The step-by-step walkthrough experience. The plugin opens files, highlights ranges, and shows explanations as the user navigates through the flow map.
- **Step**: One location in the flow map: a file, line range, symbol name, and explanation.

## Architecture

Four layers with strict responsibilities:

```
Tool Window (UI Layer)
    │
    ├── Input state: question text area, "Map Flow" button, recent tours
    ├── Overview state: summary, step list, "Start Tour" button, follow-up input
    └── Tour Active state: step explanation, navigation controls
    │
Domain Layer
    │
    ├── FlowMap, FlowStep: immutable data classes
    ├── TourSession: mutable session state (current step, active steps)
    └── StepValidator: validates LLM output against actual project files
    │
AI Layer
    │
    ├── ClaudeCliService: manages claude subprocess lifecycle
    └── FlowPlannerService: builds prompts, parses JSON, calls validator
    │
Editor Integration Layer
    │
    ├── EditorDecorationController: applies/removes highlights and inlays
    └── TourHighlightAttributes: theme-aware TextAttributesKey
```

**Key principle:** The LLM plans the walkthrough, the plugin owns IDE behavior. The model returns semantic data (file, range, explanation). The plugin deterministically handles navigation, highlighting, and cleanup.

## JSON Protocol

The plugin enforces a strict JSON response schema via the system prompt.

### Response Types

**Flow map response:**

```json
{
  "type": "flow_map",
  "summary": "One-paragraph high-level answer.",
  "steps": [
    {
      "id": "step-1",
      "title": "CLI entrypoint",
      "file_path": "quicktest/cli.py",
      "symbol": "main",
      "start_line": 12,
      "end_line": 48,
      "explanation": "Parses CLI arguments and delegates to QuicktestRunner.run().",
      "why_included": "This is where the pipeline begins when invoked from the terminal.",
      "uncertain": false
    }
  ]
}
```

**Clarification response:**

```json
{
  "type": "clarification",
  "clarification_question": "Are you asking about the CLI entrypoint or the test runner path?"
}
```

### Schema Rules

- `file_path`: relative to project root
- `explanation`: 1-2 sentences max. Reasoning goes in `why_included`
- `uncertain`: true when the step is inferred rather than traced from code
- Steps are ordered by execution sequence
- Follow-up questions reuse the same schema; the LLM returns a new flow map or clarification

## Package Layout

```
com.github.nearkim.aicodewalkthrough/
├── model/
│   ├── FlowMap.kt                      # FlowMap, FlowStep, ClarificationResponse
│   └── TourSession.kt                  # Mutable session state
├── service/
│   ├── ClaudeCliService.kt             # Subprocess lifecycle, stdin/stdout I/O
│   ├── FlowPlannerService.kt           # Prompt construction, envelope unwrap, JSON parsing, validation
│   ├── TourSessionService.kt           # Active tour state, navigation, listener notification
│   └── StepValidator.kt                # File existence, range bounds, symbol fuzzy match, deduplication
├── editor/
│   ├── EditorDecorationController.kt   # RangeHighlighter + block inlay management
│   └── TourHighlightAttributes.kt      # TextAttributesKey, ColorSettingsPage
├── action/
│   ├── MapFlowAction.kt                # Triggers flow mapping (Ctrl+Alt+W)
│   ├── StartTourAction.kt              # Begins step-by-step tour
│   ├── NextStepAction.kt               # Next step (Ctrl+Alt+Right)
│   ├── PrevStepAction.kt               # Previous step (Ctrl+Alt+Left)
│   ├── SkipStepAction.kt              # Skip broken/irrelevant step, advance to next
│   └── StopTourAction.kt               # End tour, cleanup (Escape)
├── toolwindow/
│   ├── CodeTourToolWindowFactory.kt    # ToolWindowFactory registered in plugin.xml
│   └── CodeTourPanel.kt               # Three-state Swing panel
└── settings/
    ├── CodeTourSettings.kt             # PersistentStateComponent
    └── CodeTourConfigurable.kt         # Settings UI under Preferences > Tools
```

## plugin.xml Registration

```xml
<idea-plugin>
    <id>com.github.nearkim.aicodewalkthrough</id>
    <name>AI Code Walkthrough</name>
    <vendor>nearkim</vendor>

    <depends>com.intellij.modules.platform</depends>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="Code Tour"
                    factoryClass="com.github.nearkim.aicodewalkthrough.toolwindow.CodeTourToolWindowFactory"
                    anchor="right"
                    icon="AllIcons.Actions.Play_forward"/>

        <projectService serviceImplementation="com.github.nearkim.aicodewalkthrough.service.ClaudeCliService"/>
        <projectService serviceImplementation="com.github.nearkim.aicodewalkthrough.service.FlowPlannerService"/>
        <projectService serviceImplementation="com.github.nearkim.aicodewalkthrough.service.TourSessionService"/>
        <projectService serviceImplementation="com.github.nearkim.aicodewalkthrough.settings.CodeTourSettings"/>

        <projectConfigurable parentId="tools"
                             instance="com.github.nearkim.aicodewalkthrough.settings.CodeTourConfigurable"
                             displayName="AI Code Walkthrough"/>
    </extensions>

    <actions>
        <group id="CodeTour.ActionGroup" text="Code Tour" popup="true">
            <add-to-group group-id="ToolsMenu" anchor="last"/>

            <action id="CodeTour.MapFlow"
                    class="com.github.nearkim.aicodewalkthrough.action.MapFlowAction"
                    text="Map Flow..."
                    description="Ask AI to map the execution flow">
                <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt W"/>
            </action>
            <action id="CodeTour.StartTour"
                    class="com.github.nearkim.aicodewalkthrough.action.StartTourAction"
                    text="Start Tour"/>
            <action id="CodeTour.NextStep"
                    class="com.github.nearkim.aicodewalkthrough.action.NextStepAction"
                    text="Next Step">
                <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt RIGHT"/>
            </action>
            <action id="CodeTour.PrevStep"
                    class="com.github.nearkim.aicodewalkthrough.action.PrevStepAction"
                    text="Previous Step">
                <keyboard-shortcut keymap="$default" first-keystroke="ctrl alt LEFT"/>
            </action>
            <action id="CodeTour.SkipStep"
                    class="com.github.nearkim.aicodewalkthrough.action.SkipStepAction"
                    text="Skip Step"/>
            <action id="CodeTour.StopTour"
                    class="com.github.nearkim.aicodewalkthrough.action.StopTourAction"
                    text="Stop Tour">
                <keyboard-shortcut keymap="$default" first-keystroke="ESCAPE"/>
            </action>
        </group>
    </actions>
</idea-plugin>
```

## Claude CLI Integration

### Process Lifecycle

Each request spawns a separate `claude --print` process (one-shot mode). There is no persistent subprocess — `--print` runs the full agentic loop (including file reads, grep, etc.) and exits after producing the final response.

For follow-up questions that need prior context, the plugin builds a context block containing:

1. **Original question** — the user's initial query that produced the current flow map
2. **Current flow map** — the full JSON response from the previous request
3. **Active step ID** — the step the user is currently viewing (for "ask about this step" prompts)
4. **Clarification history** — any prior clarification exchanges (question + user's answer), ordered chronologically

This context is injected into the new prompt as a structured JSON preamble. The approach is stateless — all context lives in the prompt, no conversation state files or CLI resume flags required. The context block is bounded: at most 1 flow map + 5 clarification exchanges. If the history exceeds this, the oldest clarification exchanges are dropped.

```
Plugin                          claude CLI
  │                                │
  │── spawn(1) ──────────────────>│  claude --print --output-format json ...
  │<── stdout: JSON response ─────│
  │── process exits ──────────────│
  │                                │
  │── spawn(2) ──────────────────>│  claude --print --output-format json ...
  │   (prompt includes context     │  (new process, context via prompt)
  │    block for follow-up)        │
  │<── stdout: JSON response ─────│
  │── process exits ──────────────│
```

### Follow-Up Context Block

Injected as a JSON preamble in the user prompt for follow-up requests:

```json
{
  "context": {
    "original_question": "How does the quicktest pipeline work?",
    "active_step_id": "step-3",
    "clarification_history": [
      {
        "question": "Are you asking about the CLI entrypoint or the test runner?",
        "answer": "The CLI entrypoint"
      }
    ],
    "previous_flow_map": { "...": "full flow map JSON from last response" }
  },
  "follow_up": "What does the config loader do in step 3?"
}
```

Bounds: at most 1 flow map + 5 clarification exchanges. Oldest clarifications are dropped first when the limit is exceeded.

> **Alternative for v2:** `claude -c` (continue last conversation) could preserve multi-turn context without re-injection, but depends on CLI state files and adds failure modes. Evaluate once the stateless approach proves limiting.

### ClaudeCliService

- Project-level service, implements `Disposable`
- Spawns a new `claude` process per request via `ProcessBuilder` with working directory = project root
- Uses `--print` mode (no interactive TUI) and `--output-format json`
- `--print` runs the full agentic loop — Claude can read files, grep, and explore the repo before answering. This is not a degraded mode
- System prompt passed via `-s` flag to enforce JSON schema
- Reads full response from stdout, then process exits
- For follow-ups, builds and injects the context block (original question, flow map, active step ID, clarification history) into the new prompt
- Inherits PATH so the user's claude installation is found
- Tracks the active `Process` reference so in-flight requests can be cancelled via `Process.destroyForcibly()`

### Subprocess Contract

**Invocation:** `claude --print --output-format json -s <system_prompt> -p <user_prompt>` with working directory set to the project root.

**stdout:** The only channel for response data. `--output-format json` wraps the LLM's output in a CLI metadata envelope:

```json
{
  "type": "result",
  "subtype": "success",
  "cost_usd": 0.05,
  "is_error": false,
  "duration_ms": 8200,
  "duration_api_ms": 7100,
  "num_turns": 3,
  "result": "{ ... the flow map or clarification JSON as a string ... }",
  "session_id": "..."
}
```

`FlowPlannerService` reads the envelope, checks `is_error`, then parses the `result` string as the flow map schema. If `is_error` is true, the `result` field contains the error message from Claude.

**stderr:** Diagnostic/progress output from the CLI (e.g., tool use logs). The plugin captures stderr for debugging but does not parse it for control flow. stderr content is logged at DEBUG level and shown to the user only on failure.

**Exit codes:**
- `0` — success, stdout contains the JSON envelope
- Non-zero — process-level failure (crash, signal, missing binary). The plugin reads stderr for a diagnostic message, shows it to the user, and offers retry.

**Edge cases:**
- If stdout is empty despite exit code 0, treat as malformed response (retry path)
- If the process is killed via `destroyForcibly()` (user cancel or timeout), discard any partial stdout

### System Prompt

Instructs Claude to:
1. Always respond with valid JSON matching the schema
2. Explore the codebase using built-in tools before answering
3. Use file paths relative to the project root
4. Return `type: "clarification"` when the question is ambiguous
5. Order steps by execution sequence — linearize branching control flow (try/except, if/else) by following the most common/happy path and noting alternatives in `explanation`
6. Keep `explanation` to 1-2 sentences; put reasoning in `why_included`
7. Mark `uncertain: true` for inferred steps
8. Always populate the `symbol` field when the step targets a specific function, class, or method — the plugin uses this for line range validation

### Error Handling

| Failure | Recovery |
|---------|----------|
| `claude` not found in PATH | Notification with install instructions link |
| Process dies mid-session | Error message, "Retry" button spawns new session |
| Malformed JSON | Trim markdown fences, extract JSON block. If still invalid, show raw response with "Retry" |
| Timeout (>120s) | Kill process, show timeout message, offer retry |
| User cancels in-flight request | `Process.destroyForcibly()`, return to Input state with question preserved |
| No steps in response | Show summary text, prompt user to refine question |

## Editor Integration

### Decorations Per Step

Two decorations applied simultaneously:

1. **Range highlight**: `MarkupModel.addRangeHighlighter()` with theme-aware background color via `TourHighlightAttributes.STEP_HIGHLIGHT_KEY`
2. **Block inlay**: Single-line inlay above the highlighted range showing step number and title (e.g., "Step 3/7 -- QuicktestRunner.run() dispatches stage executors"). Implemented via `EditorCustomElementRenderer` with mouse event handling — hovering/clicking the inlay expands the full explanation inline, so users don't have to context-switch to the tool window.
3. **Gutter breadcrumbs** (Phase 3+): All steps in the current flow map that fall in an open file get small numbered icons in the gutter via `RangeHighlighter.setGutterIconRenderer()`. This gives spatial awareness of the whole flow without requiring the tool window.

Full explanation lives in the tool window panel, not in the editor (but is also accessible via inlay hover).

### Navigation Sequence

When moving to a step:

1. Remove all decorations from previous step
2. Resolve file: `VirtualFileManager.findFileByNioPath(projectRoot.resolve(step.filePath))`
3. Open file: `FileEditorManager.openFile(virtualFile, focusEditor = true)`
4. Get editor: `FileEditorManager.getSelectedTextEditor()`
5. Calculate offset: `document.getLineStartOffset(step.startLine - 1)`
6. Scroll: `editor.scrollingModel.scrollToCaret(ScrollType.CENTER)`
7. Add range highlight
8. Add block inlay

Steps 2-8 run on EDT.

### Cleanup Triggers

Decorations are removed on: Next/Previous, Stop Tour, file closed manually, project close, file edited under highlight, skip broken step. The controller tracks all active highlighters, inlays, and gutter icons for deterministic cleanup.

### Theme Awareness

Register `TextAttributesKey` with fallback to `EditorColors.SEARCH_RESULT_ATTRIBUTES`. Users can customize via Settings > Editor > Color Scheme > Code Tour.

## Tool Window UI

### Three States

**Input state**: `JBTextArea` for question, "Map Flow" button (`JButton`), recent/pinned tours list (`JBList`).

**Overview state**: Summary as `JBLabel` with HTML, clickable step list (`JBList`), "Start Tour" button, follow-up input.

**Tour Active state**: Current step explanation panel, "Why this step?" expandable section, navigation toolbar (`ActionToolbar` wrapping tour actions), "Ask about this step" input. Steps marked `uncertain: true` show a subtle warning indicator. A "Skip Step" button allows dismissing broken steps (file missing, range invalid) without aborting the tour.

### State Transitions

```
Input ──[Map Flow]──> Loading ──[response]──> Overview
                       │ │                       │
                  [cancel] [error]          [Start Tour]
                       v   v                     v
                   Input (preserved)       Tour Active
                                             │     │
                                     [Stop / │  [Skip Step]
                                     last]   │     │
                                         v   v     v
                                       Overview  Next Step
```

Follow-ups from Overview or Tour Active go through Loading and return to Overview.

### Implementation

- Root container: `SimpleToolWindowPanel` (standard toolbar + content split)
- Navigation buttons: `ActionToolbar` wrapping `AnAction` instances
- State managed by `TourSessionService` listener pattern; panel subscribes to session changes

### StepValidator: Symbol Fuzzy Matching

LLM-provided line numbers are the weakest link in the flow map. When a step includes a `symbol` field, `StepValidator` performs a text search in the target file (e.g., searching for `def main` or `class QuicktestRunner`) to find the actual line range of that symbol. If found, the validator overrides the LLM's `start_line`/`end_line` with the real location. This is cheap (simple string search, no PSI required) and dramatically improves reliability — especially when the user is on a different branch or the file has been recently edited.

Validation priority:
1. File exists → if not, mark step as broken (skippable in UI)
2. Symbol search → if `symbol` is provided, find real line range and override LLM values
3. Range bounds → ensure `start_line`/`end_line` are within file length
4. Deduplication → collapse steps pointing to the same range

### Action State: Escape Key Gating

`StopTourAction` uses `AnAction.update()` to set `e.presentation.isEnabled = TourSessionService.hasActiveTour()`. This prevents the Escape shortcut from conflicting with IntelliJ's other Escape handlers (close popups, cancel search, exit multi-cursor) when no tour is active. All tour navigation actions (Next, Prev, Skip, Stop) follow the same pattern — only enabled when a tour session exists.

## Services

| Service | Scope | Threading | Purpose |
|---------|-------|-----------|---------|
| `ClaudeCliService` | Project | Coroutine (BGT) | Spawns/manages claude process, stdin/stdout I/O |
| `FlowPlannerService` | Project | Coroutine (BGT) | Builds prompt, calls ClaudeCliService, parses + validates JSON |
| `TourSessionService` | Project | EDT for state reads, BGT for LLM calls | Session state, step index, navigation, listener notification |
| `CodeTourSettings` | Project | EDT | PersistentStateComponent for preferences and pinned tours |

## Settings

| Setting | Default | Purpose |
|---------|---------|---------|
| `claudePath` | `"claude"` | Path to claude CLI binary |
| `requestTimeout` | `120` seconds | Max wait for LLM response |
| `maxSteps` | `20` | Cap steps to prevent runaway responses |
| `pinnedTours` | `[]` | Saved flow maps as JSON |

## MVP Phases

| Phase | Deliverable | Validates |
|-------|-------------|-----------|
| **1** | Question input, Claude CLI call, flow map summary + step list in tool window. No editor interaction. | LLM integration, JSON parsing, UI state machine |
| **2** | Clicking a step opens the file and applies range highlight. Symbol fuzzy matching overrides LLM line numbers. No inlays, no player. | File navigation, highlight lifecycle, validator reliability |
| **3** | Start Tour / Next / Prev / Skip / Stop with block inlays, gutter breadcrumbs, and keyboard shortcuts. Cancel button in loading state. | Full tour experience, decoration cleanup, action state, cancellation |
| **4** | Clarification questions, "Why this step?", follow-up questions with conversation context. | Multi-turn sessions, clarification flow |
| **5** | Pin tour, recent tours list, settings page (claude path, timeout, highlight color). | Persistence, configurability |

Each phase is independently shippable.

## Testing Strategy

### Unit Tests (JUnit 5, no IDE dependencies)

- `FlowMap` / `FlowStep` JSON deserialization: valid, malformed, edge cases
- `StepValidator`: missing file, out-of-range lines, symbol fuzzy match override, duplicate IDs
- `TourSession`: navigation logic, boundary conditions

### Integration Tests (BasePlatformTestCase)

- Action availability: MapFlowAction enabled/disabled based on state
- Tool window creation and state transitions
- Editor decoration lifecycle
- File opening for valid step paths

### CI

- `./gradlew check` runs unit + integration tests
- Plugin Verifier against target IDE versions

## Dependencies

No external dependencies beyond the IntelliJ Platform. The only external requirement is the `claude` CLI installed on the user's machine.

- `java.lang.ProcessBuilder` for subprocess management
- `kotlinx.serialization` for JSON parsing (add as an explicit Gradle dependency)
- Standard IntelliJ Platform APIs for editor, tool window, actions, and settings

## Design Decisions Log

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| AI provider | Claude CLI subprocess | Direct API, MCP server | Simplest auth, leverages user's existing Claude Code setup, CLI handles repo exploration |
| Response format | Structured JSON | Streaming markdown, two-phase | Deterministic parsing, clean data/UI separation, reliable step navigation |
| Step ordering | Linear array | DAG with next_step_ids | Simpler UI, simpler for LLM to produce, covers 90% of cases. DAG is a v2 candidate. **Known limitation:** branching control flow (try/except, if/else dispatch) is awkwardly linearized. The system prompt mitigates this by instructing Claude to follow the happy path and note alternatives in `explanation` |
| Editor explanation | Short block inlay + panel | Inlay-only, gutter-only, panel-only | Editor shows where + brief context; panel shows full explanation. Clean separation |
| Code intelligence | Claude CLI explores repo | PSI-based candidate graph | `claude --print` runs the full agentic loop (file reads, grep, etc.) — not a degraded mode. Local PSI analysis duplicates this and adds complexity. Validate outputs instead of curating inputs |
| Confidence scores | Binary `uncertain` flag | Decimal confidence (0.0-1.0) | LLM self-reported confidence is unreliable. Binary flag triggers clarification without false precision |
| IDE scope | All JetBrains IDEs (`com.intellij.modules.platform`) | PyCharm only | Plugin uses no language-specific PSI — Claude CLI handles code intelligence, plugin validates via text search. No reason to restrict scope |
