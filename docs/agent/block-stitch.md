# Region-preserving block stitching: engine draft

Scope: engine model, atomic region overrides, additive ProjectJson codec and
default-output parity. No UI/photo adapter or change to the current app path.

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
unchanged (`angle`, `row_spacing_mm`, `zigzag_spacing_mm`, underlay, etc.). These are resolved default descriptors with immutable override snapshots.
ProjectCommand.SetBlockStitch targets one ShapeObject and a region-ID set plus a
BlockStitchPatch. No geometry setter or sewing-order UI is installed.

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

Integration boundary: ShapeObjectCodec is a built-in type=shape codec with
shapeVersion=1. The outer .myembro version remains 1; old stitch/text payloads
and custom-codec precedence are unchanged. It stores source paths/transforms,
width and region overrides, never generated stitches as authoritative geometry.
Region recoloring commands are rejected until their geometry/backend mapping exists. Two app sealed-type
compatibility guards prevent unsupported color commands; no shape creation or
UI editing flow is installed. Agent summaries recognize the new type without
flattening it into an imported object. Next unit: UI adoption, region recolor/order and broader backend controls.

Verification: `./gradlew :engine:test --tests '*ShapeObject*'`. The public Fluent
heart SVG and original `inkstitch/fill/hole.svg` fixture compare every stitch,
thread, metadata/warning and complete PES V1/V6 byte against VectorArt.render;
both outputs must pass generated SewoutCheck and exact PecViewportCheck. Other
tests cover preserved hole rings, defensive copies, transform/duplication cache,
codec round-trip and placed/encoded out-of-hoop rejection. No private assets.


## Region override contract (second engine unit)

One SetBlockStitch command, even for multiple IDs, is one EditSession undo unit.
Unknown object/region, invalid values, unsupported backend or incomplete/blocked
sewout analysis reject the entire command; Batch failure keeps the original
project and failure path. No-op patches create no history. Generation uses the
existing VectorArt region dispatch: untouched regions keep their old routing;
explicit overrides select the upstream backend without safety fallback to another
method. The set of emitted region IDs must remain identical (no silent exclusion).
The candidate *whole project* is checked for placed hoop bounds, then through
PesWriter, PecViewportCheck and generated-origin SewoutCheck before publication.

Fill routes are FontTatami (tatami), MeanderFill, ContourFill and CircularFill;
underlay uses FillUnderlay. Satin uses RegionSatin with SatinParameters; running
uses Stroke. Other upstream fill_method options are recognized but explicitly
return UNSUPPORTED_FILL_BACKEND rather than being approximated. No new texture
algorithm is introduced. Angle=automatic restores the app's existing 0-degree
tatami default (VectorArt.tatamiGroups -> FontTatami empty attributes ->
FillParameters.angle). Returning all settings to resolved defaults removes the
override and restores the exact original renderer path/bytes. This is not a new
geometry-based angle estimator. meander_angle is a distinct upstream attribute;
this unit does not rename tatami angle to that attribute.

### Parameter sources and rejection bounds

Source is Ink/Stitch v3.3.0, commit
b0edd96311ece82ee48816dc93466274b12c2a9c. Only default values and getter bounds
below are used; no upper bounds are invented. The host rejects values below an
upstream clamp instead of silently clamping an explicit user edit. All numeric
inputs must be finite, as required by the existing engine command/geometry API.

| Input | Accepted domain | v3.3.0 definition (file:lines) |
|---|---|---|
| fill_method | upstream option IDs; unported adapter routes reject at generation | [fill_stitch.py:166–184](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L166) |
| angle | finite degrees, negatives allowed, no numeric min/max | [fill_stitch.py:300–310](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L300) |
| row_spacing_mm | >=0.1mm, no upper bound | [fill_stitch.py:364–380](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L364); units converted by [element.py:111–123](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/element.py#L111) |
| meander_pattern | an installed upstream tile ID; unknown IDs reject instead of upstream's missing-tile fallback | [fill_stitch.py:223–226](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L223) and bundled /inkstitch/tiles/index.txt |
| meander_scale_percent | 1 or 2 finite percentages, each >=1, no upper bound | [fill_stitch.py:239–248](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L239) |
| zigzag_spacing_mm | >=0.01 / PIXELS_PER_MM mm, no upper bound (the clamp is **pixels**, not 0.01mm) | [satin_column.py:263–272](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/satin_column.py#L263) and element.py:111–123 |

Codec payloads validate the same bounds and source-region IDs. Source/defaults
regenerate the baseline IDs before overrides are attached. JSON can preserve an
editable project outside a hoop, as before; machine output always uses the normal
final safety gates. The codec supports the fields this command exposes and
rejects unknown override fields instead of silently losing settings.

Measured heart-region table is emitted by ShapeObjectOverridesTest as BLOCK_TABLE
rows (PASS includes identical JSON-restored stitches/PES and exact hoop check).
Any valid-parameter input that fails generation/safety instead emits its actual
SEWOUT_BLOCKED reason, with no publication. Tests also assert range rejection,
one undo/redo for a region-list edit, Batch rollback, automatic reset, and legacy
ProjectJson compatibility through the existing ProjectJson tests.

Measured (ShapeObjectOverridesTest, heart at 30mm, one selected region):

| Change | Sewout / exact hoop | Local stitches | JSON/PES round trip |
|---|---|---:|---|
| meander_fill | PASS | 824 | identical |
| contour_fill | PASS | 1322 | identical |
| circular_fill | PASS | 1606 | identical |
| manual tatami angle 135 degrees | PASS | 1456 | identical |

Command: `./gradlew :engine:test --tests '*ShapeObject*' --tests '*ProjectJson*'`
Result: BUILD SUCCESSFUL in 20s, 15 tests, 0 failures/errors/skips. This is a
fixture regression, not a claim that arbitrary method/geometry combinations
are sewable; other combinations return an actual blocking reason atomically.
