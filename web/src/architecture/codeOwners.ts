import type {
  ArchitectureComponent,
  ArchitectureResponsibility,
  EvidenceItem,
  FlowStep,
  MechanicalCallable,
  MechanicalClass,
  MechanicalModule,
  MechanicalSymbolInventory,
} from '../types';
import {
  evidenceBelongsToOwner,
  lastSymbolPart,
  responsibilityBelongsToOwner,
  responsibilityOwners,
  uniqueEvidence,
} from './evidence';

/** A class or function from the mechanical inventory, with the responsibilities it carries. */
export interface CodeOwner {
  readonly evidence: EvidenceItem;
  readonly methods: ReadonlyArray<EvidenceItem>;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
}

/**
 * Prefers owners the AST actually indexed, keeping only those carrying a responsibility or named in
 * the component's key symbols. Falls back to the full index rather than showing nothing.
 */
export function codeOwners(
  modules: ReadonlyArray<MechanicalModule>,
  responsibilities: ReadonlyArray<ArchitectureResponsibility>,
  keySymbols: ReadonlyArray<string>,
): ReadonlyArray<CodeOwner> {
  const indexed = modules.flatMap((module) => [
    ...module.classes.map((item) => codeOwnerForClass(module, item, responsibilities)),
    ...module.functions.map((item) => codeOwnerForFunction(module, item, responsibilities)),
  ]);
  const relevantIndexed = indexed.filter((owner) =>
    owner.responsibilities.length > 0 || ownerMatchesKeySymbols(owner, keySymbols));
  const visibleIndexed = relevantIndexed.length > 0 ? relevantIndexed : indexed;
  return uniqueCodeOwners([...visibleIndexed, ...fallbackCodeOwners(responsibilities)]);
}

export function modulesForComponent(
  inventory: MechanicalSymbolInventory,
  mappedPaths: ReadonlyArray<string>,
): ReadonlyArray<MechanicalModule> {
  const paths = mappedPaths.map((path) => path.replace(/\/$/, ''));
  return [...inventory.modules
    .filter((module) => paths.some((path) => module.path === path || module.path.startsWith(`${path}/`)))]
    .sort((left, right) => left.path.localeCompare(right.path));
}

export function stepsForComponent(
  component: ArchitectureComponent,
  steps: ReadonlyArray<FlowStep>,
): ReadonlyArray<FlowStep> {
  const paths = new Set(component.key_paths);
  const symbols = new Set(component.key_symbols);
  return steps.filter((step) => paths.has(step.file_path) || (step.symbol !== undefined && symbols.has(step.symbol)));
}

function codeOwnerForClass(
  module: MechanicalModule,
  item: MechanicalClass,
  responsibilities: ReadonlyArray<ArchitectureResponsibility>,
): CodeOwner {
  const evidence = callableEvidence('class', item.name, module.path, item);
  return {
    evidence,
    methods: item.methods.map((method) => callableEvidence('method', `${item.name}.${method.name}`, module.path, method)),
    responsibilities: responsibilities.filter((responsibility) => responsibilityBelongsToOwner(responsibility, evidence)),
  };
}

function codeOwnerForFunction(
  module: MechanicalModule,
  item: MechanicalCallable,
  responsibilities: ReadonlyArray<ArchitectureResponsibility>,
): CodeOwner {
  const evidence = callableEvidence('function', item.name, module.path, item);
  return {
    evidence,
    methods: [],
    responsibilities: responsibilities.filter((responsibility) => responsibilityBelongsToOwner(responsibility, evidence)),
  };
}

/** Owners recovered from responsibility evidence when the AST index has nothing for them. */
function fallbackCodeOwners(responsibilities: ReadonlyArray<ArchitectureResponsibility>): ReadonlyArray<CodeOwner> {
  const candidates = uniqueEvidence(responsibilities.flatMap(responsibilityOwners));
  const classes = candidates.filter((evidence) => evidence.kind === 'class');
  const owners = candidates.filter((evidence) => evidence.kind !== 'method' ||
    !classes.some((owner) => evidenceBelongsToOwner(evidence, owner)));
  return owners.map((evidence) => ({
    evidence,
    methods: evidence.kind === 'class'
      ? uniqueEvidence(responsibilities.flatMap((responsibility) => responsibility.evidence)
        .filter((candidate) => candidate.kind === 'method' && evidenceBelongsToOwner(candidate, evidence)))
      : [],
    responsibilities: responsibilities.filter((responsibility) => responsibilityBelongsToOwner(responsibility, evidence)),
  }));
}

function callableEvidence(kind: string, label: string, path: string, item: MechanicalCallable): EvidenceItem {
  return {
    kind,
    label,
    file_path: path,
    start_line: item.start_line,
    end_line: item.end_line,
  };
}

function ownerMatchesKeySymbols(owner: CodeOwner, keySymbols: ReadonlyArray<string>): boolean {
  const symbols = new Set(keySymbols);
  return symbols.has(owner.evidence.label) ||
    owner.methods.some((method) => symbols.has(method.label) || symbols.has(lastSymbolPart(method.label)));
}

function uniqueCodeOwners(owners: ReadonlyArray<CodeOwner>): ReadonlyArray<CodeOwner> {
  const seen = new Set<string>();
  return owners.filter((owner) => {
    const key = `${owner.evidence.kind}:${owner.evidence.label}:${owner.evidence.file_path ?? ''}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}
