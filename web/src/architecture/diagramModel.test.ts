import { expect, test } from 'vitest';
import { availableArchitectureDepths, createArchitectureDiagramModel, runtimeCoverageGroups } from './diagramModel';
import type { CodebaseArchitecture } from '../types';

const architecture: CodebaseArchitecture = {
  system_name: 'Request processor',
  system_purpose: 'Process a request and store its result.',
  containers: [{
    id: 'cli',
    name: 'request-cli',
    kind: 'command_line_application',
    responsibility: 'Runs requests from a terminal.',
    component_ids: ['interface', 'application', 'data', 'shared'],
    evidence: [{
      kind: 'module',
      label: 'request entry',
      file_path: 'src/http.ts',
      start_line: 1,
      end_line: 10,
    }],
    uncertain: false,
  }, {
    id: 'mcp',
    name: 'request-mcp',
    kind: 'mcp_server',
    responsibility: 'Exposes requests to MCP clients.',
    component_ids: ['application', 'data', 'shared'],
    evidence: [],
    uncertain: false,
  }],
  components: [
    {
      id: 'interface',
      name: 'HTTP interface',
      kind: 'entrypoint',
      responsibility: 'Accept requests.',
      responsibilities: [],
      key_paths: ['src/http.ts'],
      key_symbols: ['handle'],
      evidence: [],
      uncertain: false,
    },
    {
      id: 'application',
      name: 'Application service',
      kind: 'application',
      responsibility: 'Run the use case.',
      responsibilities: [{
        id: 'coordinate',
        title: 'Coordinate the request',
        description: 'Runs the use case and records its result.',
        evidence: [{
          kind: 'class',
          label: 'ApplicationService',
          file_path: 'src/one.ts',
          start_line: 1,
          end_line: 20,
          text: 'Owns request coordination.',
        }, {
          kind: 'method',
          label: 'run',
          file_path: 'src/one.ts',
          start_line: 5,
          end_line: 10,
          text: 'Executes the use case.',
        }],
        collaborator_component_ids: ['interface', 'data'],
        relationship_ids: ['calls', 'writes'],
        uncertain: false,
      }],
      key_paths: ['src/one.ts', 'src/two.ts', 'src/three.ts', 'src/four.ts', 'src/five.ts'],
      key_symbols: ['run', 'load', 'save', 'publish', 'cleanup'],
      evidence: [],
      uncertain: false,
    },
    {
      id: 'data',
      name: 'Result store',
      kind: 'data',
      responsibility: 'Persist results.',
      responsibilities: [],
      key_paths: ['src/store.ts'],
      key_symbols: ['ResultStore'],
      evidence: [],
      uncertain: false,
    },
    {
      id: 'shared',
      name: 'Shared contracts',
      kind: 'shared',
      responsibility: 'Define stable shared values.',
      responsibilities: [],
      key_paths: ['src/contracts.ts'],
      key_symbols: ['Result'],
      evidence: [],
      uncertain: false,
    },
  ],
  relationships: [
    {
      id: 'calls',
      from_component_id: 'interface',
      to_component_id: 'application',
      kind: 'calls',
      description: 'The interface calls the application.',
      evidence: [],
      uncertain: false,
    },
    {
      id: 'writes',
      from_component_id: 'application',
      to_component_id: 'data',
      kind: 'writes',
      description: 'The application stores a result.',
      evidence: [],
      uncertain: false,
    },
  ],
  cross_cutting_concerns: [],
  coverage_notes: [],
};

test('builds context, runtime, component, and code diagrams', () => {
  expect(availableArchitectureDepths(architecture)).toEqual(['context', 'runtime', 'components', 'code']);
  const context = createArchitectureDiagramModel(architecture, 'context', 'application');
  expect(context.nodes.map((node) => node.label)).toEqual(['request-cli runtime', 'Shared runtime core']);
  expect(context.edges.map((edge) => edge.label)).toEqual(['calls']);

  const runtime = createArchitectureDiagramModel(architecture, 'runtime', 'application', 'cli');
  expect(runtime.nodes.map((node) => node.label)).toEqual([
    'request-cli',
    'HTTP interface',
    'Application service',
    'Result store',
    'Shared contracts',
  ]);
  expect(runtime.edges.map((edge) => edge.label)).toEqual(['starts', 'calls', 'writes']);

  const component = createArchitectureDiagramModel(architecture, 'components', 'application');
  expect(component.nodes.map((node) => node.label)).toEqual([
    'HTTP interface',
    'Application service',
    'Result store',
    'Shared contracts',
  ]);
  expect(component.edges.map((edge) => edge.label)).toEqual(['calls', 'writes']);
  expect(component.caption).toContain('All 4 components stay visible');
  expect(component.rankDirection).toBe('LR');

  const wide = createArchitectureDiagramModel({
    ...architecture,
    components: [
      ...architecture.components,
      { ...architecture.components[0], id: 'queue', name: 'Work queue' },
      { ...architecture.components[0], id: 'worker', name: 'Background worker' },
    ],
  }, 'components', 'application');
  expect(wide.rankDirection).toBe('TB');

  const code = createArchitectureDiagramModel(architecture, 'code', 'application');
  expect(code.nodes.map((node) => node.label)).toContain('src/one.ts');
  expect(code.nodes.map((node) => node.label)).toContain('ApplicationService');
  expect(code.nodes.map((node) => node.label)).toContain('run()');
  expect(code.nodes.find((node) => node.label === 'ApplicationService')).toMatchObject({
    detail: 'Coordinate the request',
  });
  expect(code.nodes.find((node) => node.label === 'run()')).toMatchObject({
    detail: 'Executes the use case.',
  });
});

test('keeps code outside declared entrypoint reachability visible in context', () => {
  const support = { ...architecture.components[0], id: 'support', name: 'Fixture loader' };
  const groups = runtimeCoverageGroups({ ...architecture, components: [...architecture.components, support] });

  expect(groups.find((group) => group.label === 'Outside declared runtimes')?.componentIds).toEqual(['support']);
});

test('does not collapse mechanically classified Python packages', () => {
  const python = createArchitectureDiagramModel({
    ...architecture,
    containers: [],
    components: architecture.components.slice(0, 2).map((component) => ({ ...component, kind: 'python_package' })),
    relationships: [architecture.relationships[0]],
  }, 'components', 'interface');

  expect(python.nodes.map((node) => node.label)).toEqual(['HTTP interface', 'Application service']);
  expect(python.edges).toHaveLength(1);
});

test('keeps only a section and its direct collaborators in the component map', () => {
  const focused = createArchitectureDiagramModel(architecture, 'components', 'application', undefined, ['application']);

  expect(focused.nodes.map((node) => node.label)).toEqual([
    'HTTP interface',
    'Application service',
    'Result store',
  ]);
  expect(focused.nodes.find((node) => node.componentId === 'interface')).toMatchObject({ boundary: true });
  expect(focused.nodes.find((node) => node.componentId === 'data')).toMatchObject({ boundary: true });
  expect(focused.nodes.find((node) => node.componentId === 'shared')).toBeUndefined();
  expect(focused.edges.map((edge) => edge.label)).toEqual(['calls', 'writes']);
});
