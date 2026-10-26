import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { Miniflare, Log, LogLevel } from 'miniflare';
import { createHash } from 'node:crypto';
import { RELAY_TOOLS } from '../src/tools';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const origin = 'https://relay.test';
const ownerPin = 'test-only-owner-code';
const fixed = 'test-only-fixed-token';
const redirect = 'https://chatgpt.test/callback';
const verifier = 'a'.repeat(64);
const challenge = createHash('sha256').update(verifier).digest('base64url');
let mf: Miniflare;
const logs: string[] = [];
class CapturedLog extends Log { protected log(message: string) { logs.push(message); } }
const persistence = mkdtempSync(join(tmpdir(), 'myembro-oauth-test-'));
const create = () => new Miniflare({ log: new CapturedLog(LogLevel.INFO), modules: true, scriptPath: 'dist/worker.js', compatibilityDate: '2026-07-30', compatibilityFlags: ['nodejs_compat'],
  durableObjects: { RELAY: { className: 'Relay', useSQLite: true } }, kvNamespaces: ['OAUTH_KV'],
  bindings: { APP_KEY: 'test-only-app-key', MCP_TOKEN: fixed, OWNER_PIN: ownerPin }, durableObjectsPersist: join(persistence, 'do'), kvPersist: join(persistence, 'kv') });
beforeAll(() => { mf = create(); });
afterAll(async () => { await mf.dispose(); rmSync(persistence, { recursive: true, force: true }); });
async function register() {
 const response = await mf.dispatchFetch(origin + '/oauth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
  client_name: 'ChatGPT <script>alert(1)</script>', redirect_uris: [redirect], grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'], token_endpoint_auth_method: 'none',
 }) });
 expect(response.status, await response.clone().text()).toBe(201); return await response.json() as any;
}
async function consent(clientId: string, override = {}) {
 const params = new URLSearchParams({ client_id: clientId, redirect_uri: redirect, response_type: 'code', code_challenge: challenge, code_challenge_method: 'S256', scope: 'mcp offline_access', resource: origin + '/mcp', state: 'test-state', ...override });
 const response = await mf.dispatchFetch(origin + '/authorize?' + params);
 const html = await response.text();
 expect(response.status, html).toBe(200);
 const cookie = response.headers.getSetCookie().map(c => c.split(';')[0]).join('; ');
 const handle = html.match(/name="handle" value="([^"]+)"/)![1];
 return { html, cookie, handle };
}
async function approve(clientId: string, pin = ownerPin) {
 const page = await consent(clientId);
 const response = await mf.dispatchFetch(origin + '/authorize', { method: 'POST', headers: { Origin: origin, Cookie: page.cookie, 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ handle: page.handle, decision: 'approve', pin }).toString(), redirect: 'manual' });
 return { response, page };
}
async function exchange(clientId: string, code: string, codeVerifier = verifier) {
 return mf.dispatchFetch(origin + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({
  grant_type: 'authorization_code', client_id: clientId, redirect_uri: redirect, code, code_verifier: codeVerifier, resource: origin + '/mcp',
 }).toString() });
}
async function rpc(token?: string) {
 return mf.dispatchFetch(origin + '/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} }) });
}
describe.sequential('official OAuth 2.1 provider with owner PIN', () => {
 it('publishes AS/DCR/S256 and RFC9728 metadata, and challenges missing/bad tokens', async () => {
  const metadata: any = await (await mf.dispatchFetch(origin + '/.well-known/oauth-authorization-server')).json();
  expect(metadata.authorization_endpoint).toBe(origin + '/authorize'); expect(metadata.token_endpoint).toBe(origin + '/oauth/token');
  expect(metadata.registration_endpoint).toBe(origin + '/oauth/register'); expect(metadata.code_challenge_methods_supported).toEqual(['S256']);
  for (const path of ['/.well-known/oauth-protected-resource', '/.well-known/oauth-protected-resource/mcp']) {
   const r = await mf.dispatchFetch(origin + path); expect(r.status).toBe(200); const resource: any = await r.json();
   expect(resource.resource).toBe(origin + '/mcp'); expect(resource.authorization_servers).toEqual([origin]);
  }
  for (const token of [undefined, 'wrong-token']) {
   const response = await rpc(token); expect(response.status).toBe(401);
   expect(response.headers.get('WWW-Authenticate')).toContain('resource_metadata="' + origin + '/.well-known/oauth-protected-resource');
   expect(await response.text()).not.toContain('wrong-token');
  }
 });
 it('DCR → browser-bound PIN consent → S256 token → MCP works and uses default lifetimes/refresh', async () => {
  const client = await register();
  const { response, page } = await approve(client.client_id);
  expect(page.html).not.toContain('<script>alert(1)</script>'); expect(page.html).not.toContain(ownerPin);
  expect(response.status, await response.clone().text()).toBe(302);
  const location = new URL(response.headers.get('Location')!); expect(location.origin + location.pathname).toBe(redirect); expect(location.searchParams.get('state')).toBe('test-state');
  const exchanged = await exchange(client.client_id, location.searchParams.get('code')!);
  expect(exchanged.status, await exchanged.clone().text()).toBe(200); const token: any = await exchanged.json();
  expect(token.expires_in).toBe(3600); expect(token.refresh_token).toBeTruthy(); expect((await rpc(token.access_token)).status).toBe(200);
  const wrongAudience = await mf.dispatchFetch('https://other.test/mcp', { headers: { Authorization: 'Bearer ' + token.access_token } });
  expect(wrongAudience.status).toBe(401);
  const listed: any = await (await rpc(token.access_token)).json(); expect(listed.result.tools).toEqual(RELAY_TOOLS);
  const refresh = await mf.dispatchFetch(origin + '/oauth/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'refresh_token', client_id: client.client_id, refresh_token: token.refresh_token, resource: origin + '/mcp' }).toString() });
  expect(refresh.status, await refresh.clone().text()).toBe(200); const renewed: any = await refresh.json(); expect((await rpc(renewed.access_token)).status).toBe(200);
  expect((await exchange(client.client_id, location.searchParams.get('code')!)).status).toBe(400);
  const output = logs.join('\n'); for (const secret of [ownerPin, fixed, token.access_token, token.refresh_token, renewed.access_token]) expect(output).not.toContain(secret);
 });
 it('keeps existing fixed Bearer and app authentication unchanged; internal PIN path stays private', async () => {
  expect((await rpc(fixed)).status).toBe(200);
  expect((await mf.dispatchFetch(origin + '/app', { headers: { Upgrade: 'websocket', Authorization: 'Bearer wrong' } })).status).toBe(401);
  expect((await mf.dispatchFetch(origin + '/_oauth/pin', { method: 'POST', body: JSON.stringify({ pin: ownerPin }) })).status).toBe(404);
  expect((await mf.dispatchFetch(origin + '/mcp/retired-secret')).status).toBe(404);
 });
 it('requires public-client S256 PKCE, exact registered redirects and correct verifier', async () => {
  const client = await register();
  for (const fields of [{ code_challenge: '', code_challenge_method: '' }, { code_challenge_method: 'plain' }, { redirect_uri: 'https://evil.test/callback' }]) {
   const params = new URLSearchParams({ client_id: client.client_id, redirect_uri: redirect, response_type: 'code', code_challenge: challenge, code_challenge_method: 'S256', resource: origin + '/mcp', ...fields });
   const response = await mf.dispatchFetch(origin + '/authorize?' + params); expect(response.status).toBe(400);
  }
  const confidentialResponse = await mf.dispatchFetch(origin + '/oauth/register', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({
   client_name: 'Confidential client', redirect_uris: [redirect], grant_types: ['authorization_code'], response_types: ['code'], token_endpoint_auth_method: 'client_secret_basic',
  }) });
  expect(confidentialResponse.status).toBe(201); const confidential: any = await confidentialResponse.json();
  const withoutPkce = await mf.dispatchFetch(origin + '/authorize?' + new URLSearchParams({ client_id: confidential.client_id, redirect_uri: redirect, response_type: 'code', resource: origin + '/mcp' }));
  expect(withoutPkce.status).toBe(400);
  const { response } = await approve(client.client_id); const code = new URL(response.headers.get('Location')!).searchParams.get('code')!;
  expect((await exchange(client.client_id, code, 'b'.repeat(64))).status).toBe(400);
 });
 it('requires consent cookie and same-origin POST; never grants on a wrong PIN', async () => {
  const client = await register(); const page = await consent(client.client_id);
  const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
  const body = new URLSearchParams({ handle: page.handle, decision: 'approve', pin: ownerPin }).toString();
  expect((await mf.dispatchFetch(origin + '/authorize', { method: 'POST', headers: { ...headers, Cookie: page.cookie, Origin: 'https://evil.test' }, body })).status).toBe(403);
  expect((await mf.dispatchFetch(origin + '/authorize', { method: 'POST', headers: { ...headers, Origin: origin }, body })).status).toBe(400);
  const { response } = await approve(client.client_id, 'wrong-code'); expect(response.status).toBe(401); expect(response.headers.has('Location')).toBe(false); expect(await response.text()).not.toContain('wrong-code');
 });
 it('limits the global owner PIN to 5 failures per 15-minute window using durable state', async () => {
  const client = await register();
  // One failure above; 4 more reach the limit. A different browser/client cannot reset it.
  for (let i = 0; i < 4; i++) { const { response } = await approve(client.client_id, 'bad'); expect(response.status).toBe(i === 3 ? 429 : 401); }
  const other = await register(); const { response } = await approve(other.client_id); expect(response.status).toBe(429); expect(response.headers.get('Retry-After')).toBe('900');
  await mf.dispose(); mf = create();
  const fresh = await register(); expect((await approve(fresh.client_id)).response.status).toBe(429);
 });
});
