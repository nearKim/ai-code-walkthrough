export type DiagramTone = 'primary' | 'data' | 'dependency' | 'neutral';

export const kindOrder = ['entrypoint', 'application', 'domain', 'infrastructure', 'data', 'shared'];

export function toneForKind(kind: string): DiagramTone {
  if (kind === 'entrypoint' || kind === 'application') return 'primary';
  if (kind === 'data' || kind === 'shared') return 'data';
  if (kind === 'domain' || kind === 'infrastructure') return 'dependency';
  return 'neutral';
}

export function toneForRelationship(kind: string): DiagramTone {
  if (kind === 'call' || kind === 'calls' || kind === 'creates' || kind === 'instantiation') return 'primary';
  if (kind === 'read' || kind === 'reads' || kind === 'write' || kind === 'writes' || kind === 'data_flow') return 'data';
  if (kind === 'depends_on' || kind === 'dependency') return 'dependency';
  return 'neutral';
}

export function toneForRelationships(kinds: ReadonlySet<string>): DiagramTone {
  if ([...kinds].some((kind) => toneForRelationship(kind) === 'primary')) return 'primary';
  if ([...kinds].some((kind) => toneForRelationship(kind) === 'data')) return 'data';
  if ([...kinds].some((kind) => toneForRelationship(kind) === 'dependency')) return 'dependency';
  return 'neutral';
}

export function titleForKind(kind: string): string {
  const titles: Record<string, string> = {
    entrypoint: 'Interfaces',
    application: 'Application',
    domain: 'Domain',
    infrastructure: 'Infrastructure',
    data: 'Data boundaries',
    shared: 'Shared contracts',
  };
  return titles[kind] ?? humanize(kind);
}

export function roleForKind(kind: string): string {
  const roles: Record<string, string> = {
    entrypoint: 'entry point',
    application: 'application workflow',
    domain: 'domain policy',
    infrastructure: 'runtime infrastructure',
    data: 'data boundary',
    shared: 'shared contract',
  };
  return roles[kind] ?? humanize(kind);
}

export function humanize(value: string): string {
  return value.replaceAll('_', ' ');
}
