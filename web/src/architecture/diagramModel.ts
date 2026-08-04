import type { ArchitectureComponent, CodebaseArchitecture } from '../types';
import {
  architectureEvidenceKey,
  componentResponsibilities,
  methodLabel,
  responsibilityOwners,
  responsibilitySummary,
  uniqueEvidence,
  evidenceBelongsToOwner,
  methodBehavior,
  responsibilityBelongsToOwner,
} from './evidence';
import {
  humanize,
  kindOrder,
  titleForKind,
  toneForKind,
  toneForRelationship,
  toneForRelationships,
  type DiagramTone,
} from './taxonomy';

export type ArchitectureDepth = 'system' | 'component' | 'responsibilities';

export interface DiagramNode {
  readonly id: string;
  readonly label: string;
  readonly detail: string;
  readonly description: string;
  readonly tone: DiagramTone;
  readonly componentId?: string;
  readonly componentKind?: string;
  readonly ownerKey?: string;
}

export interface DiagramEdge {
  readonly id: string;
  readonly from: string;
  readonly to: string;
  readonly label: string;
  readonly tone: DiagramTone;
  readonly uncertain?: boolean;
  readonly muted?: boolean;
}

export interface ArchitectureDiagramModel {
  readonly level: ArchitectureDepth;
  readonly rankDirection: 'LR' | 'TB';
  readonly caption: string;
  readonly nodes: ReadonlyArray<DiagramNode>;
  readonly edges: ReadonlyArray<DiagramEdge>;
}

export function createArchitectureDiagramModel(
  architecture: CodebaseArchitecture,
  level: ArchitectureDepth,
  selectedComponentId: string,
): ArchitectureDiagramModel {
  if (level === 'system') return createSystemModel(architecture);
  if (level === 'component') return createComponentModel(architecture, selectedComponentId);
  return createResponsibilityModel(architecture, selectedComponentId);
}

function createSystemModel(architecture: CodebaseArchitecture): ArchitectureDiagramModel {
  const groups = new Map<string, ArchitectureComponent[]>();
  for (const component of architecture.components) {
    groups.set(component.kind, [...(groups.get(component.kind) ?? []), component]);
  }
  if (groups.size === 1) {
    return {
      level: 'system',
      rankDirection: architecture.components.length >= 6 ? 'TB' : 'LR',
      caption: 'Verified Python packages and their import edges.',
      nodes: architecture.components.map((component) => ({
        id: component.id,
        label: component.name,
        detail: component.responsibility,
        description: component.responsibility,
        tone: 'neutral',
        componentId: component.id,
      })),
      edges: architecture.relationships.map((relationship) => ({
        id: `system:${relationship.id}`,
        from: relationship.from_component_id,
        to: relationship.to_component_id,
        label: humanize(relationship.kind),
        tone: toneForRelationship(relationship.kind),
        uncertain: relationship.uncertain,
      })),
    };
  }
  const kinds = [...groups.keys()].sort((left, right) => {
    const leftIndex = kindOrder.indexOf(left);
    const rightIndex = kindOrder.indexOf(right);
    if (leftIndex === -1 && rightIndex === -1) return left.localeCompare(right);
    if (leftIndex === -1) return 1;
    if (rightIndex === -1) return -1;
    return leftIndex - rightIndex;
  });
  const componentsById = new Map(architecture.components.map((component) => [component.id, component]));
  const aggregated = new Map<string, { from: string; to: string; kinds: Set<string>; count: number; uncertain: boolean }>();
  for (const relationship of architecture.relationships) {
    const from = componentsById.get(relationship.from_component_id)?.kind;
    const to = componentsById.get(relationship.to_component_id)?.kind;
    if (from === undefined || to === undefined || from === to) continue;
    const key = `${from}->${to}`;
    const current = aggregated.get(key) ?? { from, to, kinds: new Set<string>(), count: 0, uncertain: false };
    current.kinds.add(relationship.kind);
    current.count += 1;
    current.uncertain ||= relationship.uncertain;
    aggregated.set(key, current);
  }

  return {
    level: 'system',
    rankDirection: 'TB',
    caption: 'Select a responsibility band to inspect its components.',
    nodes: kinds.map((kind) => {
      const components = groups.get(kind) ?? [];
      return {
        id: `kind:${kind}`,
        label: titleForKind(kind),
        detail: `${components.length} ${components.length === 1 ? 'component' : 'components'}`,
        description: components.map((component) => component.name).join(', '),
        tone: toneForKind(kind),
        componentKind: kind,
      };
    }),
    edges: [...aggregated.entries()].map(([id, edge]) => ({
      id: `system:${id}`,
      from: `kind:${edge.from}`,
      to: `kind:${edge.to}`,
      label: edge.count === 1 ? humanize([...edge.kinds][0] ?? 'relates') : `${edge.count} links`,
      tone: toneForRelationships(edge.kinds),
      uncertain: edge.uncertain,
    })),
  };
}

function createComponentModel(
  architecture: CodebaseArchitecture,
  selectedComponentId: string,
): ArchitectureDiagramModel {
  const component = architecture.components.find((candidate) => candidate.id === selectedComponentId)
    ?? architecture.components[0];
  if (component === undefined) {
    return { level: 'component', rankDirection: 'LR', caption: 'No grounded components.', nodes: [], edges: [] };
  }
  const aggregated = new Map<string, {
    from: string;
    to: string;
    kinds: Set<string>;
    uncertain: boolean;
  }>();
  for (const relationship of architecture.relationships) {
    const key = `${relationship.from_component_id}->${relationship.to_component_id}`;
    const current = aggregated.get(key) ?? {
      from: relationship.from_component_id,
      to: relationship.to_component_id,
      kinds: new Set<string>(),
      uncertain: false,
    };
    current.kinds.add(relationship.kind);
    current.uncertain ||= relationship.uncertain;
    aggregated.set(key, current);
  }
  const directCount = architecture.relationships.filter((relationship) =>
    relationship.from_component_id === component.id || relationship.to_component_id === component.id).length;

  return {
    level: 'component',
    rankDirection: architecture.components.length >= 6 ? 'TB' : 'LR',
    caption: `All ${architecture.components.length} components stay visible. ${directCount} ${directCount === 1 ? 'connection touches' : 'connections touch'} ${component.name}; select any component to inspect it.`,
    nodes: architecture.components.map((candidate) => ({
      id: candidate.id,
      label: candidate.name,
      detail: candidate.responsibility,
      description: candidate.responsibility,
      tone: toneForKind(candidate.kind),
      componentId: candidate.id,
    })),
    edges: [...aggregated.entries()].map(([id, relationship]) => ({
      id: `component:${id}`,
      from: relationship.from,
      to: relationship.to,
      label: [...relationship.kinds].map(humanize).join(' / '),
      tone: toneForRelationships(relationship.kinds),
      uncertain: relationship.uncertain,
      muted: relationship.from !== component.id && relationship.to !== component.id,
    })),
  };
}

function createResponsibilityModel(
  architecture: CodebaseArchitecture,
  selectedComponentId: string,
): ArchitectureDiagramModel {
  const component = architecture.components.find((candidate) => candidate.id === selectedComponentId)
    ?? architecture.components[0];
  if (component === undefined) {
    return { level: 'responsibilities', rankDirection: 'LR', caption: 'No grounded responsibilities.', nodes: [], edges: [] };
  }
  const responsibilities = componentResponsibilities(component).slice(0, 5);
  const nodes: DiagramNode[] = [{
    id: component.id,
    label: component.name,
    detail: component.responsibility,
    description: component.responsibility,
    tone: toneForKind(component.kind),
    componentId: component.id,
  }];
  const edges: DiagramEdge[] = [];
  const owners = uniqueEvidence(responsibilities.flatMap(responsibilityOwners));

  owners.forEach((owner) => {
    const ownedResponsibilities = responsibilities.filter((responsibility) =>
      responsibilityBelongsToOwner(responsibility, owner));
    const ownerNodeId = `owner:${architectureEvidenceKey(owner)}`;
    nodes.push({
      id: ownerNodeId,
      label: owner.label,
      detail: responsibilitySummary(ownedResponsibilities) || owner.text || humanize(owner.kind),
      description: owner.text ?? responsibilitySummary(ownedResponsibilities) ?? owner.label,
      tone: 'dependency',
      ownerKey: architectureEvidenceKey(owner),
    });
    edges.push({
      id: `${component.id}:${ownerNodeId}`,
      from: component.id,
      to: ownerNodeId,
      label: '',
      tone: 'dependency',
      uncertain: ownedResponsibilities.some((responsibility) => responsibility.uncertain),
    });

    if (owner.kind !== 'class') return;
    const methods = uniqueEvidence(ownedResponsibilities.flatMap((responsibility) =>
      responsibility.evidence.filter((evidence) => evidence.kind === 'method' && evidenceBelongsToOwner(evidence, owner))));
    methods.forEach((method) => {
      const methodNodeId = `method:${architectureEvidenceKey(method)}`;
      const behavior = methodBehavior(method, ownedResponsibilities) ?? '';
      nodes.push({
        id: methodNodeId,
        label: methodLabel(method.label),
        detail: behavior,
        description: behavior || method.label,
        tone: 'neutral',
      });
      edges.push({
        id: `${ownerNodeId}:${methodNodeId}`,
        from: ownerNodeId,
        to: methodNodeId,
        label: '',
        tone: 'neutral',
      });
    });
  });

  return {
    level: 'responsibilities',
    rankDirection: nodes.length >= 7 ? 'TB' : 'LR',
    caption: `${component.name} class ownership.`,
    nodes,
    edges,
  };
}
