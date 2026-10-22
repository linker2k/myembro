# Editor model integration

`project.flatten()` always uses the stored sewing order. To preview a travel optimization,
call `project.suggestOrder()` for object IDs and before/after inter-object travel in 0.1 mm.
Apply explicitly with `project.optimizeOrder()`; `preserveOriginalOrder = true` returns the
original project. The heuristic permutes whole objects only within contiguous same-thread
runs, accounting for both neighboring objects and the initial hoop origin. It never splits
an object's internal color blocks. Hidden/empty/multicolor objects and different thread
identities are barriers. Inspect the suggestion before accepting changes to layering.

`project.statistics()` reports visible flattened stitch/jump/trim/color-change/stop counts,
estimated seconds, and sewn bounds exceeding the hoop. Timing parameters share the
`EmbPattern.estimatedSeconds()` defaults; speed must be positive and overheads nonnegative.

## Lettering serialization

Implement `TextObjectCodec : TypedProjectObjectCodec<TextObject>("text", TextObject::class.java)`:

- `encodePayload(obj)` returns the editable text/font/height/spacing/arc/thread fields as JSON.
- `decodeObject(fields, data)` constructs TextObject using `fields.id`, `fields.name`,
  `fields.transform`, and `fields.visible`, and reads the editable fields from `data`.
- The adapter handles all common object fields and protects them from payload overwrites.
- Use `ProjectJson.encodeThread(thread)` / `ProjectJson.decodeThread(jsonObject)` to retain
  thread RGB/alpha, description, catalog number, brand, and chart.
- Override `TextObject.withId(id)` so duplication retains editable lettering data.

Register once at app startup:

```kotlin
ProjectJson.registerCodec(TextObjectCodec)
val saved = project.toJson()
val restored = ProjectJson.decode(saved)
```

Alternatively pass `listOf(TextObjectCodec)` to both encode/toJson and decode. Registered
and per-call codecs share one validation/dispatch path; the same instance can occur in
both. Type strings must be stable, unique, nonblank, and cannot use the reserved `stitch`.
Codecs are consulted before built-in stitch encoding. Registration is synchronized and each
save/load uses one immutable registry snapshot. `unregisterCodec(codec)` removes only that
exact instance for scoped usage. Missing codecs fail explicitly instead of flattening text
and silently losing editability. The version-1 stitch JSON schema remains compatible.
