# SE400 PES coordinate-origin regression (2026-10-24)

SE400 positions the design rectangle declared in PEC. In a large sampler that recorded center-relative negative coordinates unchanged, the user observed repeated needle entries blocked at the left hoop limit. Matching the top-left origin in the local PE-Design comparison table, default PES v1/v6 writing translates minimum active-command coordinates to `(0,0)`. Editor/project originals and screen coordinates are unchanged.

`PesWriter.prepareForOutput` computes bounds only up to the original first END. Applies existing 0-length cleanup and cleanup after coordinate translation without regenerating lock shapes. If the first move is `(0,0)`, omits that move and the needle entry at the same location. Since the origin then exists implicitly, PEC size and thumbnails use 0 through maximum coordinates. v6 CEmbOne/CSewSeg use the same width/height and transformation. The minimal v1 22byte header has no separate transformation field.

## App paths

| Path | Actual payload generation |
|---|---|
| Direct USB / SAF USB | `DirectUsbExporter.export` / `UsbExporter.export` → `DesignFormats.preparePes` |
| Viewer SAF save / cache share | Project PES generation in `readViewerDesign` → `ViewerViewModel.export` → `PesExport.prepare` |
| Editor PES save | `EditorViewModel` → `PesWriter.write(project.flatten(), settings)` |
| PEC/DST → PES conversion | `PesExport.prepare` → `PesWriter.write` |
| Existing PES | Preserves original bytes(no conversion/repositioning) |

Default-setting Writer v1/v6 performs top-left correction on all generation/conversion paths. Explicit `inkstitchCommands=true` retains existing behavior as an original-comparison profile unused by the app. Importing previously saved PES with an incorrect origin also follows original-preservation policy, so re-export from the project is required.

## Automated regressions

- `PesViewportTest`: literal coordinate expectations for all public Writer overloads, top-left PES round trips, PEC width/height, v6 transformation, implicit origin, input immutability.
- `PesOutputRoutesTest`: byte equality for actual project flatten/viewer payload/shared USB preparation/shared SAF-sharing conversion functions, PEC conversion, original PES preservation.
- Existing generator tests retain original-coordinate expectations. Only PES reread expectations are updated to final-output coordinates.
- `GatePolicyParityTest`: compares changed synthetic PES bytes against SHA/metrics remeasured with Python gate v6. The long-first-stitch case still leaves 20mm travel after origin translation, preserving blocking validation.

Run: `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug`

Machine retest files: `/tmp/myembro-sewtest/s23/S23SAM4.PES` and PNG. The coordinator independently confirmed needle-order/translation agreement with SAM3, bounds 0..59.4 / 0..55.5mm, and 0-length commands 0. Python gate output is recorded as `PASS 1/1` in `S23SAM4-gate.txt` in the same directory. Private corpus and temporary artifacts are not included in the repository.
