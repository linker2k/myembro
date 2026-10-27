# Thread texture UI seam

UI-only unit based on main `80ff526`. No texture stitching algorithm, numeric
stitch parameters, fake production output, or PE-Design equivalence is introduced.

In step 2, Color drawing shows **Thread texture** (off by default) and an
**Experimental** badge. When enabled, Detail offers Coarse/Normal/Fine and Stitch
length offers Short/Normal/Long. These are enums only: the model engine owner must
supply verified parameter mappings before production hookup. Existing color count,
noise, contrast, black-and-white, crop and physical size controls remain shared.

`ThreadTextureGenerator.generate(pixels,w,h,palette,detail,runLength)` is the app
interface. `ThreadTexturePreview.from` captures the
existing quantized display preview plus the corrected pre-quantization ARGB raster
(optionally converted by the existing grayscale helper) and shared Wu RGB palette.
The generator receives the unpadded source raster so tonal variation is retained.
Step 2 never calls the generator. Step 3 passes copies into the injected generator;
returned EmbPattern goes through the existing PES writer/SewoutCheck and send gate.
The adapter must bind current physical width, hoop, selected thread inventory and
finalization rules; these are not inferred from pixel dimensions or enum ordinals.
Engine owner must review the input contract before hookup.

`PhotoViewModel(Application)` stays compatible with the Android default factory;
a second optional generator argument permits integration. Production currently has
no generator: preview/options work, but Next is disabled while texture is on with
a user-facing availability message. Disabling it restores ordinary Color drawing.
Fake implementations exist only in tests, and failures never fall back to fill.

Texture generation leaves `shapeObject` null, so the existing MainActivity path
creates a StitchObject. Both the photo result and editor-entry message explain
that individual color blocks cannot be edited; ordinary color block behavior stays
unchanged. Validation is JVM-only; no emulator execution is claimed.

Validation: `./gradlew :app:testDebugUnitTest --console=plain` →
`BUILD SUCCESSFUL in 52s`. App XML totals: 451 tests, 0 failures, 0 errors;
`ThreadTexturePreviewTest`: 4 tests, 0 failures, covering all 9 enum combinations,
shared correction/grayscale input, immutable preview copies, off-path PES byte
identity, and absent/failing-generator behavior. No texture sewing-quality claim
is made: the fake returns a fixed test pattern, not a texture algorithm.
