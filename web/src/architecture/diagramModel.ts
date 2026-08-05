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

export type ArchitectureDepth = 'context' | 'runtime' | 'components' | 'code';

export interface RuntimeCoverageGroup {
  readonly id: string;
  readonly label: string;
  readonly detail: string;
  readonly description: string;
  readonly componentIds: ReadonlyArray<string>;
  readonly runtimeIds: ReadonlyArray<string>;
  readonly tone: DiagramTone;
}

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
  readonly boundary?: boolean;
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
    'context' as const,
    ...(containers.length > 0 ? ['runtime' as const] : []),
    'components' as const,
    'code' as const,
  ];
}

export function createArchitectureDiagramModel(
  architecture: CodebaseArchitecture,
  level: ArchitectureDepth,
  selectedComponentId: string,
  selectedRuntimeId?: string,
  focusedComponentIds?: ReadonlyArray<string>,
): ArchitectureDiagramModel {
  const focus = componentFocus(architecture, focusedComponentIds);
  if (level === 'context') return createContextModel(architecture, focus);
  if (level === 'runtime') return createRuntimeModel(architecture, selectedRuntimeId, focus);
  if (level === 'components') return createComponentModel(architecture, selectedComponentId, focus);
  return createCodeModel(architecture, selectedComponentId);
}

interface ComponentFocus {
  readonly selectedIds: ReadonlySet<string>;
  readonly visibleIds: ReadonlySet<string>;
  readonly boundaryIds: ReadonlySet<string>;
}

function componentFocus(
  architecture: CodebaseArchitecture,
  focusedComponentIds?: ReadonlyArray<string>,
): ComponentFocus | undefined {
  if (focusedComponentIds === undefined || focusedComponentIds.length === 0) return undefined;
  const knownIds = new Set(architecture.components.map((component) => component.id));
  const selectedIds = new Set(focusedComponentIds.filter((id) => knownIds.has(id)));
  if (selectedIds.size === 0) return undefined;
  const visibleIds = new Set(selectedIds);
  for (const relationship of architecture.relationships) {
    if (selectedIds.has(relationship.from_component_id)) visibleIds.add(relationship.to_component_id);
    if (selectedIds.has(relationship.to_component_id)) visibleIds.add(relationship.from_component_id);
  }
  return {
    selectedIds,
    visibleIds,
    boundaryIds: new Set([...visibleIds].filter((id) => !selectedIds.has(id))),
  };
}

function createContextModel(
  architecture: CodebaseArchitecture,
  focus?: ComponentFocus,
): ArchitectureDiagramModel {
  const groups = runtimeCoverageGroups(architecture, focus?.visibleIds);
  const groupByComponent = new Map(groups.flatMap((group) =>
    group.componentIds.map((componentId) => [componentId, group.id] as const)));
  const aggregated = new Map<string, { from: string; to: string; kinds: Set<string>; uncertain: boolean }>();
  architecture.relationships.forEach((relationship) => {
    if (focus !== undefined && (!focus.visibleIds.has(relationship.from_component_id) || !focus.visibleIds.has(relationship.to_component_id))) return;
    const from = groupByComponent.get(relationship.from_component_id);
    const to = groupByComponent.get(relationship.to_component_id);
    if (from === undefined || to === undefined || from === to) return;
    const id = `${from}->${to}`;
    const current = aggregated.get(id) ?? { from, to, kinds: new Set<string>(), uncertain: false };
    current.kinds.add(relationship.kind);
    current.uncertain ||= relationship.uncertain;
    aggregated.set(id, current);
  });
  return {
    level: 'context',
    rankDirection: 'LR',
    caption: focus === undefined
      ? `${architecture.components.length} components grouped by verified runtime reachability.`
      : focusedCaption(focus),
    nodes: groups.map((group) => ({
      id: group.id,
      label: group.label,
      detail: group.detail,
      description: group.description,
      tone: group.tone,
    })),
    edges: [...aggregated.entries()].map(([id, relationship]) => ({
      id: `context:${id}`,
      from: relationship.from,
      to: relationship.to,
      label: [...relationship.kinds].map(humanize).join(' / '),
      tone: toneForRelationships(relationship.kinds),
      uncertain: relationship.uncertain,
    })),
  };
}

function createRuntimeModel(
  architecture: CodebaseArchitecture,
  selectedRuntimeId?: string,
  focus?: ComponentFocus,
): ArchitectureDiagramModel {
  const runtime = architecture.containers?.find((candidate) => candidate.id === selectedRuntimeId)
    ?? architecture.containers?.[0];
  if (runtime === undefined) {
    return { level: 'runtime', rankDirection: 'LR', caption: 'No verified runtime entrypoints.', nodes: [], edges: [] };
  }
  const componentIds = new Set(runtime.component_ids);
  const components = architecture.components.filter((component) =>
    componentIds.has(component.id) && (focus === undefined || focus.visibleIds.has(component.id)));
  const entryPath = runtime.evidence.find((item) => item.kind === 'module')?.file_path;
  const entryComponent = entryPath === undefined
    ? undefined
    : components.find((component) => component.key_paths.includes(entryPath));
  const runtimeNodeId = `runtime:${runtime.id}`;
  const relationships = architecture.relationships.filter((relationship) =>
    componentIds.has(relationship.from_component_id) && componentIds.has(relationship.to_component_id) &&
    (focus === undefined || (focus.visibleIds.has(relationship.from_component_id) && focus.visibleIds.has(relationship.to_component_id))));
  return {
    level: 'runtime',
    rankDirection: 'LR',
    caption: focus === undefined
      ? `${runtime.name} reaches ${components.length} components through ${relationships.length} verified package dependencies.`
      : `${runtime.name}: ${focusedCaption(focus)}`,
    nodes: [{
      id: runtimeNodeId,
      label: runtime.name,
      detail: humanize(runtime.kind),
      description: runtime.responsibility,
      tone: runtime.kind === 'mcp_server' ? 'dependency' : 'primary',
      containerId: runtime.id,
    }, ...components.map((component) => ({
      id: component.id,
      label: component.name,
      detail: component.responsibility,
      description: component.responsibility,
      tone: toneForKind(component.kind),
      componentId: component.id,
      boundary: focus?.boundaryIds.has(component.id),
    }))],
    edges: [
      ...(entryComponent === undefined ? [] : [{
        id: `${runtimeNodeId}:${entryComponent.id}`,
        from: runtimeNodeId,
        to: entryComponent.id,
        label: 'starts',
        tone: 'primary' as const,
      }]),
      ...relationships.map((relationship) => ({
        id: `runtime:${relationship.id}`,
        from: relationship.from_component_id,
        to: relationship.to_component_id,
        label: humanize(relationship.kind),
        tone: toneForRelationships(new Set([relationship.kind])),
        uncertain: relationship.uncertain,
      })),
    ],
  };
}

export function runtimeCoverageGroups(
  architecture: CodebaseArchitecture,
  componentIds?: ReadonlySet<string>,
): ReadonlyArray<RuntimeCoverageGroup> {
  const runtimes = architecture.containers ?? [];
  const grouped = new Map<string, ArchitectureComponent[]>();
  architecture.components.filter((component) => componentIds === undefined || componentIds.has(component.id)).forEach((component) => {
    const runtimeIds = runtimes
      .filter((runtime) => runtime.component_ids.includes(component.id))
      .map((runtime) => runtime.id)
      .sort();
    const key = runtimeIds.join('|');
    grouped.set(key, [...(grouped.get(key) ?? []), component]);
  });
  return [...grouped.entries()].map<RuntimeCoverageGroup>(([key, components]) => {
    const runtimeIds = key.length === 0 ? [] : key.split('|');
    const matchingRuntimes = runtimes.filter((runtime) => runtimeIds.includes(runtime.id));
    const label = runtimeIds.length === 0
      ? 'Outside declared runtimes'
      : runtimeIds.length === runtimes.length && runtimes.length > 1
        ? 'Shared runtime core'
        : `${matchingRuntimes.map((runtime) => runtime.name).join(' + ')} runtime`;
    return {
      id: `context:${key || 'unreached'}`,
      label,
      detail: `${components.length} ${components.length === 1 ? 'component' : 'components'}`,
      description: components.map((component) => component.name).join(', '),
      componentIds: components.map((component) => component.id),
      runtimeIds,
      tone: runtimeIds.length === 0 ? 'neutral' : runtimeIds.length > 1 ? 'data' : 'primary',
    };
  }).sort((left, right) => {
    if (left.runtimeIds.length === 0) return 1;
    if (right.runtimeIds.length === 0) return -1;
    return left.label.localeCompare(right.label);
  });
}

function createComponentModel(
  architecture: CodebaseArchitecture,
  selectedComponentId: string,
  focus?: ComponentFocus,
): ArchitectureDiagramModel {
  const components = architecture.components.filter((candidate) =>
    focus === undefined || focus.visibleIds.has(candidate.id));
  const component = components.find((candidate) => candidate.id === selectedComponentId) ?? components[0];
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
    if (!components.some((candidate) => candidate.id === relationship.from_component_id) ||
      !components.some((candidate) => candidate.id === relationship.to_component_id)) continue;
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
    rankDirection: components.length >= 6 ? 'TB' : 'LR',
    caption: focus === undefined
      ? `All ${components.length} components stay visible. ${directCount} ${directCount === 1 ? 'connection touches' : 'connections touch'} ${component.name}.`
      : `${focusedCaption(focus)} ${directCount} ${directCount === 1 ? 'connection touches' : 'connections touch'} ${component.name}.`,
    nodes: components.map((candidate) => ({
      id: candidate.id,
      label: candidate.name,
      detail: candidate.responsibility,
      description: candidate.responsibility,
      tone: toneForKind(candidate.kind),
      componentId: candidate.id,
      boundary: focus?.boundaryIds.has(candidate.id),
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

function focusedCaption(focus: ComponentFocus): string {
  const selected = focus.selectedIds.size;
  const boundaries = focus.boundaryIds.size;
  return `Showing ${selected} selected ${selected === 1 ? 'component' : 'components'}${boundaries === 0
    ? ''
    : ` and ${boundaries} direct ${boundaries === 1 ? 'collaborator' : 'collaborators'}`}.`;
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

function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.slice(-2).join('/');
}
