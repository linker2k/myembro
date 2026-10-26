import { OAuthProvider, OAuthAuthorizationServer, AuthorizationError, type OAuthHelpers } from '@cloudflare/workers-oauth-provider';
import { secretMatches, type RelaySecrets } from './auth';

export interface OAuthEnv extends RelaySecrets {
  OWNER_PIN?: string;
  OAUTH_KV?: KVNamespace;
  OAUTH_PROVIDER?: OAuthHelpers;
  RELAY: DurableObjectNamespace;
}
const SCOPES = ['mcp', 'offline_access'];
const escape = (value: string) => value.replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const noStore = { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' };
export function oauthChallenge(request: Request): Response {
  const metadata = new URL('/.well-known/oauth-protected-resource', request.url).href;
  return new Response('Unauthorized', { status: 401, headers: { ...noStore, 'WWW-Authenticate': `Bearer resource_metadata="${metadata}"` } });
}
function page(handle: string, name: string, redirect: string, scopes: string[]): string {
  return `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>myembro 연결 승인</title>
  <h1>myembro 연결 승인</h1><p><strong>${escape(name)}</strong>에서 자수 프로젝트를 조회하고 편집하도록 허용합니다.</p>
  <p>클라이언트 이름은 검증되지 않았습니다. 돌아갈 주소: ${escape(redirect)}</p><p>권한: ${escape(scopes.join(', '))}</p>
  <form method="post" action="/authorize"><input type="hidden" name="handle" value="${escape(handle)}">
  <label>소유자 비밀 코드 <input name="pin" type="password" autocomplete="off" required maxlength="256"></label>
  <button name="decision" value="approve">승인</button><button name="decision" value="deny" formnovalidate>취소</button></form></html>`;
}
async function authorize(request: Request, env: OAuthEnv): Promise<Response> {
  if (!env.OWNER_PIN || !env.APP_KEY) return new Response('Authorization is not configured', { status: 503, headers: noStore });
  const oauth = env.OAUTH_PROVIDER!;
  try {
    if (request.method === 'GET') {
      const auth = await oauth.parseAuthRequest(request);
      if (!auth.codeChallenge || auth.codeChallengeMethod !== 'S256')
        return new Response('S256 PKCE is required', { status: 400, headers: noStore });
      // All exposed tools share one owner permission; show the exact grant on the consent page.
      if (!auth.scope.includes('mcp')) auth.scope.push('mcp');
      const details = await oauth.describeConsent(auth);
      const consent = await oauth.beginConsent(auth);
      consent.headers.set('Content-Type', 'text/html; charset=utf-8');
      consent.headers.set('Referrer-Policy', 'no-referrer');
      return new Response(page(consent.handle, details.clientName, details.redirectHost, auth.scope), { headers: consent.headers });
    }
    if (request.method !== 'POST') return new Response('Method not allowed', { status: 405 });
    if (request.headers.get('Origin') !== new URL(request.url).origin) return new Response('Forbidden', { status: 403, headers: noStore });
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > 4096) return new Response('Form too large', { status: 413, headers: noStore });
    const form = new URLSearchParams(raw); const handle = form.get('handle') ?? '';
    if (form.get('decision') === 'deny') {
      const denied = await oauth.denyConsent(request, handle);
      return new Response(null, { status: 302, headers: denied.headers });
    }
    if (form.get('decision') !== 'approve') return new Response('Invalid decision', { status: 400, headers: noStore });
    // Validates the browser-bound one-use handle before any PIN attempt. Wrong PIN requires a new consent page.
    const approved = await oauth.approveConsent(request, handle);
    const pin = form.get('pin') ?? '';
    const check = await env.RELAY.get(env.RELAY.idFromName('single-relay')).fetch(new Request(new URL('/_oauth/pin', request.url), {
      method: 'POST', headers: { Authorization: `Bearer ${env.APP_KEY}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ pin }),
    }));
    if (!check.ok) return new Response(check.status === 429 ? 'Too many attempts. Try again later.' : 'Invalid code. Start authorization again.', {
      status: check.status, headers: { ...noStore, ...(check.status === 429 ? { 'Retry-After': '900' } : {}) },
    });
    const { redirectTo } = await oauth.completeAuthorization({ request: approved.request, userId: 'owner', metadata: {}, scope: approved.request.scope, props: { userId: 'owner' } });
    approved.headers.set('Location', redirectTo); approved.headers.set('Referrer-Policy', 'no-referrer');
    return new Response(null, { status: 302, headers: approved.headers });
  } catch (error) {
    if (error instanceof AuthorizationError) return new Response('Invalid or expired authorization request', { status: 400, headers: noStore });
    throw error;
  }
}

/** Official token validation is repeated at the DO boundary; never trust a caller-controlled bypass header. */
export async function oauthTokenAccepted(request: Request, env: OAuthEnv): Promise<boolean> {
  if (!env.OAUTH_KV) return false;
  const bearer = request.headers.get('Authorization')?.match(/^Bearer ([^\s,]+)$/i)?.[1];
  if (!bearer) return false;
  const origin = new URL(request.url).origin;
  const server = new OAuthAuthorizationServer<OAuthEnv>({ issuer: origin, resources: [origin + '/mcp'], scopesSupported: SCOPES });
  const token = await server.validateToken(origin + '/mcp', bearer, env);
  return !!token && token.scope.includes('mcp') && token.props?.userId === 'owner';
}

export async function oauthFetch(request: Request, env: OAuthEnv, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  if (!env.OAUTH_KV) return url.pathname === '/mcp' ? oauthChallenge(request) : new Response('OAuth is not configured', { status: 503, headers: noStore });
  const provider = new OAuthProvider<OAuthEnv>({
    apiRoute: '/mcp', authorizeEndpoint: '/authorize', tokenEndpoint: '/oauth/token', clientRegistrationEndpoint: '/oauth/register',
    scopesSupported: SCOPES, requiredScopes: ['mcp'],
    resourceMetadata: { resource: url.origin + '/mcp', authorization_servers: [url.origin], resource_name: 'myembro' },
    apiHandler: { async fetch(req, bindings, context) {
      const auth = context as ExecutionContext & { auth: { scope: string[] }; props: { userId?: string } };
      if (!auth.auth.scope.includes('mcp') || auth.props.userId !== 'owner') return new Response('Forbidden', { status: 403, headers: noStore });
      return bindings.RELAY.get(bindings.RELAY.idFromName('single-relay')).fetch(req);
    } },
    defaultHandler: { fetch(req, bindings) {
      return new URL(req.url).pathname === '/authorize' ? authorize(req, bindings) : Promise.resolve(new Response('Not found', { status: 404 }));
    } },
  });
  // The provider publishes the resource-specific RFC9728 location; expose the requested root alias too.
  const metadataRequest = url.pathname === '/.well-known/oauth-protected-resource'
    ? new Request(new URL('/.well-known/oauth-protected-resource/mcp', url), request) : request;
  return provider.fetch(metadataRequest, { ...env, OAUTH_PROVIDER: undefined }, ctx);
}

/** Global owner gate: 5 wrong attempts in a fixed 15-minute window, persisted and serialized by DO storage. */
export async function checkOwnerPin(request: Request, env: OAuthEnv, storage: DurableObjectStorage): Promise<Response> {
  const bearer = request.headers.get('Authorization')?.match(/^Bearer ([^\s,]+)$/i)?.[1];
  if (request.method !== 'POST' || !await secretMatches(bearer ?? '', env.APP_KEY)) return new Response('Unauthorized', { status: 401 });
  const body: any = await request.json().catch(() => undefined);
  if (typeof body?.pin !== 'string' || body.pin.length > 256) return new Response('Invalid code', { status: 401 });
  return storage.transaction(async tx => {
    const now = Date.now();
    let attempts = await tx.get<{ failures: number; until: number }>('oauth:pin-attempts');
    if (!attempts || attempts.until <= now) attempts = { failures: 0, until: now + 900_000 };
    if (attempts.failures >= 5) return new Response('Too many attempts', { status: 429 });
    if (await secretMatches(body.pin, env.OWNER_PIN)) return new Response('Approved');
    attempts.failures++;
    await tx.put('oauth:pin-attempts', attempts);
    return new Response('Invalid code', { status: attempts.failures >= 5 ? 429 : 401 });
  });
}
