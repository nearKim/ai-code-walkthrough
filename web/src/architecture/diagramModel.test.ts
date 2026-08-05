import { expect, test } from 'vitest';
import { createArchitectureDiagramModel } from './diagramModel';
import type { CodebaseArchitecture } from '../types';

const architecture: CodebaseArchitecture = {
  system_name: 'Request processor',
  system_purpose: 'Process a request and store its result.',
  components: [
    component('interface', 'HTTP interface', 'entrypoint'),
    component('application', 'Application service', 'application'),
    component('data', 'Result store', 'data'),
    component('shared', 'Shared contracts', 'shared'),
  ],
  relationships: [
    relationship('calls', 'interface', 'application', 'calls'),
    relationship('writes', 'application', 'data', 'writes'),
  ],
  cross_cutting_concerns: [],
  coverage_notes: [],
};

test('builds one whole-system component map', () => {
  const map = createArchitectureDiagramModel(architecture);

  expect(map.nodes.map((node) => node.id)).toEqual(['interface', 'application', 'data', 'shared']);
  expect(map.edges.map((edge) => edge.id)).toEqual(['calls', 'writes']);
  expect(map.rankDirection).toBe('LR');
});

test('feature scopes visibly narrow the map and keep only internal edges', () => {
  const wholeSystem = createArchitectureDiagramModel(architecture);
  const feature = createArchitectureDiagramModel(architecture, ['application', 'data']);

  expect(feature.nodes.map((node) => node.id)).toEqual(['application', 'data']);
  expect(feature.edges.map((edge) => edge.id)).toEqual(['writes']);
  expect(feature.nodes.map((node) => node.id)).not.toEqual(wholeSystem.nodes.map((node) => node.id));
});

test('uses a vertical layout for large maps', () => {
  const map = createArchitectureDiagramModel({
    ...architecture,
    components: [
      ...architecture.components,
      component('queue', 'Work queue', 'shared'),
      component('worker', 'Background worker', 'application'),
    ],
  });

  expect(map.rankDirection).toBe('TB');
});

function component(id: string, name: string, kind: string) {
  return {
    id,
    name,
    kind,
    responsibility: `${name} responsibility.`,
    responsibilities: [],
    key_paths: [],
    key_symbols: [],
    evidence: [],
    uncertain: false,
  };
}

function relationship(id: string, from: string, to: string, kind: string) {
  return {
    id,
    from_component_id: from,
    to_component_id: to,
    kind,
    description: '',
    evidence: [],
    uncertain: false,
  };
}
