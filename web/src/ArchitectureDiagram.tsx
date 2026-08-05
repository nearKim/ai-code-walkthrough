import { Graph, layout, type EdgeLabel, type GraphLabel, type NodeLabel, type Point } from '@dagrejs/dagre';
import { useEffect, useMemo, useRef } from 'react';
import {
  TransformComponent,
  TransformWrapper,
  type ReactZoomPanPinchContentRef,
} from 'react-zoom-pan-pinch';
import {
  createArchitectureDiagramModel,
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
  readonly focusedComponentIds?: ReadonlyArray<string>;
  readonly onComponentSelect: (componentId: string) => void;
}

export function ArchitectureDiagram({
  architecture,
  selectedComponentId,
  focusedComponentIds,
  onComponentSelect,
}: ArchitectureDiagramProps) {
  const transformRef = useRef<ReactZoomPanPinchContentRef>(null);
  const model = useMemo(
    () => createArchitectureDiagramModel(architecture, focusedComponentIds),
    [architecture, focusedComponentIds],
  );
  const diagram = useMemo(() => positionDiagram(model), [model]);

  useEffect(() => {
    let animation = 0;
    const fit = () => {
      window.cancelAnimationFrame(animation);
      animation = window.requestAnimationFrame(() => {
        const controls = transformRef.current;
        if (controls !== null) fitDiagram(controls, diagram);
      });
    };
    fit();
    window.addEventListener('resize', fit);
    return () => {
      window.removeEventListener('resize', fit);
      window.cancelAnimationFrame(animation);
    };
  }, [diagram]);

  return <div className="architecture-diagram">
    <div className="architecture-diagram-frame">
      <TransformWrapper
        ref={transformRef}
        minScale={0.25}
        maxScale={2.5}
        limitToBounds={false}
        centerZoomedOut
        panning={{ excluded: ['diagram-node'] }}
        wheel={{ step: 0.08 }}
        doubleClick={{ mode: 'reset' }}
      >
        <TransformComponent
          wrapperClass="diagram-transform-wrapper"
          contentClass="diagram-transform-content"
          wrapperStyle={{ height: '100%' }}
        >
          <svg
            aria-label="Architecture map"
            className="architecture-diagram-canvas"
            role="group"
            viewBox={`0 0 ${diagram.width} ${diagram.height}`}
            width={diagram.width}
            height={diagram.height}
          >
            <title>Architecture map</title>
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
                className={`diagram-edge ${edge.tone}${edge.uncertain === true ? ' uncertain' : ''}`}
                d={pathFrom(edge.points)}
                markerEnd={`url(#diagram-arrow-${edge.tone})`}
              />
              {edge.label.length > 0 && edge.x !== undefined && edge.y !== undefined && <g
                transform={`translate(${edge.x}, ${edge.y})`}
              >
                <rect
                  className="diagram-edge-label-bg"
                  x={-edge.width / 2}
                  y={-edge.height / 2}
                  width={edge.width}
                  height={edge.height}
                  rx="4"
                />
                <text className={`diagram-edge-label ${edge.tone}`} dominantBaseline="middle" textAnchor="middle">
                  {truncate(edge.label, 24)}
                </text>
              </g>}
            </g>)}
            {diagram.nodes.map((node) => {
              const selected = node.componentId === selectedComponentId;
              const titleLines = wrapText(node.label, 23, 2);
              const detailLines = wrapText(node.detail, 34, 2);
              const titleY = titleLines.length === 1 ? 28 : 20;
              return <g
                key={node.id}
                id={diagramNodeDomId(node.componentId)}
                aria-label={`${node.label}, ${node.detail}`}
                className={`diagram-node ${node.tone}${selected ? ' selected' : ''}`}
                data-component-id={node.componentId}
                onClick={() => onComponentSelect(node.componentId)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onComponentSelect(node.componentId);
                  }
                }}
                role="button"
                tabIndex={0}
                transform={`translate(${node.x - node.width / 2}, ${node.y - node.height / 2})`}
              >
                <title>{node.description}</title>
                <rect width={node.width} height={node.height} rx="8" />
                {titleLines.map((line, index) => <text
                  key={`${line}:${index}`}
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
      </TransformWrapper>
    </div>
  </div>;
}

function positionDiagram(model: ArchitectureDiagramModel): PositionedDiagram {
  const graph = new Graph<GraphLabel, NodeLabel, EdgeLabel>({ directed: true, multigraph: true });
  const nodeWidth = 218;
  const nodeHeight = 86;
  graph.setGraph({
    rankdir: model.rankDirection,
    ranker: 'network-simplex',
    acyclicer: 'greedy',
    ranksep: 72,
    nodesep: 32,
    edgesep: 16,
    marginx: 28,
    marginy: 28,
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
      return { ...node, x: positioned.x ?? 0, y: positioned.y ?? 0, width: positioned.width, height: positioned.height };
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

function pathFrom(points: ReadonlyArray<Point>): string {
  if (points.length === 0) return '';
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x},${point.y}`).join(' ');
}

function fitDiagram(controls: ReactZoomPanPinchContentRef, diagram: PositionedDiagram) {
  const wrapper = controls.instance.wrapperComponent;
  if (wrapper === null) return;
  const scale = Math.max(0.25, Math.min(1.4, (wrapper.clientWidth - 24) / diagram.width, (wrapper.clientHeight - 24) / diagram.height));
  controls.centerView(scale, 0);
}

function diagramNodeDomId(componentId: string): string {
  return `architecture-node-${encodeURIComponent(componentId)}`;
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
    else lines[index] = `${lines[index].slice(0, Math.max(1, lineLength - 1))}...`;
  }
  return lines.slice(0, maxLines);
}

function truncate(value: string, length: number): string {
  return value.length > length ? `${value.slice(0, length - 3)}...` : value;
}
