# myembro relay spike

Implements the unchanged [manager contract v0](CONTRACT.md). One Worker routes
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
to overwrite it. `.dev.vars`, `.env`, node_modules, dist and local Worker state
are ignored. Real APP_KEY/MCP_SECRET must be provided as Worker secrets outside
source control; wrangler.toml contains no values. No login or deployment command
is needed or executed. Local dev binds only 127.0.0.1:8787. An Android emulator
can reach this host through 10.0.2.2:8787 per the contract.

`local-smoke.mjs` connects a fake app, sends hello, then invokes **curl** for MCP
initialize/tools/list. It prints responses but never keys or secret URLs. It
closes its socket after validation. Use the HTTPS/WSS endpoints from CONTRACT.md
when a deployment is separately authorized; this unit does not test Claude mobile.

## Routing and limits

- `/app?key=<APP_KEY>` requires a correct key and WebSocket upgrade. A valid hello
  selects the active app; a new hello closes the previous socket and fails its
  pending requests. Definitions retain their original inputSchema/annotations.
- Only get_project and render_preview with a positive readOnly/readOnlyHint
  annotation are retained. A confirmation annotation other than false excludes
  the definition. Other tool names, writes and attachment tools are not exposed.
  render_preview accepts exactly one integer widthPx in 256–1024; get_project
  accepts no arguments. The app remains responsible for rendering the current
  project including its hoop.
- `/mcp/<MCP_SECRET>` requires the exact secret path. Missing/incorrect keys are
  denied before forwarding to the object; the object also checks authentication.
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
end-to-end testing remain outside this single-app trial contract. The path secret
is bearer authorization; do not share or publish endpoint URLs or production logs
containing them. Source code deliberately does not log requests or secrets.

## Measured verification

`npm run typecheck`: exit 0.
`npm test`: **1 test file, 11 tests passed** (Miniflare + Vitest).

Five integration tests use the actual bundled Worker, Durable Object storage,
WebSocket and official SDK HTTP transport: disconnected error; hello/list identity;
call forwarding/text/error/image preservation; cached tools after app disconnect
and runtime restart plus forbidden/invalid calls; wrong keys. Six broker tests
cover exact 60-second timeout using fake timers, 30/90-second liveness including deadline renewal between ping ticks, excluded
write/confirmation tools, concurrent UUID routing and replacement/late-result
isolation. Fake timers target the production broker directly, not workerd's clock.

Actual local `wrangler dev --local` plus curl output (no secret URL):

```text
CURL initialize HTTP 200 {"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"myembro-relay","version":"0.1.0"}},"jsonrpc":"2.0","id":1}
CURL tools/list HTTP 200 {"result":{"tools":[{"name":"get_project","description":"Read project","inputSchema":{"type":"object","properties":{},"required":[],"additionalProperties":false},"annotations":{"readOnlyHint":true}},{"name":"render_preview","description":"Render hoop","inputSchema":{"type":"object","properties":{"widthPx":{"type":"integer","minimum":256,"maximum":1024}},"required":["widthPx"],"additionalProperties":false},"annotations":{"readOnlyHint":true}}]},"jsonrpc":"2.0","id":1}
```

This is local relay verification; no Cloudflare deployment, login, real app,
Claude mobile connection or image rendering was performed.
