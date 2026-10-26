# Deployment-time static tool discovery

The Claude connector can retain the tools/list response from the beginning of a
conversation. A relay that advertises the last app hello can therefore get stuck
with the two-tool list from an old app, even after the app updates.

`src/tool-definitions.json` is a committed deployment snapshot, not a runtime
read of the engine catalog. `src/tools.ts` exports the **24 static RELAY_TOOLS**,
including schemas and MCP annotations. Worker discovery always advertises this
complete list: before any app connects, after a two-tool hello, after an empty or
changed hello, after disconnect, and after Durable Object restart. Legacy cached
hello data is no longer loaded or persisted. The runtime never reads docs/.

App hello is used only to determine whether the active app supports a call:

1. No active app: return the existing disconnected isError text.
2. Tool outside the deployed static catalog: refuse it; file/export/settings
   operations never become available just because the app advertises them.
3. Static tool missing from hello, or its inputSchema differs: return isError text
   **myembro 앱을 최신으로 업데이트해 주세요** without sending a call to the app.
4. Matching name/schema: preserve the existing argument guards and forward it.

Compatibility is **per tool**, not all-or-nothing: get_project/render_preview
still work on a two-tool app if their schemas match. Object-key ordering and
wording/annotation differences do not cause an update error. Schema array order
is preserved, matching the app's catalog serialization. A matching newer hello
immediately permits its supported calls without changing discovery.

The list is fixed for a deployment, so `tools.listChanged` is false. Hello changes
no longer publish tools/list_changed. Existing legacy GET handling remains, but
there is no dynamic catalog to notify. The 120-second call timer, Bearer auth,
confirmation in the app, sewout checks and all other execution rules are unchanged.
A connector that already cached an old two-tool list may need a fresh connection
or conversation once after deployment; this code does not alter a host's cache.

## Schema drift gate

Run from relay/:

```sh
npm run typecheck
npm test
```

`test/static-tools.test.ts` verifies:

- The **22 shared snapshot inputSchemas** exactly match the corresponding
  docs/agent/tools.json entries. All fields, nested schemas and numeric ranges
  participate in deep equality; the snapshot must be deliberately updated if the
  canonical contract changes.
- Advertised schemas match the **existing** RelayProtocol.definitions adapter:
  project edits omit transactionId because the app owns each turn. This is the
  only projection. Photo add_generated_art retains transactionId, as the current
  RelayPhotoTools registry requires. Changing the engine catalog to remove that
  field globally would break unrelated command/script paths.
- The two app-only schemas, list_attachments and view_attachment, match their
  current definitions in RelayPhotoTools.kt. They are not present in the engine
  catalog; the test reads that actual source fixture rather than assuming a
  corresponding docs entry exists.
- Every advertised entry is a valid official SDK Tool, the deployed set has no
  duplicates, and save/share/USB/file deletion/restore/settings mutation stay out.

Broker and real Miniflare HTTP/WS tests cover initial full discovery, old-app
update errors with zero WS calls, changed schemas, compatible forwarding and
unchanged discovery across hello/disconnect/restart in both MCP protocol eras.

No deployment or login is performed by this change.

Measured result: `npm test` reports **4 files passed, 31 tests passed**; typecheck
exits 0. A separate local HTTP/curl smoke reports
`LOCAL_STATIC_DISCOVERY PASS: 24 advertised tools with a two-tool app; initialize/tools/list HTTP 200`.
