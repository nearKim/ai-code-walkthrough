import { ActionIcon, Alert, Button, Group, Menu, Text, Title, UnstyledButton } from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import { ArchitectureDiagram } from '../ArchitectureDiagram';
import { hasCodeLocation } from '../architecture/evidence';
import type { RightPaneActions } from '../RightPane';
import type { ArchitectureComponent, CodebaseArchitecture, DiagramSection, EvidenceItem, SessionSnapshot } from '../types';

interface OverviewViewProps {
  readonly session: SessionSnapshot;
  readonly actions: RightPaneActions;
}

const ALL_CODE = '__all__';
const reservedSectionIds = new Set(['system-overview', 'component-map']);

export function OverviewView({ session, actions }: OverviewViewProps) {
  const flow = session.flow_map;
  if (flow === undefined) return <Alert color="red">The walkthrough map is unavailable.</Alert>;
  if (flow.architecture === undefined) return <SimpleWalkthrough actions={actions} />;

  return <MapView
    architecture={flow.architecture}
    sections={flow.diagram_sections ?? []}
    activeSectionId={session.active_section_id}
    actions={actions}
  />;
}

function SimpleWalkthrough({ actions }: { readonly actions: RightPaneActions }) {
  return <div className="pane-content simple-walkthrough">
    <Title order={2}>Walkthrough</Title>
    <Button onClick={() => void actions.tour('start')}>Walk</Button>
    <OverflowMenu actions={actions} />
  </div>;
}

function MapView({
  architecture,
  sections,
  activeSectionId,
  actions,
}: {
  readonly architecture: CodebaseArchitecture;
  readonly sections: ReadonlyArray<DiagramSection>;
  readonly activeSectionId?: string;
  readonly actions: RightPaneActions;
}) {
  const featureSections = useMemo(() => visibleFeatureSections(architecture, sections), [architecture, sections]);
  const [scopeId, setScopeId] = useState(ALL_CODE);
  const scope = featureSections.find((section) => section.id === scopeId);
  const visibleComponents = architecture.components.filter((component) =>
    scope === undefined || scope.component_ids.includes(component.id));
  const [selectedComponentId, setSelectedComponentId] = useState('');
  const selectedComponent = visibleComponents.find((component) => component.id === selectedComponentId)
    ?? visibleComponents[0];

  useEffect(() => {
    setScopeId(featureSections.some((section) => section.id === activeSectionId) ? activeSectionId! : ALL_CODE);
  }, [activeSectionId, featureSections]);
  useEffect(() => {
    setSelectedComponentId((current) =>
      visibleComponents.some((component) => component.id === current) ? current : visibleComponents[0]?.id ?? '');
  }, [visibleComponents]);

  const walk = () => {
    if (scope === undefined) void actions.tour('start');
    else void actions.tour('start_section', undefined, scope.id);
  };

  return <div className="pane-column map-view">
    <header className="pane-header overview-header">
      <div>
        <Title order={2} lineClamp={1}>{scope?.title ?? architecture.system_name ?? 'System map'}</Title>
      </div>
      <Group gap="xs" wrap="nowrap">
        <Button size="compact-sm" onClick={walk}>Walk</Button>
        <OverflowMenu actions={actions} />
      </Group>
    </header>
    {featureSections.length > 0 && <nav aria-label="Feature scope" className="map-scopes">
      <ScopeButton active={scope === undefined} onClick={() => setScopeId(ALL_CODE)}>All code</ScopeButton>
      {featureSections.map((section) => <ScopeButton
        active={section.id === scope?.id}
        key={section.id}
        onClick={() => setScopeId(section.id)}
      >{section.title}</ScopeButton>)}
    </nav>}
    <div className="architecture-workspace">
      <ArchitectureDiagram
        architecture={architecture}
        focusedComponentIds={scope?.component_ids}
        selectedComponentId={selectedComponent?.id ?? ''}
        onComponentSelect={setSelectedComponentId}
      />
      {selectedComponent !== undefined && <ComponentPeek component={selectedComponent} actions={actions} />}
    </div>
  </div>;
}

function ScopeButton({ active, children, onClick }: {
  readonly active: boolean;
  readonly children: string;
  readonly onClick: () => void;
}) {
  return <UnstyledButton
    aria-pressed={active}
    className={`map-scope${active ? ' selected' : ''}`}
    onClick={onClick}
  >{children}</UnstyledButton>;
}

function ComponentPeek({ component, actions }: {
  readonly component: ArchitectureComponent;
  readonly actions: RightPaneActions;
}) {
  const evidence = component.evidence
    .filter(hasCodeLocation)
    .filter((item, index, items) => items.findIndex((candidate) => candidate.file_path === item.file_path) === index)
    .slice(0, 3);
  return <section aria-live="polite" className="component-peek">
    <Text fw={650}>{component.name}</Text>
    <Text size="sm" c="dimmed">{component.responsibility}</Text>
    {evidence.length > 0 && <div className="component-sources">
      {evidence.map((item, index) => <UnstyledButton
        aria-label={`Show ${item.label} source`}
        key={`${item.file_path}:${item.start_line}:${index}`}
        onClick={() => previewEvidence(actions, item)}
      >{shortPath(item.file_path!)}</UnstyledButton>)}
    </div>}
  </section>;
}

function OverflowMenu({ actions }: { readonly actions: RightPaneActions }) {
  return <Menu position="bottom-end" shadow="sm" withinPortal>
    <Menu.Target>
      <ActionIcon aria-label="More walkthrough actions" title="More walkthrough actions" variant="subtle" color="gray">
        ...
      </ActionIcon>
    </Menu.Target>
    <Menu.Dropdown>
      <Menu.Item onClick={() => void actions.tour('new')}>New walkthrough</Menu.Item>
      <Menu.Item onClick={() => void actions.copyMarkdown()}>Copy Markdown</Menu.Item>
      <Menu.Item onClick={() => void actions.downloadTechnicalReference()}>Technical reference</Menu.Item>
    </Menu.Dropdown>
  </Menu>;
}

function visibleFeatureSections(
  architecture: CodebaseArchitecture,
  sections: ReadonlyArray<DiagramSection>,
): ReadonlyArray<DiagramSection> {
  const allIds = new Set(architecture.components.map((component) => component.id));
  return sections.filter((section) => {
    if (reservedSectionIds.has(section.id)) return false;
    const componentIds = new Set(section.component_ids.filter((id) => allIds.has(id)));
    return componentIds.size > 0 && componentIds.size < allIds.size;
  });
}

function previewEvidence(actions: RightPaneActions, evidence: EvidenceItem) {
  actions.previewEvidence(evidence, evidence.text ?? `Source evidence for ${evidence.label}.`);
}

function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.slice(-2).join('/');
}
