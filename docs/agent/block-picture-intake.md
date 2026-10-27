# Region-preserving picture intake (integration unit)

Base: `83306dc`, integrated with `agent/block-model` (`3704f13`). ShapeObject constructor visibility is widened from private to internal with the model owner’s approval; all rendering logic in that file remains unchanged.

- SVG photo-screen input and emoji selection retain `ShapeObject` through MainActivity/editor intake, rather than flattening it into `StitchObject`.
- Engine SVG/emoji preparation retains the same source shape and `add_generated_art` adds it. Therefore the relay's SVG `import_picture` path also keeps geometry without an app-side special case.
- Preserve the previous coordinates: agent additions explicitly apply the center offset formerly supplied by `StitchObject.recenter`; screen-to-editor additions retain the raw preview placement. Renaming an added layer now also updates the generated pattern's `name` metadata, without changing any stitch/thread.
- Add a default-source project codec so autosave/undo/script summaries can preserve geometry. Its `shapeVersion=1`, `shapes`, `viewBox`, `widthMm`, `blocks` fields match the model worker's in-progress codec envelope. The model worker’s complete block-override codec is retained, with the photo payload added alongside it.

## Photo fill integration draft

`ShapeObjectFactory` now retains the already emitted ColorDrawingPipeline pattern plus the Potrace polygon rings (mm, including holes). ColorDrawingPipeline only exposes those source rings; tracing, classification, routing and finishing are unchanged. The photo screen and PhotoPreparation use this factory for all-tatami area results; the relay PNG/JPEG import_picture uses the latter automatically. Renaming, duplication and project encode/decode retain the cached photo backend/default pattern. The `photo` codec payload stores thread identity, commands, metadata, warnings and region boundaries, avoiding a decode-time switch to VectorArt.

AutoTrace satin/running or mixed area/centerline results, outline and redwork deliberately remain StitchObject. Excluded/unsewn regions are not presented as emitted shape blocks. The retained photo payload must be preserved when combining this codec with the model worker's block-override codec; simply dropping `photo` during decode would reroute the source and change stitches. The later native-photo-region integration below extends this to overrides and sewing order while preserving default output.

## Checks

`ShapeIntakeParityTest` compares the public bundled red-heart emoji and SVG hole fixture against their pre-conversion VectorArt output: all stitches/threads, placed coordinates, composed PES bytes, retained hole/source geometry and project encode/decode. `SvgPictureToolTest` additionally asserts that the real AI SVG add/undo path contains a ShapeObject. No private images are used.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk \
  ./gradlew --offline :engine:test :app:testDebugUnitTest
```

`PhotoShapeParityTest` uses only generated in-memory 2-color and 3-color PNG fixtures (white included), including a real hole, comparing pre-conversion ColorDrawingPipeline against direct factory and engine preparation/add placement. It asserts exact stitches/threads/metadata/warnings, composed PES bytes and codec round-trip, plus explicit outline fallback to StitchObject. Verification uses the ordinary build, without source overlays.

Integration verification after merging `3704f13` (ordinary command above, no overlay):

```text
BUILD SUCCESSFUL in 2m 11s
engine: tests=914 failures=0 errors=0 skipped=0
app: tests=405 failures=0 errors=0 skipped=0
PHOTO_SHAPE_PARITY colors=2 stitches=2278 coordinates=IDENTICAL pes=IDENTICAL codec=IDENTICAL regions=1
PHOTO_SHAPE_PARITY colors=3 stitches=2650 coordinates=IDENTICAL pes=IDENTICAL codec=IDENTICAL regions=2
```

## Region-order model constructor compatibility (`077a59e`)

Merged the model branch into this integration branch. Its `Lazy<VectorArt.Result>`
constructor now receives the immutable existing photo pattern plus matching source-order
Part IDs, rather than `Lazy<EmbPattern>`. No photo pattern is regenerated through
VectorArt. The codec retains both the photo payload and the model's optional regionOrder;
renaming retains the current order. The only ShapeObject.kt difference from the model
revision remains the previously approved private → internal constructor access.

Historical limitation at `dad01d5`: retained photo caches had no native per-region
stitch fragments. This is resolved for newly generated pictures by the integration
below. Older photo payloads still need reimport for missing source provenance.

Verification (ordinary sources, no overlay; no emulator used):

```text
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk \
  ./gradlew --offline --max-workers=2 :engine:test :app:testDebugUnitTest
engine: tests=929 failures=0 errors=0 skipped=0
app: tests=410 failures=0 errors=0 skipped=0
PhotoShapeParityTest: 2/2 PASS; 2/3-color coordinates/PES/codec IDENTICAL
ShapeIntakeParityTest: 2/2 PASS; SVG/emoji coordinates/PES/codec IDENTICAL
CircularBlockProfileTest: 2/2 PASS; 6/6 renders golden=IDENTICAL
BUILD SUCCESSFUL in 2m 7s
```

## Native photo region editing

The source was available at generation time: Potrace polygon rings and the
ColorDrawingPipeline region → StitchGroup mapping. The previous integration discarded
the latter when it finished the groups into one pattern. Finished needles cannot
reliably reconstruct that ownership, especially when distinct regions share a thread.

ColorDrawingPipeline now captures ownership using the same `myembro.region:` stitch
and lock tags as VectorArt, with the existing GeneratedPatternProcessor provenance
and RegionOrderPatterns.split. It does not change quantization, tracing, classification,
fill defaults, generated coordinates, metadata, threads or PES. An untagged generation
mode exists internally so the test compares all original pattern fields directly.

ShapeObject retains a PhotoShapeSource containing immutable original source-order
output and its native region fragments. Defaults return that original pattern.
For an override, only selected regions use the existing ShapeBlockRenderer with the
retained mm polygons and original thread. Other regions reuse their native fragments.
Both overrides and order composition use the existing RegionOrderPatterns.compose;
there is no geometry reconstruction, source-art rescaling or new fill algorithm.
The ordinary SetBlockStitch/SetBlockOrder commands run their existing PES safety and
hoop checks before accepting a project change. Resetting all overrides returns the
original cached output exactly.

Photo payload v2 persists the original source pattern and original native fragments,
not the currently reordered/overridden output. The outer codec persists overrides
and sewing order, which are reapplied on decode. Renaming also retains this source.
Version 1 payloads remain loadable with identical default output, but cannot invent
missing region ownership: order changes reject with
`PHOTO_SOURCE_PROVENANCE_MISSING: reimport this picture`. Partial overrides on a
legacy file similarly reject when an unchanged region lacks provenance. Reimporting
regenerates ownership from the original picture; no personal picture is included in
tests or in the repository.

The generated 3-color PNG fixture (white background plus 2 sewn regions) verifies
angle 135°, row spacing 0.4mm, contour texture, baseline/overridden region order,
unchanged nonselected native stitches, restoring defaults, renamed/reordered cache
retention, v2 project/PES round-trip, and v1 fallback. Every accepted variant explicitly
asserts SewoutCheck PASS and valid PecViewportCheck. These test settings do not add
new product limits. Redwork/AutoTrace fallback behavior remains unchanged.

Native-photo integration verification:

```text
./gradlew --offline --max-workers=2 :engine:test :app:testDebugUnitTest
engine: tests=930 failures=0 errors=0 skipped=0
app: tests=410 failures=0 errors=0 skipped=0
PhotoShapeParityTest: tests=3 failures=0 errors=0
PHOTO_SHAPE_PARITY colors=2 shape=true stitches=2278 coordinates=IDENTICAL pes=IDENTICAL codec=IDENTICAL regions=1
PHOTO_REGION_EDIT override=none order=[region-1, region-0] sewout=PASS other_region=IDENTICAL codec_PES=IDENTICAL
PHOTO_REGION_EDIT override=tatami_fill order=[region-1, region-0] sewout=PASS other_region=IDENTICAL codec_PES=IDENTICAL
PHOTO_REGION_EDIT override=tatami_fill order=[region-1, region-0] sewout=PASS other_region=IDENTICAL codec_PES=IDENTICAL
PHOTO_REGION_EDIT override=contour_fill order=[region-1, region-0] sewout=PASS other_region=IDENTICAL codec_PES=IDENTICAL
PHOTO_SHAPE_PARITY colors=3 shape=true stitches=2650 coordinates=IDENTICAL pes=IDENTICAL codec=IDENTICAL regions=2
ShapeIntakeParityTest: tests=2 failures=0 errors=0
CircularBlockProfileTest: tests=2 failures=0 errors=0
BUILD SUCCESSFUL in 1m 54s
```
