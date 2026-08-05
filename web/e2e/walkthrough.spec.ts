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
    system_name: 'Sample application',
    system_purpose: 'Run an application from a local entrypoint.',
    components: [{
      id: 'application',
      name: 'Application',
      kind: 'application',
      responsibility: 'Coordinate the application run.',
      responsibilities: [],
      key_paths: ['src/Main.kt'],
      key_symbols: ['ApplicationRunner', 'start'],
      evidence: [{ kind: 'class', label: 'ApplicationRunner', file_path: 'src/Main.kt', start_line: 5, end_line: 7 }],
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
      description: 'main starts the application.',
      evidence: [],
      uncertain: false,
    }],
    cross_cutting_concerns: [],
    coverage_notes: [],
  },
  steps: [firstStep, secondStep],
  learning_path: [{
    id: 'orientation',
    title: 'Orientation',
    goal: 'Find the executable entrypoint.',
    component_ids: ['entrypoint'],
    step_ids: ['entry'],
  }, {
    id: 'application-logic',
    title: 'Application logic',
    goal: 'Follow the application work.',
    component_ids: ['application'],
    step_ids: ['logic'],
  }],
  diagram_sections: [{
    id: 'authentication',
    title: 'Authentication',
    component_ids: ['application'],
    step_ids: ['entry'],
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

test('maps a feature, walks it, and keeps the source visible', async ({ page }) => {
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
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/events') return route.fulfill({ contentType: 'text/event-stream', body: '' });
    if (path === '/api/session') return route.fulfill({ json: session });
    if (path === '/api/settings') return route.fulfill({ json: { settings } });
    if (path === '/api/providers') return route.fulfill({ json: [{ id: 'codex_cli', name: 'Codex CLI', available: true, message: 'Available' }] });
    if (path === '/api/source') {
      return route.fulfill({ json: { path: 'src/Main.kt', content: 'fun main() {\n    ApplicationRunner().start()\n}\n\nclass ApplicationRunner {\n    fun start() = Unit\n}\n' } });
    }
    if (path === '/api/mapping') {
      session = { ...baseSession, state: 'OVERVIEW', question: '', flow_map: flow };
      await page.evaluate((snapshot) => {
        (window as Window & { emitSession?: (value: unknown) => void }).emitSession?.(snapshot);
      }, session);
      return route.fulfill({ json: { ...baseSession, state: 'LOADING', question: '' } });
    }
    if (path === '/api/tour') {
      const { action, section_id: sectionId } = route.request().postDataJSON() as { action: string; section_id?: string };
      if (action === 'start_section') {
        expect(sectionId).toBe('authentication');
        session = {
          ...baseSession,
          state: 'TOUR_ACTIVE',
          flow_map: flow,
          active_section_id: sectionId,
          current_step_index: 0,
          displayed_step_index: 0,
          displayed_step: firstStep,
          next_step: secondStep,
          next_edge: edge,
          completed_step_ids: [],
        };
      } else if (action === 'next') {
        session = {
          ...baseSession,
          state: 'TOUR_ACTIVE',
          flow_map: flow,
          active_section_id: 'authentication',
          current_step_index: 1,
          displayed_step_index: 1,
          displayed_step: secondStep,
          completed_step_ids: ['entry'],
        };
      } else if (action === 'stop') {
        session = { ...baseSession, state: 'OVERVIEW', flow_map: flow, completed_step_ids: ['entry'] };
      } else if (action === 'new') {
        session = { ...baseSession, state: 'INPUT', flow_map: flow };
      }
      return route.fulfill({ json: session });
    }
    return route.fulfill({ status: 404, json: { message: 'Not mocked' } });
  });

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/');
  await page.getByRole('button', { name: 'Learn' }).click();
  await expect(page.getByRole('heading', { name: 'Sample application' })).toBeVisible();
  await expect(page.getByLabel('Architecture map')).toBeVisible();
  await expect(page.locator('.diagram-node[data-component-id]')).toHaveCount(2);

  await page.getByRole('button', { name: 'Authentication' }).click();
  await expect(page.locator('.diagram-node[data-component-id]')).toHaveCount(1);
  await page.getByRole('button', { name: 'Walk', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Enter the program' })).toBeVisible();
  await expect(page.locator('.walkthrough-zone-annotation')).toContainText('crosses into application logic');
  await expect(page.locator('#code')).not.toHaveCSS('width', '0px');

  await page.setViewportSize({ width: 390, height: 844 });
  const [sourceBox, controlsBox] = await Promise.all([
    page.locator('#code').boundingBox(),
    page.getByLabel('Walkthrough controls').boundingBox(),
  ]);
  expect(sourceBox).not.toBeNull();
  expect(controlsBox).not.toBeNull();
  expect(sourceBox!.y).toBeLessThan(controlsBox!.y);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);

  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page.getByRole('heading', { name: 'Run application logic' })).toBeVisible();
  await expect(page.getByRole('progressbar', { name: 'Learning progress: 1 of 2 code stops' }))
    .toHaveAttribute('aria-valuenow', '1');
  await expect(page.locator('.walkthrough-zone-annotation')).toContainText('reached the implementation');
  await page.getByRole('button', { name: 'Back to map' }).click();
  await expect(page.getByRole('heading', { name: 'Sample application' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Orientation: 1 of 1 code stops complete' })).toBeVisible();

  await expect(page.locator('.architecture-diagram-frame')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await page.getByRole('button', { name: 'More walkthrough actions' }).click();
  await page.getByRole('menuitem', { name: 'New walkthrough' }).click();
  await expect(page.getByRole('heading', { name: 'Understand the codebase' })).toBeVisible();
  await expect.poll(() => page.locator('#code').evaluate((element) => element.getBoundingClientRect().height <= 1)).toBe(true);
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
