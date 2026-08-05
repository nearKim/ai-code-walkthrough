import { MantineProvider } from '@mantine/core';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { RightPane, type RightPaneActions } from './RightPane';
import type { SessionSnapshot } from './types';

test('starts a whole-codebase walkthrough from the minimal input', async () => {
  const startMapping = vi.fn(async () => undefined);
  const showSample = vi.fn(async () => undefined);
  const actions = testActions({ startMapping, showSample });

  render(<MantineProvider><RightPane
    session={inputSession()}
    providers={[{ id: 'codex_cli', name: 'Codex CLI', available: true, message: 'Available' }]}
    actions={actions}
  /></MantineProvider>);

  fireEvent.click(screen.getByRole('button', { name: 'Learn' }));
  await waitFor(() => expect(startMapping).toHaveBeenCalledWith('', 'understand', 'codex_cli'));
  fireEvent.click(screen.getByRole('button', { name: 'Walkthrough options' }));
  fireEvent.click(await screen.findByRole('menuitem', { name: 'Preview sample' }));
  await waitFor(() => expect(showSample).toHaveBeenCalledOnce());
});

test('narrows the map to a feature and starts its scoped walkthrough', () => {
  const tour = vi.fn(async () => undefined);
  const previewEvidence = vi.fn();
  const actions = testActions({ tour, previewEvidence });

  render(<MantineProvider><RightPane
    session={overviewSession()}
    providers={[]}
    actions={actions}
  /></MantineProvider>);

  expect(screen.getByLabelText('Architecture map')).toBeVisible();
  expect(screen.getByRole('button', { name: 'Application flow' })).toHaveAttribute('aria-pressed', 'true');
  expect(document.getElementById('architecture-node-interface')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Walk' }));
  expect(tour).toHaveBeenCalledWith('start_section', undefined, 'application-flow');

  fireEvent.click(screen.getByRole('button', { name: 'All code' }));
  expect(document.getElementById('architecture-node-interface')).not.toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Show ExperimentRunner source' }));
  expect(previewEvidence).toHaveBeenCalledWith(
    expect.objectContaining({ label: 'ExperimentRunner', start_line: 5 }),
    'Owns the experiment lifecycle.',
  );
});

function testActions(overrides: Partial<RightPaneActions> = {}): RightPaneActions {
  const noop = vi.fn(async () => undefined);
  return {
    startMapping: noop,
    showSample: noop,
    cancelMapping: noop,
    tour: noop,
    answer: noop,
    copyMarkdown: noop,
    downloadTechnicalReference: noop,
    openSettings: vi.fn(),
    previewEvidence: vi.fn(),
    ...overrides,
  };
}

function inputSession(): SessionSnapshot {
  return {
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
}

function overviewSession(): SessionSnapshot {
  return {
    ...inputSession(),
    state: 'OVERVIEW',
    flow_map: {
      summary: 'A small request flow.',
      steps: [{
        id: 'run',
        title: 'Run the application',
        file_path: 'src/app.ts',
        start_line: 5,
        end_line: 9,
        explanation: 'Runs the use case.',
        why_included: 'This is the application boundary.',
        uncertain: false,
        line_annotations: [],
        evidence: [],
      }],
      architecture: {
        system_name: 'Experiment system',
        system_purpose: 'Handle a request.',
        components: [{
          id: 'application',
          name: 'Experiment application',
          kind: 'application',
          responsibility: 'Coordinate the experiment workflow.',
          responsibilities: [],
          key_paths: ['src/app.ts'],
          key_symbols: ['run'],
          evidence: [{
            kind: 'class',
            label: 'ExperimentRunner',
            file_path: 'src/app.ts',
            start_line: 5,
            end_line: 9,
            text: 'Owns the experiment lifecycle.',
          }],
          uncertain: false,
        }, {
          id: 'interface',
          name: 'Operator interfaces',
          kind: 'entrypoint',
          responsibility: 'Accept operator commands.',
          responsibilities: [],
          key_paths: ['src/cli.ts'],
          key_symbols: ['main'],
          evidence: [],
          uncertain: false,
        }],
        relationships: [{
          id: 'interface-calls-application',
          from_component_id: 'interface',
          to_component_id: 'application',
          kind: 'calls',
          description: 'The CLI starts the experiment workflow.',
          evidence: [],
          uncertain: false,
        }],
        cross_cutting_concerns: [],
        coverage_notes: [],
      },
      learning_path: [],
      diagram_sections: [{
        id: 'application-flow',
        title: 'Application flow',
        component_ids: ['application'],
        step_ids: ['run'],
      }],
      terminal_step_ids: ['run'],
      edges: [],
    },
    active_section_id: 'application-flow',
  };
}
