import { Alert, Center, Loader, MantineProvider, Text } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Group as PanelGroup, Panel, Separator } from 'react-resizable-panels';
import { api, subscribeToEvents } from './api';
import { CodePane } from './CodePane';
import { RightPane, type RightPaneActions } from './RightPane';
import { SettingsModal } from './SettingsModal';
import type {
  AnalysisModeId,
  ProviderId,
  ProviderStatus,
  SessionSnapshot,
  WalkthroughSettings,
} from './types';

export function App() {
  const dark = useMediaQuery('(prefers-color-scheme: dark)') ?? false;
  const [session, setSession] = useState<SessionSnapshot>();
  const [settings, setSettings] = useState<WalkthroughSettings>();
  const [providers, setProviders] = useState<ReadonlyArray<ProviderStatus>>([]);
  const [settingsOpened, setSettingsOpened] = useState(false);
  const [actionError, setActionError] = useState<string>();
  const [focusNonce, setFocusNonce] = useState(0);

  const refreshProviders = useCallback(async () => {
    try {
      setProviders(await api.providers());
    } catch (reason: unknown) {
      setActionError(messageOf(reason));
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([api.session(), api.settings()])
      .then(([initialSession, initialSettings]) => {
        if (!active) return;
        setSession(initialSession);
        setSettings(initialSettings);
      })
      .catch((reason: unknown) => active && setActionError(messageOf(reason)));
    void refreshProviders();
    const unsubscribe = subscribeToEvents(
      (snapshot) => active && setSession(snapshot),
      (line) => active && setSession((current) => current === undefined ? current : {
        ...current,
        progress_lines: [...current.progress_lines, line].slice(-200),
      }),
    );
    return () => {
      active = false;
      unsubscribe();
    };
  }, [refreshProviders]);

  const perform = useCallback(async (operation: () => Promise<SessionSnapshot>) => {
    setActionError(undefined);
    try {
      setSession(await operation());
    } catch (reason: unknown) {
      setActionError(messageOf(reason));
    }
  }, []);

  const startMapping = useCallback(async (question: string, mode: AnalysisModeId, provider: ProviderId) => {
    setActionError(undefined);
    setSession((current) => current === undefined ? current : {
      ...current,
      state: 'LOADING',
      question,
      mode,
      provider,
      current_step_index: -1,
      displayed_step_index: -1,
      displayed_step: undefined,
      next_step: undefined,
      next_edge: undefined,
      error_message: undefined,
      progress_lines: [],
    });
    try {
      const accepted = await api.startMapping(question, mode, provider);
      setSession((current) => current?.state === 'LOADING'
        ? { ...accepted, progress_lines: current.progress_lines }
        : current);
    } catch (reason: unknown) {
      const message = messageOf(reason);
      setActionError(message);
      setSession((current) => current === undefined ? current : {
        ...current,
        state: 'INPUT',
        error_message: message,
      });
    }
  }, []);

  const actions: RightPaneActions = useMemo(() => ({
    startMapping,
    cancelMapping: async () => perform(api.cancelMapping),
    tour: async (action, stepId) => perform(() => api.tour(action, stepId)),
    answer: async (question) => perform(() => api.answer(question)),
    copyMarkdown: async () => {
      setActionError(undefined);
      try {
        await navigator.clipboard.writeText(await api.exportMarkdown());
      } catch (reason: unknown) {
        setActionError(messageOf(reason));
      }
    },
    openSettings: () => setSettingsOpened(true),
    focusCode: () => setFocusNonce((value) => value + 1),
  }), [perform, startMapping]);

  const saveSettings = async (value: WalkthroughSettings) => {
    setActionError(undefined);
    try {
      const saved = await api.saveSettings(value);
      setSettings(saved);
      await refreshProviders();
    } catch (reason: unknown) {
      setActionError(messageOf(reason));
      throw reason;
    }
  };

  return <MantineProvider defaultColorScheme="auto">
    <main className="app-shell">
      <header className="app-header">
        <Text fw={700}>AI Code Walkthrough</Text>
        <Text size="xs" c="dimmed">{session?.repository_path ?? 'Connecting to local server…'}</Text>
      </header>
      {actionError !== undefined && <Alert className="global-error" color="red" withCloseButton onClose={() => setActionError(undefined)}>
        {actionError}
      </Alert>}
      {session === undefined
        ? <Center className="app-loading"><Loader size="sm" /></Center>
        : <PanelGroup orientation="horizontal" className="workspace" defaultLayout={{ code: 70, walkthrough: 30 }}>
            <Panel id="code" minSize={480}>
              <CodePane
                step={session.displayed_step}
                nextStep={session.next_step}
                nextEdge={session.next_edge}
                dark={dark}
                focusNonce={focusNonce}
              />
            </Panel>
            <Separator className="pane-separator" />
            <Panel id="walkthrough" defaultSize="30" minSize={360} maxSize="55">
              <RightPane
                session={session}
                settings={settings}
                providers={providers}
                actions={actions}
                actionError={actionError}
              />
            </Panel>
          </PanelGroup>}
      <SettingsModal
        opened={settingsOpened}
        settings={settings}
        onClose={() => setSettingsOpened(false)}
        onSave={saveSettings}
      />
    </main>
  </MantineProvider>;
}

function messageOf(value: unknown): string {
  return value instanceof Error ? value.message : 'Unexpected application error.';
}
