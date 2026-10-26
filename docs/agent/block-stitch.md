# Region-preserving block stitching: engine draft

Scope: model and default-output parity only. No UI, ProjectJson codec, texture
algorithm, setting overrides, photo adapter, or change to the current app path.

`ShapeObject : DesignObject` owns immutable source `VectorArt.Art`, requested
picture width, stable object ID, pose/visibility, and read-only `regions`.
`fromArt`/`fromSvg` use the existing renderer to discover the *actually emitted*
regions, including overlap removal, holes, classification and safety fallbacks.
Each ShapeRegion records a local region ID, source-shape index, paint role,
centered local-mm polygon/line paths, selected thread and default BlockStitch.
Paths are captured from the existing renderer's geometry, not inferred from PES.
Source SVG paths/transforms/fill rules are retained as well: boundary rings alone
cannot reproduce original stroke centerlines, joins or document-order occlusion.
Region IDs survive pose/visibility/object-ID changes; IDs across a later
geometry-changing rebuild need an explicit matching contract in the next unit.

BlockStitch records `type = FILL | SATIN | RUNNING`, the existing upstream
`FillParameters` and nullable `SatinParameters`. Names/units/defaults are used
unchanged (`angle`, `row_spacing_mm`, `zigzag_spacing_mm`, underlay, etc.). These
are **resolved default descriptors**, not an editable-settings API yet. Region
lists have no public replacement constructor/setter, so unsupported parameter
changes cannot be accepted and silently ignored. Implementing manual method,
angle/density/direction, texture and sewing-order changes is a separate step.

`localPattern()` lazily re-runs `VectorArt.render` from source geometry with the
same width/name and unchanged defaults; it never stores a frozen StitchObject
as the authoritative source. Pose/visibility/ID copies share the immutable
geometry/pattern cache. The original VectorArt classification, per-region
fallback, nearest-thread matching, previous-stitch routing, finishing, locks and
metadata remain in place. `Part.paths` is additive diagnostic geometry: it does
not participate in stitch generation. Renderer coordinates are centered on the
paint footprint, exactly as before. Hoop placement uses DesignObject's existing
implementation; writer/SewoutCheck/PecViewportCheck still enforce P1–P8 and
exclusive hoop bounds (100mm means 0–999). No safety profiles are relaxed.

ColorDrawingPipeline is deliberately not routed through VectorArt. It retains
`Preview.layers[].outline.paths`, palette threads and the cleaned label raster;
`generate` splits each layer into polygons, routes centerlines/satin, inserts
per-region trims and fits final stitches using the selected hoop. A future
photo ShapeObject source adapter must preserve those layer paths plus that
backend's scale/routing context and prove its own byte parity. It must not
retrace a preview image or substitute VectorArt's different finishing behavior.

Integration boundary: shape serialization currently fails explicitly through
ProjectJson's existing unsupported-object guard. Region recoloring commands are
rejected until their geometry/backend mapping exists. Two app sealed-type
compatibility guards prevent unsupported color commands; no shape creation or
UI editing flow is installed. Agent summaries recognize the new type without
flattening it into an imported object. Next unit: backend-specific regeneration
plan/configuration overrides, then codec/version migration and UI adoption.

Verification: `./gradlew :engine:test --tests '*ShapeObject*'`. The public Fluent
heart SVG and original `inkstitch/fill/hole.svg` fixture compare every stitch,
thread, metadata/warning and complete PES V1/V6 byte against VectorArt.render;
both outputs must pass generated SewoutCheck and exact PecViewportCheck. Other
tests cover preserved hole rings, defensive copies, transform/duplication cache,
unsupported codec and placed/encoded out-of-hoop rejection. No private assets.
