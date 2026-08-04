import { MantineProvider } from '@mantine/core';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { RightPane, type RightPaneActions } from './RightPane';
import type { SessionSnapshot } from './types';

test('starts the default whole-codebase walkthrough', async () => {
  const startMapping = vi.fn(async () => undefined);
  const showSample = vi.fn(async () => undefined);
  const noop = vi.fn(async () => undefined);
  const actions: RightPaneActions = {
    startMapping,
    showSample,
    cancelMapping: noop,
    tour: noop,
    answer: noop,
    loadSymbolInventory: async () => ({
      tool: 'python_stdlib_ast', language: 'python', files_scanned: 0, symbol_count: 0, truncated: false, modules: [],
    }),
    copyMarkdown: noop,
    openSettings: vi.fn(),
    focusCode: vi.fn(),
    previewEvidence: vi.fn(),
  };
  const session: SessionSnapshot = {
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

  render(
    <MantineProvider>
      <RightPane
        session={session}
        providers={[{ id: 'codex_cli', name: 'Codex CLI', available: true, message: 'Available' }]}
        actions={actions}
      />
    </MantineProvider>,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Learn codebase' }));
  await waitFor(() => expect(startMapping).toHaveBeenCalledWith('', 'understand', 'codex_cli'));
  fireEvent.click(screen.getByRole('button', { name: 'Preview sample result' }));
  await waitFor(() => expect(showSample).toHaveBeenCalledOnce());
});

test('explains component roles and links details to validated code', async () => {
  const tour = vi.fn(async () => undefined);
  const previewEvidence = vi.fn();
  const noop = vi.fn(async () => undefined);
  const actions: RightPaneActions = {
    startMapping: noop,
    showSample: noop,
    cancelMapping: noop,
    tour,
    answer: noop,
    loadSymbolInventory: async () => ({
      tool: 'python_stdlib_ast',
      language: 'python',
      files_scanned: 1,
      symbol_count: 4,
      truncated: false,
      modules: [{
        path: 'src/app.ts',
        imports: [],
        classes: [{
          name: 'ExperimentRunner',
          start_line: 5,
          end_line: 19,
          bases: [],
          state_fields: ['resultStore'],
          methods: [{ name: 'run', start_line: 6, end_line: 8 }],
        }],
        functions: [{ name: 'buildRunner', start_line: 21, end_line: 24 }],
      }],
    }),
    copyMarkdown: noop,
    openSettings: vi.fn(),
    focusCode: vi.fn(),
    previewEvidence,
  };
  const session: SessionSnapshot = {
    state: 'OVERVIEW',
    repository: 'sample-repo',
    repository_path: '/tmp/sample-repo',
    mode: 'understand',
    provider: 'codex_cli',
    current_step_index: -1,
    displayed_step_index: -1,
    broken_step_ids: [],
    step_answer_loading: false,
    progress_lines: [],
    flow_map: {
      summary: 'A small request flow.',
      steps: [{
        id: 'run',
        title: 'Run the application',
        file_path: 'src/app.ts',
        symbol: 'run',
        start_line: 5,
        end_line: 9,
        explanation: 'Runs the use case.',
        why_included: 'This is the application boundary.',
        uncertain: false,
        line_annotations: [],
        evidence: [],
      }],
      architecture: {
        system_purpose: 'Handle a request.',
        components: [{
          id: 'application',
          name: 'Experiment application',
          kind: 'application',
          responsibility: 'Coordinate the experiment workflow.',
          responsibilities: [{
            id: 'coordinate-run',
            title: 'Coordinate one experiment run',
            description: 'Sequences preparation, execution, and result recording.',
            evidence: [{
              kind: 'class',
              label: 'ExperimentRunner',
              file_path: 'src/app.ts',
              start_line: 5,
              end_line: 9,
              text: 'Owns the experiment lifecycle.',
            }, {
              kind: 'method',
              label: 'run',
              file_path: 'src/app.ts',
              start_line: 5,
              end_line: 8,
              text: 'Sequences the lifecycle operations.',
            }, {
              kind: 'state',
              label: 'resultStore',
              file_path: 'src/app.ts',
              start_line: 9,
              end_line: 9,
              text: 'Retains the result boundary used by the run.',
            }, {
              kind: 'class',
              label: 'PlanBuilder',
              file_path: 'src/app.ts',
              start_line: 20,
              end_line: 30,
              text: 'Builds the experiment plan.',
            }, {
              kind: 'method',
              label: 'buildPlan',
              file_path: 'src/app.ts',
              start_line: 22,
              end_line: 25,
              text: 'Builds one plan.',
            }],
            collaborator_component_ids: ['interface'],
            relationship_ids: ['interface-calls-application'],
            uncertain: false,
          }],
          key_paths: ['src/app.ts'],
          key_symbols: ['run'],
          evidence: [],
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
          evidence: [{ kind: 'callsite', label: 'run call', file_path: 'src/app.ts', start_line: 6, end_line: 6 }],
          uncertain: false,
        }],
        cross_cutting_concerns: ['All requests use the same audit trail.'],
        coverage_notes: ['Generated files were not inspected.'],
      },
      learning_path: [],
      terminal_step_ids: ['run'],
      edges: [],
    },
  };

  render(
    <MantineProvider>
      <RightPane session={session} providers={[]} actions={actions} />
    </MantineProvider>,
  );

  expect(screen.getByLabelText('Architecture depth')).toBeVisible();
  expect(screen.getByRole('button', { name: 'Zoom in' })).toBeVisible();
  expect(screen.getByLabelText('Architecture diagram workspace')).toBeVisible();
  expect(screen.getByLabelText('Component details')).toBeVisible();
  expect(screen.queryByText('What this system does')).not.toBeInTheDocument();
  expect(screen.queryByText('Handle a request.')).not.toBeInTheDocument();
  expect(screen.getAllByText('application workflow').length).toBeGreaterThan(0);
  const codeOwnership = screen.getByLabelText('Class ownership');
  expect(codeOwnership).toBeVisible();
  await waitFor(() => expect(within(codeOwnership).getByRole('button', { name: 'Show ExperimentRunner.run source' })).toBeVisible());
  const experimentRunner = screen.getByText('ExperimentRunner').closest('.code-owner-row');
  expect(experimentRunner).not.toBeNull();
  expect(within(experimentRunner as HTMLElement).getByText('run()')).toBeVisible();
  expect(within(experimentRunner as HTMLElement).getByText('Coordinate one experiment run')).toBeVisible();
  expect(within(experimentRunner as HTMLElement).getByText('Sequences preparation, execution, and result recording.')).toBeVisible();
  expect(within(experimentRunner as HTMLElement).getByText('Sequences the lifecycle operations.')).toBeVisible();
  expect(codeOwnership.querySelectorAll('.code-owner-row')).toHaveLength(2);
  expect(screen.queryByLabelText('Responsibility behavior map')).not.toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: 'Code files' })).not.toBeInTheDocument();
  const selectedComponent = screen.getByLabelText('Selected diagram component');
  expect(within(selectedComponent).getByRole('heading', { name: 'Experiment application' })).toBeVisible();
  expect(screen.queryByText('Selected component')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Choose component')).not.toBeInTheDocument();
  fireEvent.click(within(codeOwnership).getByRole('button', { name: 'Show ExperimentRunner.run source' }));
  expect(previewEvidence).toHaveBeenCalledWith(
    expect.objectContaining({ label: 'ExperimentRunner.run', start_line: 6 }),
    'Sequences the lifecycle operations.',
  );
  fireEvent.click(screen.getByRole('button', { name: 'Interfaces, 1 component' }));
  await waitFor(() => expect(within(selectedComponent).getByRole('heading', { name: 'Operator interfaces' })).toBeVisible());
  fireEvent.click(screen.getByRole('button', { name: 'Operator interfaces, Accept operator commands.' }));
  expect(screen.getByRole('radio', { name: 'Component' })).toBeChecked();

  expect(screen.getByRole('tab', { name: 'System notes' })).toBeVisible();
  expect(screen.getByText('Rules affecting multiple components')).not.toBeVisible();
  fireEvent.click(screen.getByRole('tab', { name: 'System notes' }));
  expect(screen.getByText('Rules affecting multiple components')).toBeVisible();
  expect(screen.getByText('Analysis boundaries')).toBeVisible();
  expect(screen.getByText('All requests use the same audit trail.')).toBeVisible();
  expect(screen.getByText('Generated files were not inspected.')).toBeVisible();
});
