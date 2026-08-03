# AI Code Walkthrough

AI Code Walkthrough is an IntelliJ Platform plugin and local web application for learning an unfamiliar repository from the outside in. It maps the system architecture and component relationships first, builds a staged learning path, and then walks the validated code stops in a read-only editor.

## Learning workflow

1. **Map the architecture** — identify the system purpose, major components, ownership boundaries, and representative paths.
2. **Inspect responsibilities** — map each component outcome to its owning interface, class, or function, then drill into the important methods, state, and collaborators.
3. **Follow a curriculum** — progress from system orientation through component responsibilities to representative end-to-end execution paths.
4. **Read the real code** — open validated symbols and line ranges with editor explanations, important-line annotations, and next-hop previews.
5. **Interrogate each step** — ask scoped follow-up questions without remapping the repository.

Choose **Learn** and leave the prompt blank to generate a whole-codebase lesson. Add a prompt to focus the architecture and curriculum on a subsystem or behavior. **Review** and **Trace** remain available for risk-oriented and concrete execution-path walkthroughs.

## Grounding model

The model proposes the architecture and code path; the shared walkthrough engine validates before either UI renders it:

- Grounded walkthroughs require Codex CLI or Claude CLI so the provider can inspect the local repository.
- Python repositories are indexed first with the standard-library AST. The provider receives exact production-file classes, functions, methods, bases, state fields, imports, and line ranges before it maps conceptual components.
- In the architecture inspector, **Responsibilities** are AI-selected explanations grounded to code, while **Code structure** lists only mechanically parsed symbols from the files mapped to that component.
- Codex CLI is fixed to `gpt-5.6-sol` with `ultra` or `max` reasoning.
- Claude CLI offers only Claude Fable 5 (`fable`) and Claude Opus 5 (`opus`).
- Component anchors and responsibility code owners must resolve to real project files and lines.
- Step files, symbols, line annotations, evidence, and next-hop call sites are checked or repaired.
- Invalid responsibility collaborators, component relationships, and learning-stage references are removed.
- Coverage notes identify areas that were intentionally excluded or not inspected deeply.

Both clients are language-agnostic. The plugin targets IntelliJ Platform 2025.2 or newer.

## Local web application

Building the web application requires Node.js 22.13 or newer. Run one repository per local server process:

```bash
./gradlew :web-server:run --args="/path/to/repository"

# Or build a reusable launcher
./gradlew :web-server:installDist
./web-server/build/install/ai-code-walkthrough/bin/ai-code-walkthrough /path/to/repository
```

The server binds to `127.0.0.1`, opens the browser, and serves a resizable split view: local source and temporary line explanations on the left, walkthrough controls on the right. Add `--no-open` or `--port 8080` inside the `--args` value when needed. CLI providers are launched with read-only repository access; browser source requests are restricted to existing UTF-8 files inside the selected repository.

## Build and test

```bash
./gradlew build
./gradlew test
./gradlew runIde
./gradlew verifyPlugin

cd web
npm test
npx playwright install chromium # once per machine
npm run test:e2e
```

## Installation

Build the plugin and install the generated archive through:

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙</kbd> → <kbd>Install Plugin from Disk…</kbd>

<!-- Plugin description -->
AI Code Walkthrough turns an unfamiliar repository into a grounded learning path. It maps component architecture and relationships, organizes the important concepts into stages, and guides you through validated code locations directly in the editor. Use Codex CLI or Claude CLI for repository-aware analysis, next-hop previews, and scoped follow-up questions.
<!-- Plugin description end -->
