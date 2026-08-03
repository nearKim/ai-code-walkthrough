import { expect, test } from 'vitest';
import { createArchitectureDiagramModel } from './ArchitectureDiagram';
import type { CodebaseArchitecture } from './types';

const architecture: CodebaseArchitecture = {
  system_purpose: 'Process a request and store its result.',
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

test('builds stable architecture and responsibility diagrams', () => {
  const system = createArchitectureDiagramModel(architecture, 'system', 'application');
  expect(system.nodes.map((node) => node.label)).toEqual(['Interfaces', 'Application', 'Data boundaries', 'Shared contracts']);
  expect(system.edges).toHaveLength(2);

  const component = createArchitectureDiagramModel(architecture, 'component', 'application');
  expect(component.nodes.map((node) => node.label)).toEqual([
    'HTTP interface',
    'Application service',
    'Result store',
    'Shared contracts',
  ]);
  expect(component.edges.map((edge) => edge.label)).toEqual(['calls', 'writes']);
  expect(component.caption).toContain('All 4 components stay visible');

  const responsibilities = createArchitectureDiagramModel(architecture, 'responsibilities', 'application');
  expect(responsibilities.nodes.map((node) => node.label)).toEqual([
    'Application service',
    'Coordinate the request',
    'ApplicationService',
    'HTTP interface',
    'Result store',
  ]);
  expect(responsibilities.edges.map((edge) => edge.label)).toEqual(['', 'implemented by', 'calls', 'writes']);
  expect(responsibilities.edges.find((edge) => edge.label === 'calls')).toMatchObject({
    from: 'collaborator:interface',
    to: 'responsibility:coordinate',
  });
  expect(responsibilities.nodes.some((node) => node.label === 'run')).toBe(false);
});
