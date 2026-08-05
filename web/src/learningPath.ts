import type { FlowMap, LearningStage } from './types';

export interface LearningProgress {
  readonly complete: number;
  readonly total: number;
}

export function navigableLearningStages(flow: FlowMap): ReadonlyArray<LearningStage> {
  const stepIds = new Set(flow.steps.map((step) => step.id));
  return flow.learning_path.filter((stage) => stage.step_ids.some((id) => stepIds.has(id)));
}

export function stageForStep(
  stages: ReadonlyArray<LearningStage>,
  stepId: string | undefined,
): LearningStage | undefined {
  return stages.find((stage) => stepId !== undefined && stage.step_ids.includes(stepId));
}

export function stageProgress(
  stage: LearningStage,
  completedStepIds: ReadonlyArray<string>,
): LearningProgress {
  return progressForStepIds(stage.step_ids, completedStepIds);
}

export function learningProgress(
  stages: ReadonlyArray<LearningStage>,
  completedStepIds: ReadonlyArray<string>,
): LearningProgress {
  return progressForStepIds(stages.flatMap((stage) => stage.step_ids), completedStepIds);
}

function progressForStepIds(
  stepIds: ReadonlyArray<string>,
  completedStepIds: ReadonlyArray<string>,
): LearningProgress {
  const expectedIds = new Set(stepIds);
  const completedIds = new Set(completedStepIds);
  const complete = [...expectedIds].filter((id) => completedIds.has(id)).length;
  return { complete, total: expectedIds.size };
}
