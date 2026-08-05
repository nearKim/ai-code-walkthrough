import { Alert, Button, Group, Loader, ScrollArea, Stack, Text, TextInput, Title } from '@mantine/core';
import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import type { RightPaneActions } from '../RightPane';
import type { SessionSnapshot } from '../types';

interface TourViewProps {
  readonly session: SessionSnapshot;
  readonly actions: RightPaneActions;
}

export function TourView({ session, actions }: TourViewProps) {
  const step = session.displayed_step;
  const [question, setQuestion] = useState('');
  const [showAnswer, setShowAnswer] = useState(false);

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

  return <div className="pane-column">
    <header className="pane-header tour-header">
      <Title order={2} lineClamp={2}>{step.title}</Title>
      <Button size="compact-xs" variant="subtle" color="gray" onClick={() => void actions.tour('stop')}>Back to map</Button>
    </header>
    <ScrollArea className="tour-scroll" offsetScrollbars>
      {showAnswer
        ? <AnswerView session={session} back={() => setShowAnswer(false)} />
        : <StepView step={step} />}
    </ScrollArea>
    <div className="tour-dock">
      <Group gap="xs" className="tour-navigation" wrap="nowrap">
        <Button size="compact-sm" variant="default" disabled={session.can_previous !== true} onClick={() => void actions.tour('previous')}>Previous</Button>
        <Button size="compact-sm" onClick={() => void actions.tour('next')}>Next</Button>
      </Group>
      <TextInput
        aria-label="Ask about this step"
        placeholder="Ask about this step"
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
  </div>;
}

function StepView({ step }: { readonly step: NonNullable<SessionSnapshot['displayed_step']> }) {
  return <div className="step-content">
    <Text className="preserve-lines">{step.detailed_explanation?.trim() || step.explanation.trim()}</Text>
  </div>;
}

function AnswerView({ session, back }: { readonly session: SessionSnapshot; readonly back: () => void }) {
  return <Stack gap="md" p="md">
    <Button variant="subtle" size="compact-sm" className="back-link" onClick={back}>Back</Button>
    {session.step_answer_loading && <Group gap="sm"><Loader size="sm" /><Text>Thinking</Text></Group>}
    {session.step_answer_error !== undefined && <Alert color="red">{session.step_answer_error}</Alert>}
    {session.step_answer !== undefined && <>
      <div className="markdown-body"><ReactMarkdown>{session.step_answer.answer}</ReactMarkdown></div>
      {session.step_answer.why_it_matters !== undefined && <Text size="sm" c="dimmed">{session.step_answer.why_it_matters}</Text>}
      {session.step_answer.important_lines.length > 0 && <div className="answer-lines">
        {session.step_answer.important_lines.map((line, index) =>
          <Text key={`${line.start_line}-${index}`} size="sm"><strong>L{line.start_line}</strong> {line.text}</Text>)}
      </div>}
    </>}
  </Stack>;
}
