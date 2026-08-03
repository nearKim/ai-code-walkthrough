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
  Stack,
  Text,
  Textarea,
  Title,
} from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import type {
  AnalysisModeId,
  EvidenceItem,
  ProviderId,
  ProviderStatus,
  SessionSnapshot,
  WalkthroughSettings,
} from './types';
import { OverviewView } from './views/OverviewView';
import { TourView } from './views/TourView';

export interface RightPaneActions {
  readonly startMapping: (question: string, mode: AnalysisModeId, provider: ProviderId) => Promise<void>;
  readonly cancelMapping: () => Promise<void>;
  readonly tour: (action: 'start' | 'preview' | 'next' | 'previous' | 'stop' | 'new', stepId?: string) => Promise<void>;
  readonly answer: (question: string) => Promise<void>;
  readonly copyMarkdown: () => Promise<void>;
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
}

export function RightPane({ session, settings, providers, actions, actionError }: RightPaneProps) {
  return (
    <aside className="right-pane" aria-label="Walkthrough controls">
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
    <Stack className="pane-content" gap="md">
      <div>
        <Text size="xs" c="dimmed" tt="uppercase" fw={700}>Local repository</Text>
        <Title order={3}>{session.repository}</Title>
        <Text size="xs" c="dimmed" truncate title={session.repository_path}>{session.repository_path}</Text>
      </div>
      {(session.error_message ?? actionError) !== undefined &&
        <Alert color="red" title="Walkthrough failed">{session.error_message ?? actionError}</Alert>}
      <SegmentedControl
        fullWidth
        value={mode}
        onChange={(value) => setMode(value as AnalysisModeId)}
        data={[
          { value: 'understand', label: 'Learn' },
          { value: 'review', label: 'Review' },
          { value: 'trace', label: 'Trace' },
        ]}
      />
      <div>
        <Text fw={600}>{modeDetails.title}</Text>
        <Text size="sm" c="dimmed">{modeDetails.description}</Text>
      </div>
      <Textarea
        label="Question"
        description={mode === 'understand' ? 'Optional—leave blank for a whole-codebase learning path.' : 'Required for this mode.'}
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
      <Select
        label="Provider"
        value={provider}
        data={providers.length > 0
          ? providers.map((status) => ({ value: status.id, label: status.name }))
          : [{ value: 'claude_cli', label: 'Claude CLI' }, { value: 'codex_cli', label: 'Codex CLI' }]}
        onChange={(value) => value !== null && setProvider(value as ProviderId)}
      />
      <Group justify="space-between" wrap="nowrap">
        <Group gap="xs" wrap="nowrap" className="provider-status">
          <span className={`status-dot ${selectedStatus?.available === true ? 'available' : selectedStatus === undefined ? 'checking' : 'unavailable'}`} />
          <Text size="xs" c="dimmed" lineClamp={2}>{selectedStatus?.message ?? 'Checking provider…'}</Text>
        </Group>
        <Button variant="subtle" size="compact-sm" onClick={actions.openSettings}>Settings</Button>
      </Group>
      <Button
        onClick={() => void submit()}
        loading={submitting}
        disabled={selectedStatus?.available === false || (mode !== 'understand' && question.trim().length === 0)}
      >
        {mode === 'understand' && question.trim().length === 0 ? 'Learn codebase' : 'Start walkthrough'}
      </Button>
      <Text size="xs" c="dimmed" ta="center">Ctrl/⌘ + Enter to start</Text>
    </Stack>
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
    <Stack className="pane-content pane-fill" gap="md">
      <Group justify="space-between">
        <Group gap="sm"><Loader size="sm" /><Text fw={600}>Mapping walkthrough…</Text></Group>
        <Badge variant="light">{elapsed.toFixed(1)}s</Badge>
      </Group>
      <Text size="sm" c="dimmed">{lines.at(-1) ?? 'Inspecting the repository…'}</Text>
      <ScrollArea className="progress-log" type="auto" offsetScrollbars>
        <Code block>{lines.join('\n') || 'Waiting for provider output…'}</Code>
      </ScrollArea>
      <Button color="red" variant="light" onClick={() => void onCancel()}>Stop</Button>
    </Stack>
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
