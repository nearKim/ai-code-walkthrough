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
  TourAction,
  WalkthroughSettings,
} from './types';
import { OverviewView } from './views/OverviewView';
import { TourView } from './views/TourView';

export interface RightPaneActions {
  readonly startMapping: (question: string, mode: AnalysisModeId, provider: ProviderId) => Promise<void>;
  readonly showSample: () => Promise<void>;
  readonly cancelMapping: () => Promise<void>;
  readonly tour: (action: TourAction, stepId?: string, sectionId?: string) => Promise<void>;
  readonly answer: (question: string) => Promise<void>;
  readonly copyMarkdown: () => Promise<void>;
  readonly downloadTechnicalReference: () => Promise<void>;
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
        <div className="input-intro-lead">
          <p className="field-label">Local code review workspace</p>
          <Title order={1}>
            Read <span className="repo-highlight">{session.repository}</span>
            <br />as a system
          </Title>
          <Text className="input-intro-copy">
            Grounded maps of architecture and execution paths. Every stop links to validated
            source ranges in this repository—nothing escapes the project tree.
          </Text>
        </div>

        <ol aria-label="Walkthrough workflow" className="workflow-diagram">
          <li>
            <span className="workflow-index">1</span>
            <div>
              <strong>Map structure</strong>
              <small>Components, ownership, import edges</small>
            </div>
          </li>
          <li>
            <span className="workflow-index">2</span>
            <div>
              <strong>Stage a route</strong>
              <small>Ordered stops with checkpoints</small>
            </div>
          </li>
          <li>
            <span className="workflow-index">3</span>
            <div>
              <strong>Inspect source</strong>
              <small>Highlights, hops, line notes</small>
            </div>
          </li>
        </ol>

        <ul className="constraint-list">
          <li>CLI providers only (Codex / Claude)</li>
          <li>Source-grounded locations only</li>
          <li>Analysis stays on this machine</li>
        </ul>
      </section>

      <section className="input-console">
        <header className="input-console-heading">
          <p className="field-label">New walkthrough</p>
          <Title order={3}>What are you looking at?</Title>
        </header>

        {(session.error_message ?? actionError) !== undefined &&
          <Alert color="red" title="Walkthrough failed" variant="light">
            {session.error_message ?? actionError}
          </Alert>}

        <div className="console-block">
          <p className="field-label">Mode</p>
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
            <Text fw={650} size="sm">{modeDetails.title}</Text>
            <Text size="sm" c="dimmed">{modeDetails.description}</Text>
          </div>
        </div>

        <Textarea
          className="walkthrough-prompt"
          label="Focus"
          description={mode === 'understand' ? 'Optional. Leave blank to map the whole codebase.' : 'Required for this mode.'}
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
              <span
                className={`status-dot ${
                  selectedStatus?.available === true
                    ? 'available'
                    : selectedStatus === undefined
                      ? 'checking'
                      : 'unavailable'
                }`}
              />
              <Text size="xs" c="dimmed" lineClamp={2}>
                {selectedStatus?.message ?? 'Checking provider…'}
              </Text>
            </Group>
            <Button variant="default" size="compact-sm" onClick={actions.openSettings}>
              Settings
            </Button>
          </div>
        </div>

        <div className="console-actions">
          <Button
            className="start-button"
            size="md"
            onClick={() => void submit()}
            loading={submitting}
            disabled={
              selectedStatus?.available === false ||
              (mode !== 'understand' && question.trim().length === 0)
            }
          >
            {mode === 'understand' && question.trim().length === 0
              ? 'Learn codebase'
              : 'Start walkthrough'}
          </Button>
          <Text size="xs" c="dimmed" className="shortcut-hint">
            ⌘/Ctrl + Enter
          </Text>
        </div>

        <div className="sample-preview">
          <Button size="compact-sm" variant="subtle" color="gray" onClick={() => void actions.showSample()}>
            Preview sample result
          </Button>
          <Text size="xs" c="dimmed">Illustrative map only. No project analysis.</Text>
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
          <Loader size="sm" color="copper" />
          <div>
            <p className="field-label">Analysis in progress</p>
            <Title order={2}>Mapping the walkthrough</Title>
          </div>
        </div>
        <div aria-label="Mapping pipeline" className="mapping-pipeline">
          <div>
            <span>1</span>
            <strong>Inspect</strong>
            <small>symbols &amp; modules</small>
          </div>
          <i aria-hidden="true" />
          <div>
            <span>2</span>
            <strong>Connect</strong>
            <small>components &amp; hops</small>
          </div>
          <i aria-hidden="true" />
          <div>
            <span>3</span>
            <strong>Validate</strong>
            <small>paths &amp; ranges</small>
          </div>
        </div>
        <Group justify="space-between" align="center">
          <Text size="sm" c="dimmed">
            Provider runs until the map is complete or you stop it.
          </Text>
          <Badge variant="outline" color="gray" className="elapsed-badge">
            {elapsed.toFixed(1)}s
          </Badge>
        </Group>
      </section>
      <section className="activity-panel">
        <div className="activity-panel-heading">
          <Text fw={650}>Live analysis trace</Text>
          <Text size="xs" c="dimmed">Newest provider output at the bottom.</Text>
        </div>
        <ScrollArea className="progress-log" type="auto" offsetScrollbars>
          <Code block>{lines.join('\n') || 'Waiting for provider output…'}</Code>
        </ScrollArea>
        <Button color="red" variant="light" onClick={() => void onCancel()}>
          Stop analysis
        </Button>
      </section>
    </div>
  );
}

const modeDescriptions: Record<AnalysisModeId, { readonly title: string; readonly description: string; readonly placeholder: string }> = {
  understand: {
    title: 'Architecture → staged path → code',
    description: 'Grounded component map and a curriculum of validated execution stops.',
    placeholder: 'Optional: focus on a subsystem or question',
  },
  review: {
    title: 'Findings ordered by severity',
    description: 'Connect review concerns to the concrete code path that matters.',
    placeholder: 'Is this change safe? Where are the likely regressions?',
  },
  trace: {
    title: 'Follow one execution path',
    description: 'Callers, branches, async hops, and sinks at validated locations.',
    placeholder: 'What happens when a request reaches /api/login?',
  },
};
