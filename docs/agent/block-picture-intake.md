# Region-preserving picture intake (integration unit)

Base: `83306dc`, integrated with `agent/block-model` (`3704f13`). ShapeObject constructor visibility is widened from private to internal with the model owner’s approval; all rendering logic in that file remains unchanged.

- SVG photo-screen input and emoji selection retain `ShapeObject` through MainActivity/editor intake, rather than flattening it into `StitchObject`.
- Engine SVG/emoji preparation retains the same source shape and `add_generated_art` adds it. Therefore the relay's SVG `import_picture` path also keeps geometry without an app-side special case.
- Preserve the previous coordinates: agent additions explicitly apply the center offset formerly supplied by `StitchObject.recenter`; screen-to-editor additions retain the raw preview placement. Renaming an added layer now also updates the generated pattern's `name` metadata, without changing any stitch/thread.
- Add a default-source project codec so autosave/undo/script summaries can preserve geometry. Its `shapeVersion=1`, `shapes`, `viewBox`, `widthMm`, `blocks` fields match the model worker's in-progress codec envelope. The model worker’s complete block-override codec is retained, with the photo payload added alongside it.

## Photo fill integration draft

`ShapeObjectFactory` now retains the already emitted ColorDrawingPipeline pattern plus the Potrace polygon rings (mm, including holes). ColorDrawingPipeline only exposes those source rings; tracing, classification, routing and finishing are unchanged. The photo screen and PhotoPreparation use this factory for all-tatami area results; the relay PNG/JPEG import_picture uses the latter automatically. Renaming, duplication and project encode/decode retain the cached photo backend/default pattern. The `photo` codec payload stores thread identity, commands, metadata, warnings and region boundaries, avoiding a decode-time switch to VectorArt.

AutoTrace satin/running or mixed area/centerline results, outline and redwork deliberately remain StitchObject. Excluded/unsewn regions are not presented as emitted shape blocks. The retained photo payload must be preserved when combining this codec with the model worker's block-override codec; simply dropping `photo` during decode would reroute the source and change stitches. This unit verifies unchanged defaults, not application of the model worker's new override renderer to photo sources.

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
