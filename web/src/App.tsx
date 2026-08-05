import {
  Alert,
  Button,
  Center,
  Loader,
  MantineProvider,
  Text,
  useComputedColorScheme,
} from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
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

export function App() {
  return (
    <MantineProvider
      theme={walkthroughTheme}
      defaultColorScheme="auto"
    >
      <WalkthroughApplication />
    </MantineProvider>
  );
}

function WalkthroughApplication() {
  const colorScheme = useComputedColorScheme('light');
  const dark = colorScheme === 'dark';
  const narrowScreen = useMediaQuery('(max-width: 720px)');
  const [session, setSession] = useState<SessionSnapshot>();
  const [settings, setSettings] = useState<WalkthroughSettings>();
  const [providers, setProviders] = useState<ReadonlyArray<ProviderStatus>>([]);
  const [settingsOpened, setSettingsOpened] = useState(false);
  const [actionError, setActionError] = useState<string>();
  const [codeCollapsed, setCodeCollapsed] = useState(true);
  const [evidencePreview, setEvidencePreview] = useState<FlowStep>();
  const [codePanel, setCodePanel] = usePanelCallbackRef();
  const [workspaceNarrow, setWorkspaceNarrow] = useState(narrowScreen);
  const displayedStep = evidencePreview ?? session?.displayed_step;
  const shouldShowCode = session?.state === 'TOUR_ACTIVE' || displayedStep !== undefined;

  useEffect(() => {
    if (workspaceNarrow === narrowScreen) return;
    setCodePanel(null);
    setWorkspaceNarrow(narrowScreen);
  }, [narrowScreen, setCodePanel, workspaceNarrow]);

  useEffect(() => {
    if (codePanel === null) return;
    if (shouldShowCode) {
      if (codePanel.isCollapsed()) codePanel.resize(workspaceNarrow ? '56%' : '64%');
    } else {
      codePanel.collapse();
    }
  }, [codePanel, shouldShowCode, workspaceNarrow]);

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
    tour: async (action, stepId, sectionId, stageId) => {
      setEvidencePreview(undefined);
      await perform(() => api.tour(action, stepId, sectionId, stageId));
    },
    answer: async (question) => perform(() => api.answer(question)),
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

  return (
    <main className="app-shell">
      <header className="app-header">
        <div className="app-header-primary">
          <Text className="app-brand-title">Code Walkthrough</Text>
          <Text className="repository-name" ff="monospace" truncate title={session?.repository_path}>
            {session?.repository ?? 'Connecting...'}
          </Text>
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
        <Center className="app-loading"><Loader size="sm" /></Center>
      ) : (
        <PanelGroup
          key={workspaceNarrow ? 'narrow' : 'wide'}
          orientation={workspaceNarrow ? 'vertical' : 'horizontal'}
          className="workspace"
          defaultLayout={shouldShowCode ? { code: workspaceNarrow ? 56 : 64, walkthrough: workspaceNarrow ? 44 : 36 } : { code: 0, walkthrough: 100 }}
        >
          <Panel
            id="code"
            panelRef={setCodePanel}
            minSize={workspaceNarrow ? 220 : 420}
            collapsible
            collapsedSize={0}
            onResize={(size) => setCodeCollapsed(size.inPixels <= 1)}
          >
            <CodePane
              step={displayedStep}
              nextStep={evidencePreview === undefined ? session.next_step : undefined}
              nextEdge={evidencePreview === undefined ? session.next_edge : undefined}
              dark={dark}
            />
          </Panel>
          <Separator className="pane-separator" />
          <Panel
            id="walkthrough"
            defaultSize="30"
            minSize={workspaceNarrow ? 260 : 360}
          >
            <RightPane
              session={session}
              settings={settings}
              providers={providers}
              actions={actions}
              actionError={actionError}
              compact={workspaceNarrow}
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
