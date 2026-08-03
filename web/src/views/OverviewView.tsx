import {
  Alert,
  Badge,
  Button,
  Code,
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
  MechanicalCallable,
  MechanicalClass,
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
  const [componentId, setComponentId] = useState(architecture.components[0]?.id ?? '');
  const [ownerKey, setOwnerKey] = useState<string>();
  const [detailTab, setDetailTab] = useState<string>('responsibilities');
  const [symbolInventory, setSymbolInventory] = useState<MechanicalSymbolInventory>();
  const [symbolError, setSymbolError] = useState<string>();
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
    ...component.evidence
      .map((evidence) => evidence.file_path)
      .filter((path): path is string => path !== undefined),
    ...responsibilities.flatMap((responsibility) => responsibility.evidence)
      .map((evidence) => evidence.file_path)
      .filter((path): path is string => path !== undefined),
  ])], [component, responsibilities]);
  const mechanicalModules = useMemo(
    () => component === undefined || symbolInventory === undefined
      ? []
      : modulesForComponent(symbolInventory, mappedFiles),
    [component, mappedFiles, symbolInventory],
  );
  const mechanicalCounts = useMemo(() => countMechanicalSymbols(mechanicalModules), [mechanicalModules]);
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
  useEffect(() => {
    let active = true;
    loadSymbolInventory()
      .then((inventory) => active && setSymbolInventory(inventory))
      .catch((reason: unknown) => active && setSymbolError(messageOf(reason)));
    return () => { active = false; };
  }, [loadSymbolInventory]);

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
            {mappedFiles.length > 0 && <details className="component-files">
              <summary>Mapped code <span>{formatCount(mappedFiles.length, 'path')}</span></summary>
              <div className="component-file-list">
                {mappedFiles.map((path) => <Code key={path}>{path}</Code>)}
              </div>
            </details>}
            {symbolInventory !== undefined && <UnstyledButton
              className="component-structure-summary"
              onClick={() => setDetailTab('structure')}
            >
              <span>AST-grounded</span>
              <Text size="xs">Explore implementation · {formatMechanicalCounts(mechanicalCounts)}</Text>
            </UnstyledButton>}
          </section>}
          {component !== undefined && <Tabs value={detailTab} onChange={(value) => setDetailTab(value ?? 'responsibilities')}>
            <Tabs.List grow>
              <Tabs.Tab value="responsibilities">Responsibilities ({responsibilities.length})</Tabs.Tab>
              <Tabs.Tab value="structure">Implementation ({mechanicalCounts.classes + mechanicalCounts.functions})</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="responsibilities" pt="md">
              <Stack gap="md">
                <ResponsibilityMap
                  component={component}
                  responsibilities={responsibilities}
                  relationships={architecture.relationships}
                  names={names}
                  selectedOwnerKey={ownerKey}
                  onOwnerSelect={setOwnerKey}
                />
                {component.responsibilities.length === 0 && <section className="inspector-panel">
                  <Text fw={700}>Representative code</Text>
                  <Text size="xs" c="dimmed">No AI responsibility mapping was returned for this component.</Text>
                  <ComponentDetail
                    component={component}
                    steps={componentSteps}
                    brokenStepIds={brokenStepIds}
                    onPreviewStep={onPreviewStep}
                  />
                </section>}
                {selectedOwner !== undefined && <CodeOwnerDetail
                  component={component}
                  owner={selectedOwner}
                  responsibilities={responsibilities}
                  relationships={architecture.relationships}
                  names={names}
                  onPreviewEvidence={onPreviewEvidence}
                />}
              </Stack>
            </Tabs.Panel>
            <Tabs.Panel value="structure" pt="md">
              {symbolError !== undefined
                ? <Alert color="yellow">{symbolError}</Alert>
                : symbolInventory === undefined
                  ? <Text size="sm" c="dimmed">Loading mechanical code structure…</Text>
                  : <MechanicalStructure
                      modules={mechanicalModules}
                      responsibilities={responsibilities}
                      inventory={symbolInventory}
                      onPreviewEvidence={onPreviewEvidence}
                    />}
            </Tabs.Panel>
          </Tabs>}
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

function MechanicalStructure({
  modules,
  responsibilities,
  inventory,
  onPreviewEvidence,
}: {
  readonly modules: ReadonlyArray<MechanicalModule>;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly inventory: MechanicalSymbolInventory;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
}) {
  const counts = countMechanicalSymbols(modules);
  return <section aria-label="Mechanical code structure" className="mechanical-structure">
    <div className="mechanical-structure-heading">
      <div>
        <Text fw={700}>How this component is implemented</Text>
        <Text size="xs" c="dimmed">
          Headings make exact AST names easier to read. Responsibility notes appear only when the provider mapped them to a symbol.
        </Text>
      </div>
      <Text size="xs" c="dimmed" className="mechanical-counts">
        {formatMechanicalCounts(counts)}
      </Text>
    </div>
    {inventory.truncated && <Alert color="yellow">The mechanical inventory reached its configured limit.</Alert>}
    {modules.length === 0
      ? <Text size="sm" c="dimmed">No Python classes or functions were detected in this component’s mapped files.</Text>
      : <div className="symbol-module-list">
          {modules.map((module, index) => <details className="symbol-module" key={module.path} open={index === 0}>
            <summary>
              <Code>{module.path}</Code>
              <span>{formatModuleCounts(module)}</span>
            </summary>
            <div className="symbol-module-body">
              {module.classes.length > 0 && <div className="symbol-group">
                <Text size="xs" fw={700} c="dimmed" tt="uppercase">Types defined here</Text>
                {module.classes.map((item) => <MechanicalClassCard
                  classSymbol={item}
                  modulePath={module.path}
                  responsibilities={responsibilities}
                  key={`${module.path}:${item.name}:${item.start_line}`}
                  onPreviewEvidence={onPreviewEvidence}
                />)}
              </div>}
              {module.functions.length > 0 && <details className="symbol-callable-group" open={module.functions.length <= 6}>
                <summary>Module operations <span>{formatCount(module.functions.length, 'function')}</span></summary>
                <div className="symbol-callable-list">
                  {module.functions.map((item) => <MechanicalCallableRow
                    symbol={item}
                    kind="function"
                    modulePath={module.path}
                    responsibilities={responsibilities}
                    key={`${module.path}:${item.name}:${item.start_line}`}
                    onPreviewEvidence={onPreviewEvidence}
                  />)}
                </div>
              </details>}
            </div>
          </details>)}
        </div>}
  </section>;
}

function MechanicalClassCard({
  classSymbol,
  modulePath,
  responsibilities,
  onPreviewEvidence,
}: {
  readonly classSymbol: MechanicalClass;
  readonly modulePath: string;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
}) {
  const mappings = mappingsForSymbol(responsibilities, modulePath, [classSymbol.name]);
  const plainName = humanizeSymbol(classSymbol.name);
  const explanation = mappings[0]?.description ?? `Defines ${plainName.toLowerCase()} in ${modulePath}.`;
  const evidence = mechanicalEvidence('class', classSymbol.name, modulePath, classSymbol);
  const declaration = classSymbol.bases.length === 0
    ? classSymbol.name
    : `${classSymbol.name}(${classSymbol.bases.join(', ')})`;
  return <article className="mechanical-class">
    <div className="mechanical-symbol-heading">
      <div>
        <Text size="sm" fw={700}>{plainName}</Text>
        <div className="symbol-signature">
          <span>Class</span>
          <Code>{declaration}</Code>
          <span>L{classSymbol.start_line}–{classSymbol.end_line}</span>
        </div>
      </div>
      <Button size="compact-xs" variant="subtle" onClick={() => onPreviewEvidence(evidence, explanation)}>
        View source
      </Button>
    </div>
    <SymbolMappings mappings={mappings} />
    {classSymbol.state_fields.length > 0 && <div className="mechanical-state">
      <Text size="xs" fw={700} c="dimmed">Keeps track of</Text>
      <Text size="sm">{naturalList(classSymbol.state_fields.map(humanizeSymbol))}</Text>
    </div>}
    {classSymbol.methods.length > 0 && <details className="symbol-callable-group" open={classSymbol.methods.length <= 5}>
      <summary>What it can do <span>{formatCount(classSymbol.methods.length, 'method')}</span></summary>
      <div className="symbol-callable-list">
        {classSymbol.methods.map((method) => <MechanicalCallableRow
          symbol={method}
          kind="method"
          modulePath={modulePath}
          responsibilities={responsibilities}
          ownerName={classSymbol.name}
          key={`${modulePath}:${classSymbol.name}:${method.name}:${method.start_line}`}
          onPreviewEvidence={onPreviewEvidence}
        />)}
      </div>
    </details>}
  </article>;
}

function MechanicalCallableRow({
  symbol,
  kind,
  modulePath,
  responsibilities,
  ownerName,
  onPreviewEvidence,
}: {
  readonly symbol: MechanicalCallable;
  readonly kind: 'function' | 'method';
  readonly modulePath: string;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly ownerName?: string;
  readonly onPreviewEvidence: (evidence: EvidenceItem, explanation: string) => void;
}) {
  const qualifiedName = ownerName === undefined ? symbol.name : `${ownerName}.${symbol.name}`;
  const mappings = mappingsForSymbol(responsibilities, modulePath, [qualifiedName, symbol.name]);
  const evidence = mechanicalEvidence(kind, qualifiedName, modulePath, symbol);
  const plainName = humanizeSymbol(symbol.name);
  const explanation = mappings[0]?.description ?? `${plainName} in ${modulePath}.`;
  return <div className="mechanical-callable-row">
    <div>
      <Text size="sm" fw={600}>{plainName}</Text>
      <div className="symbol-signature">
        <Code>{qualifiedName}()</Code>
        <span>L{symbol.start_line}–{symbol.end_line}</span>
      </div>
      <SymbolMappings mappings={mappings} />
    </div>
    <Button size="compact-xs" variant="subtle" onClick={() => onPreviewEvidence(evidence, explanation)}>
      View source
    </Button>
  </div>;
}

function SymbolMappings({ mappings }: {
  readonly mappings: ReadonlyArray<{ readonly title: string; readonly description: string }>;
}) {
  if (mappings.length === 0) return null;
  return <div className="symbol-mapping-list">
    {mappings.map((mapping) => <div className="symbol-mapping" key={`${mapping.title}:${mapping.description}`}>
      <Text size="xs" fw={700} c="dimmed">Supports “{mapping.title}”</Text>
      <Text size="sm">{mapping.description}</Text>
    </div>)}
  </div>;
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
      <Text fw={700}>What this component owns</Text>
      <Text size="xs" c="dimmed">Outcome, grounded owner, and collaborating boundary.</Text>
    </div>
    <div className="responsibility-table-wrap">
      <table className="responsibility-table">
        <thead><tr><th>Responsibility</th><th>Code owner</th><th>Collaborates with</th></tr></thead>
        <tbody>{responsibilities.map((responsibility, index) => {
          const owners = responsibilityOwners(responsibility);
          const connections = relationshipsForResponsibility(component.id, responsibility, relationships);
          return <tr key={responsibility.id}>
            <td className="responsibility-copy">
              <Text className="responsibility-index" size="xs" c="dimmed" fw={700}>
                {String(index + 1).padStart(2, '0')}
              </Text>
              <Text size="sm" fw={700}>{responsibility.title}</Text>
              <Text size="xs" c="dimmed">{responsibility.description}</Text>
            </td>
            <td><div className="responsibility-owner-list">
              {owners.length === 0
                ? <Text size="xs" c="dimmed">No grounded owner</Text>
                : owners.map((owner) => {
                  const key = architectureEvidenceKey(owner);
                  return <UnstyledButton
                    aria-label={`${humanize(owner.kind)} ${owner.label}`}
                    aria-pressed={key === selectedOwnerKey}
                    className={`responsibility-owner${key === selectedOwnerKey ? ' selected' : ''}`}
                    key={key}
                    onClick={() => onOwnerSelect(key)}
                  >
                    <Text size="sm" fw={600}>{humanizeSymbol(owner.label)}</Text>
                    <div className="responsibility-owner-heading">
                      <Code>{owner.label}</Code>
                      <span>{humanize(owner.kind)}</span>
                    </div>
                    {owner.file_path !== undefined && <Text size="xs" c="dimmed">
                      {formatEvidenceLocation(owner)}
                    </Text>}
                  </UnstyledButton>;
                })}
            </div></td>
            <td><div className="responsibility-collaborator-list">
              {responsibility.collaborator_component_ids.length === 0
                ? <Text size="xs" c="dimmed">Internal</Text>
                : responsibility.collaborator_component_ids.map((id) => {
                  const relationship = connections.find((candidate) =>
                    candidate.from_component_id === id || candidate.to_component_id === id);
                    return <div className="responsibility-collaborator" key={id}>
                      <Text size="xs" fw={600}>{names.get(id) ?? id}</Text>
                      {relationship !== undefined && <Text size="xs" c="dimmed">{relationship.description}</Text>}
                    </div>;
                })}
            </div></td>
          </tr>;
        })}</tbody>
      </table>
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
        <Title order={4}>{humanizeSymbol(owner.label)}</Title>
        <div className="symbol-signature">
          <span>{humanize(owner.kind)}</span>
          <Code>{owner.label}</Code>
        </div>
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
            <Text size="xs" fw={700} c="dimmed" mb={5}>Implementation details</Text>
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
      <Text size="sm" fw={600}>{humanizeSymbol(evidence.label)}</Text>
      <Group className="evidence-signature" gap={5} wrap="wrap">
        <Code>{evidence.label}</Code>
        <Text size="xs" c="dimmed">{humanize(evidence.kind)}</Text>
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

function humanizeSymbol(value: string): string {
  const specialNames: Readonly<Record<string, string>> = {
    __call__: 'Run when called',
    __enter__: 'Enter the context',
    __exit__: 'Exit the context',
    __init__: 'Initialize',
  };
  const special = specialNames[value];
  if (special !== undefined) return special;
  const words = value
    .replace(/\(\)$/, '')
    .replace(/^_+|_+$/g, '')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[._]+/g, ' ')
    .trim()
    .toLowerCase();
  return words.length === 0 ? value : `${words[0].toUpperCase()}${words.slice(1)}`;
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

function modulesForComponent(
  inventory: MechanicalSymbolInventory,
  mappedPaths: ReadonlyArray<string>,
): ReadonlyArray<MechanicalModule> {
  const paths = mappedPaths.map((path) => path.replace(/\/$/, ''));
  return [...inventory.modules
    .filter((module) => paths.some((path) => module.path === path || module.path.startsWith(`${path}/`)))]
    .sort((left, right) => left.path.localeCompare(right.path));
}

interface MechanicalCounts {
  readonly classes: number;
  readonly functions: number;
  readonly methods: number;
}

function countMechanicalSymbols(modules: ReadonlyArray<MechanicalModule>): MechanicalCounts {
  return modules.reduce((counts, module) => ({
    classes: counts.classes + module.classes.length,
    functions: counts.functions + module.functions.length,
    methods: counts.methods + module.classes.reduce((total, item) => total + item.methods.length, 0),
  }), { classes: 0, functions: 0, methods: 0 });
}

function formatMechanicalCounts(counts: MechanicalCounts): string {
  return [
    formatCount(counts.classes, 'class', 'classes'),
    formatCount(counts.functions, 'function'),
    formatCount(counts.methods, 'method'),
  ].join(' · ');
}

function formatModuleCounts(module: MechanicalModule): string {
  return [
    formatCount(module.classes.length, 'type'),
    formatCount(module.functions.length, 'module operation'),
  ].join(' · ');
}

function formatCount(value: number, singular: string, plural = `${singular}s`): string {
  return `${value} ${value === 1 ? singular : plural}`;
}

function naturalList(values: ReadonlyArray<string>): string {
  if (values.length < 2) return values[0] ?? '';
  if (values.length === 2) return `${values[0]} and ${values[1]}`;
  return `${values.slice(0, -1).join(', ')}, and ${values[values.length - 1]}`;
}

function mappingsForSymbol(
  responsibilities: ReadonlyArray<ArchitectureResponsibility>,
  modulePath: string,
  names: ReadonlyArray<string>,
): ReadonlyArray<{ readonly title: string; readonly description: string }> {
  const mappings = responsibilities.flatMap((responsibility) => responsibility.evidence
    .filter((evidence) => evidence.file_path === modulePath && names.some((name) => {
      const label = evidence.label.replaceAll('`', '').replace(/\(\)$/, '');
      return label === name || label.startsWith(`${name} `);
    }))
    .map((evidence) => ({
      title: responsibility.title,
      description: evidence.text ?? responsibility.description,
    })));
  return mappings.filter((mapping, index) => mappings.findIndex((candidate) =>
    candidate.title === mapping.title && candidate.description === mapping.description) === index);
}

function mechanicalEvidence(
  kind: string,
  label: string,
  filePath: string,
  symbol: MechanicalCallable,
): EvidenceItem {
  return {
    kind,
    label,
    file_path: filePath,
    start_line: symbol.start_line,
    end_line: symbol.end_line,
  };
}

function messageOf(reason: unknown): string {
  return reason instanceof Error ? reason.message : String(reason);
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
