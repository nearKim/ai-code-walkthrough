import { expect, test } from 'vitest';
import {
  evidenceBelongsToOwner,
  methodBehavior,
  methodLabel,
  sameMethod,
  uniqueEvidence,
} from './evidence';
import type { ArchitectureResponsibility, EvidenceItem } from '../types';

const owner: EvidenceItem = { kind: 'class', label: 'Store', file_path: 'src/store.py', start_line: 1, end_line: 20 };
const method: EvidenceItem = { kind: 'method', label: 'Store.save', file_path: 'src/store.py', start_line: 5, end_line: 9 };

test('owner containment respects file and line bounds', () => {
  expect(evidenceBelongsToOwner(method, owner)).toBe(true);
  expect(evidenceBelongsToOwner({ ...method, start_line: 21, end_line: 25 }, owner)).toBe(false);
  expect(evidenceBelongsToOwner({ ...method, file_path: 'src/other.py' }, owner)).toBe(false);
  expect(evidenceBelongsToOwner({ ...method, start_line: undefined }, owner)).toBe(false);
});

test('sameMethod matches on trailing symbol part and overlapping ranges', () => {
  expect(sameMethod(method, { ...method, label: 'save' })).toBe(true);
  expect(sameMethod(method, { ...method, start_line: 9, end_line: 14 })).toBe(true);
  expect(sameMethod(method, { ...method, start_line: 10, end_line: 14 })).toBe(false);
  expect(sameMethod(method, { ...method, label: 'Store.load' })).toBe(false);
});

// Pins the signature the two former copies had drifted on: absent behavior stays
// undefined here so callers choose their own fallback, rather than collapsing to ''.
test('methodBehavior prefers evidence text, then description, then the method text', () => {
  const withText: ArchitectureResponsibility = {
    id: 'r1',
    title: 'Persist',
    description: 'Describes persistence.',
    evidence: [{ ...method, text: 'Writes the row.' }],
    collaborator_component_ids: [],
    relationship_ids: [],
    uncertain: false,
  };
  expect(methodBehavior(method, [withText])).toBe('Writes the row.');

  const withoutText: ArchitectureResponsibility = { ...withText, evidence: [{ ...method, text: undefined }] };
  expect(methodBehavior(method, [withoutText])).toBe('Describes persistence.');

  expect(methodBehavior(method, [])).toBeUndefined();
  expect(methodBehavior({ ...method, text: 'Fallback.' }, [])).toBe('Fallback.');
});

test('uniqueEvidence dedupes by kind, label, path and start line', () => {
  expect(uniqueEvidence([owner, { ...owner }, method])).toHaveLength(2);
});

test('methodLabel appends parentheses once', () => {
  expect(methodLabel('Store.save')).toBe('save()');
  expect(methodLabel('save()')).toBe('save()');
});
