import {
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  Loader,
  ScrollArea,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useEffect, useMemo, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import type { RightPaneActions } from '../RightPane';
import type { LearningStage, SessionSnapshot } from '../types';

interface TourViewProps {
  readonly session: SessionSnapshot;
  readonly actions: RightPaneActions;
}

export function TourView({ session, actions }: TourViewProps) {
  const step = session.displayed_step;
  const [question, setQuestion] = useState('');
  const [showAnswer, setShowAnswer] = useState(false);
  const stage = useMemo(
    () => session.flow_map?.learning_path.find((candidate) => step !== undefined && candidate.step_ids.includes(step.id)),
    [session.flow_map?.learning_path, step],
  );
  const stageIndex = stage === undefined ? -1 : session.flow_map?.learning_path.indexOf(stage) ?? -1;

  useEffect(() => {
    setQuestion('');
    setShowAnswer(false);
  }, [step?.id]);
  useEffect(() => {
    if (session.step_answer_loading || session.step_answer !== undefined || session.step_answer_error !== undefined) {
      setShowAnswer(true);
    }
  }, [session.step_answer, session.step_answer_error, session.step_answer_loading]);

  if (step === undefined) return <Alert color="red">The active tour step is unavailable.</Alert>;

  const submit = async () => {
    const trimmed = question.trim();
    if (trimmed.length === 0) return;
    setQuestion('');
    setShowAnswer(true);
    await actions.answer(trimmed);
  };

  return (
    <div className="pane-column">
      <div className="pane-header tour-header">
        <Group justify="space-between" align="start" wrap="nowrap">
          <div className="tour-title">
            <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
              Step {session.current_step_index + 1}/{session.flow_map?.steps.length ?? 0}
            </Text>
            <Title order={3} lineClamp={2}>{step.title}</Title>
            {stage !== undefined && <Text size="xs" c="dimmed">
              Stage {stageIndex + 1}/{session.flow_map?.learning_path.length ?? 0} · {stage.title}
            </Text>}
            <Text size="xs" c="dimmed" truncate>{step.file_path}:{step.start_line}-{step.end_line}</Text>
          </div>
          <Button size="compact-xs" variant="subtle" onClick={actions.focusCode}>Go to code</Button>
        </Group>
        <Group gap="xs" mt="sm">
          <Button size="xs" variant="default" disabled={session.can_previous !== true} onClick={() => void actions.tour('previous')}>◀ Prev</Button>
          <Button size="xs" onClick={() => void actions.tour('next')}>Next ▶</Button>
          <Button size="xs" color="red" variant="subtle" onClick={() => void actions.tour('stop')}>Stop</Button>
        </Group>
      </div>
      <ScrollArea className="tour-scroll" offsetScrollbars>
        {showAnswer
          ? <AnswerView session={session} back={() => setShowAnswer(false)} />
          : <StepView step={step} stage={stage} />}
      </ScrollArea>
      <div className="follow-up-strip">
        <TextInput
          aria-label="Ask about this step"
          placeholder="Ask about this step…"
          value={question}
          onChange={(event) => setQuestion(event.currentTarget.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              void submit();
            }
          }}
          rightSection={session.step_answer_loading ? <Loader size={14} /> : undefined}
        />
      </div>
    </div>
  );
}

function StepView({ step, stage }: {
  readonly step: NonNullable<SessionSnapshot['displayed_step']>;
  readonly stage?: LearningStage;
}) {
  const detail = step.detailed_explanation?.trim() || step.explanation.trim();
  return <Stack gap="md" p="md">
    {stage !== undefined && <Alert variant="light" title="Learning goal">{stage.goal}</Alert>}
    <div>
      <Text fw={700} size="lg">{step.why_included || step.title}</Text>
      <Text size="sm" mt="xs" className="preserve-lines">{detail}</Text>
    </div>
    {step.line_annotations.length > 0 && <div>
      <Divider label="Important lines" labelPosition="left" mb="xs" />
      <Stack gap="xs">
        {step.line_annotations.map((annotation, index) => <div className="annotation-row" key={`${annotation.start_line}-${index}`}>
          <Badge size="sm" variant="light" color="gray">L{annotation.start_line}</Badge>
          <Text size="sm">{annotation.text}</Text>
        </div>)}
      </Stack>
    </div>}
    {stage?.checkpoint !== undefined && <Alert color="blue" variant="light" title="Before moving on">{stage.checkpoint}</Alert>}
  </Stack>;
}

function AnswerView({ session, back }: { readonly session: SessionSnapshot; readonly back: () => void }) {
  return <Stack gap="md" p="md">
    <Button variant="subtle" size="compact-sm" className="back-link" onClick={back}>← Back to step</Button>
    {session.step_answer_loading && <Group gap="sm"><Loader size="sm" /><Text>Thinking…</Text></Group>}
    {session.step_answer_error !== undefined && <Alert color="red" title="Answer failed">{session.step_answer_error}</Alert>}
    {session.step_answer !== undefined && <>
      <div className="markdown-body"><ReactMarkdown>{session.step_answer.answer}</ReactMarkdown></div>
      {session.step_answer.why_it_matters !== undefined &&
        <Alert variant="light" title="Why it matters">{session.step_answer.why_it_matters}</Alert>}
      {session.step_answer.important_lines.length > 0 && <div>
        <Divider label="Important lines" labelPosition="left" mb="xs" />
        {session.step_answer.important_lines.map((line, index) =>
          <Text key={`${line.start_line}-${index}`} size="sm"><strong>L{line.start_line}</strong> · {line.text}</Text>)}
      </div>}
    </>}
  </Stack>;
}
