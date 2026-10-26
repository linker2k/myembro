# myembro relay spike contract v0 (manager, 2026-10-26)
Goal: Claude mobile app (custom connector, remote MCP) -> Cloudflare Worker relay -> myembro app on the same phone over WebSocket. No PC, no LAN, no VPN.

App <-> relay (WebSocket, app connects outbound):
- URL: wss://<host>/app?key=<APP_KEY>   (local: ws://10.0.2.2:8787/app?key=... from emulator)
- app -> relay on open: {"type":"hello","appVersion":"<str>","tools":[<MCP tool defs: name, description, inputSchema, annotations>]}
- relay -> app: {"type":"call","id":"<uuid>","tool":"<name>","args":{...}}
- app -> relay: {"type":"result","id":"<same>","isError":false,"content":[{"type":"text","text":"..."} | {"type":"image","data":"<base64>","mimeType":"image/png"}]}
- either side: {"type":"ping"} / {"type":"pong"} every 30 s; relay drops app after 90 s silence.
- One app connection per relay; a new hello replaces the old socket.

Claude <-> relay (MCP Streamable HTTP):
- Endpoint: https://<host>/mcp/<MCP_SECRET>  (spike auth = unguessable path secret; OAuth later)
- tools/list = tools from the latest hello; if no app connected, list returns the same tools cached from last hello, and tools/call returns isError text "myembro app is not connected. Open myembro and turn on AI connection."
- tools/call forwarded to app, 60 s timeout -> isError "timed out".
- Secrets APP_KEY and MCP_SECRET are Worker secrets; never committed.

Spike tool set (readOnly only): get_project, render_preview(widthPx:int[256..1024]) -> image/png of current project with hoop.
Write tools, needsUserConfirmation tools and attachments are NOT exposed in the spike.
