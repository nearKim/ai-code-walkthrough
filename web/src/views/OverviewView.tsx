import {
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  ScrollArea,
  Select,
  Stack,
  Tabs,
  Text,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import type { RightPaneActions } from '../RightPane';
import type {
  ArchitectureComponent,
  CodebaseArchitecture,
  ComponentRelationship,
  FlowStep,
  LearningStage,
  SessionSnapshot,
} from '../types';

interface OverviewViewProps {
  readonly session: SessionSnapshot;
  readonly actions: RightPaneActions;
}

export function OverviewView({ session, actions }: OverviewViewProps) {
  const flow = session.flow_map;
  const stages = useMemo(() => deriveStages(flow?.learning_path ?? [], flow?.steps ?? []), [flow]);
  const [stageIndex, setStageIndex] = useState(0);
  const activeStage = stages[stageIndex];
  const stageSteps = activeStage?.step_ids
    .map((id) => flow?.steps.find((step) => step.id === id))
    .filter((step): step is FlowStep => step !== undefined) ?? [];
  const [selectedStepId, setSelectedStepId] = useState<string>();

  useEffect(() => {
    setStageIndex(0);
    setSelectedStepId(stages[0]?.step_ids[0]);
  }, [flow]);
  useEffect(() => {
    setSelectedStepId(activeStage?.step_ids[0]);
  }, [activeStage?.id]);

  if (flow === undefined) {
    return <Alert color="red">The provider completed without a walkthrough map.</Alert>;
  }
  const broken = new Set(session.broken_step_ids);
  const selectedStep = flow.steps.find((step) => step.id === selectedStepId);
  const entry = flow.steps.find((step) => step.id === flow.entry_step_id) ?? flow.steps[0];

  return (
    <div className="pane-column">
      <div className="pane-header">
        <Text size="xs" c="dimmed" tt="uppercase" fw={700}>Walkthrough mapped</Text>
        <Title order={3} lineClamp={2}>{session.question ?? 'Walkthrough'}</Title>
        <Text size="xs" c="dimmed">
          {flow.architecture?.components.length ?? 0} components · {stages.length} stages · {flow.steps.length} steps
          {entry !== undefined ? ` · entry: ${entry.title}` : ''}
        </Text>
        <Text size="sm" mt="xs">{flow.summary}</Text>
      </div>
      <Tabs defaultValue={flow.architecture === undefined ? 'path' : 'architecture'} className="overview-tabs">
        <Tabs.List grow>
          {flow.architecture !== undefined && <Tabs.Tab value="architecture">Architecture</Tabs.Tab>}
          <Tabs.Tab value="path">Learning path</Tabs.Tab>
        </Tabs.List>
        {flow.architecture !== undefined && <Tabs.Panel value="architecture" className="overview-panel">
          <ArchitectureView architecture={flow.architecture} />
        </Tabs.Panel>}
        <Tabs.Panel value="path" className="overview-panel">
          <ScrollArea className="overview-scroll" offsetScrollbars>
            <Stack gap="sm" p="sm">
              <Select
                label="Learning stage"
                data={stages.map((stage, index) => ({
                  value: String(index),
                  label: `${index + 1}. ${stage.title} · ${stage.step_ids.length} stops`,
                }))}
                value={String(stageIndex)}
                onChange={(value) => value !== null && setStageIndex(Number(value))}
              />
              {activeStage !== undefined && <div>
                <Text fw={600}>{activeStage.goal}</Text>
                {activeStage.checkpoint !== undefined &&
                  <Text size="sm" c="dimmed" mt={4}>Checkpoint: {activeStage.checkpoint}</Text>}
              </div>}
              <Divider label="Validated code stops" labelPosition="left" />
              <Stack gap={4}>
                {stageSteps.map((step) => {
                  const disabled = broken.has(step.id);
                  return <UnstyledButton
                    key={step.id}
                    className={`step-row ${selectedStepId === step.id ? 'selected' : ''}`}
                    disabled={disabled}
                    onClick={() => setSelectedStepId(step.id)}
                  >
                    <div className="step-row-main">
                      <Text size="sm" fw={600} lineClamp={1}>{step.title}</Text>
                      <Text size="xs" c="dimmed" lineClamp={1}>{shortPath(step.file_path)}:{step.start_line}</Text>
                    </div>
                    <Badge size="xs" variant="light" color={disabled ? 'red' : 'gray'}>
                      {disabled ? 'broken' : step.step_type ?? step.importance ?? 'step'}
                    </Badge>
                  </UnstyledButton>;
                })}
              </Stack>
            </Stack>
          </ScrollArea>
        </Tabs.Panel>
      </Tabs>
      <Group className="pane-actions" gap="xs" wrap="wrap">
        <Button size="xs" onClick={() => void actions.tour('start')}>Start guided tour</Button>
        <Button
          size="xs"
          variant="default"
          disabled={selectedStep === undefined || broken.has(selectedStep.id)}
          onClick={() => selectedStep !== undefined && void actions.tour('preview', selectedStep.id)}
        >Preview selected</Button>
        <Button size="xs" variant="subtle" onClick={() => void actions.copyMarkdown()}>Copy Markdown</Button>
        <Button size="xs" variant="subtle" onClick={() => void actions.tour('new')}>New question</Button>
      </Group>
    </div>
  );
}

function ArchitectureView({ architecture }: { readonly architecture: CodebaseArchitecture }) {
  const [componentId, setComponentId] = useState(architecture.components[0]?.id ?? '');
  const [relationshipId, setRelationshipId] = useState(architecture.relationships[0]?.id ?? '');
  const names = useMemo(
    () => new Map(architecture.components.map((component) => [component.id, component.name])),
    [architecture.components],
  );
  const component = architecture.components.find((candidate) => candidate.id === componentId);
  const relationship = architecture.relationships.find((candidate) => candidate.id === relationshipId);

  return (
    <ScrollArea className="overview-scroll" offsetScrollbars>
      <Stack gap="md" p="sm">
        <Section title="System purpose"><Text size="sm">{architecture.system_purpose}</Text></Section>
        {architecture.relationships.length > 0 && <Section title="System map">
          <div className="system-map">
            {architecture.relationships.slice(0, 6).map((edge) =>
              <Text key={edge.id} size="sm" ff="monospace">
                {names.get(edge.from_component_id)} ──{humanize(edge.kind)}──▶ {names.get(edge.to_component_id)}
              </Text>)}
            {architecture.relationships.length > 6 &&
              <Text size="xs" c="dimmed">+{architecture.relationships.length - 6} more relationships</Text>}
          </div>
        </Section>}
        {architecture.components.length > 0 && <Section title="Explore a component">
          <Select
            data={architecture.components.map((item) => ({ value: item.id, label: `${item.name} · ${humanize(item.kind)}` }))}
            value={componentId}
            onChange={(value) => setComponentId(value ?? '')}
          />
          {component !== undefined && <ComponentDetail component={component} />}
        </Section>}
        {architecture.relationships.length > 0 && <Section title="Inspect a relationship">
          <Select
            data={architecture.relationships.map((edge) => ({
              value: edge.id,
              label: `${names.get(edge.from_component_id)} → ${names.get(edge.to_component_id)}`,
            }))}
            value={relationshipId}
            onChange={(value) => setRelationshipId(value ?? '')}
          />
          {relationship !== undefined && <RelationshipDetail relationship={relationship} names={names} />}
        </Section>}
        {architecture.cross_cutting_concerns.length > 0 && <Section title="Cross-cutting concerns">
          {architecture.cross_cutting_concerns.map((item) => <Text key={item} size="sm">• {item}</Text>)}
        </Section>}
        {architecture.coverage_notes.length > 0 && <Alert color="yellow" title="Coverage notes">
          {architecture.coverage_notes.map((item) => <Text key={item} size="sm">• {item}</Text>)}
        </Alert>}
      </Stack>
    </ScrollArea>
  );
}

function ComponentDetail({ component }: { readonly component: ArchitectureComponent }) {
  return <div className="detail-box">
    <Text size="sm">{component.responsibility}</Text>
    {component.key_paths.length > 0 && <Text size="xs" c="dimmed">Anchors: {component.key_paths.join(' · ')}</Text>}
    {component.key_symbols.length > 0 && <Text size="xs" c="dimmed">Symbols: {component.key_symbols.join(', ')}</Text>}
    {component.uncertain && <Text size="xs" c="yellow">Grounding is uncertain.</Text>}
  </div>;
}

function RelationshipDetail({ relationship, names }: {
  readonly relationship: ComponentRelationship;
  readonly names: ReadonlyMap<string, string>;
}) {
  const evidence = relationship.evidence
    .filter((item) => item.file_path !== undefined)
    .map((item) => `${item.file_path}:${item.start_line ?? ''}`);
  return <div className="detail-box">
    <Text size="sm" ff="monospace">
      {names.get(relationship.from_component_id)} ──{humanize(relationship.kind)}──▶ {names.get(relationship.to_component_id)}
    </Text>
    <Text size="sm">{relationship.description}</Text>
    {evidence.length > 0 && <Text size="xs" c="dimmed">Evidence: {evidence.join(' · ')}</Text>}
    {relationship.uncertain && <Text size="xs" c="yellow">Grounding is uncertain.</Text>}
  </div>;
}

function Section({ title, children }: { readonly title: string; readonly children: React.ReactNode }) {
  return <section><Text fw={700} mb={4}>{title}</Text>{children}</section>;
}

function deriveStages(stages: ReadonlyArray<LearningStage>, steps: ReadonlyArray<FlowStep>): ReadonlyArray<LearningStage> {
  if (stages.length > 0) return stages;
  if (steps.length === 0) return [];
  return [{
    id: 'walkthrough',
    title: 'Walkthrough path',
    goal: 'Follow the validated code stops in order.',
    component_ids: [],
    step_ids: steps.map((step) => step.id),
  }];
}

function humanize(value: string): string {
  return value.replaceAll('_', ' ');
}

function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.slice(-2).join('/');
}
