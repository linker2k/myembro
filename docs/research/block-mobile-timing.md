# Android block generation timing

Source: `agent/block-pipeline`, parent `0757906`. This adds instrumentation only;
no engine algorithm, parser security option, default, or product limit is changed.

Device: `emulator-5560`, `myembro-36`, Android API 36,
`sdk_gphone64_x86_64` (x86_64 ART emulator). This is not physical-phone timing.
The requested 5556 emulator was shut down by the manager because of host memory
pressure; the manager authorized short runs on 5560 instead. Host contention and
cold/JIT/cache state are not controlled. There is no excluded warm-up run.

## Measurement

Inputs are generated SVG paths: a 40×40mm square and a 40mm-diameter circle made
of two SVG arcs. The application's SVG parser supplies the actual flattened
geometry; it is not the catalog's 256-edge JTS circle. No private files are read.

Each sample constructs a fresh ShapeObject with explicit block overrides through
the public codec, then times only `localPattern()` with
`SystemClock.elapsedRealtimeNanos()`. Preparing/decoding the object, generating its
original default pattern, and validating overrides happen outside the timer.
The measured lazy renderer includes VectorArt geometry processing, fill underlay,
top fill, routing and stitch-plan finishing. It excludes output PES serialization,
SewoutCheck, UI drawing and I/O. Therefore these values are not direct equivalents
of the pure-fill JVM catalog timings at `bc7eef9`.

All backend settings use existing BlockStitch/FillParameters defaults. The only
choices are fill method and the requested tile IDs. `automatic_angle=false` with
the unchanged default angle selects an explicit tatami override, ensuring its
lazy render is measured rather than returning the existing default cache.

## Reproduce

The project uses standalone Instrumentation runners without AndroidX test dependencies.
The class reports one test to the Android test runner, logs per-sample and CSV rows
under `BlockGenerationTiming`, and fails if any sample throws. It writes the CSV
inside the test app; UTP may uninstall that app after execution, so retain its
logcat/Gradle test output before relying on that file.

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
export ANDROID_HOME=/home/gosh2/Android/Sdk
export ANDROID_SERIAL=emulator-5560
./gradlew --offline --max-workers=2 :app:connectedAndroidTest \
  -PmyembroTestRunner=io.github.linker2k.myembro.ui.editor.BlockGenerationTimingInstrumentation \
  -Pandroid.testInstrumentationRunnerArguments.skipCircular=true
```

The initial offline attempt lacked cached Android UTP dependencies; a retry without
`--offline` resolved those existing Gradle dependencies. No dependency versions or
build files were changed.

For separated circular measurements, replace `skipCircular` with
`-Pandroid.testInstrumentationRunnerArguments.method=circular_fill`,
`-Pandroid.testInstrumentationRunnerArguments.shape=circle` (or `square`), and
`-Pandroid.testInstrumentationRunnerArguments.timeoutSeconds=100` (or `150`).
The optional watchdog is a total instrumentation-run budget, including preparation,
not a per-sample limit, product limit or performance acceptance threshold. Budget
expiry is a failed test, never a successful generated sample. The default invocation
without filters requests all 12 combinations, each with 3 attempts.

## Results

See the adjacent CSV for raw samples and the table below for successful-generation
medians. Exception latency is retained separately and must not be presented as
successful generation time. Device failures or missing samples are not replaced
by JVM estimates. These performance tests make no sewing-safety PASS claim.

| Backend | Square median (ms) | Circle median (ms) |
|---|---:|---:|
| tatami_fill | 1391.375 | 1114.845 |
| contour_fill | 618.801 | 672.095 |
| circular_fill | 29085.536 | 27801.817 * |
| meander_fill/NC5-11a | unavailable: XML exception (3/3) | unavailable: XML exception (3/3) |
| meander_fill/P3-1 | unavailable: XML exception (3/3) | unavailable: XML exception (3/3) |
| meander_fill/N3-11a | unavailable: XML exception (3/3) | unavailable: XML exception (3/3) |

* Circle circular has exactly 3 completed samples across 2 interrupted test runs
(23715.458, 29862.936, 27801.817ms), rather than 3 consecutive samples in a passing
run. A Python `statistics.median` calculation over those completed sample log lines
gives 27801.817ms. Both runs subsequently exceeded their total 30s/100s budgets;
the connected test verdict is **FAIL**, not PASS. An earlier full-matrix run was
manually stopped after square circular had not reported its 3-sample row for over
2 minutes. The later isolated square run completed all 3 samples normally.

All 18 meander attempts (2 shapes × 3 tile IDs × 3 runs) threw
`javax.xml.parsers.ParserConfigurationException:
http://apache.org/xml/features/disallow-doctype-decl`. Their recorded 371–480ms
values are exception latency, not generated-design time. The current Android XML
parser rejects the feature requested while loading tile SVGs; this test does not
weaken XML security or change the engine to bypass that failure.

Commands used the same `connectedAndroidTest` class with the filters above.
Actual Gradle/test outputs:

```text
skipCircular=true:
  1/1 tests completed; 1 failed (18 caught generation exceptions)
  BUILD FAILED in 1m 21s
method=circular_fill shape=circle timeoutSeconds=30:
  FAILED: MEASUREMENT_TIMEOUT after 30s
  BUILD FAILED in 49s
method=circular_fill shape=circle timeoutSeconds=100:
  FAILED: MEASUREMENT_TIMEOUT after 100s
  BUILD FAILED in 2m 7s
method=circular_fill shape=square timeoutSeconds=150:
  1/1 tests completed; 0 failed
  BUILD SUCCESSFUL in 2m 9s
```

[Raw measurements](block-mobile-timing.csv) and
[dedicated logcat/test output](block-mobile-timing-output.txt) preserve the evidence,
including the earlier attempts. The full requested matrix has **not passed**.
The tests ran on 5560; validation on 5556 remains deferred. After measurements,
only the app/test-app processes were force-stopped; no emulator was started,
stopped or reconfigured by this task.
