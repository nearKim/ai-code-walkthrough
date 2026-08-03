import {
  Alert,
  Badge,
  Button,
  Code,
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
import {
  ArchitectureDiagram,
  architectureEvidenceKey,
  componentResponsibilities,
  responsibilityOwners,
  roleForKind,
} from '../ArchitectureDiagram';
import type { RightPaneActions } from '../RightPane';
import type {
  ArchitectureComponent,
  ArchitectureResponsibility,
  CodebaseArchitecture,
  ComponentRelationship,
  EvidenceItem,
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
  const hasSystemNotes = (flow.architecture?.cross_cutting_concerns.length ?? 0) > 0 ||
    (flow.architecture?.coverage_notes.length ?? 0) > 0;

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
          {hasSystemNotes && <Tabs.Tab value="notes">System notes</Tabs.Tab>}
        </Tabs.List>
        {flow.architecture !== undefined && <Tabs.Panel value="architecture" className="overview-panel">
          <ArchitectureView
            architecture={flow.architecture}
            steps={flow.steps}
            brokenStepIds={broken}
            onPreviewStep={(stepId) => void actions.tour('preview', stepId)}
            onPreviewEvidence={actions.previewEvidence}
          />
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
        {flow.architecture !== undefined && hasSystemNotes && <Tabs.Panel value="notes" className="overview-panel">
          <SystemNotesView architecture={flow.architecture} />
        </Tabs.Panel>}
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

function ArchitectureView({ architecture, steps, brokenStepIds, onPreviewStep, onPreviewEvidence }: {
  readonly architecture: CodebaseArchitecture;
  readonly steps: ReadonlyArray<FlowStep>;
  readonly brokenStepIds: ReadonlySet<string>;
  readonly onPreviewStep: (stepId: string) => void;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
}) {
  const [componentId, setComponentId] = useState(architecture.components[0]?.id ?? '');
  const [ownerKey, setOwnerKey] = useState<string>();
  const names = useMemo(
    () => new Map(architecture.components.map((component) => [component.id, component.name])),
    [architecture.components],
  );
  const component = architecture.components.find((candidate) => candidate.id === componentId);
  const responsibilities = useMemo(
    () => component === undefined ? [] : componentResponsibilities(component),
    [component],
  );
  const mappedFiles = useMemo(() => component === undefined ? [] : [...new Set([
    ...component.key_paths,
    ...responsibilities.flatMap(responsibilityOwners)
      .map((evidence) => evidence.file_path)
      .filter((path): path is string => path !== undefined),
  ])], [component, responsibilities]);
  const selectedOwner = responsibilities
    .flatMap(responsibilityOwners)
    .find((evidence) => architectureEvidenceKey(evidence) === ownerKey);
  const componentSteps = useMemo(
    () => component === undefined ? [] : stepsForComponent(component, steps),
    [component, steps],
  );

  useEffect(() => {
    if (!architecture.components.some((candidate) => candidate.id === componentId)) {
      setComponentId(architecture.components[0]?.id ?? '');
    }
  }, [architecture.components, componentId]);
  useEffect(() => setOwnerKey(undefined), [componentId]);

  return (
    <div className="architecture-workspace">
      <div aria-label="Architecture diagram workspace" className="architecture-workspace-diagram">
        {architecture.components.length > 0 && <ArchitectureDiagram
          architecture={architecture}
          selectedComponentId={componentId}
          selectedOwnerKey={ownerKey}
          onComponentSelect={setComponentId}
          onOwnerSelect={setOwnerKey}
        />}
      </div>
      <ScrollArea aria-label="Component details" className="architecture-inspector" offsetScrollbars>
        <Stack gap="md" p="sm">
          {component !== undefined && <section
            aria-label="Selected diagram component"
            aria-live="polite"
            className="component-summary"
          >
            <Group gap="xs" wrap="wrap">
              <Title order={4}>{component.name}</Title>
              <Badge size="sm" variant="light">{roleForKind(component.kind)}</Badge>
            </Group>
            <Text size="sm">{component.responsibility}</Text>
            {mappedFiles.length > 0 && <div className="component-files">
              <Text size="xs" c="dimmed" fw={700}>Representative files</Text>
              <div className="code-anchor-list">
                {mappedFiles.map((path) => <Code key={path}>{path}</Code>)}
              </div>
            </div>}
          </section>}
          {component !== undefined && <ResponsibilityMap
            component={component}
            responsibilities={responsibilities}
            relationships={architecture.relationships}
            names={names}
            selectedOwnerKey={ownerKey}
            onOwnerSelect={setOwnerKey}
          />}
          {component !== undefined && component.responsibilities.length === 0 && <section className="inspector-panel">
            <Text fw={700}>Representative code</Text>
            <Text size="xs" c="dimmed">Remap this repository to generate responsibility-to-owner detail.</Text>
            <ComponentDetail
              component={component}
              steps={componentSteps}
              brokenStepIds={brokenStepIds}
              onPreviewStep={onPreviewStep}
            />
          </section>}
          {component !== undefined && selectedOwner !== undefined && <CodeOwnerDetail
            component={component}
            owner={selectedOwner}
            responsibilities={responsibilities}
            relationships={architecture.relationships}
            names={names}
            onPreviewEvidence={onPreviewEvidence}
          />}
        </Stack>
      </ScrollArea>
    </div>
  );
}

function SystemNotesView({ architecture }: { readonly architecture: CodebaseArchitecture }) {
  return <ScrollArea className="overview-scroll" offsetScrollbars>
    <Stack gap="lg" p="sm">
      <div>
        <Title order={4}>Context for interpreting this map</Title>
        <Text size="sm" c="dimmed">
          System-wide behavior and analysis boundaries are separated from the component map so they do not interrupt navigation.
        </Text>
      </div>
      {architecture.cross_cutting_concerns.length > 0 && <section className="system-notes-section">
        <Group justify="space-between" gap="xs">
          <Text fw={700}>Rules affecting multiple components</Text>
          <Badge size="sm" variant="light">{architecture.cross_cutting_concerns.length}</Badge>
        </Group>
        <Text size="sm" c="dimmed">
          Guarantees and operating rules that cannot be explained by one component alone.
        </Text>
        <ul className="system-note-list">
          {architecture.cross_cutting_concerns.map((item) => <li key={item}>{item}</li>)}
        </ul>
      </section>}
      {architecture.cross_cutting_concerns.length > 0 && architecture.coverage_notes.length > 0 && <Divider />}
      {architecture.coverage_notes.length > 0 && <section className="system-notes-section">
        <Group justify="space-between" gap="xs">
          <Text fw={700}>Analysis boundaries</Text>
          <Badge size="sm" color="yellow" variant="light">{architecture.coverage_notes.length}</Badge>
        </Group>
        <Text size="sm" c="dimmed">
          What was inspected, skipped, or not verified. Use these notes to judge how complete the generated map is.
        </Text>
        <ul className="system-note-list limits">
          {architecture.coverage_notes.map((item) => <li key={item}>{item}</li>)}
        </ul>
      </section>}
    </Stack>
  </ScrollArea>;
}

function ResponsibilityMap({
  component,
  responsibilities,
  relationships,
  names,
  selectedOwnerKey,
  onOwnerSelect,
}: {
  readonly component: ArchitectureComponent;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly relationships: ReadonlyArray<ComponentRelationship>;
  readonly names: ReadonlyMap<string, string>;
  readonly selectedOwnerKey?: string;
  readonly onOwnerSelect: (ownerKey: string) => void;
}) {
  return <section aria-label="Responsibility map" className="responsibility-section">
    <div>
      <Text fw={700}>Responsibility map</Text>
      <Text size="xs" c="dimmed">Each outcome is mapped to its code owner and collaborating boundary.</Text>
    </div>
    <div className="responsibility-map">
      <div aria-hidden="true" className="responsibility-map-header">
        <span>Responsibility</span><span>Code owner</span><span>Collaborator</span>
      </div>
      {responsibilities.map((responsibility) => {
        const owners = responsibilityOwners(responsibility);
        const connections = relationshipsForResponsibility(component.id, responsibility, relationships);
        return <div className="responsibility-map-row" key={responsibility.id}>
          <div className="responsibility-copy">
            <Text size="sm" fw={700}>{responsibility.title}</Text>
            <Text size="xs" c="dimmed">{responsibility.description}</Text>
          </div>
          <div className="responsibility-owner-list">
            {owners.length === 0
              ? <Text size="xs" c="dimmed">No grounded code owner</Text>
              : owners.map((owner) => {
                const key = architectureEvidenceKey(owner);
                return <UnstyledButton
                  aria-label={`${humanize(owner.kind)} ${owner.label}`}
                  aria-pressed={key === selectedOwnerKey}
                  className={`responsibility-owner${key === selectedOwnerKey ? ' selected' : ''}`}
                  key={key}
                  onClick={() => onOwnerSelect(key)}
                >
                  <div className="responsibility-owner-copy">
                    <Code>{owner.label}</Code>
                    {owner.file_path !== undefined && <Text size="xs" c="dimmed">
                      {formatEvidenceLocation(owner)}
                    </Text>}
                  </div>
                  <span>{humanize(owner.kind)}</span>
                </UnstyledButton>;
              })}
          </div>
          <div className="responsibility-collaborator-list">
            {responsibility.collaborator_component_ids.length === 0
              ? <Text size="xs" c="dimmed">Internal to this component</Text>
              : responsibility.collaborator_component_ids.map((id) => {
                const relationship = connections.find((candidate) =>
                  candidate.from_component_id === id || candidate.to_component_id === id);
                return <div className="responsibility-collaborator" key={id}>
                  <Text size="xs" fw={600}>{names.get(id) ?? id}</Text>
                  {relationship !== undefined && <Text size="xs" c="dimmed">{relationshipSentence(relationship, names)}</Text>}
                </div>;
              })}
          </div>
        </div>;
      })}
    </div>
  </section>;
}

function CodeOwnerDetail({
  component,
  owner,
  responsibilities,
  relationships,
  names,
  onPreviewEvidence,
}: {
  readonly component: ArchitectureComponent;
  readonly owner: EvidenceItem;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly relationships: ReadonlyArray<ComponentRelationship>;
  readonly names: ReadonlyMap<string, string>;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
}) {
  const ownerKey = architectureEvidenceKey(owner);
  const ownedResponsibilities = responsibilities.filter((responsibility) =>
    responsibilityOwners(responsibility).some((candidate) => architectureEvidenceKey(candidate) === ownerKey));

  return <section aria-label="Code owner detail" className="code-owner-detail">
    <div className="code-owner-header">
      <div>
        <Group gap="xs" wrap="wrap">
          <Title order={4}>{owner.label}</Title>
          <Badge size="sm" variant="light">{humanize(owner.kind)}</Badge>
        </Group>
        {owner.text !== undefined && <Text size="sm" c="dimmed">{owner.text}</Text>}
      </div>
      <EvidenceAction evidence={owner} explanation={component.responsibility} onPreview={onPreviewEvidence} />
    </div>
    <Stack gap="md">
      {ownedResponsibilities.map((responsibility) => {
        const responsibilityOwnerKeys = new Set(responsibilityOwners(responsibility).map(architectureEvidenceKey));
        const implementationEvidence = responsibility.evidence.filter((evidence) =>
          !responsibilityOwnerKeys.has(architectureEvidenceKey(evidence)) && evidenceBelongsToOwner(evidence, owner));
        const otherOwners = responsibilityOwners(responsibility).filter((evidence) =>
          architectureEvidenceKey(evidence) !== ownerKey);
        const connections = relationshipsForResponsibility(component.id, responsibility, relationships);
        return <section className="owner-responsibility" key={responsibility.id}>
          <div>
            <Text size="sm" fw={700}>{responsibility.title}</Text>
            <Text size="sm">{responsibility.description}</Text>
          </div>
          {implementationEvidence.length > 0 && <div>
            <Text size="xs" fw={700} mb={5}>Methods and state</Text>
            <div className="owner-evidence-list">
              {implementationEvidence.map((evidence) => <EvidenceRow
                evidence={evidence}
                explanation={evidence.text ?? responsibility.description}
                key={architectureEvidenceKey(evidence)}
                onPreview={onPreviewEvidence}
              />)}
            </div>
          </div>}
          {otherOwners.length > 0 && <div>
            <Text size="xs" fw={700} mb={5}>Related code owners</Text>
            <div className="owner-evidence-list">
              {otherOwners.map((evidence) => <EvidenceRow
                evidence={evidence}
                explanation={evidence.text ?? responsibility.description}
                key={architectureEvidenceKey(evidence)}
                onPreview={onPreviewEvidence}
              />)}
            </div>
          </div>}
          {connections.length > 0 && <div>
            <Text size="xs" fw={700} mb={5}>Relationships</Text>
            <Stack gap={5}>
              {connections.map((relationship) => <div className="owner-relationship" key={relationship.id}>
                <Text size="xs" fw={700}>{relationshipSentence(relationship, names)}</Text>
                <Text size="xs" c="dimmed">{relationship.description}</Text>
                {relationship.evidence.filter(hasCodeLocation).map((evidence) => <EvidenceAction
                  evidence={evidence}
                  explanation={relationship.description}
                  key={`${relationship.id}:${architectureEvidenceKey(evidence)}`}
                  onPreview={onPreviewEvidence}
                />)}
              </div>)}
            </Stack>
          </div>}
          {responsibility.uncertain && <Text size="xs" c="yellow">This responsibility mapping is uncertain.</Text>}
        </section>;
      })}
    </Stack>
  </section>;
}

function EvidenceRow({ evidence, explanation, onPreview }: {
  readonly evidence: EvidenceItem;
  readonly explanation: string;
  readonly onPreview: (evidence: EvidenceItem, explanation: string) => void;
}) {
  return <div className="owner-evidence-row">
    <div>
      <Group gap={5} wrap="wrap">
        <Code>{evidence.label}</Code>
        <Badge size="xs" variant="outline">{humanize(evidence.kind)}</Badge>
      </Group>
      {evidence.text !== undefined && <Text size="xs" c="dimmed">{evidence.text}</Text>}
    </div>
    <EvidenceAction evidence={evidence} explanation={explanation} onPreview={onPreview} />
  </div>;
}

function EvidenceAction({ evidence, explanation, onPreview }: {
  readonly evidence: EvidenceItem;
  readonly explanation: string;
  readonly onPreview: (evidence: EvidenceItem, explanation: string) => void;
}) {
  return <div className="evidence-action">
    <Code>{formatEvidenceLocation(evidence)}</Code>
    <Button
      size="compact-xs"
      variant="subtle"
      disabled={!hasCodeLocation(evidence)}
      onClick={() => onPreview(evidence, explanation)}
    >Show code</Button>
  </div>;
}

function ComponentDetail({ component, steps, brokenStepIds, onPreviewStep }: {
  readonly component: ArchitectureComponent;
  readonly steps: ReadonlyArray<FlowStep>;
  readonly brokenStepIds: ReadonlySet<string>;
  readonly onPreviewStep: (stepId: string) => void;
}) {
  return <div className="component-code-detail">
    {steps.length > 0 && <>
      <div className="code-reference-list">
        {steps.slice(0, 6).map((step) => <div className="code-reference-row" key={step.id}>
          <div className="code-reference-copy">
            <Text size="sm" fw={600}>{step.symbol ?? step.title}</Text>
            <Code>{formatStepLocation(step)}</Code>
          </div>
          <Button
            size="compact-xs"
            variant="subtle"
            disabled={brokenStepIds.has(step.id)}
            onClick={() => onPreviewStep(step.id)}
          >Show code</Button>
        </div>)}
      </div>
    </>}
    {component.key_paths.length > 0 && <>
      <Text size="xs" fw={700} mt={4}>Key files</Text>
      <div className="code-anchor-list">
        {component.key_paths.map((path) => <Code key={path}>{path}</Code>)}
      </div>
    </>}
    {component.key_symbols.length > 0 && <>
      <Text size="xs" fw={700} mt={4}>Key symbols</Text>
      <div className="code-anchor-list">
        {component.key_symbols.map((symbol) => <Code key={symbol}>{symbol}</Code>)}
      </div>
    </>}
    {component.uncertain && <Text size="xs" c="yellow">Grounding is uncertain.</Text>}
  </div>;
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

function stepsForComponent(component: ArchitectureComponent, steps: ReadonlyArray<FlowStep>): ReadonlyArray<FlowStep> {
  const paths = new Set(component.key_paths);
  const symbols = new Set(component.key_symbols);
  return steps.filter((step) => paths.has(step.file_path) || (step.symbol !== undefined && symbols.has(step.symbol)));
}

function relationshipSentence(relationship: ComponentRelationship, names: ReadonlyMap<string, string>): string {
  const from = names.get(relationship.from_component_id) ?? relationship.from_component_id;
  const to = names.get(relationship.to_component_id) ?? relationship.to_component_id;
  return `${from} ${relationshipVerb(relationship.kind)} ${to}`;
}

function relationshipVerb(kind: string): string {
  const verbs: Record<string, string> = {
    call: 'calls',
    calls: 'calls',
    creates: 'creates',
    depends_on: 'depends on',
    read: 'reads from',
    reads: 'reads from',
    write: 'writes to',
    writes: 'writes to',
    data_flow: 'sends data to',
  };
  return verbs[kind] ?? humanize(kind);
}

function relationshipsForResponsibility(
  componentId: string,
  responsibility: ArchitectureResponsibility,
  relationships: ReadonlyArray<ComponentRelationship>,
): ReadonlyArray<ComponentRelationship> {
  const relationshipIds = new Set(responsibility.relationship_ids);
  const explicit = relationships.filter((relationship) => relationshipIds.has(relationship.id));
  if (explicit.length > 0) return explicit;
  const collaborators = new Set(responsibility.collaborator_component_ids);
  return relationships.filter((relationship) => {
    if (relationship.from_component_id === componentId) return collaborators.has(relationship.to_component_id);
    if (relationship.to_component_id === componentId) return collaborators.has(relationship.from_component_id);
    return false;
  });
}

function hasCodeLocation(evidence: EvidenceItem): boolean {
  return evidence.file_path !== undefined && evidence.start_line !== undefined;
}

function evidenceBelongsToOwner(evidence: EvidenceItem, owner: EvidenceItem): boolean {
  if (evidence.file_path !== owner.file_path || evidence.start_line === undefined || owner.start_line === undefined) {
    return false;
  }
  const evidenceEnd = evidence.end_line ?? evidence.start_line;
  const ownerEnd = owner.end_line ?? owner.start_line;
  return evidence.start_line >= owner.start_line && evidenceEnd <= ownerEnd;
}

function formatStepLocation(step: FlowStep): string {
  return `${step.file_path}:${formatLineRange(step.start_line, step.end_line)}`;
}

function formatEvidenceLocation(evidence: EvidenceItem): string {
  if (evidence.file_path === undefined) return evidence.label;
  if (evidence.start_line === undefined) return evidence.file_path;
  return `${evidence.file_path}:${formatLineRange(evidence.start_line, evidence.end_line ?? evidence.start_line)}`;
}

function formatLineRange(start: number, end: number): string {
  return start === end ? String(start) : `${start}-${end}`;
}

function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.slice(-2).join('/');
}
