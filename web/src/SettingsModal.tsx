import {
  Button,
  Group,
  Modal,
  NumberInput,
  Select,
  Stack,
  Switch,
  TextInput,
} from '@mantine/core';
import { useEffect, useState } from 'react';
import type { AnalysisModeId, ProviderId, WalkthroughSettings } from './types';

interface SettingsModalProps {
  readonly opened: boolean;
  readonly settings?: WalkthroughSettings;
  readonly onClose: () => void;
  readonly onSave: (settings: WalkthroughSettings) => Promise<void>;
}

export function SettingsModal({ opened, settings, onClose, onSave }: SettingsModalProps) {
  const [draft, setDraft] = useState(settings);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (opened) setDraft(settings);
  }, [opened, settings]);

  if (draft === undefined) return null;

  const save = async () => {
    setSaving(true);
    try {
      await onSave(draft);
      onClose();
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal opened={opened} onClose={onClose} title="Walkthrough settings" size="lg">
      <Stack gap="sm">
        <Select
          label="Default provider"
          data={providerOptions}
          value={draft.provider_id}
          onChange={(value) => value !== null && setDraft({ ...draft, provider_id: value as ProviderId })}
        />
        <TextInput
          label="Codex CLI path"
          value={draft.codex_cli_path}
          onChange={(event) => setDraft({ ...draft, codex_cli_path: event.currentTarget.value })}
        />
        <Group grow align="start">
          <Select label="Codex model" data={['gpt-5.6-sol']} value={draft.codex_model} disabled />
          <Select
            label="Codex reasoning"
            data={['ultra', 'max']}
            value={draft.codex_reasoning_effort}
            onChange={(value) => value !== null && setDraft({ ...draft, codex_reasoning_effort: value })}
          />
        </Group>
        <TextInput
          label="Claude CLI path"
          value={draft.claude_path}
          onChange={(event) => setDraft({ ...draft, claude_path: event.currentTarget.value })}
        />
        <Group grow align="start">
          <Select
            label="Claude model"
            data={[{ value: 'fable', label: 'Claude Fable 5' }, { value: 'opus', label: 'Claude Opus 5' }]}
            value={draft.claude_model}
            onChange={(value) => value !== null && setDraft({ ...draft, claude_model: value })}
          />
          <TextInput
            label="Claude effort"
            value={draft.claude_effort}
            onChange={(event) => setDraft({ ...draft, claude_effort: event.currentTarget.value })}
          />
        </Group>
        <Group grow align="start">
          <NumberInput
            label="Maximum steps"
            min={1}
            max={100}
            value={draft.max_steps}
            onChange={(value) => typeof value === 'number' && setDraft({ ...draft, max_steps: value })}
          />
          <Select
            label="Default mode"
            data={modeOptions}
            value={draft.default_mode_id}
            onChange={(value) => value !== null && setDraft({ ...draft, default_mode_id: value as AnalysisModeId })}
          />
        </Group>
        <Switch
          label="Enable Claude MCP semantic navigation"
          checked={draft.enable_mcp}
          onChange={(event) => setDraft({ ...draft, enable_mcp: event.currentTarget.checked })}
        />
        {draft.enable_mcp && <TextInput
          label="MCP config path"
          value={draft.mcp_config_path}
          onChange={(event) => setDraft({ ...draft, mcp_config_path: event.currentTarget.value })}
        />}
        <Group justify="flex-end" mt="sm">
          <Button variant="default" onClick={onClose}>Cancel</Button>
          <Button onClick={() => void save()} loading={saving}>Save</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

const providerOptions = [
  { value: 'claude_cli', label: 'Claude CLI' },
  { value: 'codex_cli', label: 'Codex CLI' },
];

const modeOptions = [
  { value: 'understand', label: 'Learn' },
  { value: 'review', label: 'Review' },
  { value: 'trace', label: 'Trace' },
];
