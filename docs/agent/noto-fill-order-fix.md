# Deterministic polygon representation for TTF fill

2026-10-25, `agent/font-snapshots`, based on `810715d`. This fixes the
[observed ring-start divergence](noto-fill-parity.md) by ordering representation,
without replacing any fill/stitch algorithm.

## Boundary and upstream basis

`PolygonRegion.fromPaths` still performs the existing noding, JTS polygonization,
even-odd/nonzero classification and union. `CanonicalPolygonOrder` now rotates
closed rings to the lexicographically smallest **existing** (x,y) coordinate,
preserving direction and every vertex bit. Tied minimum coordinates compare the
remaining cyclic sequence. Holes and polygon lists are lexicographically sorted.
Ordering occurs immediately after Polygonizer and again after the final union;
the latter can otherwise choose another ring start. No precision snap, smoothing,
orientation reversal, offset, stitch length or density rule is introduced.

This is a JTS adapter difference, **not a claim that Ink/Stitch uses lexicographic
minimum starts**. In the pinned Ink/Stitch v3.3.0 source
`b0edd96311ece82ee48816dc93466274b12c2a9c`,
[`fill_stitch.py original_shape:934–941`](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/fill_stitch.py#L934)
builds Shapely polygons from ordered flattened SVG paths after sorting by area.
`shape:946–954` applies make-valid/precision handling, and
[`tatami_fill.py:77`](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/stitches/tatami_fill.py#L77)
then uses boundary order for graph projection/routing. Our extra even-odd JTS
Polygonizer bridge had introduced identity-HashSet order. Fixing that bridge's
representation removes this source of entropy before `FillRouting.tagNodes` and
`outlineEdges`. No upstream Python was executed and no new precision settings or stitching algorithms
were added. The source checkout was read only.

The difference is also recorded in [the shared port table](../inkstitch-port.md#differences).
Tests cover cyclic input rotations, swapped holes/components, idempotence,
topological equality, area, vertex bits and clockwise/counterclockwise preservation.

## Verified output

- Final JVM and emulator-5556 generate **10/10 each**, all 20 PES hashes identical:
  `054edaac486d74a147282ecbad3b780fca1e958c41be4c6516176fd42fc58f51`.
- Noto default/Thin `Ab12`, 15mm: **949 needles, 2916 bytes**. The old nondeterministic
  examples were 958/962 needles and 2934/2942 bytes; those were not fixed canonical
  targets. The reference was regenerated through the actual app `renderLettering`.
- All **6** Android snapshot object JSON and PES cases now compare strictly by bytes,
  including Jua/Noto Latin FILL. No environment-variable skip/tolerance remains.
  Jua `Ab12` is 3196 bytes; all 4 Hangul satin references remain unchanged.
- Engine fill/geometry/snapshot regressions: **141 tests**, zero failures/errors.
  App unit tests: **247**, zero failures/errors.
  Official rectangle/circle/hole checks retain count difference <=5% and Hausdorff
  <=0.2mm; observed counts 570/571, 661/662, 823/824 and Hausdorff 0.141422mm.
- External five-condition gate: **430/430 PASS before and after**, same threshold
  SHA-256. Existing fill regression PES: **4/4 PASS** (three official-default
  shapes and the Jua Korean app regression). Actual Noto/Jua Latin text PES also pass **2/2**. No thresholds or gate code changed.
  [Machine-readable results and commands](noto-fill-order-results.json).

These are automated checks, not a new real-machine sew-out approval.

## 430-case byte and needle-count changes

Baseline is the geometry at `810715d`, generated once before the production fix;
it was itself nondeterministic. Both runs use the same current writer/count harness.
The old fixture asserted pre-export count equals read-back count, but the current
writer's P4 terminal cleanup removes stationary motions. The fixture now compares
against `PesWriter.prepareForOutput(pattern).stitchCount`, exactly the writer's
existing contract. This **test-only assertion correction does not alter PES bytes**.

**70/430 files changed; 52/430 needle counts changed**. The full filename,
before/after count, signed delta and both SHA-256 values are attached in
[noto-fill-order-changes.json](noto-fill-order-changes.json).
A byte change with zero count delta is still reported. All other 360 files are
byte identical. Baseline and new outputs stay separately under
`/tmp/myembro-ring-fix/{before,after}/`.

```sh
# Before editing production geometry, after aligning only the round-trip assertion:
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:generateTatamiSweep \
  -PtatamiSweepDir=/tmp/myembro-ring-fix/before
# After ordering fix:
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:generateTatamiSweep \
  -PtatamiSweepDir=/tmp/myembro-ring-fix/after :engine:test \
  --tests '*CanonicalPolygonOrderTest' --tests '*FontFillProbeTest' \
  --tests '*AndroidFontSnapshotTest' --tests '*Fill*Test' \
  --tests '*DefaultTatami*Test' --tests '*TatamiLetteringIntegrationTest'
/tmp/myembro-quality-gate/quality-gate check \
  --matrix /tmp/myembro-ring-fix/after/generation.json \
  --json /tmp/myembro-ring-fix/after/gate.json
/tmp/myembro-quality-gate/quality-gate check engine/build/tatami-oracle engine/build/s3-fill \
  --json /tmp/myembro-ring-fix/fill-regression-gate.json
python3 tools/font-snapshots/compare-fill-sweep.py \
  /tmp/myembro-ring-fix/before /tmp/myembro-ring-fix/after /tmp/changes.json
# Cross-runtime repeat reproduction (installs only on 5556):
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 tools/font-snapshots/run-fill-probe.sh
# Fresh actual app PES snapshots; production outline/raster data unchanged:
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 tools/font-snapshots/capture.sh
```

## Chicken Little KOR: verification only

This catalog entry is **Ink/Stitch SVG**, not TTF: it has no cmap table. The bundled
`fonts/bundled/inkstitch-chicken-little/ltr.svg` has **138 `GlyphLayer-` labels**,
**0 Hangul glyphs**, and exactly the same glyph set as `inkstitch-catalog.json`.
The file SHA-256 matches the catalog pin
`f4c08d4e3d79470febc7c5217a4741f37e8293d53fdc45270309aa09cdfdbc75`.
Its `font.json` also declares `latin` and identifies the original font as Henny Penny.
Thus **the file and Latin-only catalog data agree**. The suffix KOR does not prove
Hangul coverage; its intended meaning is unconfirmed. No catalog/name/font changed.
