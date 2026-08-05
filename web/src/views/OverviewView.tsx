import {
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  ScrollArea,
  Stack,
  Tabs,
  Text,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import { ArchitectureDiagram } from '../ArchitectureDiagram';
import {
  availableArchitectureDepths,
  type ArchitectureDepth,
} from '../architecture/diagramModel';
import {
  codeOwners,
  modulesForComponent,
  stepsForComponent,
  type CodeOwner,
} from '../architecture/codeOwners';
import {
  architectureEvidenceKey,
  componentResponsibilities,
  hasCodeLocation,
  methodBehavior,
  methodLabel,
  responsibilityGrounding,
} from '../architecture/evidence';
import { humanize, roleForKind } from '../architecture/taxonomy';
import type { RightPaneActions } from '../RightPane';
import type {
  ArchitectureContainer,
  ArchitectureResponsibility,
  CodebaseArchitecture,
  EvidenceItem,
  FlowStep,
  LearningStage,
  MechanicalModule,
  MechanicalSymbolInventory,
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
  const hasSystemNotes = (flow.architecture?.cross_cutting_concerns.length ?? 0) > 0 ||
    (flow.architecture?.coverage_notes.length ?? 0) > 0;

  return (
    <div className="pane-column">
      <div className="pane-header overview-header">
        <div>
          <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>Walkthrough mapped</Text>
          <Title order={2} lineClamp={1}>{session.question ?? 'Repository walkthrough'}</Title>
        </div>
        <Text size="sm" c="dimmed" lineClamp={2}>{flow.summary}</Text>
      </div>
      <Tabs defaultValue={flow.architecture === undefined ? 'path' : 'architecture'} className="overview-tabs">
        <Tabs.List className="overview-tab-list">
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
            loadSymbolInventory={actions.loadSymbolInventory}
          />
        </Tabs.Panel>}
        <Tabs.Panel value="path" className="overview-panel">
          <div className="learning-path-workspace">
            <nav aria-label="Learning stages" className="learning-stage-rail">
              <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>Route</Text>
              {stages.map((stage, index) => <UnstyledButton
                aria-current={index === stageIndex ? 'step' : undefined}
                className={`learning-stage${index === stageIndex ? ' selected' : ''}`}
                key={stage.id}
                onClick={() => setStageIndex(index)}
              >
                <span>{String(index + 1).padStart(2, '0')}</span>
                <div>
                  <Text size="sm" fw={700}>{stage.title}</Text>
                  <Text size="xs" c="dimmed">{stage.step_ids.length} code {stage.step_ids.length === 1 ? 'stop' : 'stops'}</Text>
                </div>
              </UnstyledButton>)}
            </nav>
            <ScrollArea className="learning-stage-detail" offsetScrollbars>
              {activeStage !== undefined && <div className="learning-stage-content">
                <header className="stage-goal">
                  <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>
                    Stage {stageIndex + 1} of {stages.length}
                  </Text>
                  <Title order={3}>{activeStage.goal}</Title>
                </header>
                <div aria-label="Validated code stops" className="code-stop-table" role="table">
                  <div aria-hidden="true" className="code-stop-table-header" role="row">
                    <span>Stop</span><span>Validated stop</span><span>Type</span>
                  </div>
                  {stageSteps.map((step, index) => {
                    const disabled = broken.has(step.id);
                    return <UnstyledButton
                      aria-label={`${step.title}, ${shortPath(step.file_path)} line ${step.start_line}`}
                      className={`code-stop-row${selectedStepId === step.id ? ' selected' : ''}`}
                      disabled={disabled}
                      key={step.id}
                      onClick={() => setSelectedStepId(step.id)}
                      role="row"
                    >
                      <span className="code-stop-index">{String(index + 1).padStart(2, '0')}</span>
                      <span className="code-stop-title">
                        <Text size="sm" fw={700}>{step.title}</Text>
                        <Text size="xs" c="dimmed" ff="monospace">{shortPath(step.file_path)}:{step.start_line}</Text>
                      </span>
                      <Badge size="xs" variant="light" color={disabled ? 'red' : 'gray'}>
                        {disabled ? 'broken' : step.step_type ?? step.importance ?? 'step'}
                      </Badge>
                    </UnstyledButton>;
                  })}
                </div>
                {activeStage.checkpoint !== undefined && <div className="stage-checkpoint">
                  <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>Checkpoint</Text>
                  <Text size="sm">{activeStage.checkpoint}</Text>
                </div>}
              </div>}
            </ScrollArea>
          </div>
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

function ArchitectureView({
  architecture,
  steps,
  brokenStepIds,
  onPreviewStep,
  onPreviewEvidence,
  loadSymbolInventory,
}: {
  readonly architecture: CodebaseArchitecture;
  readonly steps: ReadonlyArray<FlowStep>;
  readonly brokenStepIds: ReadonlySet<string>;
  readonly onPreviewStep: (stepId: string) => void;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
  readonly loadSymbolInventory: () => Promise<MechanicalSymbolInventory>;
}) {
  const isVirtualSample = architecture.components.some((component) => component.key_paths.includes('.walkthrough-sample.ts'));
  const [depth, setDepth] = useState<ArchitectureDepth>('context');
  const [componentId, setComponentId] = useState(architecture.components[0]?.id ?? '');
  const [containerId, setContainerId] = useState(architecture.containers?.[0]?.id);
  const [ownerKey, setOwnerKey] = useState<string>();
  const [symbolInventory, setSymbolInventory] = useState<MechanicalSymbolInventory>();
  const [symbolError, setSymbolError] = useState<string>();
  const effectiveArchitecture = symbolInventory?.architecture ?? architecture;
  const depths = useMemo(() => availableArchitectureDepths(effectiveArchitecture), [effectiveArchitecture]);
  const component = effectiveArchitecture.components.find((candidate) => candidate.id === componentId)
    ?? effectiveArchitecture.components[0];
  const container = (effectiveArchitecture.containers ?? []).find((candidate) => candidate.id === containerId)
    ?? effectiveArchitecture.containers?.[0];
  const responsibilities = useMemo(
    () => component === undefined ? [] : componentResponsibilities(component),
    [component],
  );
  const mappedFiles = useMemo(() => component === undefined ? [] : [...new Set([
    ...component.key_paths,
    ...component.evidence
      .map((evidence) => evidence.file_path)
      .filter((path): path is string => path !== undefined),
    ...responsibilities.flatMap((responsibility) => responsibility.evidence)
      .map((evidence) => evidence.file_path)
      .filter((path): path is string => path !== undefined),
  ])], [component, responsibilities]);
  const mechanicalModules = useMemo(
    () => symbolInventory === undefined ? [] : modulesForComponent(symbolInventory, mappedFiles),
    [mappedFiles, symbolInventory],
  );
  const componentSteps = useMemo(
    () => component === undefined ? [] : stepsForComponent(component, steps),
    [component, steps],
  );
  const inventoryLoading = !isVirtualSample && symbolInventory === undefined && symbolError === undefined;

  useEffect(() => {
    if (!effectiveArchitecture.components.some((candidate) => candidate.id === componentId)) {
      setComponentId(effectiveArchitecture.components[0]?.id ?? '');
    }
    if (!(effectiveArchitecture.containers ?? []).some((candidate) => candidate.id === containerId)) {
      setContainerId(effectiveArchitecture.containers?.[0]?.id);
    }
    if (!inventoryLoading && !depths.includes(depth)) setDepth(depths[0] ?? 'components');
  }, [componentId, containerId, depth, depths, effectiveArchitecture, inventoryLoading]);
  useEffect(() => setOwnerKey(undefined), [componentId]);
  useEffect(() => {
    if (isVirtualSample) return;
    let active = true;
    setSymbolError(undefined);
    loadSymbolInventory()
      .then((inventory) => active && setSymbolInventory(inventory))
      .catch((reason: unknown) => active && setSymbolError(messageOf(reason)));
    return () => { active = false; };
  }, [isVirtualSample, loadSymbolInventory]);

  const selectContainer = (selectedId: string) => {
    setContainerId(selectedId);
    const selected = effectiveArchitecture.containers?.find((candidate) => candidate.id === selectedId);
    const firstComponentId = selected?.component_ids[0];
    if (firstComponentId !== undefined) setComponentId(firstComponentId);
  };
  const selectComponent = (selectedId: string) => {
    setComponentId(selectedId);
    setOwnerKey(undefined);
  };
  const previewEvidence = (evidence: EvidenceItem) =>
    onPreviewEvidence(evidence, evidence.text ?? `Source evidence for ${evidence.label}.`);

  return (
    <div className="architecture-workspace">
      <div aria-label="Architecture diagram workspace" className="architecture-workspace-diagram">
        {inventoryLoading && <Text size="sm" c="dimmed">Refreshing grounded architecture…</Text>}
        {!inventoryLoading && effectiveArchitecture.components.length > 0 && <ArchitectureDiagram
          architecture={effectiveArchitecture}
          depth={depth}
          selectedComponentId={componentId}
          selectedContainerId={container?.id}
          selectedOwnerKey={ownerKey}
          onDepthChange={setDepth}
          onComponentSelect={selectComponent}
          onContainerSelect={selectContainer}
          onOwnerSelect={setOwnerKey}
          onEvidenceSelect={previewEvidence}
        />}
      </div>
      <ScrollArea aria-label="Component details" className="architecture-inspector" offsetScrollbars>
        <Stack gap="md" p="sm">
          {symbolError !== undefined && <Text size="xs" c="yellow">Mechanical structure unavailable: {symbolError}</Text>}
          {depth === 'context' && <ContextInspector architecture={effectiveArchitecture} />}
          {depth === 'containers' && container !== undefined && <ContainerInspector
            container={container}
            architecture={effectiveArchitecture}
            onComponentSelect={(id) => { selectComponent(id); setDepth('components'); }}
            onPreviewEvidence={previewEvidence}
          />}
          {depth === 'components' && component !== undefined && <ComponentInspector
            componentId={component.id}
            architecture={effectiveArchitecture}
            onComponentSelect={selectComponent}
            onPreviewEvidence={previewEvidence}
          />}
          {depth === 'code' && component !== undefined && <section
            aria-label="Selected diagram component"
            aria-live="polite"
            className="component-summary"
          >
            <Group gap="xs" wrap="wrap">
              <Title order={4}>{component.name}</Title>
              <Badge size="sm" variant="light">{roleForKind(component.kind)}</Badge>
            </Group>
            <Text size="sm">{component.responsibility}</Text>
          </section>}
          {depth === 'code' && component !== undefined && <CodeOwnershipList
            responsibilities={responsibilities}
            modules={mechanicalModules}
            keySymbols={component.key_symbols}
            selectedOwnerKey={ownerKey}
            fallbackSteps={componentSteps}
            brokenStepIds={brokenStepIds}
            symbolError={undefined}
            inventoryLoading={symbolInventory === undefined}
            onPreviewEvidence={onPreviewEvidence}
            onPreviewStep={onPreviewStep}
          />}
        </Stack>
      </ScrollArea>
    </div>
  );
}

function ContextInspector({ architecture }: { readonly architecture: CodebaseArchitecture }) {
  const containers = architecture.containers ?? [];
  return <Stack gap="md">
    <section className="component-summary">
      <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>System context</Text>
      <Title order={4}>{architecture.system_name ?? 'Analyzed system'}</Title>
      <Text size="sm">{architecture.system_purpose}</Text>
    </section>
    <section className="architecture-fact-list">
      <Text fw={700} size="sm">Verified entry surfaces</Text>
      {containers.map((item) => <div key={item.id}>
        <Text size="sm" fw={700}>{item.name}</Text>
        <Text size="xs" c="dimmed">{humanize(item.kind)}</Text>
      </div>)}
    </section>
  </Stack>;
}

function ContainerInspector({ container, architecture, onComponentSelect, onPreviewEvidence }: {
  readonly container: ArchitectureContainer;
  readonly architecture: CodebaseArchitecture;
  readonly onComponentSelect: (componentId: string) => void;
  readonly onPreviewEvidence: (evidence: EvidenceItem) => void;
}) {
  const components = container.component_ids.map((id) => architecture.components.find((item) => item.id === id))
    .filter((item): item is NonNullable<typeof item> => item !== undefined);
  return <Stack gap="md">
    <section className="component-summary">
      <Group gap="xs" wrap="wrap"><Title order={4}>{container.name}</Title><Badge size="sm" variant="light">{humanize(container.kind)}</Badge></Group>
      <Text size="sm">{container.responsibility}</Text>
      <Group gap="xs" wrap="wrap">{container.evidence.map((item) => <Button
        key={`${item.file_path}:${item.start_line}`}
        size="compact-xs"
        variant="subtle"
        disabled={!hasCodeLocation(item)}
        onClick={() => onPreviewEvidence(item)}
      >{item.label}</Button>)}</Group>
    </section>
    <section className="architecture-fact-list">
      <Text fw={700} size="sm">Included components</Text>
      {components.map((item) => <UnstyledButton key={item.id} onClick={() => onComponentSelect(item.id)}>
        <Text size="sm" fw={700}>{item.name}</Text><Text size="xs" c="dimmed">{item.responsibility}</Text>
      </UnstyledButton>)}
    </section>
  </Stack>;
}

function ComponentInspector({ componentId, architecture, onComponentSelect, onPreviewEvidence }: {
  readonly componentId: string;
  readonly architecture: CodebaseArchitecture;
  readonly onComponentSelect: (componentId: string) => void;
  readonly onPreviewEvidence: (evidence: EvidenceItem) => void;
}) {
  const component = architecture.components.find((item) => item.id === componentId);
  if (component === undefined) return null;
  const connectedIds = architecture.relationships.flatMap((relationship) => {
    if (relationship.from_component_id === component.id) return [relationship.to_component_id];
    if (relationship.to_component_id === component.id) return [relationship.from_component_id];
    return [];
  });
  const connected = [...new Set(connectedIds)].map((id) => architecture.components.find((item) => item.id === id))
    .filter((item): item is NonNullable<typeof item> => item !== undefined);
  return <Stack gap="md">
    <section aria-label="Selected diagram component" aria-live="polite" className="component-summary">
      <Group gap="xs" wrap="wrap"><Title order={4}>{component.name}</Title><Badge size="sm" variant="light">{roleForKind(component.kind)}</Badge></Group>
      <Text size="sm">{component.responsibility}</Text>
      <Group gap="xs" wrap="wrap">{component.evidence.slice(0, 4).map((item) => <Button
        key={`${item.file_path}:${item.start_line}`}
        size="compact-xs"
        variant="subtle"
        disabled={!hasCodeLocation(item)}
        onClick={() => onPreviewEvidence(item)}
      >{shortPath(item.file_path ?? item.label)}</Button>)}</Group>
    </section>
    <section className="architecture-fact-list">
      <Text fw={700} size="sm">Direct collaborators</Text>
      {connected.length === 0 && <Text size="sm" c="dimmed">No verified cross-component relationship.</Text>}
      {connected.map((item) => <UnstyledButton key={item.id} onClick={() => onComponentSelect(item.id)}>
        <Text size="sm" fw={700}>{item.name}</Text><Text size="xs" c="dimmed">{item.responsibility}</Text>
      </UnstyledButton>)}
    </section>
  </Stack>;
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

function CodeOwnershipList({
  responsibilities,
  modules,
  keySymbols,
  selectedOwnerKey,
  fallbackSteps,
  brokenStepIds,
  symbolError,
  inventoryLoading,
  onPreviewEvidence,
  onPreviewStep,
}: {
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly modules: ReadonlyArray<MechanicalModule>;
  readonly keySymbols: ReadonlyArray<string>;
  readonly selectedOwnerKey?: string;
  readonly fallbackSteps: ReadonlyArray<FlowStep>;
  readonly brokenStepIds: ReadonlySet<string>;
  readonly symbolError?: string;
  readonly inventoryLoading: boolean;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
  readonly onPreviewStep: (stepId: string) => void;
}) {
  const owners = codeOwners(modules, responsibilities, keySymbols);
  return <section aria-label="Class ownership" className="code-ownership">
    {symbolError !== undefined && <Text size="xs" c="yellow">Mechanical structure unavailable: {symbolError}</Text>}
    {owners.length > 0 && <div className="code-ownership-list">
      {owners.map((owner) => <CodeOwnerRow
        owner={owner}
        selected={selectedOwnerKey === architectureEvidenceKey(owner.evidence)}
        key={architectureEvidenceKey(owner.evidence)}
        onPreview={onPreviewEvidence}
      />)}
    </div>}
    {owners.length === 0 && inventoryLoading && <Text size="sm" c="dimmed">Loading source structure…</Text>}
    {owners.length === 0 && !inventoryLoading && fallbackSteps.length === 0 && <Text size="sm" c="dimmed">
      No class or function could be grounded for this component.
    </Text>}
    {owners.length === 0 && fallbackSteps.length > 0 && <ValidatedCodeStops
      steps={fallbackSteps}
      brokenStepIds={brokenStepIds}
      onPreviewStep={onPreviewStep}
    />}
  </section>;
}

function CodeOwnerRow({ owner, selected, onPreview }: {
  readonly owner: CodeOwner;
  readonly selected: boolean;
  readonly onPreview: (evidence: EvidenceItem, explanation: string) => void;
}) {
  const { evidence } = owner;
  return <article className={`code-owner-row${selected ? ' selected' : ''}`}>
    <header className="code-owner-heading">
      <div>
        <Text className="code-owner-kind" size="xs">{humanize(evidence.kind)}</Text>
        <Text className="code-owner-name" fw={700}>{evidence.label}</Text>
      </div>
      <Button
        aria-label={`Show ${evidence.label} source`}
        size="compact-xs"
        variant="subtle"
        disabled={!hasCodeLocation(evidence)}
        onClick={() => onPreview(evidence, evidence.text ?? 'Source anchor for this code owner.')}
      >Show code</Button>
    </header>
    {owner.responsibilities.length > 0 && <div className="code-owner-responsibilities">
      <Text className="code-owner-label" size="xs">Responsibilities</Text>
      <ul className="code-owner-responsibility-list">
        {owner.responsibilities.map((responsibility) => {
          const grounding = responsibilityGrounding(responsibility);
          return <li key={responsibility.id}>
            <Text size="sm" fw={700}>{responsibility.title}</Text>
            <Text size="xs" c="dimmed">{responsibility.description}</Text>
            {grounding !== undefined && <Text className="code-owner-responsibility-evidence" size="xs" c="dimmed">
              Evidence: {grounding}
            </Text>}
          </li>;
        })}
      </ul>
    </div>}
    {owner.methods.length > 0 && <div className="code-owner-methods">
      <Text className="code-owner-label" size="xs">Methods</Text>
      <div className="code-owner-method-list">
        {owner.methods.map((method) => {
          const behavior = methodBehavior(method, owner.responsibilities);
          return <div className="code-owner-method-row" key={architectureEvidenceKey(method)}>
            <UnstyledButton
              aria-label={`Show ${method.label} source`}
              className="code-owner-method"
              disabled={!hasCodeLocation(method)}
              onClick={() => onPreview(method, behavior ?? `Method owned by ${evidence.label}.`)}
            >{methodLabel(method.label)}</UnstyledButton>
            {behavior !== undefined && <Text className="code-owner-method-behavior" size="xs" c="dimmed">{behavior}</Text>}
          </div>;
        })}
      </div>
    </div>}
  </article>;
}

function ValidatedCodeStops({ steps, brokenStepIds, onPreviewStep }: {
  readonly steps: ReadonlyArray<FlowStep>;
  readonly brokenStepIds: ReadonlySet<string>;
  readonly onPreviewStep: (stepId: string) => void;
}) {
  return <div className="validated-code-stops">
    <Text className="code-owner-label" size="xs">Validated code stops</Text>
    {steps.map((step) => <UnstyledButton
      aria-label={`Show ${step.symbol ?? step.title} source`}
      className="validated-code-stop"
      disabled={brokenStepIds.has(step.id)}
      key={step.id}
      onClick={() => onPreviewStep(step.id)}
    >
      <Text size="sm" fw={700}>{step.symbol ?? step.title}</Text>
      <Text size="xs" c="dimmed">{shortPath(step.file_path)}:{formatLineRange(step.start_line, step.end_line)}</Text>
    </UnstyledButton>)}
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

function formatLineRange(start: number, end: number): string {
  return start === end ? String(start) : `${start}-${end}`;
}

function shortPath(path: string): string {
  const parts = path.split('/');
  return parts.slice(-2).join('/');
}

function messageOf(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
}
