# Automatic non-border satin coverage

Automatically classified picture/SVG satin is checked against its own generated needle segments. If uncovered area remains beyond the distance below, only that non-border region tries the existing default tatami generator. No stitching algorithm, generator parameter, palette assignment or P1–P8 gate changes. Explicit block overrides and lettering choices are not reclassified. The color-photo generator currently chooses tatami/running, not satin; its defaults remain unchanged.

## Border exemption (user decision)

Border satin remains satin despite gaps. Source information is used without a numerical shape/color heuristic:

- `CLOSED_STROKE`: existing SVG stroke provenance and all source subpaths marked closed.
- `POLYGON_RING`: the existing JTS polygon has an interior ring, including holes produced by the existing visible-paint/overlap removal.
- `NONE`: neither source/topology indicator exists; automatic satin is eligible for coverage fallback.

There is no semantic "border" flag on filled SVG paths. An interior ring is therefore a conservative topology proxy, not proof of artistic intent: a ring-like glyph can also be exempt. No width, color or area cutoff is invented to guess intent. The crown black outline is a polygon ring; skull region-5 jaw is not. Existing generation-failure and sewout-validation fallbacks still apply to borders; the exemption never releases an unsafe file.

## Distance basis and limits

Ink/Stitch v3.3.0 `lib/elements/satin_column.py` at `b0edd96311ece82ee48816dc93466274b12c2a9c` has default `zigzag_spacing_mm=0.4` (`SatinParameters.kt`). A regular 0.4mm grid has nearest-row distance at most 0.2mm. PEC rounding moves an endpoint at most 0.05mm in each axis, or `sqrt(2)*0.05mm` in Euclidean distance; interpolation between endpoints cannot exceed that displacement.

The inclusive accepted distance is thus `SatinParameters().zigzag_spacing_mm/2 + sqrt(2)*0.05`, about **0.270711mm**. This is a derived coordinate policy, not an upstream rejection feature. Physical thread diameter/coverage on fabric remains unconfirmed and is not used as a threshold.

Existing generated-pattern postprocessing supplies quantized 0.1mm needle coordinates. JTS standard round-cap buffer and polygon difference compute area outside this distance. Travel jumps never provide coverage, and source holes are excluded. Any positive uncovered area rejects eligible automatic satin; no invented area/percentage tolerance hides a gap. JTS's default polygonal cap approximation is conservative and can trigger a fallback on a small cap sliver.

Only the region's own sewn segments count. Adjacent black stitches cannot conceal missing gold paint. This differs from the earlier structure report's whole-design 0.2mm ROI metric. The new area must not be compared directly to that diagnostic as though ownership, distance and ROI were identical.

The app computes only the pre-satin decision. Post-tatami coverage is an offline diagnostic, computed from the final owned region pattern; it must not add an extra uncancellable buffer to interactive generation. `Part.satinCoverage` stores the pre-area and coverage-fallback flag. `Part.satinBorder` records the exemption source; `Part.areaMm2` records the original visible polygon area.

## Reproducible measurements

External synthetic inputs, frozen harness/classes, flat PNGs, per-region lists and comparison tables are in `/tmp/myembro-satin-coverage/`. No private artwork or generated sample pack is committed. The previous superseded all-satin policy is archived separately in `/tmp/myembro-satin-coverage-pre-border/`.

The preceding small-region measurement snapshot predates the stable `small-region-*` IDs. `MapRegions.java` enumerates all source candidates through the existing generation-free region override callback to map current IDs to legacy global ordinals. Each SVG asserts the candidate count equals its frozen baseline count; emitted source sets are then compared. This prevents a region-number shift from corrupting the before/after method attribution.

Report definitions:

- Coverage fallback lists are split into **border** and **non-border**, with source shape, old/new method, border source, area and diagnostic areas. Border coverage-triggered fallback must be empty; any existing safety/generation fallback is distinguished.
- Uncovered-area improvement totals refer only to eligible non-border regions that were final satin in the baseline. Exempt border gaps are not claimed to be repaired.
- Satin area share is satin target polygon area / all emitted target polygon area, weighted across the 126 filled-SVG inputs. The 10 running line-art inputs have no painted area and are excluded from this denominator.
- Satin needle share is owned satin penetrations / all output penetrations across all 136 designs. Region ownership sums are asserted equal to the generated pattern's needle count. Total needle change also compares all 136 outputs to the preceding 1,428,827-needle baseline.
- Flat stitch previews use the same app `RenderData`/`StitchCanvas` primitive rules on each side; no realistic renderer is used. Top-left export translations can alter antialiasing phase, so PNG intensity is not itself a coverage measurement.

## Verification

`SatinCoverageTest` exercises actual JTS n−1/n/n+1 floating-point boundaries, jumps, holes, incomplete non-border satin→existing tatami, preserved complete satin, the real open V-corner route, and deliberately incomplete border satin staying satin. Existing generator/oracle and app tests stay enabled.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ANDROID_HOME=/home/gosh2/Android/Sdk ./gradlew test lint --parallel --max-workers=2
```

Final code verification: `BUILD SUCCESSFUL in 9m 55s`; XML totals engine 1,044 + app debug/release 969 + MCP 10 = **2,023 tests, 0 failures/errors/skips**. Android lint: **0 errors/fatals**, 177 warnings. Full output: `/tmp/myembro-satin-coverage/full-check.log`. Cancellation regression completed in 1,551ms (debug) and 2,577ms (release), within the existing 3,000ms bound.
