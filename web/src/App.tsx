import {
  Alert,
  Button,
  Center,
  Loader,
  localStorageColorSchemeManager,
  MantineProvider,
  Text,
  UnstyledButton,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Group as PanelGroup, Panel, Separator, usePanelCallbackRef } from 'react-resizable-panels';
import { api, subscribeToEvents } from './api';
import { CodePane } from './CodePane';
import { RightPane, type RightPaneActions } from './RightPane';
import { SettingsModal } from './SettingsModal';
import { walkthroughTheme } from './theme';
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
  return (
    <MantineProvider
      theme={walkthroughTheme}
      colorSchemeManager={colorSchemeManager}
      defaultColorScheme="auto"
    >
      <WalkthroughApplication />
    </MantineProvider>
  );
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
  const [walkthroughNarrow, setWalkthroughNarrow] = useState(false);
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
    showSample: async () => {
      setEvidencePreview(undefined);
      await perform(api.sample);
    },
    cancelMapping: async () => perform(api.cancelMapping),
    tour: async (action, stepId, sectionId) => {
      setEvidencePreview(undefined);
      await perform(() => api.tour(action, stepId, sectionId));
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
    downloadTechnicalReference: async () => {
      setActionError(undefined);
      try {
        const url = URL.createObjectURL(await api.exportTechnicalReference());
        const link = document.createElement('a');
        link.href = url;
        link.download = 'technical-reference.html';
        link.click();
        window.setTimeout(() => URL.revokeObjectURL(url), 0);
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

  const stateLabel = session === undefined
    ? 'connecting'
    : session.state === 'INPUT'
      ? 'ready'
      : session.state === 'LOADING'
        ? 'mapping'
        : session.state === 'OVERVIEW'
          ? 'overview'
          : 'tour';

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="app-brand">
          <span aria-hidden="true" className="app-brand-mark">
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
              <path d="M2 14V4h2.4l2.5 6.2L9.4 4H12v10h-2V7.6L7.7 14H6.1L3.9 7.6V14H2z" fill="currentColor" />
              <path d="M13 4h1.5l1.8 10H14.8l-.3-1.8h-2.2L12 14h-1.6L13 4zm.7 2.6-.7 4.4h1.4l-.7-4.4z" fill="currentColor" opacity="0.55" />
            </svg>
          </span>
          <div className="app-brand-copy">
            <Text className="app-brand-title">Code Cartograph</Text>
            <Text className="app-brand-sub">Review · map · understand</Text>
          </div>
        </div>

        <div className="app-header-meta">
          <div className="repository-context" title={session?.repository_path}>
            <span className="meta-label">Repository</span>
            <Text className="repository-name" ff="monospace" truncate>
              {session?.repository ?? 'Connecting…'}
            </Text>
          </div>
          <div className="session-chip" data-state={stateLabel}>
            <span className="session-chip-dot" aria-hidden="true" />
            <span className="meta-label">Session</span>
            <Text className="session-chip-value">{stateLabel}</Text>
          </div>
        </div>

        <div className="app-header-controls">
          {shouldShowCode && (
            <Button
              aria-controls="code"
              aria-expanded={!codeCollapsed}
              size="compact-xs"
              variant="default"
              onClick={toggleCodePane}
            >
              {codeCollapsed ? 'Show source' : 'Hide source'}
            </Button>
          )}
          <div className="theme-toggle" role="group" aria-label="Color theme">
            <UnstyledButton
              className={!dark ? 'active' : undefined}
              aria-pressed={!dark}
              onClick={() => setColorScheme('light')}
            >
              Light
            </UnstyledButton>
            <UnstyledButton
              className={dark ? 'active' : undefined}
              aria-pressed={dark}
              onClick={() => setColorScheme('dark')}
            >
              Dark
            </UnstyledButton>
          </div>
        </div>
      </header>

      {actionError !== undefined && (
        <Alert
          className="global-error"
          color="red"
          withCloseButton
          onClose={() => setActionError(undefined)}
        >
          {actionError}
        </Alert>
      )}

      {session === undefined ? (
        <Center className="app-loading"><Loader size="sm" color="copper" /></Center>
      ) : (
        <PanelGroup
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
          <Panel
            id="walkthrough"
            defaultSize="30"
            minSize={360}
            onResize={(size) => setWalkthroughNarrow((current) => {
              const next = size.inPixels <= 720;
              return current === next ? current : next;
            })}
          >
            <RightPane
              session={session}
              settings={settings}
              providers={providers}
              actions={actions}
              actionError={actionError}
              compact={walkthroughNarrow}
            />
          </Panel>
        </PanelGroup>
      )}

      <SettingsModal
        opened={settingsOpened}
        settings={settings}
        onClose={() => setSettingsOpened(false)}
        onSave={saveSettings}
      />
    </main>
  );
}

function messageOf(value: unknown): string {
  return value instanceof Error ? value.message : 'Unexpected application error.';
}
