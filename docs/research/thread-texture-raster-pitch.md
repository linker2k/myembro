# Texture raster pitch measurement (2026-10-27)

Only the **input raster scale** changes. DrawingBotV3 Free algorithm/defaults/seed, Wu/Brother matching, Ink/Stitch running/locks, hoop checks, generated density maximum 15 and the existing SE400 stitch-count limit remain unchanged.

## Reproduce

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk ./gradlew :engine:sweepThreadTextureRaster -PtexturePitchOutput=/tmp/myembro-texture-pitch-floor
```

This explicit local task uses only our Kotlin engine; it never downloads/executes DrawingBot or updates policies/goldens automatically. The full sweep is separate from normal CI. The three fixtures are shared with the previous 192px experiment in `TextureSyntheticFixtures` (ramp, disk on white, red/Prussian Blue split). Their only varying input is raster size: 0.20..0.60mm/px in 0.05 steps × frame 30/40/60/90mm = 108 cases. Wu uses 4 requested colors for the ramp and 2 for the others, unchanged.

Pixel count is `floor(frameMm / requestedPitchMm)`, so the actual `frameMm / pixelCount` is **not finer** than the chosen minimum. The test records both requested/actual pitch and integer raster edge. A preliminary ceil experiment was discarded for policy selection because nondivisible widths could produce a slightly finer actual pitch; the table below uses floor only. This rounding changes no plotting or stitching parameters.

A row passes only if candidate SewoutCheck has no blocker and completes analysis, the default PES writer accepts it, actual encoded PEC viewport passes, and exported-PES SewoutCheck has no blocker. Existing machine stitch-count blockers are retained, not treated as density passes. Blocked candidates return no PES. `max_peak` is the larger candidate/exported peak when exported (candidate only when blocked). The full data also preserves block codes.

The measured run took **14.249630216 seconds** inside the JVM; Gradle reported `BUILD SUCCESSFUL in 15s`. Generation milliseconds in the raw table include palette, plotting, adaptation and gate/output checks; these are single-JVM measurements, not phone latency or actual sewing.

## Results

| Pitch mm/px | Passing cases | Maximum 0.5mm-cell penetrations | Selection |
|---:|---:|---:|---|
| 0.20 | 3/12 | 24 | excluded |
| 0.25 | 9/12 | 19 | excluded |
| 0.30 | 9/12 | 18 | excluded |
| 0.35 | 11/12 | 14 | excluded |
| 0.40 | 11/12 | 16 | excluded |
| 0.45 | 12/12 | 13 | normal / fine floor |
| 0.50 | 12/12 | 8 | coarse candidate |
| 0.55 | 12/12 | 11 | coarse candidate |
| 0.60 | 12/12 | 8 | measured, not a preset |

Overall: 91/108 PASS, 17 blocked. All 12 cases at each selected normal/coarse pitch pass (36/36 selected combinations).

Complete 108-row results with raster size, actual scale, stitches, peak and findings: [results.tsv](../../engine/src/test/resources/texture-pitch/results.tsv).

### Failure list (no threshold changes)

| Pitch | Picture | Frame mm | Peak | Blocking rules |
|---:|---|---:|---:|---|
| 0.2 | ramp | 30.0 | 21 | DENSITY_HOTSPOT |
| 0.2 | ramp | 40.0 | 22 | DENSITY_HOTSPOT |
| 0.2 | ramp | 60.0 | 21 | DENSITY_HOTSPOT |
| 0.2 | ramp | 90.0 | 20 | DENSITY_HOTSPOT,STITCH_LIMIT_EXCEEDED |
| 0.2 | circle | 90.0 | 14 | STITCH_LIMIT_EXCEEDED |
| 0.2 | two-color | 30.0 | 17 | DENSITY_HOTSPOT |
| 0.2 | two-color | 40.0 | 24 | DENSITY_HOTSPOT |
| 0.2 | two-color | 60.0 | 21 | DENSITY_HOTSPOT,STITCH_LIMIT_EXCEEDED |
| 0.2 | two-color | 90.0 | 24 | DENSITY_HOTSPOT,STITCH_LIMIT_EXCEEDED |
| 0.25 | ramp | 90.0 | 19 | DENSITY_HOTSPOT |
| 0.25 | two-color | 40.0 | 16 | DENSITY_HOTSPOT |
| 0.25 | two-color | 90.0 | 16 | DENSITY_HOTSPOT,STITCH_LIMIT_EXCEEDED |
| 0.3 | ramp | 30.0 | 18 | DENSITY_HOTSPOT |
| 0.3 | ramp | 60.0 | 16 | DENSITY_HOTSPOT |
| 0.3 | two-color | 90.0 | 13 | STITCH_LIMIT_EXCEEDED |
| 0.35 | two-color | 90.0 | 12 | STITCH_LIMIT_EXCEEDED |
| 0.4 | ramp | 30.0 | 16 | DENSITY_HOTSPOT |

## Generated policy and engine integration

The sweep selects the **smallest tested pitch whose entire 12-case group passes**, then the next 2 larger all-passing pitches. It does not assume density is monotonic in pitch: rasterization/seed paths can change discretely. Output `policy.json` was copied unchanged to `engine/src/main/resources/texture/raster-policy.json`; unit tests recompute selection from the frozen 108-row measurement and require exact equality.

- Normal: **0.45mm/px**.
- Coarse candidates: **0.50 and 0.55mm/px**, both 12/12 PASS.
- Fine minimum: **0.45mm/px**, currently the same as normal; finer/unmeasured values are rejected.
- 0.60mm/px also passed, but is not one of the requested 2 coarse presets.

`TextureRasterPolicy.edge(frameMm, pitch)` computes the physical edge and exposes the data-backed choices. Existing detail indices map 0→first coarse, 1→normal, 2→fine minimum; the second coarse value is explicitly available to callers. `ThreadTextureAdapter.generate` keeps the shared photo correction/minimum-width/hoop-width policies but validates against this **texture-only physical raster limit**, replacing the old 192px cap. It never silently resizes an already corrected raster. Future photo-window integration must request the texture edge before correction/generation; ordinary block-fill raster policy stays unchanged. No app/UI code is changed here.

The previous 192px ramp at 40mm remains blocked (peak 19), now asserted explicitly, including failure of `Result.pes()`. The normal-pitch three-picture × 40/60/90mm CI cases all require SewoutCheck, PEC viewport and exact PES stitch/thread round-trip success. Original plotting oracle tests remain unchanged.

## Scope and blocking guidance

This is evidence for the **3 mathematical pictures and 4 measured frame widths**, not proof for all photos, aspect ratios, widths or fabrics. The pre-existing photo minimum of 20mm is not redefined by this 30mm-start sweep. Every future real-photo result still passes mandatory generated-pattern/PES gates; no fallback changes the algorithm, seed, density, locks or threshold to force acceptance.

The public policy carries the requested Korean block guidance for the app integrator: **“너무 빽빽해요: 크기를 키우거나 디테일을 낮추세요”**. Show it alongside structured findings and keep save/transfer blocked. UI localization and binding remain with photo-window integration; no claim is made that this engine-only unit has changed the app screen.

Verification: `./gradlew :engine:test` — **986 tests, 0 failures/errors**, `BUILD SUCCESSFUL in 1m 27s`. Includes original `SketchOracleTest` 10/10, adapter 8/8 and policy 2/2. Log: `/tmp/myembro-texture-pitch-engine-test.log`; sweep log: `/tmp/myembro-texture-pitch-floor.log`.
