import { Graph, layout, type EdgeLabel, type GraphLabel, type NodeLabel, type Point } from '@dagrejs/dagre';
import { Group, SegmentedControl, Stack, Text } from '@mantine/core';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  TransformComponent,
  TransformWrapper,
  type ReactZoomPanPinchContentRef,
} from 'react-zoom-pan-pinch';
import type {
  ArchitectureComponent,
  ArchitectureResponsibility,
  CodebaseArchitecture,
  EvidenceItem,
} from './types';

export type ArchitectureDepth = 'system' | 'component' | 'responsibilities';

interface DiagramNode {
  readonly id: string;
  readonly label: string;
  readonly detail: string;
  readonly description: string;
  readonly tone: DiagramTone;
  readonly componentId?: string;
  readonly componentKind?: string;
  readonly ownerKey?: string;
}

interface DiagramEdge {
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

type DiagramTone = 'primary' | 'data' | 'dependency' | 'neutral';

interface PositionedNode extends DiagramNode {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

interface PositionedEdge extends DiagramEdge {
  readonly points: ReadonlyArray<Point>;
  readonly x?: number;
  readonly y?: number;
  readonly width: number;
  readonly height: number;
}

interface PositionedDiagram extends ArchitectureDiagramModel {
  readonly width: number;
  readonly height: number;
  readonly nodes: ReadonlyArray<PositionedNode>;
  readonly edges: ReadonlyArray<PositionedEdge>;
}

interface ArchitectureDiagramProps {
  readonly architecture: CodebaseArchitecture;
  readonly selectedComponentId: string;
  readonly selectedOwnerKey?: string;
  readonly onComponentSelect: (componentId: string) => void;
  readonly onOwnerSelect: (ownerKey: string) => void;
}

const kindOrder = ['entrypoint', 'application', 'domain', 'infrastructure', 'data', 'shared'];
const ownerKinds = new Set(['interface', 'class', 'function', 'module', 'config', 'schema']);

export function ArchitectureDiagram({
  architecture,
  selectedComponentId,
  selectedOwnerKey,
  onComponentSelect,
  onOwnerSelect,
}: ArchitectureDiagramProps) {
  const [depth, setDepth] = useState<ArchitectureDepth>('system');
  const transformRef = useRef<ReactZoomPanPinchContentRef>(null);
  const model = useMemo(
    () => createArchitectureDiagramModel(architecture, depth, selectedComponentId),
    [architecture, depth, selectedComponentId],
  );
  const diagram = useMemo(() => positionDiagram(model), [model]);

  useEffect(() => {
    const animation = window.requestAnimationFrame(() => {
      const controls = transformRef.current;
      if (controls === null) return;
      fitDiagram(controls, diagram);
    });
    return () => window.cancelAnimationFrame(animation);
  }, [architecture, depth]);

  const selectNode = (node: DiagramNode) => {
    if (node.ownerKey !== undefined) {
      onOwnerSelect(node.ownerKey);
      return;
    }
    if (node.componentKind !== undefined) {
      const current = architecture.components.find((component) => component.id === selectedComponentId);
      const component = current?.kind === node.componentKind
        ? current
        : architecture.components.find((candidate) => candidate.kind === node.componentKind);
      if (component !== undefined) onComponentSelect(component.id);
      setDepth('component');
      return;
    }
    if (node.componentId !== undefined) {
      onComponentSelect(node.componentId);
    }
  };

  return <Stack gap="xs" className="architecture-diagram">
    <div className="diagram-toolbar">
      <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>Map depth</Text>
      <SegmentedControl
        aria-label="Architecture depth"
        size="xs"
        value={depth}
        onChange={(value) => setDepth(value as ArchitectureDepth)}
        data={[
          { value: 'system', label: 'System' },
          { value: 'component', label: 'Component' },
          { value: 'responsibilities', label: 'Responsibilities' },
        ]}
      />
    </div>
    <div className="architecture-diagram-frame">
      <TransformWrapper
        key={depth}
        ref={transformRef}
        minScale={0.25}
        maxScale={2.5}
        limitToBounds={false}
        centerZoomedOut
        panning={{ excluded: ['diagram-node'] }}
        wheel={{ step: 0.08 }}
        doubleClick={{ mode: 'reset' }}
      >
        {({ zoomIn, zoomOut }) => <>
          <div className="diagram-zoom-controls" aria-label="Diagram zoom controls">
            <button type="button" aria-label="Zoom out" title="Zoom out" onClick={() => zoomOut(0.2)}>−</button>
            <button type="button" aria-label="Fit diagram" title="Fit whole diagram" onClick={() => {
              const controls = transformRef.current;
              if (controls !== null) fitDiagram(controls, diagram);
            }}>Fit</button>
            <button type="button" aria-label="Zoom in" title="Zoom in" onClick={() => zoomIn(0.2)}>+</button>
          </div>
          <TransformComponent
            wrapperClass="diagram-transform-wrapper"
            contentClass="diagram-transform-content"
            wrapperStyle={{ height: viewportHeight(depth, diagram.height) }}
          >
            <svg
              aria-label={`${titleForDepth(depth)} architecture diagram`}
              className="architecture-diagram-canvas"
              role="group"
              viewBox={`0 0 ${diagram.width} ${diagram.height}`}
              width={diagram.width}
              height={diagram.height}
            >
        <title>{model.caption}</title>
        <defs>
          {(['primary', 'data', 'dependency', 'neutral'] as const).map((tone) => <marker
            key={tone}
            id={`diagram-arrow-${tone}`}
            viewBox="0 0 10 10"
            refX="8.5"
            refY="5"
            markerWidth="7"
            markerHeight="7"
            orient="auto-start-reverse"
          >
            <path className={`diagram-arrow ${tone}`} d="M0,0 L10,5 L0,10 z" />
          </marker>)}
        </defs>
        {diagram.edges.map((edge) => <g key={edge.id}>
          <path
            className={`diagram-edge ${edge.tone}${edge.uncertain === true ? ' uncertain' : ''}${edge.muted === true ? ' muted' : ''}`}
            d={pathFrom(edge.points)}
            markerEnd={`url(#diagram-arrow-${edge.tone})`}
          />
          {edge.label.length > 0 && edge.x !== undefined && edge.y !== undefined && <g
            className={edge.muted === true ? 'diagram-edge-label-group muted' : undefined}
            transform={`translate(${edge.x}, ${edge.y})`}
          >
            <rect
              className="diagram-edge-label-bg"
              x={-edge.width / 2}
              y={-edge.height / 2}
              width={edge.width}
              height={edge.height}
              rx="3"
            />
            <text className={`diagram-edge-label ${edge.tone}`} dominantBaseline="middle" textAnchor="middle">
              {truncate(edge.label, 24)}
            </text>
          </g>}
        </g>)}
        {diagram.nodes.map((node) => {
          const interactive = node.componentId !== undefined || node.componentKind !== undefined || node.ownerKey !== undefined;
          const selected = node.componentId === selectedComponentId || node.ownerKey === selectedOwnerKey;
          const titleLines = wrapLabel(node.label);
          const detailLines = wrapText(node.detail, model.level === 'system' ? 28 : 34, 2);
          const titleY = titleLines.length === 1 ? 27 : 19;
          return <g
            key={node.id}
            id={node.componentId === undefined ? undefined : diagramNodeDomId(node.componentId)}
            aria-label={`${node.label}, ${node.detail}`}
            className={`diagram-node ${node.tone}${selected ? ' selected' : ''}${interactive ? ' interactive' : ''}`}
            data-component-id={node.componentId}
            onClick={interactive ? () => selectNode(node) : undefined}
            onKeyDown={interactive ? (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                selectNode(node);
              }
            } : undefined}
            role={interactive ? 'button' : undefined}
            tabIndex={interactive ? 0 : undefined}
            transform={`translate(${node.x - node.width / 2}, ${node.y - node.height / 2})`}
          >
            <title>{node.description}</title>
            <rect width={node.width} height={node.height} rx="5" />
            {titleLines.map((line, index) => <text
              key={line}
              className="diagram-node-title"
              x={node.width / 2}
              y={titleY + index * 15}
              textAnchor="middle"
            >{line}</text>)}
            {detailLines.map((line, index) => <text
              key={`${line}:${index}`}
              className="diagram-node-detail"
              x={node.width / 2}
              y={node.height - 22 + index * 12}
              textAnchor="middle"
            >{line}</text>)}
          </g>;
        })}
            </svg>
          </TransformComponent>
        </>}
      </TransformWrapper>
    </div>
    <div className="diagram-footer">
      <Group gap="md" className="diagram-legend">
        <Legend tone="primary" label="control / creation" />
        <Legend tone="data" label="data access" />
        <Legend tone="dependency" label="dependency" />
        <Legend tone="neutral" label="other" />
      </Group>
      <Text size="xs" c="dimmed">{model.caption}</Text>
    </div>
  </Stack>;
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
    rankDirection: 'LR',
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
  const responsibilities = responsibilitiesFor(component).slice(0, 5);
  const nodes: DiagramNode[] = [{
    id: component.id,
    label: component.name,
    detail: component.responsibility,
    description: component.responsibility,
    tone: toneForKind(component.kind),
    componentId: component.id,
  }];
  const edges: DiagramEdge[] = [];
  const nodeIds = new Set([component.id]);
  const relationshipById = new Map(architecture.relationships.map((relationship) => [relationship.id, relationship]));

  responsibilities.forEach((responsibility) => {
    const responsibilityNodeId = `responsibility:${responsibility.id}`;
    nodes.push({
      id: responsibilityNodeId,
      label: responsibility.title,
      detail: responsibility.description,
      description: responsibility.description,
      tone: 'primary',
    });
    edges.push({
      id: `${component.id}:${responsibility.id}`,
      from: component.id,
      to: responsibilityNodeId,
      label: '',
      tone: 'primary',
      uncertain: responsibility.uncertain,
    });

    responsibilityOwners(responsibility).slice(0, 2).forEach((owner) => {
      const key = architectureEvidenceKey(owner);
      const ownerNodeId = `owner:${key}`;
      if (!nodeIds.has(ownerNodeId)) {
        nodeIds.add(ownerNodeId);
        nodes.push({
          id: ownerNodeId,
          label: owner.label,
          detail: humanize(owner.kind),
          description: owner.text ?? owner.label,
          tone: 'dependency',
          ownerKey: key,
        });
      }
      edges.push({
        id: `${responsibilityNodeId}:${ownerNodeId}`,
        from: responsibilityNodeId,
        to: ownerNodeId,
        label: 'implemented by',
        tone: 'dependency',
        uncertain: responsibility.uncertain,
      });
    });

    responsibility.collaborator_component_ids.slice(0, 2).forEach((componentId) => {
      const collaborator = architecture.components.find((candidate) => candidate.id === componentId);
      if (collaborator === undefined) return;
      const collaboratorNodeId = `collaborator:${componentId}`;
      if (!nodeIds.has(collaboratorNodeId)) {
        nodeIds.add(collaboratorNodeId);
        nodes.push({
          id: collaboratorNodeId,
          label: collaborator.name,
          detail: collaborator.responsibility,
          description: collaborator.responsibility,
          tone: toneForKind(collaborator.kind),
          componentId: collaborator.id,
        });
      }
      const relationship = responsibility.relationship_ids
        .map((id) => relationshipById.get(id))
        .find((candidate) => candidate !== undefined && (
          candidate.from_component_id === componentId || candidate.to_component_id === componentId
        ));
      const relationshipStartsHere = relationship?.from_component_id === component.id;
      edges.push({
        id: `${responsibilityNodeId}:${collaboratorNodeId}`,
        from: relationship === undefined || relationshipStartsHere ? responsibilityNodeId : collaboratorNodeId,
        to: relationship === undefined || relationshipStartsHere ? collaboratorNodeId : responsibilityNodeId,
        label: relationship === undefined ? 'collaborates' : humanize(relationship.kind),
        tone: relationship === undefined ? 'neutral' : toneForRelationship(relationship.kind),
        uncertain: responsibility.uncertain || relationship?.uncertain,
      });
    });
  });

  return {
    level: 'responsibilities',
    rankDirection: 'LR',
    caption: responsibilities.length === 0
      ? `${component.name} has no structured responsibility mapping; remap the repository to generate one.`
      : `${responsibilities.length} grounded ${responsibilities.length === 1 ? 'responsibility' : 'responsibilities'} for ${component.name}. Select a code owner for method and state detail.`,
    nodes,
    edges,
  };
}

function positionDiagram(model: ArchitectureDiagramModel): PositionedDiagram {
  const graph = new Graph<GraphLabel, NodeLabel, EdgeLabel>({ directed: true, multigraph: true });
  const nodeWidth = model.level === 'system' ? 160 : 230;
  const nodeHeight = model.level === 'system' ? 60 : model.level === 'component' ? 92 : 84;
  graph.setGraph({
    rankdir: model.rankDirection,
    ranker: 'network-simplex',
    acyclicer: 'greedy',
    ranksep: model.level === 'system' ? 48 : 84,
    nodesep: model.level === 'system' ? 22 : 34,
    edgesep: model.level === 'system' ? 12 : 18,
    marginx: model.level === 'system' ? 18 : 28,
    marginy: model.level === 'system' ? 18 : 28,
  });
  graph.setDefaultEdgeLabel(() => ({}));
  for (const node of model.nodes) graph.setNode(node.id, { width: nodeWidth, height: nodeHeight });
  for (const edge of model.edges) {
    const labelWidth = edge.label.length === 0 ? 0 : Math.max(44, Math.min(160, edge.label.length * 6.4 + 16));
    graph.setEdge(edge.from, edge.to, { width: labelWidth, height: edge.label.length === 0 ? 0 : 20, labelpos: 'c' }, edge.id);
  }
  layout(graph);
  const graphLabel = graph.graph();

  return {
    ...model,
    width: Math.max(1, graphLabel.width ?? 1),
    height: Math.max(1, graphLabel.height ?? 1),
    nodes: model.nodes.map((node) => {
      const positioned = graph.node(node.id);
      return {
        ...node,
        x: positioned.x ?? 0,
        y: positioned.y ?? 0,
        width: positioned.width,
        height: positioned.height,
      };
    }),
    edges: model.edges.map((edge) => {
      const positioned = graph.edge({ v: edge.from, w: edge.to, name: edge.id });
      return {
        ...edge,
        points: positioned.points ?? [],
        x: positioned.x,
        y: positioned.y,
        width: positioned.width ?? 44,
        height: positioned.height ?? 20,
      };
    }),
  };
}

function Legend({ tone, label }: { readonly tone: DiagramTone; readonly label: string }) {
  return <Group gap={5} wrap="nowrap"><span className={`diagram-legend-line ${tone}`} /><Text size="xs" c="dimmed">{label}</Text></Group>;
}

function pathFrom(points: ReadonlyArray<Point>): string {
  if (points.length === 0) return '';
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ');
}

function fitDiagram(controls: ReactZoomPanPinchContentRef, diagram: PositionedDiagram) {
  const wrapper = controls.instance.wrapperComponent;
  if (wrapper === null) return;
  const scale = Math.max(0.25, Math.min(
    1,
    (wrapper.clientWidth - 24) / diagram.width,
    (wrapper.clientHeight - 24) / diagram.height,
  ));
  controls.centerView(scale, 0);
}

function viewportHeight(depth: ArchitectureDepth, diagramHeight: number): number {
  if (depth === 'component') return Math.min(420, Math.max(280, diagramHeight));
  if (depth === 'responsibilities') return Math.min(460, Math.max(300, diagramHeight));
  return Math.min(430, Math.max(340, diagramHeight));
}

function diagramNodeDomId(componentId: string): string {
  return `architecture-node-${encodeURIComponent(componentId)}`;
}

function wrapLabel(label: string): ReadonlyArray<string> {
  return wrapText(label, 23, 2);
}

function wrapText(value: string, lineLength: number, maxLines: number): ReadonlyArray<string> {
  if (value.length <= lineLength) return [value];
  const words = value.split(' ');
  const lines: string[] = [''];
  for (const word of words) {
    const index = lines.length - 1;
    const candidate = `${lines[index]} ${word}`.trim();
    if (candidate.length <= lineLength || lines[index].length === 0) lines[index] = candidate;
    else if (lines.length < maxLines) lines.push(word);
    else lines[index] = `${lines[index].slice(0, Math.max(1, lineLength - 1))}…`;
  }
  return lines.slice(0, maxLines);
}

function responsibilitiesFor(component: ArchitectureComponent): ReadonlyArray<ArchitectureResponsibility> {
  if (component.responsibilities.length > 0) return component.responsibilities;
  return [{
    id: `${component.id}:summary`,
    title: 'Primary responsibility',
    description: component.responsibility,
    evidence: component.evidence,
    collaborator_component_ids: [],
    relationship_ids: [],
    uncertain: component.uncertain,
  }];
}

export function responsibilityOwners(responsibility: ArchitectureResponsibility): ReadonlyArray<EvidenceItem> {
  const owners = responsibility.evidence.filter((evidence) => ownerKinds.has(evidence.kind));
  return owners.length > 0 ? owners : responsibility.evidence.slice(0, 1);
}

export function architectureEvidenceKey(evidence: EvidenceItem): string {
  return [evidence.kind, evidence.label, evidence.file_path ?? '', evidence.start_line ?? ''].join(':');
}

export function componentResponsibilities(component: ArchitectureComponent): ReadonlyArray<ArchitectureResponsibility> {
  return responsibilitiesFor(component);
}

function truncate(value: string, length: number): string {
  return value.length > length ? `${value.slice(0, length - 1)}…` : value;
}

function toneForKind(kind: string): DiagramTone {
  if (kind === 'entrypoint' || kind === 'application') return 'primary';
  if (kind === 'data' || kind === 'shared') return 'data';
  if (kind === 'domain' || kind === 'infrastructure') return 'dependency';
  return 'neutral';
}

function toneForRelationships(kinds: ReadonlySet<string>): DiagramTone {
  if ([...kinds].some((kind) => toneForRelationship(kind) === 'primary')) return 'primary';
  if ([...kinds].some((kind) => toneForRelationship(kind) === 'data')) return 'data';
  if ([...kinds].some((kind) => toneForRelationship(kind) === 'dependency')) return 'dependency';
  return 'neutral';
}

function toneForRelationship(kind: string): DiagramTone {
  if (kind === 'call' || kind === 'calls' || kind === 'creates' || kind === 'instantiation') return 'primary';
  if (kind === 'read' || kind === 'reads' || kind === 'write' || kind === 'writes' || kind === 'data_flow') return 'data';
  if (kind === 'depends_on' || kind === 'dependency') return 'dependency';
  return 'neutral';
}

function titleForKind(kind: string): string {
  const titles: Record<string, string> = {
    entrypoint: 'Interfaces',
    application: 'Application',
    domain: 'Domain',
    infrastructure: 'Infrastructure',
    data: 'Data boundaries',
    shared: 'Shared contracts',
  };
  return titles[kind] ?? humanize(kind);
}

export function roleForKind(kind: string): string {
  const roles: Record<string, string> = {
    entrypoint: 'entry point',
    application: 'application workflow',
    domain: 'domain policy',
    infrastructure: 'runtime infrastructure',
    data: 'data boundary',
    shared: 'shared contract',
  };
  return roles[kind] ?? humanize(kind);
}

function titleForDepth(depth: ArchitectureDepth): string {
  if (depth === 'system') return 'System context';
  if (depth === 'component') return 'Component relationship';
  return 'Responsibility ownership';
}

function humanize(value: string): string {
  return value.replaceAll('_', ' ');
}
