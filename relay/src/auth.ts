export interface RelaySecrets { APP_KEY?: string; MCP_TOKEN?: string }

/** Fixed-size digests avoid a length-mismatch exception and prefix comparisons.
 * https://developers.cloudflare.com/workers/examples/protect-against-timing-attacks/ */
export async function secretMatches(presented: string, expected: string | undefined): Promise<boolean> {
  if (!expected) return false; // Missing configuration always fails closed.
  const encode = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest('SHA-256', encode.encode(presented)),
    crypto.subtle.digest('SHA-256', encode.encode(expected))
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

/** Shared by the public Worker and DO. Never echo or log a URL, header or credential. */
export async function authorizationFailure(request: Request, env: RelaySecrets): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== '/mcp' && url.pathname !== '/app') return new Response('Not found', { status: 404 });
  const header = request.headers.get('Authorization');
  const bearer = header?.match(/^Bearer ([^\s,]+)$/i)?.[1];
  let accepted: boolean;
  if (url.pathname === '/mcp') {
    accepted = await secretMatches(bearer ?? '', env.MCP_TOKEN);
    // A malformed/absent scheme must not authenticate even against an odd configured token.
    accepted = bearer !== undefined && accepted;
  } else {
    // Prefer explicit header authentication. A wrong header cannot downgrade to a correct query key.
    const legacy = url.searchParams.getAll('key');
    const supplied = header !== null ? bearer : legacy.length === 1 ? legacy[0] : undefined;
    accepted = await secretMatches(supplied ?? '', env.APP_KEY);
    accepted = supplied !== undefined && accepted;
  }
  return accepted ? null : new Response('Unauthorized', { status: 401, headers: { 'WWW-Authenticate': 'Bearer' } });
}
