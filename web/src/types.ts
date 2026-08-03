export type SessionState = 'INPUT' | 'LOADING' | 'OVERVIEW' | 'TOUR_ACTIVE';
export type AnalysisModeId = 'understand' | 'review' | 'trace';
export type ProviderId = 'claude_cli' | 'codex_cli';

export interface LineAnnotation {
  readonly start_line: number;
  readonly end_line: number;
  readonly text: string;
}

export interface EvidenceItem {
  readonly kind: string;
  readonly label: string;
  readonly file_path?: string;
  readonly start_line?: number;
  readonly end_line?: number;
  readonly text?: string;
}

export interface FlowStep {
  readonly id: string;
  readonly title: string;
  readonly file_path: string;
  readonly symbol?: string;
  readonly start_line: number;
  readonly end_line: number;
  readonly explanation: string;
  readonly detailed_explanation?: string;
  readonly why_included: string;
  readonly step_type?: string;
  readonly importance?: string;
  readonly uncertain: boolean;
  readonly line_annotations: ReadonlyArray<LineAnnotation>;
  readonly severity?: string;
  readonly confidence?: string;
  readonly risk_type?: string;
  readonly evidence: ReadonlyArray<EvidenceItem>;
  readonly suggested_action?: string;
  readonly test_gap?: string;
}

export interface StepEdge {
  readonly id: string;
  readonly from_step_id: string;
  readonly to_step_id: string;
  readonly kind: string;
  readonly rationale: string;
  readonly importance?: string;
  readonly call_site_file_path?: string;
  readonly call_site_start_line?: number;
  readonly call_site_end_line?: number;
  readonly call_site_label?: string;
  readonly evidence: ReadonlyArray<EvidenceItem>;
  readonly uncertain: boolean;
}

export interface ArchitectureComponent {
  readonly id: string;
  readonly name: string;
  readonly kind: string;
  readonly responsibility: string;
  readonly responsibilities: ReadonlyArray<ArchitectureResponsibility>;
  readonly key_paths: ReadonlyArray<string>;
  readonly key_symbols: ReadonlyArray<string>;
  readonly evidence: ReadonlyArray<EvidenceItem>;
  readonly uncertain: boolean;
}

export interface ArchitectureResponsibility {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly evidence: ReadonlyArray<EvidenceItem>;
  readonly collaborator_component_ids: ReadonlyArray<string>;
  readonly relationship_ids: ReadonlyArray<string>;
  readonly uncertain: boolean;
}

export interface ComponentRelationship {
  readonly id: string;
  readonly from_component_id: string;
  readonly to_component_id: string;
  readonly kind: string;
  readonly description: string;
  readonly evidence: ReadonlyArray<EvidenceItem>;
  readonly uncertain: boolean;
}

export interface CodebaseArchitecture {
  readonly system_purpose: string;
  readonly components: ReadonlyArray<ArchitectureComponent>;
  readonly relationships: ReadonlyArray<ComponentRelationship>;
  readonly cross_cutting_concerns: ReadonlyArray<string>;
  readonly coverage_notes: ReadonlyArray<string>;
}

export interface LearningStage {
  readonly id: string;
  readonly title: string;
  readonly goal: string;
  readonly component_ids: ReadonlyArray<string>;
  readonly step_ids: ReadonlyArray<string>;
  readonly checkpoint?: string;
}

export interface FlowMap {
  readonly mode?: string;
  readonly summary: string;
  readonly steps: ReadonlyArray<FlowStep>;
  readonly architecture?: CodebaseArchitecture;
  readonly learning_path: ReadonlyArray<LearningStage>;
  readonly entry_step_id?: string;
  readonly terminal_step_ids: ReadonlyArray<string>;
  readonly edges: ReadonlyArray<StepEdge>;
}

export interface StepAnswer {
  readonly answer: string;
  readonly why_it_matters?: string;
  readonly important_lines: ReadonlyArray<LineAnnotation>;
  readonly evidence: ReadonlyArray<EvidenceItem>;
  readonly confidence?: string;
  readonly uncertain: boolean;
}

export interface ResponseMetadata {
  readonly duration_ms: number;
  readonly cost_usd?: number;
  readonly num_turns: number;
  readonly step_count: number;
  readonly file_count: number;
}

export interface SessionSnapshot {
  readonly state: SessionState;
  readonly repository: string;
  readonly repository_path: string;
  readonly question?: string;
  readonly mode: AnalysisModeId;
  readonly provider: ProviderId;
  readonly flow_map?: FlowMap;
  readonly metadata?: ResponseMetadata;
  readonly current_step_index: number;
  readonly displayed_step_index: number;
  readonly displayed_step?: FlowStep;
  readonly next_step?: FlowStep;
  readonly next_edge?: StepEdge;
  readonly broken_step_ids: ReadonlyArray<string>;
  readonly step_answer?: StepAnswer;
  readonly step_answer_loading: boolean;
  readonly step_answer_error?: string;
  readonly error_message?: string;
  readonly progress_lines: ReadonlyArray<string>;
  readonly can_previous?: boolean;
}

export interface ProviderStatus {
  readonly id: ProviderId;
  readonly name: string;
  readonly available: boolean;
  readonly message: string;
}

export interface WalkthroughSettings {
  readonly provider_id: ProviderId;
  readonly codex_cli_path: string;
  readonly codex_model: string;
  readonly codex_reasoning_effort: string;
  readonly claude_path: string;
  readonly claude_model: string;
  readonly claude_effort: string;
  readonly max_steps: number;
  readonly default_mode_id: AnalysisModeId;
  readonly enable_mcp: boolean;
  readonly mcp_config_path: string;
}

export interface SourceFile {
  readonly path: string;
  readonly content: string;
}

export interface MechanicalCallable {
  readonly name: string;
  readonly start_line: number;
  readonly end_line: number;
}

export interface MechanicalClass extends MechanicalCallable {
  readonly bases: ReadonlyArray<string>;
  readonly state_fields: ReadonlyArray<string>;
  readonly methods: ReadonlyArray<MechanicalCallable>;
}

export interface MechanicalModule {
  readonly path: string;
  readonly imports: ReadonlyArray<string>;
  readonly classes: ReadonlyArray<MechanicalClass>;
  readonly functions: ReadonlyArray<MechanicalCallable>;
}

export interface MechanicalSymbolInventory {
  readonly tool: string;
  readonly language: string;
  readonly files_scanned: number;
  readonly symbol_count: number;
  readonly truncated: boolean;
  readonly modules: ReadonlyArray<MechanicalModule>;
}
