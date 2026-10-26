import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { Miniflare, Log, LogLevel } from 'miniflare';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { RELAY_TOOLS } from '../src/tools';
import { Broker, DISCONNECTED, UPDATE_REQUIRED, type AppSocket } from '../src/broker';

// The app's own get_project/render_preview definitions (docs/agent/relay-tools.json), as a two-tool app sends them.
const tools = RELAY_TOOLS.filter(t => t.name === 'get_project' || t.name === 'render_preview');
const hello = () => JSON.stringify({ type: 'hello', appVersion: 'test', tools });

function next(socket: any, type: string): Promise<any> {
  return new Promise(resolve => {
    const listener = (e: any) => { const m = JSON.parse(e.data); if (m.type === type) { socket.removeEventListener('message', listener); resolve(m); } };
    socket.addEventListener('message', listener);
  });
}

describe.sequential('official SDK MCP over Miniflare HTTP and real app WebSocket', () => {
  let mf: Miniflare;
  const sockets: any[] = [];
  const logLines: string[] = [];
  class CapturedLog extends Log { protected log(message: string) { logLines.push(message); } }
  const persistence = mkdtempSync(join(tmpdir(), 'myembro-relay-test-'));
  const create = () => new Miniflare({ log: new CapturedLog(LogLevel.INFO), modules: true, scriptPath: 'dist/worker.js', compatibilityDate: '2026-07-30', compatibilityFlags: ['nodejs_compat'],
      durableObjects: { RELAY: { className: 'Relay', useSQLite: true } },
      durableObjectsPersist: persistence, bindings: { APP_KEY: 'test-only-app-key', MCP_TOKEN: 'test-only-mcp-token' } });
  beforeAll(() => { mf = create(); });
  afterAll(async () => { for (const s of sockets) { try { s.close(); } catch { /* replaced/closed test sockets */ } } await mf.dispose(); rmSync(persistence, { recursive: true, force: true }); });
  async function rpc(method: string, params = {}, token: string | null = 'test-only-mcp-token') {
    return mf.dispatchFetch('http://localhost/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...(token === null ? {} : { Authorization: `Bearer ${token}` }) }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
  }
  async function app() {
    const response = await mf.dispatchFetch('http://localhost/app', { headers: { Upgrade: 'websocket', Authorization: 'Bearer test-only-app-key' } });
    expect(response.status).toBe(101); const s = response.webSocket!; s.accept(); sockets.push(s);
    s.send(hello()); const ready = next(s, 'pong'); s.send(JSON.stringify({ type: 'ping' })); await ready; return s;
  }
  it('initializes and advertises all static tools before hello; disconnected calls use exact text', async () => {
    const initialized = await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'test', version: '1' } });
    expect(initialized.status).toBe(200); expect((await initialized.json() as any).result.serverInfo.name).toBe('myembro-relay');
    expect((await (await rpc('tools/list')).json() as any).result.tools).toEqual(RELAY_TOOLS);
    const response = await rpc('tools/call', { name: 'get_project', arguments: {} });
    expect((await response.json() as any).result).toEqual({ isError: true, content: [{ type: 'text', text: DISCONNECTED }] });
  });
  it('two-tool hello cannot shrink the deployment catalog', async () => {
    await app(); const response = await rpc('tools/list');
    expect(response.status).toBe(200); expect((await response.json() as any).result.tools).toEqual(RELAY_TOOLS);
  });
  it('returns the update message over MCP for a new tool missing from the two-tool app', async () => {
    const s = await app(); const sent: any[] = [];
    s.addEventListener('message', (e: any) => { const m = JSON.parse(e.data); if (m.type === 'call') sent.push(m); });
    const response = await rpc('tools/call', { name: 'transform_objects', arguments: {} });
    expect(response.status).toBe(200);
    expect((await response.json() as any).result).toEqual({ isError: true, content: [{ type: 'text', text: UPDATE_REQUIRED }] });
    expect(sent).toEqual([]);
  });
  it('forwards UUID/name/args and returns exact text, errors and image content', async () => {
    const s = await app();
    for (const result of [
      { isError: false, content: [{ type: 'text', text: '{"objects":[]}' }] },
      { isError: true, content: [{ type: 'text', text: 'app rejected' }] },
      { isError: false, content: [{ type: 'image', data: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lV8AAAAASUVORK5CYII=', mimeType: 'image/png' }] }
    ]) {
      const image = result.content[0].type === 'image';
      const received = next(s, 'call'); const pending = rpc('tools/call', { name: image ? 'render_preview' : 'get_project', arguments: image ? { widthPx: 512 } : {} });
      const call = await received; expect(call.id).toMatch(/^[0-9a-f-]{36}$/); expect(call.tool).toBe(image ? 'render_preview' : 'get_project'); expect(call.args).toEqual(image ? { widthPx: 512 } : {});
      s.send(JSON.stringify({ type: 'result', id: call.id, ...result }));
      expect((await (await pending).json() as any).result).toEqual(result);
    }
  });
  it('sends the last legacy initialize clientInfo.name with each call, one line and at most 40 characters', async () => {
    const s = await app();
    await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: ' Claude\nDesktop ' + 'x'.repeat(60), version: '1' } });
    const received = next(s, 'call'); const pending = rpc('tools/call', { name: 'get_project', arguments: {} });
    const call = await received; expect(call.client).toBe(('Claude Desktop ' + 'x'.repeat(60)).slice(0, 40));
    s.send(JSON.stringify({ type: 'result', id: call.id, isError: false, content: [{ type: 'text', text: '{}' }] })); await pending;
    await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'test', version: '1' } });
  });
  it('retains static tools across disconnect/restart and rejects unsupported calls', async () => {
    const s = await app();
    const bad = await rpc('tools/call', { name: 'render_preview', arguments: { widthPx: 99999 } });
    expect((await bad.json() as any).result.isError).toBe(true);
    const write = await rpc('tools/call', { name: 'remove_objects', arguments: {} });
    expect((await write.json() as any).result.isError).toBe(true);
    s.close();
    await new Promise(r => setTimeout(r, 10));
    expect((await (await rpc('tools/list')).json() as any).result.tools).toEqual(RELAY_TOOLS);
    // Persisted cache is independent of the old in-memory broker and its WebSocket.
    await mf.dispose(); mf = create();
    expect((await (await rpc('tools/list')).json() as any).result.tools).toEqual(RELAY_TOOLS);
    const disconnected = await rpc('tools/call', { name: 'get_project', arguments: {} });
    expect((await disconnected.json() as any).result).toEqual({ isError: true, content: [{ type: 'text', text: DISCONNECTED }] });
  });
  it('rejects missing, wrong and malformed MCP headers with generic 401 bodies', async () => {
    for (const token of [null, '', 'wrong', 'test-only-mcp-tokeX', 'test-only-mcp-token-longer']) {
      const response = await rpc('tools/list', {}, token);
      expect(response.status).toBe(401); expect(await response.text()).toBe('Unauthorized');
    }
    for (const authorization of ['Basic test-only-mcp-token', 'Bearer', 'Bearer test-only-mcp-token,other']) {
      const response = await mf.dispatchFetch('http://localhost/mcp', { headers: { Authorization: authorization } });
      expect(response.status).toBe(401); expect(await response.text()).toBe('Unauthorized');
    }
    expect((await rpc('tools/list')).status).toBe(200);
  });
  it('removes old MCP secret paths and all MCP query authentication', async () => {
    for (const suffix of ['/retired-path-value', '/anything', '/']) {
      const response = await mf.dispatchFetch('http://localhost/mcp'+suffix, { headers: { Authorization: 'Bearer test-only-mcp-token' } });
      expect(response.status).toBe(404); expect(await response.text()).toBe('Not found');
    }
    for (const query of ['?key=test-only-mcp-token', '?token=test-only-mcp-token']) {
      const response = await mf.dispatchFetch('http://localhost/mcp'+query);
      expect(response.status).toBe(401); expect(await response.text()).toBe('Unauthorized');
    }
  });
  it('allows legacy app query keys temporarily, but explicit bad headers cannot downgrade', async () => {
    for (const [path, authorization] of [
      ['/app', undefined], ['/app?key=wrong', undefined], ['/app', 'Bearer wrong'],
      ['/app?key=test-only-app-key', 'Bearer wrong'], ['/app?key=test-only-app-key', 'Basic test-only-app-key']
    ]) {
      const response = await mf.dispatchFetch('http://localhost'+path, { headers: { Upgrade: 'websocket', ...(authorization ? { Authorization: authorization } : {}) } });
      expect(response.status).toBe(401); expect(await response.text()).toBe('Unauthorized');
    }
    for (const [path, authorization] of [['/app?key=test-only-app-key', undefined], ['/app?key=wrong', 'Bearer test-only-app-key']]) {
      const response = await mf.dispatchFetch('http://localhost'+path, { headers: { Upgrade: 'websocket', ...(authorization ? { Authorization: authorization } : {}) } });
      expect(response.status).toBe(101); response.webSocket!.accept(); sockets.push(response.webSocket); response.webSocket!.close();
    }
  });
  it('does not emit credentials in captured Worker runtime logs', async () => {
    // Current credentials are sent in headers only; retired-path probes use a noncredential sentinel.
    // Access logs can echo arbitrary caller-supplied URLs, so never put a live token in a URL.
    await rpc('tools/list');
    expect(logLines.join('\n')).not.toContain('test-only-app-key');
    expect(logLines.join('\n')).not.toContain('test-only-mcp-token');
    expect(logLines.join('\n')).not.toContain('Authorization: Bearer');
  });
});

describe('contract timers on the production broker with fake clock', () => {
  function setup() {
    const sent: any[] = []; const socket: AppSocket = { send: raw => sent.push(JSON.parse(raw)), close: vi.fn() };
    const broker = new Broker(); broker.open(socket); broker.receive(socket, hello()); return { broker, socket, sent };
  }
  it('times out at exactly 120 seconds; ignores late result', async () => {
    vi.useFakeTimers();
    try {
      const { broker, socket, sent } = setup(); const pending = broker.call('get_project', {}); const id = sent[0].id;
      let done = false; pending.then(() => { done = true; });
      await vi.advanceTimersByTimeAsync(60_000); broker.receive(socket, '{"type":"pong"}');
      await vi.advanceTimersByTimeAsync(59_999); expect(done).toBe(false);
      await vi.advanceTimersByTimeAsync(1); expect(await pending).toEqual({ isError: true, content: [{ type: 'text', text: 'timed out' }] });
      broker.receive(socket, JSON.stringify({ type: 'result', id, isError: false, content: [] })); broker.close(socket);
    } finally { vi.useRealTimers(); }
  });
  it('keeps idle connections timer-free and expires silence only when a call arrives', async () => {
    vi.useFakeTimers();
    try {
      const { broker, socket, sent } = setup();
      expect(vi.getTimerCount()).toBe(0);
      await vi.advanceTimersByTimeAsync(90_000);
      expect(sent).toEqual([]); expect(socket.close).not.toHaveBeenCalled();
      expect((await broker.call('get_project', {})).isError).toBe(true);
      expect(socket.close).toHaveBeenCalledWith(1000, 'App silent for 90 seconds');
      expect(vi.getTimerCount()).toBe(0);
    } finally { vi.useRealTimers(); }
  });
  it('answers noncanonical JSON ping and renews lazy silence deadline', async () => {
    vi.useFakeTimers();
    try {
      const { broker, socket, sent } = setup();
      await vi.advanceTimersByTimeAsync(29_123); broker.receive(socket, '{ "type": "ping" }');
      expect(sent.at(-1)).toEqual({ type: 'pong' });
      await vi.advanceTimersByTimeAsync(89_999);
      expect(socket.close).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(1); await broker.call('get_project', {});
      expect(socket.close).toHaveBeenCalledWith(1000, 'App silent for 90 seconds');
    } finally { vi.useRealTimers(); }
  });
  it('forwards the edit-phase tools only, never separate-screen or unknown tool definitions', () => {
    const { broker, socket } = setup();
    const edit = { ...tools[0], name: 'remove_objects', annotations: { readOnlyHint: false, destructiveHint: true } };
    const photo = { ...tools[0], name: 'view_attachment' };
    const extra = [
      { ...tools[0], name: 'send_usb' }, { ...tools[0], name: 'save_pes' }, { ...tools[0], name: 'delete_designs' },
      { ...tools[0], name: 'set_settings' }, { ...tools[0], name: 'restore_backup' }, { ...tools[0], name: 'undo' },
      { ...tools[1], annotations: { readOnlyHint: true, needsUserConfirmation: 'onOverwrite' } }
    ];
    broker.receive(socket, JSON.stringify({ type: 'hello', appVersion: 'test', tools: [tools[0], edit, photo, ...extra] }));
    expect(broker.tools).toEqual(RELAY_TOOLS);
    expect(broker.tools.some(t => t.name === 'send_usb')).toBe(false); broker.close(socket);
  });
  it('passes view_attachment JPEG images through unchanged', async () => {
    const { broker, socket, sent } = setup();
    broker.receive(socket, JSON.stringify({ type: 'hello', appVersion: 'test', tools: [RELAY_TOOLS.find(t => t.name === 'view_attachment')] }));
    const pending = broker.call('view_attachment', { attachmentId: 'a1', maxEdgePx: 768 });
    const image = { type: 'image', data: '/9j/4AAQSkZJRgABAQ==', mimeType: 'image/jpeg' };
    broker.receive(socket, JSON.stringify({ type: 'result', id: sent.at(-1).id, isError: false, content: [image] }));
    expect(await pending).toEqual({ isError: false, content: [image] });
    expect(socket.close).not.toHaveBeenCalled(); broker.close(socket);
  });
  it('routes simultaneous calls by UUID even when results arrive in reverse order', async () => {
    const { broker, socket, sent } = setup();
    const first = broker.call('get_project', {}), second = broker.call('render_preview', { widthPx: 256 });
    expect(sent[0].id).not.toBe(sent[1].id);
    for (const index of [1, 0]) broker.receive(socket, JSON.stringify({ type: 'result', id: sent[index].id, isError: false, content: [{ type: 'text', text: String(index) }] }));
    expect((await first).content).toEqual([{ type: 'text', text: '0' }]);
    expect((await second).content).toEqual([{ type: 'text', text: '1' }]); broker.close(socket);
  });
  it('new hello replaces old socket and cannot leak old results into a new call', async () => {
    const { broker, socket, sent } = setup(); const pending = broker.call('get_project', {});
    const newer: AppSocket = { send: vi.fn(), close: vi.fn() }; broker.open(newer); broker.receive(newer, hello());
    expect(socket.close).toHaveBeenCalledWith(1000, 'Replaced by new hello'); expect((await pending).isError).toBe(true);
    broker.receive(socket, JSON.stringify({ type: 'result', id: sent[0].id, isError: false, content: [] }));
    broker.close(newer);
  });
});
