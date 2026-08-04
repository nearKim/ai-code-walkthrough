import type {
  AnalysisModeId,
  MechanicalSymbolInventory,
  ProviderId,
  ProviderStatus,
  SessionSnapshot,
  SourceFile,
  WalkthroughSettings,
} from './types';

interface ErrorResponse {
  readonly message?: string;
}

interface SettingsResponse {
  readonly settings: WalkthroughSettings;
}

interface RawCallable {
  readonly n: string;
  readonly r: readonly [number, number];
}

interface RawClass extends RawCallable {
  readonly b: ReadonlyArray<string>;
  readonly s: ReadonlyArray<string>;
  readonly m: ReadonlyArray<RawCallable>;
}

interface RawSymbolInventory {
  readonly tool: string;
  readonly language: string;
  readonly files_scanned: number;
  readonly symbol_count: number;
  readonly truncated: boolean;
  readonly modules: ReadonlyArray<{
    readonly p: string;
    readonly i: ReadonlyArray<string>;
    readonly c: ReadonlyArray<RawClass>;
    readonly f: ReadonlyArray<RawCallable>;
  }>;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    const message = await response.json()
      .then((body: unknown) => isErrorResponse(body) ? body.message : undefined)
      .catch(() => undefined);
    throw new Error(message ?? `${response.status} ${response.statusText}`);
  }
  return response.json() as Promise<T>;
}

function isErrorResponse(value: unknown): value is ErrorResponse {
  return typeof value === 'object' && value !== null &&
    (!('message' in value) || typeof value.message === 'string');
}

function jsonRequest(method: 'POST' | 'PUT', body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

export const api = {
  session: (): Promise<SessionSnapshot> => request('/api/session'),
  providers: (): Promise<ReadonlyArray<ProviderStatus>> => request('/api/providers'),
  settings: async (): Promise<WalkthroughSettings> => (await request<SettingsResponse>('/api/settings')).settings,
  symbols: async (): Promise<MechanicalSymbolInventory> => normalizeSymbolInventory(
    await request<RawSymbolInventory>('/api/symbols'),
  ),
  saveSettings: async (settings: WalkthroughSettings): Promise<WalkthroughSettings> =>
    (await request<SettingsResponse>('/api/settings', jsonRequest('PUT', settings))).settings,
  sample: (): Promise<SessionSnapshot> => request('/api/sample', jsonRequest('POST', {})),
  startMapping: (question: string, mode: AnalysisModeId, provider: ProviderId): Promise<SessionSnapshot> =>
    request('/api/mapping', jsonRequest('POST', { question, mode, provider })),
  cancelMapping: (): Promise<SessionSnapshot> => request('/api/mapping', { method: 'DELETE' }),
  tour: (action: 'start' | 'preview' | 'next' | 'previous' | 'stop' | 'new', stepId?: string): Promise<SessionSnapshot> =>
    request('/api/tour', jsonRequest('POST', { action, step_id: stepId })),
  answer: (question: string): Promise<SessionSnapshot> =>
    request('/api/step-answer', jsonRequest('POST', { question })),
  source: (path: string): Promise<SourceFile> => request(`/api/source?path=${encodeURIComponent(path)}`),
  exportMarkdown: async (): Promise<string> => {
    const response = await fetch('/api/export');
    if (!response.ok) {
      const body = await response.json() as ErrorResponse;
      throw new Error(body.message ?? `${response.status} ${response.statusText}`);
    }
    return response.text();
  },
};

function normalizeSymbolInventory(raw: RawSymbolInventory): MechanicalSymbolInventory {
  const callable = (item: RawCallable) => ({
    name: item.n,
    start_line: item.r[0],
    end_line: item.r[1],
  });
  return {
    tool: raw.tool,
    language: raw.language,
    files_scanned: raw.files_scanned,
    symbol_count: raw.symbol_count,
    truncated: raw.truncated,
    modules: raw.modules.map((module) => ({
      path: module.p,
      imports: module.i,
      classes: module.c.map((item) => ({
        ...callable(item),
        bases: item.b,
        state_fields: item.s,
        methods: item.m.map(callable),
      })),
      functions: module.f.map(callable),
    })),
  };
}

export function subscribeToEvents(
  onSession: (snapshot: SessionSnapshot) => void,
  onProgress: (line: string) => void,
): () => void {
  const events = new EventSource('/api/events');
  events.addEventListener('session', (event) => {
    onSession(JSON.parse(event.data) as SessionSnapshot);
  });
  events.addEventListener('progress', (event) => {
    const payload = JSON.parse(event.data) as { readonly line?: unknown };
    if (typeof payload.line === 'string') onProgress(payload.line);
  });
  return () => events.close();
}
