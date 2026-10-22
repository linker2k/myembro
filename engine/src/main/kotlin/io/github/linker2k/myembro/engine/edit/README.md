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

`TextObjectCodec` is built in: `project.toJson()` and `ProjectJson.decode(saved)` handle
Hershey and outline TextObjects with no registration. Text, font ID/kind, height, spacing,
arc, complete thread identity, transform, visibility, contours, contour baseline and optional
source height round-trip. Outlines are embedded so saved lettering needs no Android font
access to regenerate its stitches. `TextObject.withId` keeps duplicates editable.

## Additional object codecs

Implement a `TypedProjectObjectCodec<YourObject>("your-type", YourObject::class.java)`:

- `encodePayload(obj)` returns the editable object-specific fields as JSON.
- `decodeObject(fields, data)` constructs your object using `fields.id`, `fields.name`,
  `fields.transform`, and `fields.visible`, and reads the editable fields from `data`.
- The adapter handles all common object fields and protects them from payload overwrites.
- Use `ProjectJson.encodeThread(thread)` / `ProjectJson.decodeThread(jsonObject)` to retain
  thread RGB/alpha, description, catalog number, brand, and chart.
- Override your object's `withId(id)` so duplication retains editable data.

Register once at app startup:

```kotlin
ProjectJson.registerCodec(YourObjectCodec)
val saved = project.toJson()
val restored = ProjectJson.decode(saved)
```

Alternatively pass `listOf(YourObjectCodec)` to both encode/toJson and decode. Registered
and per-call codecs share one validation/dispatch path; the same instance can occur in
both. Type strings must be stable, unique, nonblank, and cannot use the reserved `stitch`.
Custom codecs are consulted before built-in stitch/text encoding. Registration is synchronized and each
save/load uses one immutable registry snapshot. `unregisterCodec(codec)` removes only that
exact instance for scoped usage. Missing codecs fail explicitly instead of flattening text
and silently losing editability. The version-1 stitch JSON schema remains compatible.
