import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { Miniflare } from 'miniflare';
import { Broker, type AppSocket } from '../src/broker';
const auth = { Authorization: 'Bearer v2-test-token' };
const meta = { 'io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientInfo': { name: 'test', version: '1' }, 'io.modelcontextprotocol/clientCapabilities': {} };
const tools = [{ name: 'get_project', description: 'Project', inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: true } }];
const hello = (t = tools) => JSON.stringify({ type: 'hello', appVersion: 'v2-test', tools: t });
function next(socket: any, type: string): Promise<any> { return new Promise(resolve => { const fn = (e: any) => { const m = JSON.parse(e.data); if (m.type === type) { socket.removeEventListener('message', fn); resolve(m); } }; socket.addEventListener('message', fn); }); }
async function event(reader: ReadableStreamDefaultReader<Uint8Array>) {
  let buffer = ''; const decoder = new TextDecoder();
  while (true) { const { value, done } = await reader.read(); if (done) throw new Error('Unexpected SSE close'); buffer += decoder.decode(value); const line = buffer.match(/^data: (.+)$/m); if (line) return JSON.parse(line[1]); }
}

describe.sequential('v2 actual HTTP protocol and notifications', () => {
  let mf: Miniflare; let app: any;
  beforeAll(async () => {
    mf = new Miniflare({ modules: true, scriptPath: 'dist/worker.js', compatibilityDate: '2026-07-30', compatibilityFlags: ['nodejs_compat'], durableObjects: { RELAY: { className: 'Relay', useSQLite: true } }, bindings: { APP_KEY: 'v2-test-app', MCP_TOKEN: 'v2-test-token' } });
    const response = await mf.dispatchFetch('http://relay/app', { headers: { Upgrade: 'websocket', Authorization: 'Bearer v2-test-app' } });
    app = response.webSocket!; app.accept(); app.send(hello()); const ready = next(app, 'pong'); app.send('{"type":"ping"}'); await ready;
  });
  afterAll(async () => { app.close(); await mf.dispose(); });
  const rpc = (method: string, params: any, headers = {}) => mf.dispatchFetch('http://relay/mcp', { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...(params._meta ? { 'MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': method, ...(params.name ? { 'Mcp-Name': params.name } : {}) } : {}), ...headers }, body: JSON.stringify({ jsonrpc: '2.0', id: 7, method, params }) });
  it('proves 2026 modern discovery/list and 2025 fallback without rewriting versions', async () => {
    const unsupported = await rpc('initialize', { protocolVersion: '2026-07-28', capabilities: {}, clientInfo: { name: 'test', version: '1' } }, { 'MCP-Protocol-Version': '2026-07-28' });
    expect(unsupported.status).toBe(400);
    const fallback = await rpc('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'test', version: '1' } }, { 'MCP-Protocol-Version': '2025-11-25' });
    expect(fallback.status).toBe(200); expect((await fallback.json() as any).result.protocolVersion).toBe('2025-11-25');
    const discovery = await rpc('server/discover', { _meta: meta });
    expect(discovery.status, await discovery.clone().text()).toBe(200); const body: any = await discovery.json();
    expect(body.result.supportedVersions).toContain('2026-07-28'); expect(body.result.capabilities.tools.listChanged).toBe(true);
    const listed: any = await (await rpc('tools/list', { _meta: meta })).json(); expect(listed.result.tools).toEqual(tools);
  });
  it('delivers tools/list_changed on a legacy GET stream after changed hello', async () => {
    const response = await mf.dispatchFetch('http://relay/mcp', { headers: { ...auth, Accept: 'text/event-stream', 'MCP-Protocol-Version': '2025-11-25' } });
    expect(response.status, response.status === 200 ? '' : await response.clone().text()).toBe(200); const reader = response.body!.getReader();
    const received = event(reader); app.send(hello([{ ...tools[0], description: 'Changed legacy' }]));
    expect((await received).method).toBe('notifications/tools/list_changed'); await reader.cancel();
  });
  it('delivers tools/list_changed on the official 2026 subscriptions/listen stream', async () => {
    const response = await rpc('subscriptions/listen', { _meta: meta, notifications: { toolsListChanged: true } });
    expect(response.status, response.status === 200 ? '' : await response.clone().text()).toBe(200); expect(response.headers.get('content-type')).toContain('text/event-stream');
    const reader = response.body!.getReader(); await event(reader); // official subscription acknowledgment
    const received = event(reader); app.send(hello([{ ...tools[0], description: 'Changed modern' }]));
    expect((await received).method).toBe('notifications/tools/list_changed'); await reader.cancel();
  });
  it('forwards allowed edit/photo calls unchanged; forbidden file tools stay hidden', async () => {
    const extra = (name: string) => ({ name, description: name, inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: false } });
    const advertised = [tools[0], extra('transform_objects'), extra('remove_objects'), extra('view_attachment'), extra('save_pes'), extra('send_usb'), extra('delete_designs'), extra('set_settings')];
    app.send(hello(advertised)); const ready = next(app, 'pong'); app.send('{"type":"ping"}'); await ready;
    const listed: any = await (await rpc('tools/list', { _meta: meta })).json();
    expect(listed.result.tools.map((t: any) => t.name)).toEqual(['get_project', 'transform_objects', 'remove_objects', 'view_attachment']);
    const args = { objectIds: ['logo'], deltaXMm: 1, deltaYMm: 0, rotationDeg: 0, scaleFactor: 1 };
    const received = next(app, 'call'); const pending = rpc('tools/call', { _meta: meta, name: 'transform_objects', arguments: args });
    const call = await received; expect(call.args).toEqual(args);
    const result = { isError: false, content: [{ type: 'text', text: '{"ok":true,"revision":1,"sewout":{"verdict":"PASS"}}' }] };
    app.send(JSON.stringify({ type: 'result', id: call.id, ...result }));
    const actual: any = await (await pending).json(); expect(actual.result.content).toEqual(result.content);
  });
});

it('notifies only for semantically changed hello definitions', () => {
  const changed = vi.fn(); const broker = new Broker(() => {}, changed); const socket: AppSocket = { send() {}, close() {} }; broker.open(socket);
  broker.receive(socket, hello()); broker.receive(socket, hello()); expect(changed).toHaveBeenCalledTimes(1);
  broker.receive(socket, hello([{ ...tools[0], description: 'new' }])); expect(changed).toHaveBeenCalledTimes(2);
  broker.close(socket);
});
