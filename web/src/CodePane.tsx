import Editor, { loader, type OnMount } from '@monaco-editor/react';
import { Alert, Center, Loader, Stack, Text } from '@mantine/core';
import * as monaco from 'monaco-editor';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api';
import type { FlowStep, SourceFile, StepEdge } from './types';

type MonacoEnvironmentHost = typeof globalThis & {
  MonacoEnvironment?: { readonly getWorker: () => Worker };
};

(globalThis as MonacoEnvironmentHost).MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};
loader.config({ monaco });

interface CodePaneProps {
  readonly step?: FlowStep;
  readonly nextStep?: FlowStep;
  readonly nextEdge?: StepEdge;
  readonly dark: boolean;
  readonly focusNonce: number;
}

export function CodePane({ step, nextStep, nextEdge, dark, focusNonce }: CodePaneProps) {
  const [source, setSource] = useState<SourceFile>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const decorationsRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  const zoneIdsRef = useRef<ReadonlyArray<string>>([]);

  const clearVisuals = useCallback(() => {
    decorationsRef.current?.clear();
    const editor = editorRef.current;
    if (editor !== null && zoneIdsRef.current.length > 0) {
      editor.changeViewZones((accessor) => zoneIdsRef.current.forEach((id) => accessor.removeZone(id)));
    }
    zoneIdsRef.current = [];
  }, []);

  useEffect(() => {
    if (step === undefined) {
      clearVisuals();
      return;
    }
    let active = true;
    setLoading(true);
    setError(undefined);
    api.source(step.file_path)
      .then((loaded) => {
        if (active) setSource(loaded);
      })
      .catch((reason: unknown) => {
        if (active) setError(reason instanceof Error ? reason.message : 'Could not load source file.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => { active = false; };
  }, [clearVisuals, step?.file_path]);

  const applyVisuals = useCallback(() => {
    clearVisuals();
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (editor === null || editor === undefined || model === null || model === undefined || step === undefined || source === undefined) return;
    if (source.path !== step.file_path) return;

    const maxLine = model.getLineCount();
    const startLine = clamp(step.start_line, 1, maxLine);
    const endLine = clamp(step.end_line, startLine, maxLine);
    const decorations: monaco.editor.IModelDeltaDecoration[] = [{
      range: new monaco.Range(startLine, 1, endLine, 1),
      options: {
        isWholeLine: true,
        className: 'walkthrough-current-line',
        linesDecorationsClassName: 'walkthrough-current-gutter',
      },
    }];

    const edgeStart = nextEdge?.call_site_start_line;
    const edgeEnd = nextEdge?.call_site_end_line;
    if (nextStep !== undefined && nextEdge?.call_site_file_path === step.file_path && edgeStart !== undefined && edgeEnd !== undefined) {
      const nextStart = clamp(edgeStart, startLine, endLine);
      const nextEnd = clamp(edgeEnd, nextStart, endLine);
      decorations.push({
        range: new monaco.Range(nextStart, 1, nextEnd, 1),
        options: {
          isWholeLine: true,
          className: 'walkthrough-next-line',
          glyphMarginClassName: 'walkthrough-next-glyph',
          glyphMarginHoverMessage: { value: `Next: ${nextStep.title}` },
          hoverMessage: { value: `Next: **${nextStep.title}**` },
        },
      });
    } else if (nextStep?.symbol !== undefined) {
      model.findMatches(nextStep.symbol, false, false, true, null, false, 20)
        .filter((match) => match.range.startLineNumber >= startLine && match.range.endLineNumber <= endLine)
        .forEach((match) => decorations.push({
          range: match.range,
          options: {
            inlineClassName: 'walkthrough-next-symbol',
            hoverMessage: { value: `Next: **${nextStep.title}**` },
          },
        }));
    }
    decorationsRef.current = editor.createDecorationsCollection(decorations);

    const zoneIds: string[] = [];
    editor.changeViewZones((accessor) => {
      zoneIds.push(accessor.addZone(zone(
        startLine - 1,
        'walkthrough-zone walkthrough-zone-header',
        `${step.title} · ${step.file_path}:${startLine}-${endLine}`,
        34,
        1,
      )));
      const summary = step.explanation.trim() || step.why_included.trim();
      if (summary.length > 0) {
        zoneIds.push(accessor.addZone(zone(
          startLine - 1,
          'walkthrough-zone walkthrough-zone-summary',
          summary,
          30 + Math.min(3, Math.floor(summary.length / 100)) * 20,
          2,
        )));
      }
      step.line_annotations.forEach((annotation, index) => {
        if (annotation.text.trim().length === 0) return;
        const line = clamp(annotation.start_line, startLine, endLine);
        zoneIds.push(accessor.addZone(zone(
          line - 1,
          'walkthrough-zone walkthrough-zone-annotation',
          `L${line} · ${annotation.text.trim()}`,
          annotation.text.length > 110 ? 48 : 28,
          10 + index,
        )));
      });
    });
    zoneIdsRef.current = zoneIds;
    editor.revealLinesInCenter(startLine, endLine, monaco.editor.ScrollType.Smooth);
  }, [clearVisuals, nextEdge, nextStep, source, step]);

  useEffect(() => {
    applyVisuals();
  }, [applyVisuals]);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null || step === undefined) return;
    editor.revealLinesInCenter(step.start_line, step.end_line, monaco.editor.ScrollType.Smooth);
    editor.focus();
  }, [focusNonce, step]);

  useEffect(() => clearVisuals, [clearVisuals]);

  const language = useMemo(() => source === undefined ? 'plaintext' : languageForPath(source.path), [source]);
  const handleMount: OnMount = (editor) => {
    editorRef.current = editor;
    applyVisuals();
  };

  if (source === undefined && step === undefined) {
    return (
      <Center className="code-empty">
        <Stack align="center" gap="xs">
          <Text fw={600}>Code appears here</Text>
          <Text c="dimmed" size="sm">Map a walkthrough, then preview or start a validated code stop.</Text>
        </Stack>
      </Center>
    );
  }

  return (
    <div className="code-pane">
      <div className="code-toolbar">
        <Text size="sm" fw={600} truncate>{source?.path ?? step?.file_path}</Text>
        {step !== undefined && <Text size="xs" c="dimmed">L{step.start_line}–{step.end_line}</Text>}
      </div>
      {error !== undefined && <Alert color="red" title="Source unavailable">{error}</Alert>}
      {loading && source === undefined
        ? <Center className="code-loading"><Loader size="sm" /></Center>
        : <Editor
            path={source?.path}
            value={source?.content ?? ''}
            language={language}
            theme={dark ? 'vs-dark' : 'vs'}
            onMount={handleMount}
            options={{
              readOnly: true,
              domReadOnly: true,
              glyphMargin: true,
              lineNumbersMinChars: 4,
              minimap: { enabled: false },
              padding: { top: 12, bottom: 24 },
              renderLineHighlight: 'none',
              scrollBeyondLastLine: false,
              smoothScrolling: true,
              stickyScroll: { enabled: true },
              wordWrap: 'off',
              ariaLabel: 'Walkthrough source code',
            }}
          />}
    </div>
  );
}

function zone(
  afterLineNumber: number,
  className: string,
  text: string,
  heightInPx: number,
  ordinal: number,
): monaco.editor.IViewZone {
  const node = document.createElement('div');
  node.className = className;
  node.textContent = text;
  return {
    afterLineNumber,
    ordinal,
    heightInPx,
    domNode: node,
    suppressMouseDown: true,
  };
}

function languageForPath(path: string): string {
  const normalized = path.toLowerCase();
  return monaco.languages.getLanguages()
    .find((language) => language.extensions?.some((extension) => normalized.endsWith(extension)))
    ?.id ?? 'plaintext';
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(Math.max(value, minimum), maximum);
}
