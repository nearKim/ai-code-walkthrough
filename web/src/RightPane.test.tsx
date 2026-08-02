import { MantineProvider } from '@mantine/core';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { RightPane, type RightPaneActions } from './RightPane';
import type { SessionSnapshot } from './types';

test('starts the default whole-codebase walkthrough', async () => {
  const startMapping = vi.fn(async () => undefined);
  const noop = vi.fn(async () => undefined);
  const actions: RightPaneActions = {
    startMapping,
    cancelMapping: noop,
    tour: noop,
    answer: noop,
    copyMarkdown: noop,
    openSettings: vi.fn(),
    focusCode: vi.fn(),
  };
  const session: SessionSnapshot = {
    state: 'INPUT',
    repository: 'sample-repo',
    repository_path: '/tmp/sample-repo',
    mode: 'understand',
    provider: 'codex_cli',
    current_step_index: -1,
    displayed_step_index: -1,
    broken_step_ids: [],
    step_answer_loading: false,
    progress_lines: [],
  };

  render(
    <MantineProvider>
      <RightPane
        session={session}
        providers={[{ id: 'codex_cli', name: 'Codex CLI', available: true, message: 'Available' }]}
        actions={actions}
      />
    </MantineProvider>,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Learn codebase' }));
  await waitFor(() => expect(startMapping).toHaveBeenCalledWith('', 'understand', 'codex_cli'));
});
