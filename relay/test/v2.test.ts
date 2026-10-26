import { RELAY_TOOLS } from '../src/tools';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { Miniflare } from 'miniflare';
import { Broker, type AppSocket } from '../src/broker';
const auth = { Authorization: 'Bearer v2-test-token' };
const meta = { 'io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientInfo': { name: 'test', version: '1' }, 'io.modelcontextprotocol/clientCapabilities': {} };
const tools = [structuredClone(RELAY_TOOLS.find(t => t.name === 'get_project')!)];
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
    expect(body.result.supportedVersions).toContain('2026-07-28'); expect(body.result.capabilities.tools.listChanged).toBe(false);
    const listed: any = await (await rpc('tools/list', { _meta: meta })).json(); expect(listed.result.tools).toEqual(RELAY_TOOLS);
  });
  it('legacy tools/list stays fixed after a schema/description-changing hello', async () => {
    app.send(hello([{ ...tools[0], description: 'Changed legacy' }]));
    const ready = next(app, 'pong'); app.send('{"type":"ping"}'); await ready;
    const body: any = await (await rpc('tools/list', {})).json(); expect(body.result.tools).toEqual(RELAY_TOOLS);
  });
  it('modern tools/list stays fixed after hello removes all app tools', async () => {
    app.send(hello([])); const ready = next(app, 'pong'); app.send('{"type":"ping"}'); await ready;
    const body: any = await (await rpc('tools/list', { _meta: meta })).json(); expect(body.result.tools).toEqual(RELAY_TOOLS);
  });
  it('forwards allowed edit/photo calls unchanged; forbidden file tools stay hidden', async () => {
    const extra = (name: string) => structuredClone(RELAY_TOOLS.find(t => t.name === name) ?? { name, description: name, inputSchema: { type: 'object', properties: {} }, annotations: { readOnlyHint: false } });
    const advertised = [tools[0], extra('transform_objects'), extra('remove_objects'), extra('view_attachment'), extra('save_pes'), extra('send_usb'), extra('delete_designs'), extra('set_settings')];
    app.send(hello(advertised)); const ready = next(app, 'pong'); app.send('{"type":"ping"}'); await ready;
    const listed: any = await (await rpc('tools/list', { _meta: meta })).json();
    expect(listed.result.tools).toEqual(RELAY_TOOLS);
    const args = { objectIds: ['logo'], deltaXMm: 1, deltaYMm: 0, rotationDeg: 0, scaleFactor: 1 };
    const received = next(app, 'call'); const pending = rpc('tools/call', { _meta: meta, name: 'transform_objects', arguments: args });
    const call = await received; expect(call.args).toEqual(args);
    const result = { isError: false, content: [{ type: 'text', text: '{"ok":true,"revision":1,"sewout":{"verdict":"PASS"}}' }] };
    app.send(JSON.stringify({ type: 'result', id: call.id, ...result }));
    const actual: any = await (await pending).json(); expect(actual.result.content).toEqual(result.content);
  });
});

it('hello description changes never change the deployment catalog', () => {
  const broker = new Broker(); const socket: AppSocket = { send() {}, close() {} }; broker.open(socket);
  broker.receive(socket, hello()); const before = structuredClone(broker.tools);
  broker.receive(socket, hello([{ ...tools[0], description: 'new' }]));
  expect(broker.tools).toEqual(before); broker.close(socket);
});
