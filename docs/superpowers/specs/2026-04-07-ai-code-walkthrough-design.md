# AI Code Walkthrough - PyCharm Plugin Design Spec

## Overview

A PyCharm plugin that provides interactive, LLM-guided code walkthroughs. The user asks a question about the codebase (e.g., "How does the quicktest pipeline work?"), the plugin calls Claude to produce a structured "flow map" of the answer, and the user steps through the code tour file-by-file with editor highlights and inline explanations.

**Primary AI provider:** Claude CLI (`claude`) spawned as a subprocess.

**Target IDE:** PyCharm only (depends on `com.intellij.modules.python`).

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
│   ├── FlowPlannerService.kt           # Prompt construction, JSON parsing, validation
│   ├── TourSessionService.kt           # Active tour state, navigation, listener notification
│   └── StepValidator.kt                # File existence, range bounds, deduplication
├── editor/
│   ├── EditorDecorationController.kt   # RangeHighlighter + block inlay management
│   └── TourHighlightAttributes.kt      # TextAttributesKey, ColorSettingsPage
├── action/
│   ├── MapFlowAction.kt                # Triggers flow mapping (Ctrl+Alt+W)
│   ├── StartTourAction.kt              # Begins step-by-step tour
│   ├── NextStepAction.kt               # Next step (Ctrl+Alt+Right)
│   ├── PrevStepAction.kt               # Previous step (Ctrl+Alt+Left)
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
    <depends>com.intellij.modules.python</depends>

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

One `claude` process per walkthrough session to preserve conversation context for follow-ups.

```
Plugin                          claude CLI
  │                                │
  │── spawn ──────────────────────>│  (claude --print --output-format json ...)
  │── stdin: system prompt ───────>│
  │── stdin: user question ───────>│
  │<── stdout: JSON response ─────│
  │                                │
  │── stdin: follow-up question ──>│  (same process, context preserved)
  │<── stdout: JSON response ─────│
  │                                │
  │── destroy ────────────────────>│  (stop tour / close project)
```

### ClaudeCliService

- Project-level service, implements `Disposable`
- Spawns `claude` via `ProcessBuilder` with working directory = project root
- Uses `--print` mode (no interactive TUI) and `--output-format json`
- Sends system prompt at session start to enforce JSON schema
- Reads full response from stdout per request
- Destroys process on dispose (project close) or explicit stop
- Inherits PATH so the user's claude installation is found

### System Prompt

Instructs Claude to:
1. Always respond with valid JSON matching the schema
2. Explore the codebase using built-in tools before answering
3. Use file paths relative to the project root
4. Return `type: "clarification"` when the question is ambiguous
5. Order steps by execution sequence
6. Keep `explanation` to 1-2 sentences; put reasoning in `why_included`
7. Mark `uncertain: true` for inferred steps

### Error Handling

| Failure | Recovery |
|---------|----------|
| `claude` not found in PATH | Notification with install instructions link |
| Process dies mid-session | Error message, "Retry" button spawns new session |
| Malformed JSON | Trim markdown fences, extract JSON block. If still invalid, show raw response with "Retry" |
| Timeout (>120s) | Kill process, show timeout message, offer retry |
| No steps in response | Show summary text, prompt user to refine question |

## Editor Integration

### Decorations Per Step

Two decorations applied simultaneously:

1. **Range highlight**: `MarkupModel.addRangeHighlighter()` with theme-aware background color via `TourHighlightAttributes.STEP_HIGHLIGHT_KEY`
2. **Block inlay**: Single-line inlay above the highlighted range showing step number and title (e.g., "Step 3/7 -- QuicktestRunner.run() dispatches stage executors")

Full explanation lives in the tool window panel, not in the editor.

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

Decorations are removed on: Next/Previous, Stop Tour, file closed manually, project close, file edited under highlight. The controller tracks all active highlighters and inlays for deterministic cleanup.

### Theme Awareness

Register `TextAttributesKey` with fallback to `EditorColors.SEARCH_RESULT_ATTRIBUTES`. Users can customize via Settings > Editor > Color Scheme > Code Tour.

## Tool Window UI

### Three States

**Input state**: `JBTextArea` for question, "Map Flow" button (`JButton`), recent/pinned tours list (`JBList`).

**Overview state**: Summary as `JBLabel` with HTML, clickable step list (`JBList`), "Start Tour" button, follow-up input.

**Tour Active state**: Current step explanation panel, "Why this step?" expandable section, navigation toolbar (`ActionToolbar` wrapping tour actions), "Ask about this step" input.

### State Transitions

```
Input ──[Map Flow]──> Loading ──[response]──> Overview
                         │                       │
                     [error]               [Start Tour]
                         v                       v
                   Input (error)           Tour Active
                                               │
                                       [Stop / last step]
                                               v
                                            Overview
```

Follow-ups from Overview or Tour Active go through Loading and return to Overview.

### Implementation

- Root container: `SimpleToolWindowPanel` (standard toolbar + content split)
- Navigation buttons: `ActionToolbar` wrapping `AnAction` instances
- State managed by `TourSessionService` listener pattern; panel subscribes to session changes

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
| **2** | Clicking a step opens the file and applies range highlight. No inlays, no player. | File navigation, highlight lifecycle |
| **3** | Start Tour / Next / Prev / Stop with block inlays and keyboard shortcuts. | Full tour experience, decoration cleanup, action state |
| **4** | Clarification questions, "Why this step?", follow-up questions with conversation context. | Multi-turn sessions, clarification flow |
| **5** | Pin tour, recent tours list, settings page (claude path, timeout, highlight color). | Persistence, configurability |

Each phase is independently shippable.

## Testing Strategy

### Unit Tests (JUnit 5, no IDE dependencies)

- `FlowMap` / `FlowStep` JSON deserialization: valid, malformed, edge cases
- `StepValidator`: missing file, out-of-range lines, duplicate IDs
- `TourSession`: navigation logic, boundary conditions

### Integration Tests (BasePlatformTestCase)

- Action availability: MapFlowAction enabled/disabled based on state
- Tool window creation and state transitions
- Editor decoration lifecycle
- File opening for valid step paths

### CI

- `./gradlew check` runs unit + integration tests
- Plugin Verifier against target PyCharm versions

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
| Step ordering | Linear array | DAG with next_step_ids | Simpler UI, simpler for LLM to produce, covers 90% of cases. DAG is a v2 candidate |
| Editor explanation | Short block inlay + panel | Inlay-only, gutter-only, panel-only | Editor shows where + brief context; panel shows full explanation. Clean separation |
| Code intelligence | Claude CLI explores repo | PSI-based candidate graph | Claude CLI already has file exploration tools. Local PSI analysis duplicates this and adds complexity. Validate outputs instead of curating inputs |
| Confidence scores | Binary `uncertain` flag | Decimal confidence (0.0-1.0) | LLM self-reported confidence is unreliable. Binary flag triggers clarification without false precision |
| IDE scope | PyCharm only | All JetBrains IDEs | Enables Python-specific PSI features. Cross-IDE support is a future extension |
