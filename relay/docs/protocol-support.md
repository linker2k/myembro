# MCP protocol support and relay edit phase

Measured 2026-10-26, baseline `agent/relay-header` `6f4f76e`.

## Pinned SDK evidence

| Official package | Measured support | Evidence |
|---|---|---|
| @modelcontextprotocol/sdk 1.26.0 (previous pin) | Legacy through 2025-11-25; no 2026-07-28 | `dist/esm/types.js`: LATEST_PROTOCOL_VERSION='2025-11-25'; SUPPORTED_PROTOCOL_VERSIONS contains 2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05, 2024-10-07. |
| @modelcontextprotocol/sdk 1.32.2 (latest npm v1 checked) | Same legacy version set; no 2026-07-28 | Downloaded official npm tarball to /tmp and read the same constants. No code override can turn this into modern protocol support. |
| @modelcontextprotocol/server 2.4.0 (new exact pin) | Modern 2026-07-28 through createMcpHandler, plus official legacy transport | Actual Worker test: server/discover advertises 2026-07-28; tools/list/call and subscriptions/listen succeed using the modern envelope and required HTTP headers. Legacy initialize/tools/list/call and GET SSE still pass. |

Primary references:

- [Original v1.26.0 types](https://github.com/modelcontextprotocol/typescript-sdk/blob/v1.26.0/src/types.ts)
- [Official v1.32.2 package metadata](https://registry.npmjs.org/@modelcontextprotocol/sdk/1.32.2)
- [Official v2.4.0 package metadata](https://registry.npmjs.org/@modelcontextprotocol/server/2.4.0)
- [Official v2 protocol migration](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/docs/migration/support-2026-07-28.md)
- [Official v2 web-standard serving](https://ts.sdk.modelcontextprotocol.io/v2/serving/web-standard)
- [Official notification semantics](https://ts.sdk.modelcontextprotocol.io/v2/servers/notifications)

The v2 package's exported LATEST_PROTOCOL_VERSION still describes the **legacy**
handshake codec. Modern support is provided by createMcpHandler, not by changing
that constant or feeding a new version into the old initialize handshake. The
new pin and transitive integrity hashes are committed in package-lock.json;
no protocol version or header is rewritten by relay code.

## 2026 modern requests versus fallback

A valid modern request uses params._meta with
`io.modelcontextprotocol/protocolVersion: "2026-07-28"` (and the SDK-defined client
information/capabilities), plus matching MCP-Protocol-Version and Mcp-Method HTTP
headers. tools/call additionally requires Mcp-Name matching params.name.
The official handler validates all of these; mismatches remain errors.

The production observation was a first request claiming 2026-07-28 returning 400,
then a 2025-11-25 retry succeeding. Tests reproduce **legacy initialize** with a
2026 header returning 400 and initialize with 2025-11-25 returning 200. An upgrade
does not redefine initialize as a modern method: modern clients use
server/discover/per-request envelopes. The real production first-request body was
not available to this unit, so its exact method/format is unconfirmed. Correct
modern requests are now tested and accepted; the 2025 fallback remains supported.

## Edit-phase extension

This implements the relay portion of the manager's `/tmp/myembro-relay-edit-plan.md`.
The [v1 authentication contract](relay-contract-v1.md) and
[redeploy-after-secret-change rule](operations.md) remain in force. The edit-phase
rules supersede v1's two-tool read-only scope and 60-second timeout:

- Call timeout is **120 seconds**, without automatic retry. The app must continue
  ping/pong while preparing a photo or awaiting remove confirmation; its silence
  limit remains 90 seconds. Client-side deadlines are outside relay control.
- Discovery advertises the deployed static Ask AI project-edit and approved
  photo/read catalog; calls are forwarded only when the active app advertises a
  matching name/schema. The allowlist follows AgentPrompt.SIGNATURES at
  this baseline and the manager's photo registry additions. Unannounced tools,
  save/share/USB, file/library deletion, restore, settings and export are excluded.
- No edit logic lives on the relay. App tools must use the existing dispatcher and
  EditSession, one tool edit = one atomic turn/undo step. App results supply revision,
  project summary and sewing verdict; relay forwards them unchanged. Only the app
  handles the remove_objects confirmation dialog and its 90-second response limit.
- Photo access/consent, downscale limits, sewing/hoop checks and read-only viewer
  behavior are app responsibilities. Image/png and image/jpeg inline content pass
  through; no arbitrary attachment or filesystem access is added to this server.
- The [static tool contract](static-tools.md) supersedes hello-driven discovery
  and notifications: tools/list is the fixed deployment catalog, listChanged is
  false, and hello only gates call compatibility. Both protocol eras continue
  using the official SDK; no notification is needed for an unchanged static list.

## Measured validation

```sh
cd relay
npm run typecheck
npm test
```

```text
Test Files  4 passed (4)
     Tests  31 passed (31)
```

This includes real Miniflare Worker/DO/HTTP/WS tests for modern and fallback
versions, static catalog stability, app compatibility and allowed edit forwarding; the exact 120-second timeout with fake timers and app liveness;
all prior auth, static-discovery, text/image and reconnect tests. The fake app supplies
edit result JSON; this is not a proof of Android edit/consent behavior or actual
Claude mobile reconnection on the new SDK. No deployment or login was performed.
