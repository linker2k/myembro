# Owner-approved OAuth for ChatGPT and Claude

Implemented on `agent/relay-oauth`, based on main `1acd535`. No deployment, login, real connector registration or Cloudflare resource provisioning was performed. Tests use synthetic credentials and local Miniflare only.

## Provider and protocol

The relay uses the official MIT-licensed `@cloudflare/workers-oauth-provider` **1.2.4**, pinned without a version range in package.json and with npm integrity in package-lock.json. Its license is retained under `relay/LICENSES/`. Cloudflare explicitly supports `OAuthProvider` for a single Worker acting as authorization server and protected resource: [official README](https://github.com/cloudflare/workers-oauth-provider#one-worker-oauthprovider), [authorization reference](https://github.com/cloudflare/workers-oauth-provider/blob/main/docs/authorization-server.md), [safe consent-page helpers](https://github.com/cloudflare/workers-oauth-provider/blob/main/docs/consent-page.md), [Cloudflare MCP authorization guide](https://developers.cloudflare.com/agents/model-context-protocol/protocol/authorization/).

The library owns DCR, authorization codes, S256 PKCE validation, token encryption/storage, audience validation and refresh. The app owns the PIN sign-in/consent page. Both public and confidential clients must present S256 on our authorization page. Tokens are accepted only for the canonical `https://HOST/mcp` resource, with `mcp` scope and the owner's grant. The public Worker validates OAuth, and the DO independently validates the original token with the official authorization-server API. No caller-controlled trusted/bypass header is introduced.

| Endpoint | Function |
|---|---|
| `GET /.well-known/oauth-authorization-server` | Library's RFC8414 authorization server discovery |
| `GET /.well-known/oauth-protected-resource/mcp` | Library's resource-specific RFC9728 metadata |
| `GET /.well-known/oauth-protected-resource` | Alias of the same library-generated metadata, for root discovery |
| `POST /oauth/register` | Library Dynamic Client Registration |
| `GET /authorize` | Validated request → browser-bound one-use consent form |
| `POST /authorize` | Same-origin consent + OWNER_PIN check → library authorization code |
| `POST /oauth/token` | Library code exchange, refresh and advertised revocation endpoint |
| `/mcp` | Existing MCP SDK/dispatcher; OAuth or the existing fixed Bearer |
| `/app` | Existing APP_KEY authentication; unchanged |

Missing/invalid MCP credentials return 401 with `WWW-Authenticate: Bearer ... resource_metadata="..."`. Unauthenticated tool discovery is not enabled. The canonical origin is the incoming public Worker URL; use one stable HTTPS hostname for registration and subsequent calls. Local loopback HTTP remains supported by the library.

## Configuration before a future deployment

- Bind a dedicated KV namespace as **OAUTH_KV**. `wrangler.toml` declares the binding for local development, with its real namespace ID intentionally unfilled. An operator must supply/create the actual binding before production deployment; this task did not do so. Keep this namespace stable: it contains registered clients and grants.
- Add **OWNER_PIN** as a Worker secret using the operator's normal secret-management process. Use a long random code, at most 256 characters. It is entered only into the relay's authorization page, never into ChatGPT/Claude settings, prompts, client-secret fields or URL parameters.
- Retain **APP_KEY** (app WebSocket authentication and the private DO PIN-check boundary) and **MCP_TOKEN** (existing Claude fixed-header access). OAuth consent fails closed if OWNER_PIN or APP_KEY is missing. Fixed Bearer access continues without OAUTH_KV.
- After changing secrets, follow the existing [redeployment operational note](operations.md). This task did not set secrets or deploy.

Access-token TTL, refresh-token TTL and refresh rotation are **not overridden**: provider 1.2.4 defaults are access 3600 seconds and refresh/grant lifetime 30 days. Dynamic client lifetime also remains the provider default (90 days). Client registration must remain present while a connector reuses it; do not clear KV as a login troubleshooting shortcut. A PIN change gates future approvals; existing tokens follow normal revocation/expiry rather than being silently revoked by the PIN change.

## PIN approval and failure policy

The GET page shows the requesting client, its redirect hostname and scopes, escapes all client-controlled text, and uses the library's `beginConsent` headers/cookie. POST requires the same Origin. `approveConsent` checks the browser binding and consumes its one-use handle before a PIN attempt. Only a correct PIN permits `completeAuthorization`. Deny uses the library's `denyConsent` flow. Wrong PIN consumes that form; restart the connection to get a new consent page.

PIN comparison uses the existing native constant-time comparison of fixed-size SHA-256 digests. PINs are never persisted or logged. The private DO endpoint is not publicly routed and also requires APP_KEY. Only failure counters and a deadline are stored in a DO transaction: **5 wrong attempts in a fixed 15-minute window**, globally for this single owner. The fifth failure and subsequent attempts return 429 with Retry-After 900. A new browser/client or runtime restart cannot clear it. A correct PIN is also blocked until the window expires. This deliberately trades temporary owner lockout for a bounded online guessing rate. No interval or alarm is scheduled for this policy, preserving idle hibernation.

No CIMD metadata fetching is enabled in this patch: select DCR/automatic registration in the connector. The library may emit its generic CIMD-disabled compatibility warning in local tests; this contains no credential. The package's default token-error diagnostics likewise contain error categories/descriptions, not the supplied PIN/token.

## Connector values

Replace `https://HOST` below with the deployment's actual public origin. These are future setup values; no connector was added in this task.

| Field | ChatGPT OAuth | Claude OAuth |
|---|---|---|
| Name | `myembro` | `myembro` |
| Server / remote MCP URL | `https://HOST/mcp` | `https://HOST/mcp` |
| Authentication | OAuth | Sign in now (or when needed) |
| OAuth client registration | **Dynamic Client Registration / DCR** | **Register automatically** |
| OAuth client ID / secret | Leave unset for DCR; generated by the provider | Leave unset for automatic registration |
| Authorization URL, if requested explicitly | `https://HOST/authorize` | Same |
| Token URL, if requested explicitly | `https://HOST/oauth/token` | Same |
| Registration URL, if requested explicitly | `https://HOST/oauth/register` | Same |
| Scopes | `mcp offline_access` (offline_access enables refresh) | Same |
| Resource / audience | `https://HOST/mcp` | Same |
| Redirect URI | Host-provided callback, registered by DCR; do not invent one | Host-provided callback, registered by DCR |
| Sign-in credential | Enter OWNER_PIN on **the relay page only** | Same |
| Fixed Authorization request header | None for OAuth | None for OAuth |

ChatGPT supports DCR when the registration endpoint is advertised; use that choice rather than CIMD/published identity for this implementation. OpenAI's current [MCP authentication reference](https://developers.openai.com/plugins/build/auth) documents discovery, S256, resource and DCR, and its [custom MCP setup](https://developers.openai.com/api/docs/guides/custom-mcp-server) documents OAuth registration. Claude's official [custom connector instructions](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp) offer “Register automatically” and separate fixed request headers. UI labels can vary by account/version; these endpoints and DCR contract are the values to use.

**Existing Claude fixed Bearer:** keep the same `https://HOST/mcp` URL and `Authorization: Bearer <MCP_TOKEN>` in Request headers; do not replace that token with OWNER_PIN. This path bypasses OAuth by design and is regression-tested. To test OAuth instead, use a connector without that fixed header, since a correct fixed token is already authenticated.

## Automated evidence

```sh
cd relay
npm run typecheck
npm test
```

Final result: TypeScript passes; **52/52 tests pass in 6 files**, including all existing 46 tests plus 6 OAuth tests. The OAuth tests run actual DCR, consent cookie/handle, correct/wrong PIN, code exchange, default expires_in=3600, refresh, MCP tools/list, bad token challenge, audience mismatch, code reuse, wrong verifier, missing/plain PKCE, redirect mismatch, CSRF/browser-cookie rejection, metadata alias, fixed Bearer and PIN rate limiting across a full local runtime restart. Captured logs are asserted not to contain the synthetic PIN/fixed/access/refresh tokens.

`wrangler dev --local` also started successfully with OAUTH_KV bound, then was stopped; no account login or deployment took place. This verifies local protocol implementation, **not a live ChatGPT or Claude sign-in**. Production reachability, operator bindings and host-side connector registration remain to be tested by the operator after an authorized deployment.
