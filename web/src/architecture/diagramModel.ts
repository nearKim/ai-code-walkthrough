import type { CodebaseArchitecture } from '../types';
import { humanize, toneForKind, toneForRelationships, type DiagramTone } from './taxonomy';

export interface DiagramNode {
  readonly id: string;
  readonly label: string;
  readonly detail: string;
  readonly description: string;
  readonly tone: DiagramTone;
  readonly componentId: string;
}

export interface DiagramEdge {
  readonly id: string;
  readonly from: string;
  readonly to: string;
  readonly label: string;
  readonly tone: DiagramTone;
  readonly uncertain?: boolean;
}

export interface ArchitectureDiagramModel {
  readonly rankDirection: 'LR' | 'TB';
  readonly nodes: ReadonlyArray<DiagramNode>;
  readonly edges: ReadonlyArray<DiagramEdge>;
}

/** Builds the one map used by the walkthrough: all components or a feature's components. */
export function createArchitectureDiagramModel(
  architecture: CodebaseArchitecture,
  focusedComponentIds?: ReadonlyArray<string>,
): ArchitectureDiagramModel {
  const knownIds = new Set(architecture.components.map((component) => component.id));
  const scope = focusedComponentIds === undefined
    ? undefined
    : new Set(focusedComponentIds.filter((id) => knownIds.has(id)));
  const components = architecture.components.filter((component) => scope === undefined || scope.has(component.id));
  const componentIds = new Set(components.map((component) => component.id));
  const relationships = architecture.relationships.filter((relationship) =>
    componentIds.has(relationship.from_component_id) && componentIds.has(relationship.to_component_id));

  return {
    rankDirection: components.length >= 6 ? 'TB' : 'LR',
    nodes: components.map((component) => ({
      id: component.id,
      label: component.name,
      detail: component.responsibility,
      description: component.responsibility,
      tone: toneForKind(component.kind),
      componentId: component.id,
    })),
    edges: relationships.map((relationship) => ({
      id: relationship.id,
      from: relationship.from_component_id,
      to: relationship.to_component_id,
      label: humanize(relationship.kind),
      tone: toneForRelationships(new Set([relationship.kind])),
      uncertain: relationship.uncertain,
    })),
  };
}
