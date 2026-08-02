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
  end_line: 5,
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
  steps: [firstStep, secondStep],
  learning_path: [{
    id: 'start',
    title: 'Program flow',
    goal: 'Follow the entrypoint into application logic.',
    component_ids: [],
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
    if (path === '/api/source') {
      await route.fulfill({ json: { path: 'src/Main.kt', content: 'fun main() {\n    start()\n}\n\nfun start() = Unit\n' } });
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

  await page.goto('/');
  await page.getByRole('button', { name: 'Learn codebase' }).click();
  await expect(page.getByText('Walkthrough mapped')).toBeVisible();

  await page.getByRole('button', { name: 'Preview selected' }).click();
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
