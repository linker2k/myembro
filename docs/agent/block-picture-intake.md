# Region-preserving picture intake (integration unit)

Base: `83306dc`. ShapeObject.kt is unchanged in this branch.

- SVG photo-screen input and emoji selection retain `ShapeObject` through MainActivity/editor intake, rather than flattening it into `StitchObject`.
- Engine SVG/emoji preparation retains the same source shape and `add_generated_art` adds it. Therefore the relay's SVG `import_picture` path also keeps geometry without an app-side special case.
- Preserve the previous coordinates: agent additions explicitly apply the center offset formerly supplied by `StitchObject.recenter`; screen-to-editor additions retain the raw preview placement. Renaming an added layer now also updates the generated pattern's `name` metadata, without changing any stitch/thread.
- Add a default-source project codec so autosave/undo/script summaries can preserve geometry. Its `shapeVersion=1`, `shapes`, `viewBox`, `widthMm`, `blocks` fields match the model worker's in-progress codec envelope. This draft accepts empty block overrides only; integration should retain the model worker's complete settings codec instead of this draft when resolving the parallel additions.

## Remaining dependency: photo fill / raster AI pictures

The photo pipeline cannot safely call `ShapeObject.fromArt` today. `ColorDrawingPipeline` generates tatami via `DefaultTatami` from its resolved geometry and uses raster-derived AutoTrace paths for other regions. `ShapeObject.fromArt` instead calls `VectorArt.render` (FontTatami/RegionSatin, its own scaling, occlusion and finishing). Wrapping the photo contours in SVG would reroute/reclassify the design and does not establish stitch parity.

A model-owned factory/source type must retain the already generated photo pattern and its source regions, with serialization support, before the photo color-fill and PNG/JPEG `import_picture` paths can switch to ShapeObject without changing sewing. This branch keeps those as StitchObject and annotates the dependency in AttachmentStore. AutoTrace centerline/outline and redwork are deliberately still StitchObject; no attempt is made to reconstruct an area from their machine stitches.

## Checks

`ShapeIntakeParityTest` compares the public bundled red-heart emoji and SVG hole fixture against their pre-conversion VectorArt output: all stitches/threads, placed coordinates, composed PES bytes, retained hole/source geometry and project encode/decode. `SvgPictureToolTest` additionally asserts that the real AI SVG add/undo path contains a ShapeObject. No private images are used.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk \
  ./gradlew --offline :engine:test :app:testDebugUnitTest
```

Photo/raster ShapeObject parity is **not claimed** until the factory dependency above is resolved.
