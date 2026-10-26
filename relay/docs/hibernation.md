# WebSocket hibernation and free-plan budget

Implemented from `agent/relay3` (`7284bc3`) on `agent/relay-hibernate`. No deployment or account changes were made. Figures checked against official Cloudflare documentation on 2026-10-26.

## Official compute limits

| Dimension | Workers Free / Durable Objects |
|---|---|
| Compute requests | 100,000 / day; HTTP, RPC sessions, WebSocket traffic and alarms count |
| Compute duration | 13,000 GB-s / day |
| Incoming WebSocket messages | 20 messages count as 1 compute request (billing ratio, not analytics counts) |
| WebSocket establishment | 1 request per connection |
| Outgoing WebSocket messages; incoming protocol-level ping | Not charged as requests |
| Application-level auto-response | Does not add wall-clock duration; JSON ping is application data, not a protocol ping |

Source: [Cloudflare Durable Objects pricing, compute table and footnotes 2–5](https://developers.cloudflare.com/durable-objects/platform/pricing/). Free DOs require the SQLite backend; this relay already uses `new_sqlite_classes`. These are DO compute limits, not a guarantee of remaining account-wide Workers/storage allowance. Daily limits reset at 00:00 UTC.

Cloudflare allocates/bills 128 MB per object. Following its published decimal conversion, a continuously awake object costs `128 / 1000 × 86400 = 11059.2 GB-s/day`, about `85.07%` of the 13,000 daily allowance. This is the baseline estimate, not production telemetry. Hibernating eligibility removes the connection-long duration charge; actual request/CPU handling still consumes duration. A 30-second JSON ping cadence means 2,880 incoming application messages/day per app; applying 20:1 gives 144 request equivalents before other traffic/connection requests. We do not assume auto-response makes these application data messages protocol-level free pings.

## Implementation

- `state.acceptWebSocket()` replaces `app.accept()`. Events enter `webSocketMessage`, `webSocketClose` and `webSocketError`; no per-socket event listeners pin the object.
- `state.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"type":"ping"}', '{"type":"pong"}'))` matches the Android client's existing compact JSON exactly. No app protocol change is needed. Whitespace/noncanonical JSON ping still receives pong via the regular handler, with a normal wake-up.
- Server-side `setInterval` heartbeat and idle `setTimeout` are removed. Android already sends ping every 30 seconds and reconnects after 90 seconds without received messages (`RelayClient.kt`, `RelayProtocol.kt`). Relay liveness expires lazily before forwarding a tool call: use the newer of attachment `lastSeen` and `getWebSocketAutoResponseTimestamp()`, dropping the socket if silent for at least 90 seconds. Thus a silent idle socket may remain listed longer than 90 seconds, but receives no new tool call after expiry. No periodic alarm wakes it solely for cleanup.
- A small `serializeAttachment()` stores ready status, compatible tool names, last activity and a SHA-256 catalog fingerprint. The constructor gets accepted sockets through `getWebSockets()` and restores the broker. Full schemas/images/results are not stored in attachments. A catalog mismatch fails closed with the existing update-required error until a new hello. Replaced/closed sockets are marked unready before cleanup.
- Optional idle MCP GET/SSE returns 405 with `Allow: POST`. The relay advertises no unsolicited tool-list notifications (`listChanged=false`). Keeping a GET stream open could otherwise prevent hibernation. Legacy POST transport and modern request handling still use the official pinned MCP SDK; framing is unchanged. [MCP Streamable HTTP: listening for messages](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#listening-for-messages-from-the-server) explicitly allows 405 when no standalone SSE stream is offered.

API sources: [Cloudflare state API](https://developers.cloudflare.com/durable-objects/api/state/), [official hibernation example](https://developers.cloudflare.com/durable-objects/examples/websocket-hibernation-server/).

## Pending calls and timeout

A pending MCP HTTP request cannot hibernate: Cloudflare requires every request/event to have finished before discarding the instance. Its Promise resolver therefore remains in memory, with **one bounded 120-second timeout per pending call** (the existing v2 contract). Completion, disconnect/replacement or timeout removes that timer; an idle connection has zero timers. There is no detached operation to persist or resume, so storage/alarm adds no benefit here. Persisting an HTTP Promise would not recreate the original response channel. During these bounded calls, duration charges are expected. Abrupt deployment/runtime shutdown is different from hibernation and is not promised to preserve in-flight HTTP calls.

[Official lifecycle criteria](https://developers.cloudflare.com/durable-objects/concepts/durable-object-lifecycle/) require no scheduled timers, unfinished requests/I/O or standard accepted WebSockets. They also explain constructor re-entry and that runtime decides when to hibernate. This patch makes idle connections eligible; it does not claim a locally measured production GB-s reduction.

## Automated evidence

```sh
cd relay
npm run typecheck
npm test
```

Baseline before edits: 4 files, **32 tests passed**. Existing coverage remains (2 timer tests updated to assert timer-free/lazy-expiry behavior). Added tests:

1. Miniflare/workerd automatic JSON pong: auto-response timestamp set, message callback count unchanged.
2. Reconstruct the real Relay instance with the same accepted runtime WebSockets/attachments; call/result succeeds without another hello; active attachment stays below 2048 bytes.
3. Binary message rejected through the hibernation callback.
4. Fake clock: an auto-response timestamp keeps a reconstructed connection fresh; pending timer fires at exactly 120 seconds; zero idle timers afterwards.
5. Success clears the timer; stale catalog attachment cannot forward a call.
6. Optional MCP GET/SSE returns a completed 405 response.

Final output: **5 files / 38 tests passed**; `tsc --noEmit` passed. The reconstruction seam lives only in `test/hibernation.fixture.ts` and is bundled in memory for Miniflare; no test endpoint is exported in the deployed worker. This deterministically tests constructor re-entry, not actual Cloudflare scheduler eviction or billing. No production deployment or live billing experiment was performed.
