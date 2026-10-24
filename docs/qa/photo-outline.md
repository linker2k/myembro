# Photo → outlines (one-color bean / 3-pass running)

## Behavior/evidence

- Based on main `b95f2be`. Add only two options at step 2: **Silhouette / Outline**.
  Selection does not recalculate existing Wu 2-color separation/automatic noise suppression/Potrace contours.
  Silhouette uses existing tatami/underlay; outline uses bean on all the same boundaries (including holes).
  Besides existing detail, no stitch-length/repeat/lock/filter-selection UI.
- Upstream: Ink/Stitch v3.3.0 `b0edd96311ece82ee48816dc93466274b12c2a9c`,
  `lib/elements/stroke.py`, `lib/stitches/running_stitch.py`, `lib/stitch_plan/lock_stitch.py`.
  Call already validated `Stroke`/`RunningStitch`/S1 ports; add no other stitch algorithms.
- **`bean_stitch_repeats=[1]`**: upstream `bean_stitch` adds one round trip per base stitch.
  Total passes: `1+2*1=3`. `[3]` would mean 7 passes, so do not use it.
  This parameter choice follows the user's physical redwork request for '2–3 outline lines'.
  Other values retain upstream `StrokeParameters` defaults: `repeats=1`, length 2.5mm, tolerance 0.2mm.
- Separate disconnected contours with upstream `trim_after=true`; S1 applies upstream default `half_stitch`
  start/end locks. Do not create manual locks/centerlines/straight connecting stitches.
- Path order uses **existing dependency JGraphT 1.5.2's NearestNeighborHeuristicTSP** unchanged.
  [Official algorithm description](https://jgrapht.org/javadoc-1.5.2/org.jgrapht.core/org/jgrapht/alg/tour/NearestNeighborHeuristicTSP.html).
  Fix the first contour and use closed-boundary start/end anchor distances. Do not sew the returned tour's final
  return to origin. Adopt only when actual open-order cost decreases compared with the original.
  No new routing solver/custom heuristic; no claim of globally shortest paths or upstream Ink/Stitch AutoRun equivalence.
  Also do not cut boundaries at nearby points to shift start positions.
- SVG flattening 0.1mm, hoop fitting including completed locks, PES origin/header/0-length checks,
  and SewoutCheck use the existing silhouette common path. No separate relaxed gate.

## Local measurements (private input: numbers only)

Same supplied input, JVM / centered 192×192 / 99.9mm canvas / existing automatic median/area filter:

| Item | Outline |
|---|---:|
| Decode | 118.0ms |
| Preprocess | 26.4ms |
| Wu | 15.2ms |
| Potrace/curve fitting | 43.7ms |
| Contours | 12 |
| bean/S1 | 70.9ms |
| PES | 50.8ms |
| Stitches | 1,167 |
| Inter-contour jump-anchor distance | 376.6→276.7mm |
| SewoutCheck | 0 blockers |

JVM/Android results can differ with different decoders. Single input/single run,
not physical-machine timing or certification of all photo quality. Do not commit private originals/derived PNGs/designs
or include them in screenshots. Local numeric log: `/tmp/myembro-private/portrait-outline-jvm.log`.

## Reproduction

Synthetic input uses the existing CLI's directly generated 4000×3000 image. Normal default mode
remains silhouette. Outline CLI specifies comma-separated fourth input path and fifth `outline`:

```
./gradlew :engine:photoTiming -PphotoTimingArgs=192,filtered,full,/tmp/myembro-photo-performance/synthetic-12mp.jpg,outline
./gradlew :engine:test --tests '*RedworkPipelineTest'
```

Tests: exact equality of bean coordinate lists versus upstream single running/calculated 3-fold passes,
upstream half_stitch locks/trims, library sorting completeness/determinism/nonincreasing jump distance,
ring-hole preservation and actual PES P1–P8/SewoutCheck.

## Android/screen verification

API 35 x86_64 emulator, same supplied local input (not displayed onscreen):

| Item | Outline |
|---|---:|
| Decode | 12.6ms |
| Preprocess | 56.4ms |
| Wu | 57.3ms |
| Trace/curve fitting | 110.3ms |
| Contours | 7 |
| bean/S1 | 107.3ms |
| PES and subsequent check interval | 210.8ms |
| Stitches | 1,074 |
| Jump-anchor distance | 121.9→121.9mm (original order retained) |
| SewoutCheck | 0 blockers |

`INSTRUMENTATION_CODE: -1`; log `/tmp/myembro-private/portrait-outline-android.log`.
Deleted temporary private-input copy immediately after instrumentation. Generated no private result images/screenshots.

Actual home→photo picker→step 2 mode switching→step 3 UI XML/logcat assertions with synthetic image:
```
MODE_SWITCH_REUSES_CONTOURS
OUTLINE_RESULT_PASS (349 stitches, Sewing check · Pass, Edit / Send to USB)
SILHOUETTE_OUTLINE_SWITCH_SMOKE_PASS
```
Confirmed no quantization/tracing/stitch-generation logs on switching and reuse of the same contour cache.
Screenshots use only directly generated synthetic images:
- `/tmp/myembro-photo-outline-preview.png`
- `/tmp/myembro-photo-outline-result.png`
- `/tmp/myembro-photo-silhouette-mode-preview.png`

## Final checks

```
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :app:assembleDebugAndroidTest -PmyembroTestRunner=io.github.linker2k.myembro.ui.photo.PhotoTimingInstrumentation
BUILD SUCCESSFUL in 1m 30s
113 actionable tasks: 9 executed, 104 up-to-date
```

Engine 643, app debug/release 217 tests each / failures/errors/skips 0.
ko/en photo strings: matching 51 keys. Log `/tmp/myembro-outline-check.log`.

Independent local gate for synthetic ring (actual output bytes):
```
/tmp/myembro-quality-gate/quality-gate check /tmp/myembro-port-out/editor/photo-outline/ring.pes --style running --json /tmp/myembro-photo-outline-gate.json
PASS /tmp/myembro-port-out/editor/photo-outline/ring.pes within five-condition checks
PASS 1/1 cases passed
```

The above are code/local-instrumentation decisions, distinct from actual sewout/CI approval.
