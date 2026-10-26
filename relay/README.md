# myembro relay spike

Implements [manager contract v1](docs/relay-contract-v1.md), including header authentication. One Worker routes
both authenticated endpoints to **one Durable Object**, named `single-relay`.
No cloud account, login, deployment or Android changes are part of this commit.

MCP initialization, request validation, JSON-RPC errors and Streamable HTTP are
handled by the official **@modelcontextprotocol/sdk 1.26.0** `Server` and
`WebStandardStreamableHTTPServerTransport`, not a relay-specific MCP dialect.
A fresh stateless SDK server/transport serves each request with JSON responses.
The Durable Object owns the app WebSocket, in-flight calls and persisted tool cache.
No MCP sessions or SSE resumability are promised by this spike.

Official references:

- [SDK Web Standard transport, pinned v1.26.0](https://github.com/modelcontextprotocol/typescript-sdk/blob/v1.26.0/src/server/webStandardStreamableHttp.ts)
- [Cloudflare Durable Object WebSocket example](https://developers.cloudflare.com/durable-objects/examples/websocket-server/)

## Local reproduction

Requires Node 24 and npm. Versions are exact in package.json and transitive
resolution/integrity is committed in package-lock.json.

```sh
cd relay
npm ci
npm run typecheck
npm test
node scripts/local-secrets.mjs
WRANGLER_SEND_METRICS=false npm run dev
# In another terminal, from relay/:
node scripts/local-smoke.mjs
```

`local-secrets.mjs` creates only `.dev.vars` with random local keys and refuses
to overwrite it. For an existing v0 config, replace MCP_SECRET with a fresh
MCP_TOKEN; the old variable is no longer read. `.dev.vars`, `.env`, node_modules, dist and local Worker state
are ignored. Real APP_KEY/MCP_TOKEN must be provided as Worker secrets outside
source control; wrangler.toml contains no values. No login or deployment command
is needed or executed. Local dev binds only 127.0.0.1:8787. An Android emulator
can reach this host through 10.0.2.2:8787 per the contract.

`local-smoke.mjs` connects a fake app, sends hello, then invokes **curl** for MCP
initialize/tools/list. It prints responses but never keys or secret URLs. It
closes its socket after validation. The smoke sends the curl Authorization
header through stdin, so it is absent from process arguments and command-error
messages. Optional MYEMBRO_RELAY_VARS selects a separate local test vars file. Use the HTTPS/WSS endpoints from docs/relay-contract-v1.md
when a deployment is separately authorized; this unit does not test Claude mobile.

## Routing and limits

- `/app` prefers `Authorization: Bearer <APP_KEY>` and requires WebSocket upgrade.
  `/app?key=<APP_KEY>` is temporarily accepted only when Authorization is absent;
  this legacy query form is deprecated and will be removed after the app migrates.
  Wrong/malformed headers never downgrade to query authentication. A valid hello
  selects the active app; a new hello closes the previous socket and fails its
  pending requests. Definitions retain their original inputSchema/annotations.
- Only the edit-phase tools in `RELAY_TOOLS` (src/broker.ts) are retained:
  project reads, Ask AI edits, photo tools; never save, share, USB, file
  deletion, restore or settings. A confirmation annotation other than false
  excludes the definition. Other tool names are not exposed.
  render_preview accepts exactly one integer widthPx in 256–1024; get_project
  accepts no arguments. The app remains responsible for rendering the current
  project including its hoop.
- `/mcp` accepts only `Authorization: Bearer <MCP_TOKEN>`. Configure the URL with
  no secret suffix in the claude.ai connector and add its request header there.
  Missing/incorrect credentials return 401 with the fixed body `Unauthorized`.
  Old `/mcp/<x>` paths return 404 even with a correct header; MCP query keys do
  not authenticate. Worker and Durable Object both check the same rules.
- Credential comparison hashes both UTF-8 values with SHA-256, then compares the
  fixed 32-byte buffers with Cloudflare's native `crypto.subtle.timingSafeEqual`.
  There is no prefix string comparison or unequal-length comparison exception.
  [Cloudflare timing-safe guidance](https://developers.cloudflare.com/workers/examples/protect-against-timing-attacks/).
  Missing/empty configured secrets fail closed.
- UUIDs route concurrent calls to their own results, even out of order. Text,
  application isError and inline image/png base64 content pass through unchanged.
  Late results and results from a replaced socket cannot complete a new call.
- Disconnection returns the exact contract error. Last hello definitions persist
  in Durable Object storage across both app disconnect and object restart.
- Broker timers ping every 30 seconds, drop a silent app at 90 seconds, and resolve
  each call after 60 seconds with isError and text `timed out`. A ping gets pong;
  incoming valid messages renew liveness. No pending call is retried automatically.

The regular WebSocket API keeps the object active while the app is connected.
Hibernation/cost optimization, OAuth, multi-user routing, write consent and mobile
end-to-end testing remain outside this single-app trial contract. Credentials
belong in request headers; do not log their values. The legacy app query path can
still expose APP_KEY in access logs until the app migrates. Source code does not
log requests or secrets; captured Worker runtime/access logs are tested for header-credential non-disclosure.
Arbitrary caller-supplied URLs may still appear in platform access logs: clients
must stop putting credentials in URLs, including obsolete MCP path forms.

## Measured verification

`npm run typecheck`: exit 0.
`npm test`: **2 test files, 16 tests passed** (Miniflare + Vitest).

Eight integration tests use the actual bundled Worker, Durable Object storage,
WebSocket and official SDK HTTP transport: disconnected error; hello/list identity;
call forwarding/text/error/image preservation; cached tools after app disconnect
and runtime restart plus forbidden/invalid calls; MCP header success/failure/missing,
old-path/query rejection, app header/query migration, and credential-free runtime logs.
Two auth unit tests assert native comparison uses fixed-size digests for different
lengths, plus fail-closed configuration. Six broker tests
cover exact 60-second timeout using fake timers, 30/90-second liveness including deadline renewal between ping ticks, excluded
write/confirmation tools, concurrent UUID routing and replacement/late-result
isolation. Fake timers target the production broker directly, not workerd's clock.

Current header-auth local smoke (Miniflare HTTP + app WebSocket + real curl,
using isolated temporary vars rather than changing the existing .dev.vars):

```text
CURL initialize HTTP 200 {"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"myembro-relay","version":"0.1.0"}},"jsonrpc":"2.0","id":1}
CURL tools/list HTTP 200 {"result":{"tools":[{"name":"get_project","description":"Read project","inputSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false},"annotations":{"readOnlyHint":true}},{"name":"render_preview","description":"Render hoop","inputSchema":{"type":"object","properties":{"widthPx":{"type":"integer","minimum":256,"maximum":1024}},"required":["widthPx"],"additionalProperties":false},"annotations":{"readOnlyHint":true}}]},"jsonrpc":"2.0","id":1}
HEADER_LOCAL_SMOKE PASS
```

This is local relay verification; no Cloudflare deployment, login, real app,
Claude mobile connection or image rendering was performed.
