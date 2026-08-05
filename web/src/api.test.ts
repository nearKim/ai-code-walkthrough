import { afterEach, expect, test, vi } from 'vitest';
import { api } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
});

test('reports HTML API responses instead of throwing a JSON parse error', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('<!doctype html><title>Wrong app</title>', {
    headers: { 'content-type': 'text/html' },
    status: 200,
    statusText: 'OK',
  })));

  await expect(api.session()).rejects.toThrow('/api/session returned 200 OK text/html instead of JSON');
});

test('keeps the grounded architecture returned with the symbol inventory', async () => {
  const architecture = {
    system_name: 'sample',
    system_purpose: 'Run the sample.',
    containers: [],
    components: [],
    relationships: [],
    cross_cutting_concerns: [],
    coverage_notes: [],
  };
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({
    tool: 'python_stdlib_ast',
    language: 'python',
    files_scanned: 0,
    symbol_count: 0,
    truncated: false,
    architecture,
    modules: [],
  })));

  await expect(api.symbols()).resolves.toMatchObject({ architecture });
});

test('sends the requested diagram section when starting its tour', async () => {
  const fetchMock = vi.fn(async () => Response.json({}));
  vi.stubGlobal('fetch', fetchMock);

  await api.tour('start_section', undefined, 'authentication');

  expect(fetchMock).toHaveBeenCalledWith('/api/tour', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({ action: 'start_section', section_id: 'authentication' }),
  }));
});

test('downloads the technical reference as HTML', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response('<!doctype html>', {
    headers: { 'content-type': 'text/html' },
  })));

  await expect(api.exportTechnicalReference()).resolves.toBeInstanceOf(Blob);
});
