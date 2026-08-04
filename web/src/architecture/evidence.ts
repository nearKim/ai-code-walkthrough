import type { ArchitectureComponent, ArchitectureResponsibility, EvidenceItem } from '../types';

const ownerKinds = new Set(['interface', 'class', 'function', 'module', 'config', 'schema']);

export function architectureEvidenceKey(evidence: EvidenceItem): string {
  return [evidence.kind, evidence.label, evidence.file_path ?? '', evidence.start_line ?? ''].join(':');
}

export function componentResponsibilities(
  component: ArchitectureComponent,
): ReadonlyArray<ArchitectureResponsibility> {
  if (component.responsibilities.length > 0) return component.responsibilities;
  return [{
    id: `${component.id}:summary`,
    title: 'Primary responsibility',
    description: component.responsibility,
    evidence: component.evidence,
    collaborator_component_ids: [],
    relationship_ids: [],
    uncertain: component.uncertain,
  }];
}

export function responsibilityOwners(
  responsibility: ArchitectureResponsibility,
): ReadonlyArray<EvidenceItem> {
  const owners = responsibility.evidence.filter((evidence) => ownerKinds.has(evidence.kind));
  return owners.length > 0 ? owners : responsibility.evidence.slice(0, 1);
}

/** The symbols the model cited for a responsibility, so the claim is not asserted bare. */
export function responsibilityGrounding(responsibility: ArchitectureResponsibility): string | undefined {
  const labels = [...new Set(responsibility.evidence.map((item) => lastSymbolPart(item.label)))];
  if (labels.length === 0) return undefined;
  const shown = labels.slice(0, 3).join(', ');
  return labels.length > 3 ? `${shown} +${labels.length - 3} more` : shown;
}

export function responsibilitySummary(
  responsibilities: ReadonlyArray<ArchitectureResponsibility>,
): string {
  return [...new Set(responsibilities.map((responsibility) => responsibility.title))].join(' · ');
}

export function responsibilityBelongsToOwner(
  responsibility: ArchitectureResponsibility,
  owner: EvidenceItem,
): boolean {
  return responsibility.evidence.some((evidence) =>
    sameCodeOwner(evidence, owner) || evidenceBelongsToOwner(evidence, owner));
}

export function evidenceBelongsToOwner(evidence: EvidenceItem, owner: EvidenceItem): boolean {
  if (evidence.file_path !== owner.file_path || evidence.start_line === undefined || owner.start_line === undefined) {
    return false;
  }
  const evidenceEnd = evidence.end_line ?? evidence.start_line;
  const ownerEnd = owner.end_line ?? owner.start_line;
  return evidence.start_line >= owner.start_line && evidenceEnd <= ownerEnd;
}

export function sameCodeOwner(left: EvidenceItem, right: EvidenceItem): boolean {
  return left.kind === right.kind && left.label === right.label && left.file_path === right.file_path;
}

export function uniqueEvidence(evidence: ReadonlyArray<EvidenceItem>): ReadonlyArray<EvidenceItem> {
  const seen = new Set<string>();
  return evidence.filter((item) => {
    const key = architectureEvidenceKey(item);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export function methodBehavior(
  method: EvidenceItem,
  responsibilities: ReadonlyArray<ArchitectureResponsibility>,
): string | undefined {
  for (const responsibility of responsibilities) {
    const evidence = responsibility.evidence.find((candidate) =>
      candidate.kind === 'method' && sameMethod(candidate, method));
    if (evidence?.text !== undefined) return evidence.text;
    if (evidence !== undefined) return responsibility.description;
  }
  return method.text;
}

export function sameMethod(left: EvidenceItem, right: EvidenceItem): boolean {
  if (left.file_path !== right.file_path || lastSymbolPart(left.label) !== lastSymbolPart(right.label)) return false;
  if (left.start_line === undefined || right.start_line === undefined) return true;
  const leftEnd = left.end_line ?? left.start_line;
  const rightEnd = right.end_line ?? right.start_line;
  return left.start_line <= rightEnd && right.start_line <= leftEnd;
}

export function methodLabel(label: string): string {
  const name = lastSymbolPart(label);
  return name.endsWith('()') ? name : `${name}()`;
}

export function lastSymbolPart(label: string): string {
  return label.split('.').pop() ?? label;
}

export function hasCodeLocation(evidence: EvidenceItem): boolean {
  return evidence.file_path !== undefined && evidence.start_line !== undefined;
}
