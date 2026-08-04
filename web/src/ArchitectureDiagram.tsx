import { Graph, layout, type EdgeLabel, type GraphLabel, type NodeLabel, type Point } from '@dagrejs/dagre';
import { Group, SegmentedControl, Stack, Text } from '@mantine/core';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  TransformComponent,
  TransformWrapper,
  type ReactZoomPanPinchContentRef,
} from 'react-zoom-pan-pinch';
import {
  createArchitectureDiagramModel,
  type ArchitectureDepth,
  type ArchitectureDiagramModel,
  type DiagramEdge,
  type DiagramNode,
} from './architecture/diagramModel';
import type { DiagramTone } from './architecture/taxonomy';
import type { CodebaseArchitecture } from './types';

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
          { value: 'responsibilities', label: 'Classes' },
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
    {depth !== 'responsibilities' && <div className="diagram-footer">
      <Group gap="md" className="diagram-legend">
        <Legend tone="primary" label="control / creation" />
        <Legend tone="data" label="data access" />
        <Legend tone="dependency" label="dependency" />
        <Legend tone="neutral" label="other" />
      </Group>
    </div>}
  </Stack>;
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

function truncate(value: string, length: number): string {
  return value.length > length ? `${value.slice(0, length - 1)}…` : value;
}

function titleForDepth(depth: ArchitectureDepth): string {
  if (depth === 'system') return 'System context';
  if (depth === 'component') return 'Component relationship';
  return 'Class ownership';
}
