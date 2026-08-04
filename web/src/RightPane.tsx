import {
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Loader,
  ScrollArea,
  SegmentedControl,
  Select,
  Text,
  Textarea,
  Title,
} from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import type {
  AnalysisModeId,
  EvidenceItem,
  MechanicalSymbolInventory,
  ProviderId,
  ProviderStatus,
  SessionSnapshot,
  WalkthroughSettings,
} from './types';
import { OverviewView } from './views/OverviewView';
import { TourView } from './views/TourView';

export interface RightPaneActions {
  readonly startMapping: (question: string, mode: AnalysisModeId, provider: ProviderId) => Promise<void>;
  readonly showSample: () => Promise<void>;
  readonly cancelMapping: () => Promise<void>;
  readonly tour: (action: 'start' | 'preview' | 'next' | 'previous' | 'stop' | 'new', stepId?: string) => Promise<void>;
  readonly answer: (question: string) => Promise<void>;
  readonly copyMarkdown: () => Promise<void>;
  readonly loadSymbolInventory: () => Promise<MechanicalSymbolInventory>;
  readonly openSettings: () => void;
  readonly focusCode: () => void;
  readonly previewEvidence: (evidence: EvidenceItem, explanation: string) => void;
}

interface RightPaneProps {
  readonly session: SessionSnapshot;
  readonly settings?: WalkthroughSettings;
  readonly providers: ReadonlyArray<ProviderStatus>;
  readonly actions: RightPaneActions;
  readonly actionError?: string;
  readonly compact?: boolean;
}

export function RightPane({ session, settings, providers, actions, actionError, compact = false }: RightPaneProps) {
  return (
    <aside className={`right-pane${compact ? ' compact' : ''}`} aria-label="Walkthrough controls">
      {session.state === 'INPUT' && <InputView
        session={session}
        settings={settings}
        providers={providers}
        actions={actions}
        actionError={actionError}
      />}
      {session.state === 'LOADING' && <LoadingView session={session} onCancel={actions.cancelMapping} />}
      {session.state === 'OVERVIEW' && <OverviewView session={session} actions={actions} />}
      {session.state === 'TOUR_ACTIVE' && <TourView session={session} actions={actions} />}
    </aside>
  );
}

interface InputViewProps extends RightPaneProps {}

function InputView({ session, settings, providers, actions, actionError }: InputViewProps) {
  const [mode, setMode] = useState<AnalysisModeId>(settings?.default_mode_id ?? session.mode);
  const [provider, setProvider] = useState<ProviderId>(settings?.provider_id ?? session.provider);
  const [question, setQuestion] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => setMode(settings?.default_mode_id ?? session.mode), [settings?.default_mode_id, session.mode]);
  useEffect(() => setProvider(settings?.provider_id ?? session.provider), [settings?.provider_id, session.provider]);

  const selectedStatus = providers.find((status) => status.id === provider);
  const modeDetails = modeDescriptions[mode];
  const submit = async () => {
    setSubmitting(true);
    try {
      await actions.startMapping(question, mode, provider);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="pane-content input-workspace">
      <section className="input-intro">
        <div>
          <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>Repository field guide</Text>
          <Title order={1}>Understand <span>{session.repository}</span></Title>
          <Text className="input-intro-copy" c="dimmed">
            Build a source-grounded route from the system map to the code that makes it work.
          </Text>
        </div>
        <ol aria-label="Walkthrough workflow" className="workflow-diagram">
          <li>
            <span>01</span>
            <div><strong>Map the system</strong><small>Components, boundaries, relationships</small></div>
          </li>
          <li>
            <span>02</span>
            <div><strong>Choose a route</strong><small>A staged path through validated stops</small></div>
          </li>
          <li>
            <span>03</span>
            <div><strong>Read the evidence</strong><small>Source, call sites, and line-level notes</small></div>
          </li>
        </ol>
        <Text size="xs" c="dimmed">Local analysis · repository-contained source · validated locations</Text>
      </section>

      <section className="input-console">
        <div className="input-console-heading">
          <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>New walkthrough</Text>
          <Title order={3}>What do you need to understand?</Title>
        </div>
        {(session.error_message ?? actionError) !== undefined &&
          <Alert color="red" title="Walkthrough failed">{session.error_message ?? actionError}</Alert>}
        <SegmentedControl
          className="mode-switcher"
          fullWidth
          value={mode}
          onChange={(value) => setMode(value as AnalysisModeId)}
          data={[
            { value: 'understand', label: 'Learn' },
            { value: 'review', label: 'Review' },
            { value: 'trace', label: 'Trace' },
          ]}
        />
        <div className="mode-description">
          <Text fw={700}>{modeDetails.title}</Text>
          <Text size="sm" c="dimmed">{modeDetails.description}</Text>
        </div>
        <Textarea
          className="walkthrough-prompt"
          label="Focus"
          description={mode === 'understand' ? 'Optional—leave blank to map the whole codebase.' : 'Required for this mode.'}
          placeholder={modeDetails.placeholder}
          minRows={5}
          autosize
          maxRows={12}
          value={question}
          onChange={(event) => setQuestion(event.currentTarget.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void submit();
          }}
        />
        <div className="provider-row">
          <Select
            label="Analysis engine"
            value={provider}
            data={providers.length > 0
              ? providers.map((status) => ({ value: status.id, label: status.name }))
              : [{ value: 'claude_cli', label: 'Claude CLI' }, { value: 'codex_cli', label: 'Codex CLI' }]}
            onChange={(value) => value !== null && setProvider(value as ProviderId)}
          />
          <div className="provider-state">
            <Group gap="xs" wrap="nowrap" className="provider-status">
              <span className={`status-dot ${selectedStatus?.available === true ? 'available' : selectedStatus === undefined ? 'checking' : 'unavailable'}`} />
              <Text size="xs" c="dimmed" lineClamp={2}>{selectedStatus?.message ?? 'Checking provider…'}</Text>
            </Group>
            <Button variant="subtle" size="compact-sm" onClick={actions.openSettings}>Settings</Button>
          </div>
        </div>
        <Button
          className="start-button"
          size="md"
          onClick={() => void submit()}
          loading={submitting}
          disabled={selectedStatus?.available === false || (mode !== 'understand' && question.trim().length === 0)}
        >
          {mode === 'understand' && question.trim().length === 0 ? 'Learn codebase' : 'Start walkthrough'}
        </Button>
        <Text size="xs" c="dimmed" ta="center">Ctrl/⌘ + Enter</Text>
        <div className="sample-preview">
          <Button size="compact-sm" variant="subtle" onClick={() => void actions.showSample()}>
            Preview sample result
          </Button>
          <Text size="xs" c="dimmed">Instant illustrative result — no repository analysis.</Text>
        </div>
      </section>
    </div>
  );
}

function LoadingView({ session, onCancel }: { readonly session: SessionSnapshot; readonly onCancel: () => Promise<void> }) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const started = Date.now();
    const timer = window.setInterval(() => setElapsed((Date.now() - started) / 1_000), 250);
    return () => window.clearInterval(timer);
  }, []);
  const lines = useMemo(() => session.progress_lines.slice(-200), [session.progress_lines]);

  return (
    <div className="pane-content loading-workspace">
      <section className="loading-summary">
        <div className="loading-heading">
          <Loader size="sm" />
          <div>
            <Text className="section-kicker" size="xs" c="dimmed" tt="uppercase" fw={800}>Analysis in progress</Text>
            <Title order={2}>Mapping the walkthrough</Title>
          </div>
        </div>
        <div aria-label="Mapping pipeline" className="mapping-pipeline">
          <div><span>1</span><strong>Inspect</strong><small>symbols &amp; modules</small></div>
          <i aria-hidden="true" />
          <div><span>2</span><strong>Connect</strong><small>components &amp; hops</small></div>
          <i aria-hidden="true" />
          <div><span>3</span><strong>Validate</strong><small>paths &amp; ranges</small></div>
        </div>
        <Group justify="space-between">
          <Text size="sm" c="dimmed">The provider can run until the repository map is complete.</Text>
          <Badge variant="light">{elapsed.toFixed(1)}s</Badge>
        </Group>
      </section>
      <section className="activity-panel">
        <div className="activity-panel-heading">
          <Text fw={700}>Live analysis trace</Text>
          <Text size="xs" c="dimmed">Newest provider output appears at the bottom.</Text>
        </div>
        <ScrollArea className="progress-log" type="auto" offsetScrollbars>
          <Code block>{lines.join('\n') || 'Waiting for provider output…'}</Code>
        </ScrollArea>
        <Button color="red" variant="light" onClick={() => void onCancel()}>Stop analysis</Button>
      </section>
    </div>
  );
}

const modeDescriptions: Record<AnalysisModeId, { readonly title: string; readonly description: string; readonly placeholder: string }> = {
  understand: {
    title: 'Learn the system from architecture to code',
    description: 'Build a grounded component map and staged path through representative execution flows.',
    placeholder: 'Optional: focus the lesson on a subsystem or question',
  },
  review: {
    title: 'Review a concrete concern',
    description: 'Order grounded findings by severity and connect them to the relevant code path.',
    placeholder: 'Is this change safe? Where are the likely regressions?',
  },
  trace: {
    title: 'Trace an execution path',
    description: 'Follow callers, branches, async hops, and sinks through validated locations.',
    placeholder: 'What happens when a request reaches /api/login?',
  },
};
