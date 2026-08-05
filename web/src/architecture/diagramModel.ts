import type { ArchitectureComponent, CodebaseArchitecture, EvidenceItem } from '../types';
import {
  architectureEvidenceKey,
  componentResponsibilities,
  evidenceBelongsToOwner,
  methodBehavior,
  methodLabel,
  responsibilityBelongsToOwner,
  responsibilityOwners,
  responsibilitySummary,
  uniqueEvidence,
} from './evidence';
import {
  humanize,
  toneForKind,
  toneForRelationships,
  type DiagramTone,
} from './taxonomy';

export type ArchitectureDepth = 'context' | 'containers' | 'components' | 'code';

export interface DiagramNode {
  readonly id: string;
  readonly label: string;
  readonly detail: string;
  readonly description: string;
  readonly tone: DiagramTone;
  readonly componentId?: string;
  readonly containerId?: string;
  readonly ownerKey?: string;
  readonly evidence?: EvidenceItem;
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

export function availableArchitectureDepths(architecture: CodebaseArchitecture): ReadonlyArray<ArchitectureDepth> {
  const containers = architecture.containers ?? [];
  return [
    ...(containers.length > 0 ? ['context' as const] : []),
    ...(containers.length > 1 ? ['containers' as const] : []),
    'components' as const,
    'code' as const,
  ];
}

export function createArchitectureDiagramModel(
  architecture: CodebaseArchitecture,
  level: ArchitectureDepth,
  selectedComponentId: string,
): ArchitectureDiagramModel {
  if (level === 'context') return createContextModel(architecture);
  if (level === 'containers') return createContainerModel(architecture);
  if (level === 'components') return createComponentModel(architecture, selectedComponentId);
  return createCodeModel(architecture, selectedComponentId);
}

function createContextModel(architecture: CodebaseArchitecture): ArchitectureDiagramModel {
  const containers = architecture.containers ?? [];
  const grouped = new Map<string, typeof containers>();
  containers.forEach((container) => grouped.set(container.kind, [...(grouped.get(container.kind) ?? []), container]));
  const systemId = 'context:system';
  const nodes: DiagramNode[] = [{
    id: systemId,
    label: architecture.system_name ?? 'Analyzed system',
    detail: architecture.system_purpose,
    description: architecture.system_purpose,
    tone: 'primary',
  }];
  const edges: DiagramEdge[] = [];
  [...grouped.entries()].sort(([left], [right]) => left.localeCompare(right)).forEach(([kind, entries]) => {
    const actorId = `context:actor:${kind}`;
    const entryNames = entries.map((entry) => entry.name).join(', ');
    nodes.push({
      id: actorId,
      label: actorLabel(kind),
      detail: entryNames,
      description: `Uses the system through ${entryNames}.`,
      tone: kind === 'mcp_server' ? 'dependency' : 'neutral',
    });
    edges.push({
      id: `${actorId}:${systemId}`,
      from: actorId,
      to: systemId,
      label: entryNames,
      tone: kind === 'mcp_server' ? 'dependency' : 'primary',
      uncertain: entries.some((entry) => entry.uncertain),
    });
  });
  return {
    level: 'context',
    rankDirection: 'LR',
    caption: `${architecture.system_name ?? 'The system'} and its verified entry surfaces.`,
    nodes,
    edges,
  };
}

function createContainerModel(architecture: CodebaseArchitecture): ArchitectureDiagramModel {
  const containers = architecture.containers ?? [];
  return {
    level: 'containers',
    rankDirection: 'LR',
    caption: `${containers.length} independently invokable runtime applications found in project metadata.`,
    nodes: containers.map((container) => ({
      id: container.id,
      label: container.name,
      detail: `${humanize(container.kind)} · ${container.component_ids.length} components`,
      description: container.responsibility,
      tone: container.kind === 'mcp_server' ? 'dependency' : 'primary',
      containerId: container.id,
    })),
    edges: [],
  };
}

function createComponentModel(
  architecture: CodebaseArchitecture,
  selectedComponentId: string,
): ArchitectureDiagramModel {
  const component = selectedComponent(architecture, selectedComponentId);
  if (component === undefined) {
    return { level: 'components', rankDirection: 'LR', caption: 'No grounded components.', nodes: [], edges: [] };
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
    level: 'components',
    rankDirection: architecture.components.length >= 6 ? 'TB' : 'LR',
    caption: `All ${architecture.components.length} components stay visible. ${directCount} ${directCount === 1 ? 'connection touches' : 'connections touch'} ${component.name}.`,
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

function createCodeModel(
  architecture: CodebaseArchitecture,
  selectedComponentId: string,
): ArchitectureDiagramModel {
  const component = selectedComponent(architecture, selectedComponentId);
  if (component === undefined) {
    return { level: 'code', rankDirection: 'LR', caption: 'No grounded code owners.', nodes: [], edges: [] };
  }
  const responsibilities = componentResponsibilities(component).slice(0, 8);
  const owners = uniqueEvidence(responsibilities.flatMap(responsibilityOwners));
  const files = new Map<string, EvidenceItem | undefined>();
  component.key_paths.forEach((path) => files.set(path, undefined));
  component.evidence.filter((item) => item.file_path !== undefined).forEach((item) => files.set(item.file_path!, item));
  owners.filter((item) => item.file_path !== undefined).forEach((item) => {
    if (!files.has(item.file_path!)) files.set(item.file_path!, undefined);
  });
  const nodes: DiagramNode[] = [{
    id: component.id,
    label: component.name,
    detail: component.responsibility,
    description: component.responsibility,
    tone: toneForKind(component.kind),
    componentId: component.id,
  }];
  const edges: DiagramEdge[] = [];

  files.forEach((fileEvidence, path) => {
    const fileNodeId = `file:${path}`;
    const ownerCount = owners.filter((owner) => owner.file_path === path).length;
    nodes.push({
      id: fileNodeId,
      label: shortPath(path),
      detail: `${ownerCount} ${ownerCount === 1 ? 'code owner' : 'code owners'}`,
      description: path,
      tone: 'data',
      evidence: fileEvidence,
    });
    edges.push({ id: `${component.id}:${fileNodeId}`, from: component.id, to: fileNodeId, label: '', tone: 'data' });
  });

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
      evidence: owner,
    });
    const parentId = owner.file_path === undefined ? component.id : `file:${owner.file_path}`;
    edges.push({
      id: `${parentId}:${ownerNodeId}`,
      from: parentId,
      to: ownerNodeId,
      label: '',
      tone: 'dependency',
      uncertain: ownedResponsibilities.some((responsibility) => responsibility.uncertain),
    });

    if (owner.kind !== 'class') return;
    const methods = uniqueEvidence(ownedResponsibilities.flatMap((responsibility) =>
      responsibility.evidence.filter((item) => item.kind === 'method' && evidenceBelongsToOwner(item, owner))));
    methods.forEach((method) => {
      const methodNodeId = `method:${architectureEvidenceKey(method)}`;
      const behavior = methodBehavior(method, ownedResponsibilities) ?? '';
      nodes.push({
        id: methodNodeId,
        label: methodLabel(method.label),
        detail: behavior,
        description: behavior || method.label,
        tone: 'neutral',
        evidence: method,
      });
      edges.push({ id: `${ownerNodeId}:${methodNodeId}`, from: ownerNodeId, to: methodNodeId, label: '', tone: 'neutral' });
    });
  });

  return {
    level: 'code',
    rankDirection: nodes.length >= 7 ? 'TB' : 'LR',
    caption: `${component.name} files and code owners. Showing ${responsibilities.length} responsibilities.`,
    nodes,
    edges,
  };
}

function selectedComponent(architecture: CodebaseArchitecture, selectedComponentId: string): ArchitectureComponent | undefined {
  return architecture.components.find((candidate) => candidate.id === selectedComponentId) ?? architecture.components[0];
}

function actorLabel(kind: string): string {
  if (kind === 'command_line_application') return 'Command-line users';
  if (kind === 'mcp_server') return 'MCP clients';
  return `${humanize(kind)} users`;
}

function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.slice(-2).join('/');
}
