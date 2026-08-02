import type {
  AnalysisModeId,
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
  saveSettings: async (settings: WalkthroughSettings): Promise<WalkthroughSettings> =>
    (await request<SettingsResponse>('/api/settings', jsonRequest('PUT', settings))).settings,
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
