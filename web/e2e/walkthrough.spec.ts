import { expect, test } from '@playwright/test';

const firstStep = {
  id: 'entry',
  title: 'Enter the program',
  file_path: 'src/Main.kt',
  symbol: 'main',
  start_line: 1,
  end_line: 3,
  explanation: 'The program enters here.',
  why_included: 'This is the executable entrypoint.',
  step_type: 'entrypoint',
  importance: 'high',
  uncertain: false,
  line_annotations: [{ start_line: 2, end_line: 2, text: 'This call crosses into application logic.' }],
  evidence: [],
};

const secondStep = {
  ...firstStep,
  id: 'logic',
  title: 'Run application logic',
  symbol: 'start',
  start_line: 5,
  end_line: 7,
  explanation: 'The application performs its work.',
  why_included: 'This is the next validated hop.',
  step_type: 'method',
  line_annotations: [{ start_line: 5, end_line: 5, text: 'The walkthrough has reached the implementation.' }],
};

const edge = {
  id: 'entry-to-logic',
  from_step_id: 'entry',
  to_step_id: 'logic',
  kind: 'call',
  rationale: 'main calls start',
  call_site_file_path: 'src/Main.kt',
  call_site_start_line: 2,
  call_site_end_line: 2,
  evidence: [],
  uncertain: false,
};

const flow = {
  summary: 'A small executable flow.',
  architecture: {
    system_purpose: 'Run an application from a local entrypoint.',
    components: [{
      id: 'application',
      name: 'Application',
      kind: 'application',
      responsibility: 'Coordinate the application run.',
      responsibilities: [{
        id: 'run-application',
        title: 'Run the application',
        description: 'Transfers control through the application lifecycle.',
        evidence: [{
          kind: 'class',
          label: 'ApplicationRunner',
          file_path: 'src/Main.kt',
          start_line: 5,
          end_line: 7,
          text: 'Owns the application lifecycle.',
        }, {
          kind: 'method',
          label: 'start',
          file_path: 'src/Main.kt',
          start_line: 6,
          end_line: 6,
          text: 'Executes the lifecycle.',
        }],
        collaborator_component_ids: ['entrypoint'],
        relationship_ids: ['entrypoint-application'],
        uncertain: false,
      }, {
        id: 'expose-lifecycle',
        title: 'Expose lifecycle state',
        description: 'Keeps the validated lifecycle entry visible to the walkthrough.',
        evidence: [{
          kind: 'method',
          label: 'start',
          file_path: 'src/Main.kt',
          start_line: 6,
          end_line: 6,
          text: 'Exposes the application lifecycle operation.',
        }],
        collaborator_component_ids: [],
        relationship_ids: [],
        uncertain: false,
      }],
      key_paths: ['src/Main.kt'],
      key_symbols: ['ApplicationRunner', 'start'],
      evidence: [],
      uncertain: false,
    }, {
      id: 'entrypoint',
      name: 'Program entrypoint',
      kind: 'entrypoint',
      responsibility: 'Accept process startup and enter the application.',
      responsibilities: [],
      key_paths: ['src/Main.kt'],
      key_symbols: ['main'],
      evidence: [],
      uncertain: false,
    }],
    relationships: [{
      id: 'entrypoint-application',
      from_component_id: 'entrypoint',
      to_component_id: 'application',
      kind: 'calls',
      description: 'main constructs the runner and starts the application.',
      evidence: [{ kind: 'reference', label: 'start call', file_path: 'src/Main.kt', start_line: 2, end_line: 2 }],
      uncertain: false,
    }],
    cross_cutting_concerns: [],
    coverage_notes: [],
  },
  steps: [firstStep, secondStep],
  learning_path: [{
    id: 'start',
    title: 'Program flow',
    goal: 'Follow the entrypoint into application logic.',
    component_ids: ['entrypoint', 'application'],
    step_ids: ['entry', 'logic'],
    checkpoint: 'You can explain how control reaches start.',
  }],
  entry_step_id: 'entry',
  terminal_step_ids: ['logic'],
  edges: [edge],
};

const baseSession = {
  state: 'INPUT',
  repository: 'sample-repo',
  repository_path: '/tmp/sample-repo',
  mode: 'understand',
  provider: 'codex_cli',
  current_step_index: -1,
  displayed_step_index: -1,
  broken_step_ids: [],
  step_answer_loading: false,
  progress_lines: [],
};

test('maps, annotates, and advances through local source', async ({ page }) => {
  let session: Record<string, unknown> = baseSession;
  await page.addInitScript(() => {
    const listeners = new Map<string, Array<(event: { data: string }) => void>>();
    class LocalEventSource {
      constructor(_url: string) {}
      addEventListener(name: string, listener: (event: { data: string }) => void) {
        listeners.set(name, [...(listeners.get(name) ?? []), listener]);
      }
      close() {}
    }
    Object.defineProperty(window, 'EventSource', { value: LocalEventSource });
    Object.defineProperty(window, 'emitSession', {
      value: (snapshot: unknown) => listeners.get('session')
        ?.forEach((listener) => listener({ data: JSON.stringify(snapshot) })),
    });
  });

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/api/events') {
      await route.fulfill({ contentType: 'text/event-stream', body: '' });
      return;
    }
    if (path === '/api/session') {
      await route.fulfill({ json: session });
      return;
    }
    if (path === '/api/settings') {
      await route.fulfill({ json: { settings: settings } });
      return;
    }
    if (path === '/api/providers') {
      await route.fulfill({ json: [{ id: 'codex_cli', name: 'Codex CLI', available: true, message: 'Available' }] });
      return;
    }
    if (path === '/api/symbols') {
      await route.fulfill({ json: {
        tool: 'python_stdlib_ast',
        language: 'python',
        files_scanned: 1,
        symbol_count: 3,
        truncated: false,
        modules: [{
          p: 'src/Main.kt',
          i: [],
          c: [{
            n: 'ApplicationRunner',
            r: [5, 7],
            b: [],
            s: [],
            m: [{ n: 'start', r: [6, 6] }],
          }],
          f: [{ n: 'main', r: [1, 3] }],
        }],
      } });
      return;
    }
    if (path === '/api/source') {
      await route.fulfill({ json: { path: 'src/Main.kt', content: 'fun main() {\n    ApplicationRunner().start()\n}\n\nclass ApplicationRunner {\n    fun start() = Unit\n}\n' } });
      return;
    }
    if (path === '/api/mapping') {
      session = { ...baseSession, state: 'OVERVIEW', question: 'Learn this repository', flow_map: flow };
      await page.evaluate((snapshot) => {
        (window as Window & { emitSession?: (value: unknown) => void }).emitSession?.(snapshot);
      }, session);
      await route.fulfill({ json: { ...baseSession, state: 'LOADING', question: 'Learn this repository' } });
      return;
    }
    if (path === '/api/tour') {
      const action = (request.postDataJSON() as { action: string }).action;
      const step = action === 'next' ? secondStep : firstStep;
      const index = action === 'next' ? 1 : 0;
      session = {
        ...baseSession,
        state: action === 'preview' ? 'OVERVIEW' : 'TOUR_ACTIVE',
        question: 'Learn this repository',
        flow_map: flow,
        current_step_index: action === 'preview' ? -1 : index,
        displayed_step_index: index,
        displayed_step: step,
        next_step: index === 0 ? secondStep : undefined,
        next_edge: index === 0 ? edge : undefined,
      };
      await route.fulfill({ json: session });
      return;
    }
    await route.fulfill({ status: 404, json: { message: 'Not mocked' } });
  });

  await page.setViewportSize({ width: 2560, height: 1323 });
  await page.goto('/');
  const inputWorkspace = page.locator('.input-workspace');
  await expect.poll(async () => inputWorkspace.evaluate((element) => Math.round(element.getBoundingClientRect().width))).toBe(1800);
  await page.setViewportSize({ width: 800, height: 900 });
  await expect.poll(async () => page.locator('.app-shell').evaluate((element) => Math.round(element.getBoundingClientRect().width))).toBe(800);
  await expect.poll(() => page.getByLabel('Color theme').evaluate((element) =>
    element.getBoundingClientRect().right <= window.innerWidth,
  )).toBe(true);
  await page.getByLabel('Color theme').getByText('Dark', { exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-mantine-color-scheme', 'dark');
  await expect.poll(() => page.evaluate(() => localStorage.getItem('ai-code-walkthrough-color-scheme'))).toBe('dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-mantine-color-scheme', 'dark');
  await page.getByLabel('Color theme').getByText('Light', { exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-mantine-color-scheme', 'light');
  await page.getByRole('button', { name: 'Learn codebase' }).click();
  await expect(page.getByText('Walkthrough mapped')).toBeVisible();
  const codePanel = page.locator('#code');
  await expect(codePanel).toHaveCSS('width', '0px');
  await expect(codePanel).toHaveCSS('overflow', 'hidden');
  const diagramWorkspace = page.getByLabel('Architecture diagram workspace');
  const componentDetails = page.getByLabel('Component details');
  const [diagramBox, detailsBox] = await Promise.all([
    diagramWorkspace.boundingBox(),
    componentDetails.boundingBox(),
  ]);
  expect(diagramBox).not.toBeNull();
  expect(detailsBox).not.toBeNull();
  expect(diagramBox!.x).toBeLessThan(detailsBox!.x);
  await expect(page.getByText('Run an application from a local entrypoint.', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Class ownership')).toBeVisible();
  await page.getByLabel('Architecture depth').getByText('Component', { exact: true }).click();
  await expect(page.locator('.diagram-node[data-component-id]')).toHaveCount(2);
  await page.getByRole('button', { name: /Program entrypoint, Accept process startup/ }).click();
  await expect(page.locator('.diagram-node[data-component-id]')).toHaveCount(2);
  await page.getByRole('button', { name: /Application, Coordinate the application run/ }).click();
  const codeOwnership = page.getByLabel('Class ownership');
  await expect(codeOwnership.locator('.code-owner-row')).toHaveCount(1);
  await expect(codeOwnership.getByText('ApplicationRunner')).toBeVisible();
  await expect(codeOwnership.getByText('start()')).toBeVisible();
  await expect(codeOwnership.getByText('Run the application')).toBeVisible();
  await expect(codeOwnership.getByText('Expose lifecycle state')).toBeVisible();
  await expect(codeOwnership.getByText('Executes the lifecycle.')).toBeVisible();
  await expect(codeOwnership.locator('.behavior-lane, .behavior-node')).toHaveCount(0);
  await expect(page.getByRole('tab', { name: 'Code files' })).toHaveCount(0);
  await expect(codeOwnership.getByRole('button', { name: 'Show ApplicationRunner.start source' })).toBeVisible();
  await page.getByLabel('Architecture depth').getByText('Classes', { exact: true }).click();
  await expect(page.getByLabel('Class ownership architecture diagram')).toBeVisible();
  await expect(page.locator('.diagram-node').filter({ hasText: 'ApplicationRunner' })).toHaveCount(1);
  await expect(page.locator('.diagram-node').filter({ hasText: 'start()' })).toHaveCount(1);
  await page.setViewportSize({ width: 1024, height: 800 });
  await codeOwnership.getByRole('button', { name: 'Show ApplicationRunner.start source' }).click();
  await expect.poll(async () => codePanel.evaluate((element) => element.getBoundingClientRect().width)).toBeGreaterThan(0);
  const walkthroughControls = page.getByLabel('Walkthrough controls');
  await expect(walkthroughControls).toHaveClass(/compact/);
  const [stackedDiagramBox, stackedDetailsBox] = await Promise.all([
    diagramWorkspace.boundingBox(),
    componentDetails.boundingBox(),
  ]);
  expect(stackedDiagramBox).not.toBeNull();
  expect(stackedDetailsBox).not.toBeNull();
  expect(stackedDetailsBox!.y).toBeGreaterThan(stackedDiagramBox!.y);
  await expect.poll(() => walkthroughControls.evaluate(
    (element) => element.scrollWidth <= element.clientWidth,
  )).toBe(true);
  await expect(codePanel.getByText('L6–6')).toBeVisible();
  await page.getByRole('button', { name: 'Hide code pane' }).click();
  await expect(codePanel).toHaveCSS('width', '0px');
  await page.getByRole('button', { name: 'Show code pane' }).click();
  await expect.poll(async () => codePanel.evaluate((element) => element.getBoundingClientRect().width)).toBeGreaterThan(0);
  await page.getByRole('button', { name: 'Hide code pane' }).click();
  await expect(codePanel).toHaveCSS('width', '0px');

  await page.getByRole('button', { name: 'Preview selected' }).click();
  await expect(page.getByRole('button', { name: 'Hide code pane' })).toBeVisible();
  await expect.poll(async () => codePanel.evaluate((element) => element.getBoundingClientRect().width)).toBeGreaterThan(0);
  await expect(page.locator('.walkthrough-zone-annotation')).toContainText('crosses into application logic');
  await expect(page.locator('.walkthrough-next-line').first()).toBeVisible();

  await page.getByRole('button', { name: 'Start guided tour' }).click();
  await expect(page.getByText('Step 1/2')).toBeVisible();
  await page.getByRole('button', { name: 'Next ▶' }).click();
  await expect(page.getByText('Step 2/2')).toBeVisible();
  await expect(page.locator('.walkthrough-zone-annotation')).toContainText('reached the implementation');
});

const settings = {
  provider_id: 'codex_cli',
  codex_cli_path: 'codex',
  codex_model: 'gpt-5.6-sol',
  codex_reasoning_effort: 'ultra',
  claude_path: 'claude',
  claude_model: 'fable',
  claude_effort: 'high',
  max_steps: 20,
  default_mode_id: 'understand',
  enable_mcp: false,
  mcp_config_path: '',
};
