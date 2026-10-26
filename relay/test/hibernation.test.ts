import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { build } from 'esbuild';
import { Miniflare } from 'miniflare';
import { Broker, UPDATE_REQUIRED, type AppSocket, type ConnectionAttachment } from '../src/broker';
import { RELAY_TOOLS } from '../src/tools';
const hello = JSON.stringify({ type: 'hello', appVersion: 'test', tools: RELAY_TOOLS });
let mf: Miniflare;
const sockets: any[] = [];
beforeAll(async () => {
  const bundled = await build({ entryPoints: ['test/hibernation.fixture.ts'], bundle: true, write: false, format: 'esm', platform: 'browser', conditions: ['workerd'], external: ['cloudflare:workers','node:*'] });
  mf = new Miniflare({ modules: true, script: bundled.outputFiles[0].text, compatibilityDate: '2026-07-30', compatibilityFlags: ['nodejs_compat'],
    durableObjects: { RELAY: { className: 'ReconstructedRelay', useSQLite: true } }, bindings: { APP_KEY: 'test-app', MCP_TOKEN: 'test-mcp' } });
});
afterAll(async () => { for (const s of sockets) { try { s.close(); } catch {} } await mf.dispose(); });
function next(socket: any, type: string): Promise<any> { return new Promise(resolve => {
  const fn = (e: any) => { const m = JSON.parse(e.data); if (m.type === type) { socket.removeEventListener('message', fn); resolve(m); } }; socket.addEventListener('message', fn);
}); }
async function rpc() { return mf.dispatchFetch('http://local/mcp', { method: 'POST', headers: { Authorization: 'Bearer test-mcp', 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'get_project', arguments: {} } }) }); }
async function app() {
  const res = await mf.dispatchFetch('http://local/app', { headers: { Upgrade: 'websocket', Authorization: 'Bearer test-app' } });
  const s = res.webSocket!; s.accept(); sockets.push(s); s.send(hello);
  const pong = next(s, 'pong'); s.send('{ "type": "ping" }'); await pong; return s;
}
it('runtime auto-response sends exact JSON pong without invoking the message handler', async () => {
  const s = await app();
  const before: any = await (await mf.dispatchFetch('http://local/__test/status')).json();
  const pong = next(s, 'pong'); s.send('{"type":"ping"}'); expect(await pong).toEqual({ type: 'pong' });
  const after: any = await (await mf.dispatchFetch('http://local/__test/status')).json();
  expect(after.messages).toBe(before.messages); expect(after.auto.some((t: any) => typeof t === 'number')).toBe(true);
});
it('reconstructs a broker from real accepted sockets/attachments and resumes calls without another hello', async () => {
  const s = await app();
  expect(await (await mf.dispatchFetch('http://local/__test/reconstruct')).text()).toBe('reconstructed');
  const received = next(s, 'call'); const pending = rpc(); const call = await received;
  s.send(JSON.stringify({ type: 'result', id: call.id, isError: false, content: [{ type: 'text', text: 'resumed' }] }));
  expect((await (await pending).json() as any).result.content).toEqual([{ type: 'text', text: 'resumed' }]);
  const status: any = await (await mf.dispatchFetch('http://local/__test/status')).json();
  expect(status.attachments.filter((a: any) => a.ready)).toHaveLength(1);
  expect(new TextEncoder().encode(JSON.stringify(status.attachments.find((a: any) => a.ready))).length).toBeLessThan(2048);
});
it('rejects binary messages through the hibernation callback', async () => {
  const s = await app(); const closed = new Promise<any>(resolve => s.addEventListener('close', resolve, { once: true }));
  s.send(new Uint8Array([1, 2, 3]).buffer); expect((await closed).code).toBe(1008);
});
it('auto-response timestamps keep a restored connection fresh and only pending calls create timers', async () => {
  vi.useFakeTimers();
  try {
    let attachment: ConnectionAttachment | undefined; let ping = 0; const sent: any[] = [];
    const socket: AppSocket = { send(raw) { sent.push(JSON.parse(raw)); }, close: vi.fn() };
    const original = new Broker((_, a) => { attachment = structuredClone(a); }); original.open(socket); original.receive(socket, hello);
    expect(vi.getTimerCount()).toBe(0); await vi.advanceTimersByTimeAsync(100_000); ping = Date.now();
    const restored = new Broker(() => {}, () => ping); restored.open(socket, attachment);
    const call = restored.call('get_project', {}); expect(sent).toHaveLength(1); expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(119_999); let done = false; call.then(() => { done = true; }); await Promise.resolve(); expect(done).toBe(false);
    await vi.advanceTimersByTimeAsync(1); expect((await call).content).toEqual([{ type: 'text', text: 'timed out' }]);
    expect(vi.getTimerCount()).toBe(0); restored.close(socket);
  } finally { vi.useRealTimers(); }
});
it('clears the pending timer on success and refuses a stale catalog after reconstruction', async () => {
  vi.useFakeTimers();
  try {
    let saved: ConnectionAttachment | undefined; const sent: any[] = [];
    const socket: AppSocket = { send(raw) { sent.push(JSON.parse(raw)); }, close() {} };
    const broker = new Broker((_, a) => { saved = structuredClone(a); }); broker.open(socket); broker.receive(socket, hello);
    const result = broker.call('get_project', {}); expect(vi.getTimerCount()).toBe(1);
    broker.receive(socket, JSON.stringify({ type: 'result', id: sent[0].id, isError: false, content: [] })); await result;
    expect(vi.getTimerCount()).toBe(0);
    const restored = new Broker(); restored.open(socket, { ...saved!, catalog: 'old-deployment' });
    expect((await restored.call('get_project', {})).content).toEqual([{ type: 'text', text: UPDATE_REQUIRED }]);
    expect(sent).toHaveLength(1); restored.close(socket);
  } finally { vi.useRealTimers(); }
});

it('does not leave an optional legacy MCP GET/SSE subscription open', async () => {
  const response = await mf.dispatchFetch('http://local/mcp', { headers: { Authorization: 'Bearer test-mcp', Accept: 'text/event-stream' } });
  expect(response.status).toBe(405); expect(response.headers.get('Allow')).toBe('POST');
  expect(await response.text()).toBe('Method not allowed');
});

async function identityRpc(method: string, params: any, session?: string) {
  return mf.dispatchFetch('http://local/mcp', { method: 'POST', headers: {
    Authorization: 'Bearer test-mcp', 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream',
    ...(session ? { 'Mcp-Session-Id': session } : {}),
    ...(params._meta ? { 'MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': method, ...(params.name ? { 'Mcp-Name': params.name } : {}) } : {}),
  }, body: JSON.stringify({ jsonrpc: '2.0', id: 10, method, params }) });
}
async function initializeIdentity(name: string, session?: string) {
  const response = await identityRpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name, version: 'test' } }, session);
  const body: any = await response.json(); expect(body.error).toBeUndefined(); return body;
}
async function calledIdentity(socket: any, session?: string, meta?: any) {
  const received = next(socket, 'call');
  const response = identityRpc('tools/call', { name: 'get_project', arguments: {}, ...(meta ? { _meta: meta } : {}) }, session);
  const call = await received;
  socket.send(JSON.stringify({ type: 'result', id: call.id, isError: false, content: [] }));
  expect((await (await response).json() as any).error).toBeUndefined(); return call.client;
}
it('persists isolated session identities and last-name fallback across constructor reconstruction', async () => {
  const s = await app();
  await initializeIdentity(' Claude\nDesktop\u202e  ', 'session-a');
  await initializeIdentity('Codex', 'session-b');
  await mf.dispatchFetch('http://local/__test/reconstruct');
  expect(await calledIdentity(s, 'session-a')).toBe('Claude Desktop');
  expect(await calledIdentity(s, 'session-b')).toBe('Codex');
  expect(await calledIdentity(s)).toBe('Codex');
  expect(await calledIdentity(s, 'unknown-session')).toBeUndefined();
});
it('persists modern per-request clientInfo and keeps explicit identity ahead of stored fallback', async () => {
  const s = await app(); await initializeIdentity('Old name', 'session-modern');
  expect(await calledIdentity(s, 'session-modern', {
    'io.modelcontextprotocol/protocolVersion': '2026-07-28',
    'io.modelcontextprotocol/clientInfo': { name: ' Gemini\tMobile\u0085\u2028 ', version: '1' },
    'io.modelcontextprotocol/clientCapabilities': {},
  })).toBe('Gemini Mobile');
  await mf.dispatchFetch('http://local/__test/reconstruct');
  expect(await calledIdentity(s, 'session-modern')).toBe('Gemini Mobile');
  expect(await calledIdentity(s)).toBe('Gemini Mobile');
});
it('rejects failed initialize identity updates and preserves the previously saved name', async () => {
  const s = await app(); await initializeIdentity('Known good', 'valid-name');
  const response = await identityRpc('initialize', { clientInfo: { name: 'Poisoned' } }, 'valid-name');
  expect((await response.json() as any).error).toBeDefined();
  await mf.dispatchFetch('http://local/__test/reconstruct');
  expect(await calledIdentity(s, 'valid-name')).toBe('Known good');
});
