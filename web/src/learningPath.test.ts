import { expect, test } from 'vitest';
import { learningProgress, navigableLearningStages, stageForStep, stageProgress } from './learningPath';
import type { FlowMap, FlowStep } from './types';

test('keeps only learning stages with grounded code stops', () => {
  const stages = navigableLearningStages(flow);

  expect(stages.map((stage) => stage.id)).toEqual(['orientation', 'behavior']);
  expect(stageForStep(stages, 'behavior')?.title).toBe('Behavior');
});

test('counts unique digested stops across the path and each stage', () => {
  const stages = navigableLearningStages(flow);

  expect(learningProgress(stages, ['entry', 'entry', 'outside'])).toEqual({ complete: 1, total: 2 });
  expect(stageProgress(stages[1]!, ['entry', 'behavior'])).toEqual({ complete: 1, total: 1 });
});

const flow: FlowMap = {
  summary: 'Test flow.',
  steps: [step('entry'), step('behavior')],
  learning_path: [
    { id: 'orientation', title: 'Orientation', goal: 'Start here.', component_ids: [], step_ids: ['entry'] },
    { id: 'behavior', title: 'Behavior', goal: 'Follow behavior.', component_ids: [], step_ids: ['behavior'] },
    { id: 'empty', title: 'Empty', goal: 'Ignore this.', component_ids: [], step_ids: ['missing'] },
  ],
  terminal_step_ids: ['behavior'],
  edges: [],
};

function step(id: string): FlowStep {
  return {
    id,
    title: id,
    file_path: 'src/example.ts',
    start_line: 1,
    end_line: 1,
    explanation: 'Example.',
    why_included: 'Example.',
    uncertain: false,
    line_annotations: [],
    evidence: [],
  };
}
