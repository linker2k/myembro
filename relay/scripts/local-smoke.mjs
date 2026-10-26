// Local-only curl smoke against an already running `npm run dev`. Never prints secrets.
import { readFileSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import assert from 'node:assert/strict';
const exec = promisify(execFile);
const vars = Object.fromEntries(readFileSync(new URL('../.dev.vars', import.meta.url), 'utf8').trim().split('\n').map(line => {
  const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)];
}));
assert(vars.APP_KEY && vars.MCP_SECRET, 'Local .dev.vars must define both keys');
const tools = [
  { name: 'get_project', description: 'Read project', inputSchema: { type: 'object', properties: {}, required: [], additionalProperties: false }, annotations: { readOnlyHint: true } },
  { name: 'render_preview', description: 'Render hoop', inputSchema: { type: 'object', properties: { widthPx: { type: 'integer', minimum: 256, maximum: 1024 } }, required: ['widthPx'], additionalProperties: false }, annotations: { readOnlyHint: true } }
];
const app = new WebSocket(`ws://127.0.0.1:8787/app?key=${encodeURIComponent(vars.APP_KEY)}`);
await new Promise((resolve, reject) => { app.addEventListener('open', resolve, { once: true }); app.addEventListener('error', reject, { once: true }); });
const ready = new Promise(resolve => app.addEventListener('message', e => { const m = JSON.parse(e.data); if (m.type === 'pong') resolve(); if (m.type === 'ping') app.send('{"type":"pong"}'); }));
app.send(JSON.stringify({ type: 'hello', appVersion: 'curl-local-smoke', tools }));
app.send('{"type":"ping"}');
await ready;
try {
  for (const [method, params] of [
    ['initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'curl', version: '1' } }], ['tools/list', {}]
  ]) {
    const { stdout } = await exec('curl', ['--silent', '--show-error', '--fail-with-body', '--max-time', '10',
      '-H', 'Content-Type: application/json', '-H', 'Accept: application/json, text/event-stream',
      '--data', JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }), `http://127.0.0.1:8787/mcp/${vars.MCP_SECRET}`]);
    const response = JSON.parse(stdout);
    assert.equal(response.error, undefined);
    if (method === 'initialize') assert.equal(response.result.serverInfo.name, 'myembro-relay');
    else assert.deepEqual(response.result.tools, tools);
    console.log(`CURL ${method} HTTP 200 ${JSON.stringify(response)}`);
  }
} finally { app.close(); }
