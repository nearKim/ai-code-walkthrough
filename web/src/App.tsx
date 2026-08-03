import {
  Alert,
  Button,
  Center,
  Loader,
  localStorageColorSchemeManager,
  MantineProvider,
  SegmentedControl,
  Text,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Group as PanelGroup, Panel, Separator, usePanelCallbackRef } from 'react-resizable-panels';
import { api, subscribeToEvents } from './api';
import { CodePane } from './CodePane';
import { RightPane, type RightPaneActions } from './RightPane';
import { SettingsModal } from './SettingsModal';
import type {
  AnalysisModeId,
  EvidenceItem,
  FlowStep,
  ProviderId,
  ProviderStatus,
  SessionSnapshot,
  WalkthroughSettings,
} from './types';

const colorSchemeManager = localStorageColorSchemeManager({
  key: 'ai-code-walkthrough-color-scheme',
});

export function App() {
  return <MantineProvider colorSchemeManager={colorSchemeManager} defaultColorScheme="auto">
    <WalkthroughApplication />
  </MantineProvider>;
}

function WalkthroughApplication() {
  const colorScheme = useComputedColorScheme('light');
  const { setColorScheme } = useMantineColorScheme();
  const dark = colorScheme === 'dark';
  const [session, setSession] = useState<SessionSnapshot>();
  const [settings, setSettings] = useState<WalkthroughSettings>();
  const [providers, setProviders] = useState<ReadonlyArray<ProviderStatus>>([]);
  const [settingsOpened, setSettingsOpened] = useState(false);
  const [actionError, setActionError] = useState<string>();
  const [focusNonce, setFocusNonce] = useState(0);
  const [codeCollapsed, setCodeCollapsed] = useState(true);
  const [evidencePreview, setEvidencePreview] = useState<FlowStep>();
  const [codePanel, setCodePanel] = usePanelCallbackRef();
  const displayedStep = evidencePreview ?? session?.displayed_step;
  const shouldShowCode = session?.state === 'TOUR_ACTIVE' || displayedStep !== undefined;

  useEffect(() => {
    if (codePanel === null) return;
    if (shouldShowCode) {
      if (codePanel.isCollapsed()) codePanel.resize('64%');
    } else {
      codePanel.collapse();
    }
  }, [codePanel, shouldShowCode]);

  const toggleCodePane = useCallback(() => {
    if (codePanel === null) return;
    if (codePanel.isCollapsed()) codePanel.resize('64%');
    else codePanel.collapse();
  }, [codePanel]);

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
    setEvidencePreview(undefined);
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
    tour: async (action, stepId) => {
      setEvidencePreview(undefined);
      await perform(() => api.tour(action, stepId));
    },
    answer: async (question) => perform(() => api.answer(question)),
    loadSymbolInventory: api.symbols,
    copyMarkdown: async () => {
      setActionError(undefined);
      try {
        await navigator.clipboard.writeText(await api.exportMarkdown());
      } catch (reason: unknown) {
        setActionError(messageOf(reason));
      }
    },
    openSettings: () => setSettingsOpened(true),
    focusCode: () => {
      codePanel?.expand();
      setFocusNonce((value) => value + 1);
    },
    previewEvidence: (evidence: EvidenceItem, explanation: string) => {
      if (evidence.file_path === undefined || evidence.start_line === undefined) return;
      setEvidencePreview({
        id: `evidence:${evidence.file_path}:${evidence.start_line}:${evidence.label}`,
        title: evidence.label,
        file_path: evidence.file_path,
        symbol: evidence.label,
        start_line: evidence.start_line,
        end_line: evidence.end_line ?? evidence.start_line,
        explanation,
        why_included: 'This code grounds the selected responsibility.',
        step_type: evidence.kind,
        uncertain: false,
        line_annotations: [],
        evidence: [evidence],
      });
      codePanel?.expand();
    },
  }), [codePanel, perform, startMapping]);

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

  return <main className="app-shell">
      <header className="app-header">
        <div className="app-brand">
          <span aria-hidden="true" className="app-brand-mark">↳</span>
          <div>
            <Text fw={800}>AI Code Walkthrough</Text>
            <Text size="xs" c="dimmed">Architecture to implementation</Text>
          </div>
        </div>
        <div className="app-header-controls">
          <div className="repository-context">
            <span>Repository</span>
            <Text size="xs" ff="monospace" truncate title={session?.repository_path}>
              {session?.repository_path ?? 'Connecting to local server…'}
            </Text>
          </div>
          {shouldShowCode && <Button
            aria-controls="code"
            aria-expanded={!codeCollapsed}
            size="compact-xs"
            variant="subtle"
            onClick={toggleCodePane}
          >{codeCollapsed ? 'Show code pane' : 'Hide code pane'}</Button>}
          <SegmentedControl
            aria-label="Color theme"
            data={['Light', 'Dark']}
            size="xs"
            value={dark ? 'Dark' : 'Light'}
            onChange={(value) => setColorScheme(value === 'Dark' ? 'dark' : 'light')}
          />
        </div>
      </header>
      {actionError !== undefined && <Alert className="global-error" color="red" withCloseButton onClose={() => setActionError(undefined)}>
        {actionError}
      </Alert>}
      {session === undefined
        ? <Center className="app-loading"><Loader size="sm" /></Center>
        : <PanelGroup
            orientation="horizontal"
            className="workspace"
            defaultLayout={shouldShowCode ? { code: 70, walkthrough: 30 } : { code: 0, walkthrough: 100 }}
          >
            <Panel
              id="code"
              panelRef={setCodePanel}
              minSize={480}
              collapsible
              collapsedSize={0}
              onResize={(size) => setCodeCollapsed(size.inPixels <= 1)}
            >
              <CodePane
                step={displayedStep}
                nextStep={evidencePreview === undefined ? session.next_step : undefined}
                nextEdge={evidencePreview === undefined ? session.next_edge : undefined}
                dark={dark}
                focusNonce={focusNonce}
              />
            </Panel>
            <Separator className="pane-separator" />
            <Panel id="walkthrough" defaultSize="30" minSize={360}>
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
    </main>;
}

function messageOf(value: unknown): string {
  return value instanceof Error ? value.message : 'Unexpected application error.';
}
