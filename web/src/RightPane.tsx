import { ActionIcon, Alert, Button, Loader, Menu, Text, Textarea, Title } from '@mantine/core';
import { useState } from 'react';
import type { AnalysisModeId, EvidenceItem, ProviderId, ProviderStatus, SessionSnapshot, TourAction, WalkthroughSettings } from './types';
import { OverviewView } from './views/OverviewView';
import { TourView } from './views/TourView';

export interface RightPaneActions {
  readonly startMapping: (question: string, mode: AnalysisModeId, provider: ProviderId) => Promise<void>;
  readonly showSample: () => Promise<void>;
  readonly cancelMapping: () => Promise<void>;
  readonly tour: (action: TourAction, stepId?: string, sectionId?: string, stageId?: string) => Promise<void>;
  readonly answer: (question: string) => Promise<void>;
  readonly copyMarkdown: () => Promise<void>;
  readonly downloadTechnicalReference: () => Promise<void>;
  readonly openSettings: () => void;
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
  return <aside className={`right-pane${compact ? ' compact' : ''}`} aria-label="Walkthrough controls">
    {session.state === 'INPUT' && <InputView
      session={session}
      settings={settings}
      providers={providers}
      actions={actions}
      actionError={actionError}
    />}
    {session.state === 'LOADING' && <LoadingView onCancel={actions.cancelMapping} />}
    {session.state === 'OVERVIEW' && <OverviewView session={session} actions={actions} />}
    {session.state === 'TOUR_ACTIVE' && <TourView session={session} actions={actions} />}
  </aside>;
}

function InputView({
  session,
  settings,
  providers,
  actions,
  actionError,
}: Pick<RightPaneProps, 'session' | 'settings' | 'providers' | 'actions' | 'actionError'>) {
  const [question, setQuestion] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const provider = settings?.provider_id ?? session.provider;
  const providerStatus = providers.find((status) => status.id === provider);

  const submit = async () => {
    setSubmitting(true);
    try {
      await actions.startMapping(question, 'understand', provider);
    } finally {
      setSubmitting(false);
    }
  };

  return <div className="pane-content input-workspace">
    <section className="input-console">
      <Text size="sm" c="dimmed" truncate>{session.repository}</Text>
      <Title order={1}>Understand the codebase</Title>
      {(session.error_message ?? actionError) !== undefined && <Alert color="red">
        {session.error_message ?? actionError}
      </Alert>}
      <Textarea
        aria-label="What do you want to understand?"
        className="walkthrough-prompt"
        minRows={4}
        maxRows={10}
        placeholder="What do you want to understand?"
        value={question}
        onChange={(event) => setQuestion(event.currentTarget.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) void submit();
        }}
      />
      <div className="input-actions">
        <Button
          className="start-button"
          onClick={() => void submit()}
          loading={submitting}
          disabled={providerStatus?.available === false}
          title={providerStatus?.available === false ? providerStatus.message : undefined}
        >Learn</Button>
        <Menu position="bottom-end" shadow="sm" withinPortal>
          <Menu.Target>
            <ActionIcon aria-label="Walkthrough options" title="Walkthrough options" variant="subtle" color="gray">...</ActionIcon>
          </Menu.Target>
          <Menu.Dropdown>
            <Menu.Item onClick={actions.openSettings}>Settings</Menu.Item>
            <Menu.Item onClick={() => void actions.showSample()}>Preview sample</Menu.Item>
          </Menu.Dropdown>
        </Menu>
      </div>
    </section>
  </div>;
}

function LoadingView({ onCancel }: { readonly onCancel: () => Promise<void> }) {
  return <div className="pane-content loading-workspace">
    <Loader size="sm" />
    <Title order={2}>Mapping codebase</Title>
    <Button variant="default" size="compact-sm" onClick={() => void onCancel()}>Cancel</Button>
  </div>;
}
