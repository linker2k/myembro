# myembro relay spike contract v1 (manager-authorized header authentication, 2026-10-26)
Goal: Claude mobile app (custom connector, remote MCP) -> Cloudflare Worker relay -> myembro app on the same phone over WebSocket. No PC, no LAN, no VPN.

App <-> relay (WebSocket, app connects outbound):
- URL: wss://<host>/app (local: ws://10.0.2.2:8787/app from emulator).
- Preferred authentication: Authorization: Bearer <APP_KEY> request header.
- Temporary migration compatibility: /app?key=<APP_KEY> remains accepted only when Authorization is absent. Query authentication is deprecated and will be removed after the app migrates. An explicit wrong/malformed header is rejected even if the query key is correct.
- app -> relay on open: {"type":"hello","appVersion":"<str>","tools":[<MCP tool defs: name, description, inputSchema, annotations>]}
- relay -> app: {"type":"call","id":"<uuid>","tool":"<name>","args":{...}}
- app -> relay: {"type":"result","id":"<same>","isError":false,"content":[{"type":"text","text":"..."} | {"type":"image","data":"<base64>","mimeType":"image/png"}]}
- either side: {"type":"ping"} / {"type":"pong"} every 30 s; relay drops app after 90 s silence.
- One app connection per relay; a new hello replaces the old socket.

Claude <-> relay (MCP Streamable HTTP):
- Endpoint: https://<host>/mcp only.
- Authentication: Authorization: Bearer <MCP_TOKEN> request header only. Configure this in the claude.ai connector request-header field; the connector stores the value securely. OAuth remains deferred.
- Compare SHA-256 fixed-size credential digests using the runtime native constant-time comparison. Missing/incorrect credentials return 401 with a generic body containing no credential. Unknown/old /mcp/<x> paths return 404 (no legacy path-secret authentication).
- MCP query parameters never provide authentication. Do not log authorization values, credential-bearing URLs, or secrets.
- tools/list always returns the deployment-time static catalog in src/tools.ts, independent of app hello or cache. Missing/schema-mismatched app tools return isError "myembro 앱을 최신으로 업데이트해 주세요" without forwarding; compatible tools still work. If no app is connected, tools/call returns isError text "myembro app is not connected. Open myembro and turn on AI connection."
- tools/call forwarded to app, 120 s timeout (v2) -> isError "timed out".
- Secrets APP_KEY and MCP_TOKEN are Worker secrets; never committed. MCP_SECRET is retired and is not read. Production routing and application code must not emit credential values in logs.

Tool set: v1 spike was get_project and render_preview(widthPx:int[256..1024]); the edit phase forwards `RELAY_TOOLS` in src/tools.ts (reads, Ask AI edits as one undoable turn each, photo tools). Save, share, USB, file deletion, restore and settings are never forwarded.
Of the needsUserConfirmation tools only remove_objects is forwarded; the app asks the user on the phone before it removes anything. Attachments are only photos the user handed to AI in the app.

Operations: after any Worker secret change, always rerun deployment; see the
[secret-update operational note](operations.md) for the observed stale Durable Object environment incident.

Discovery details and canonical-schema checks: [static tool contract](static-tools.md).
