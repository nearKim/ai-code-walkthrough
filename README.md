# AI Code Walkthrough

AI Code Walkthrough is an IntelliJ Platform plugin for learning an unfamiliar repository from the outside in. It maps the system architecture and component relationships first, builds a staged learning path, and then walks the validated code stops directly in the editor.

## Learning workflow

1. **Map the architecture** — identify the system purpose, major components, ownership boundaries, representative paths, and cross-cutting concerns.
2. **Understand the relationships** — show grounded calls, dependencies, data movement, configuration, and test boundaries between components.
3. **Follow a curriculum** — progress from system orientation through component responsibilities to representative end-to-end execution paths.
4. **Read the real code** — open validated symbols and line ranges with editor explanations, important-line annotations, and next-hop previews.
5. **Interrogate each step** — ask scoped follow-up questions without remapping the repository.

Choose **Learn** and leave the prompt blank to generate a whole-codebase lesson. Add a prompt to focus the architecture and curriculum on a subsystem or behavior. **Review** and **Trace** remain available for risk-oriented and concrete execution-path walkthroughs.

## Grounding model

The model proposes the architecture and code path; the plugin validates before rendering:

- Grounded walkthroughs require Codex CLI or Claude CLI so the provider can inspect the local repository.
- Codex CLI is fixed to `gpt-5.6-sol` with `ultra` or `max` reasoning.
- Claude CLI offers only Claude Fable 5 (`fable`) and Claude Opus 5 (`opus`).
- Component anchors must resolve inside the project.
- Step files, symbols, line annotations, evidence, and next-hop call sites are checked or repaired.
- Invalid component relationships and learning-stage references are removed.
- Coverage notes identify areas that were intentionally excluded or not inspected deeply.

The plugin is language-agnostic and targets IntelliJ Platform 2025.2 or newer.

## Build and test

```bash
./gradlew build
./gradlew test
./gradlew runIde
./gradlew verifyPlugin
```

## Installation

Build the plugin and install the generated archive through:

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install Plugin from Disk…</kbd>

<!-- Plugin description -->
AI Code Walkthrough turns an unfamiliar repository into a grounded learning path. It maps component architecture and relationships, organizes the important concepts into stages, and guides you through validated code locations directly in the editor. Use Codex CLI or Claude CLI for repository-aware analysis, next-hop previews, and scoped follow-up questions.
<!-- Plugin description end -->
