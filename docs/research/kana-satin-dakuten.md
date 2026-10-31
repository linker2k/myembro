# Kana satin and voiced marks

Baseline: `origin/main` `f1db5974` (2026-10-31). This is a **kana-only** adapter, not a replacement for the verified Hangul implementation. `HangulSatin`, `CenterlineGlyph`, `HangulComponentOrder`, AutoTrace and Ink/Stitch algorithm files are unchanged. The existing Hangul branch, font weight selection and output parameters are retained.

## Prior implementations and scope

The existing AutoTrace thinning → trace → fit → flatten and Ink/Stitch convert-to-satin → auto-route implementations are reused. The Hangul 50% vertical-overlap row DAG is reused **only for the kana body**. A dakuten can overlap a short body stroke and produce contradictory precedence edges; the pre-change 1,584-case sweep has 69 such generation failures. No new stroke-order inference, kana font data or new stitching algorithm is introduced.

The additional component classification is an explicit user-approved policy (2026-10-31), rather than an upstream Ink/Stitch rule. Scope is U+3041–U+3096 and U+30A1–U+30FA, after the existing NFC/newline normalization. Whitespace is allowed; mixed Hangul/Latin/kanji strings and standalone combining marks do not enter the new branch. Hangul remains U+AC00–U+D7A3.

## Geometric detection and measured limits

Only a character whose NFD decomposition ends in U+3099 (dakuten) or U+309A (handakuten) is eligible. This also handles voiced `ヴ` and `ヷ`–`ヺ`; unvoiced dots are not guessed to be marks.

Use the largest 8-connected ink component as the body anchor. A candidate must be a separate component with fewer ink pixels than that anchor, its left edge at or right of the anchor's horizontal midpoint, and its bottom edge at or above the anchor's vertical midpoint. These midpoint predicates explicitly implement **small, upper-right**; they are not a claim about a universal typographic threshold. Require exactly 2 candidates for dakuten or 1 for handakuten. Any ambiguity uses RUNNING instead of inventing a split through connected ink.

Measured at the unchanged 128px / 8px-pad raster contract, three public OFL fonts, weight 400, 176 kana each (528 glyphs):

| Quantity | Measurement |
|---|---:|
| Voiced glyphs | 168 (138 dakuten, 30 handakuten) |
| Geometrically accepted voiced glyphs | 145 |
| Ambiguous / joined-component voiced glyphs | 23 |
| Accepted mark left / anchor width, relative to anchor left | 0.518987–1.682927 |
| Accepted mark bottom / anchor height, relative to anchor top | −1.256410–0.479592 |
| Accepted mark / anchor ink pixel count | 0.039018–0.838983 |

The measured envelope supports this conservative classifier on these fonts only. It does **not** prove a general classifier for every user font. Examples routed to RUNNING include joined marks in `ぼ`, `ぽ`, `プ`, and ambiguous fragment layouts in `だ`, `バ`, `パ`. No touching component is separated heuristically.

After classification, body components use the existing row DAG; mark components are appended last in left-to-right order. The measured `ご` bounding boxes are fixed in `KanaSatinTest`: the previous DAG raises a cycle, whereas the new order is body component IDs `2,3`, then marks `1,0`.

## Width and stitching

Measure each accepted mark's **original vector rings**, including the hole of handakuten, using the existing `HangulSatin.measuredWidthSource` / `StrokeWidthMeasurement` Voronoi interior-circle median at the existing 15mm reference and 0.1mm boundary sampling. Do not measure pixel staircase boundaries: those introduce many corner samples that bias the median toward one pixel. Select complete existing `PolygonRegion` ink polygons, keeping exterior and interior rings together. Selecting an inner ring alone would measure a hole as ink; the synthetic counter-only regression rejects that case. Complete polygons must fit within the component box plus 1 raster pixel for Paint edge quantization; if isolation is uncertain, use RUNNING. Source coordinates retain the original uncentered baseline `(0,0)`; Android supplies contours with the advance offset restored, exactly as the existing glyph-layout contract does.

At the requested height, mark width **≥1.0mm** uses the existing satin-column conversion and routing; width **<1.0mm** uses the existing Ink/Stitch `Stroke` port along its fitted centerline. This width is a sampled median, **not a proven pointwise minimum**. The 1.0mm boundary is the user's approved safety policy; upstream also recommends satin strokes wider than 1mm: [Ink/Stitch v3.3.0 `stroke.py`, lines 31–39](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/stroke.py#L31-L39). Real-machine kana sew-out validation is still pending.

Triple running uses `bean_stitch_repeats=[1]`: upstream explicitly defines 1 as forward/back/forward, while 3 would make 7 passes. All other running parameters retain the port defaults (2.5mm length, 0.2mm tolerance); locks and trims use the shared S1 planner. See [upstream `stroke.py`, lines 103–125](https://github.com/inkstitch/inkstitch/blob/b0edd96311ece82ee48816dc93466274b12c2a9c/lib/elements/stroke.py#L103-L125). `KanaSatinTest` compares the actual stitches and PES bytes against that existing `Stroke` call.

A failed kana extraction/conversion falls back to the existing outline RUNNING preparation for the text object. The screen displays the resulting RUNNING method, not an inaccurate satin label. This fallback is confined to kana and does not swallow Hangul failures. Offline editable objects persist the kana centerlines and path-indexed mark widths; changing height reevaluates the mark method. All P1–P8 checks and thresholds are unchanged.

## Reproduction

Fonts are not committed. Local sweep input directory: `/tmp/myembro-cjk-satin-probe/fonts/`.

| Font file | SHA256 |
|---|---|
| `NotoSansJP.ttf` | `c2f3b4d463500a2ddcd3849cded1fceeb9fd6d1c32e6cbecd568453ba50fc68f` |
| `NotoSansSC.ttf` | `a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da` |
| `NotoSansCJKjp-Regular.otf` | `68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5` |

The checked-in harness contains no private designs or downloaded executable code. It uses native Robolectric API 34 / Android Paint, weight 400, the same reference glyph selection (`자` if available, otherwise `H`), SE400 checks, and the same 176-codepoint kana matrix as `/tmp/myembro-cjk-satin-verify.md`.

```sh
# Run this branch's script against the detached, untouched baseline and the implementation worktree.
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
  tools/verification/kana-satin/run.sh before /tmp/myembro-kana-dakuten-baseline /tmp/myembro-kana-dakuten/kana-before
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 \
  tools/verification/kana-satin/run.sh after "$PWD" /tmp/myembro-kana-dakuten/kana-after
# 11,172 Hangul syllables × 15/20/30mm; each record is a PES SHA256 or a pre-existing generation error.
tools/verification/kana-satin/run.sh hangul /tmp/myembro-kana-dakuten-baseline /tmp/myembro-kana-dakuten/before
tools/verification/kana-satin/run.sh hangul "$PWD" /tmp/myembro-kana-dakuten/after
python3 -I tools/verification/kana-satin/compare-hangul.py /tmp/myembro-kana-dakuten/before /tmp/myembro-kana-dakuten/after
./gradlew test lint
```

Results are recorded below after the complete runs; partial runs cannot pass the exhaustive comparator. A missing/duplicate case, changed error state/message or even 1 changed PES hash makes the comparison exit nonzero. The 30 representative baseline hashes (10 syllables × 3 heights) are also checked by the normal app unit tests.

## Recorded results (2026-10-31)

Native Android/Robolectric sweep: before 1464 PASS + 120 generation errors; after **1584 PASS**, with 1431 satin objects and 153 existing-outline RUNNING fallbacks. No gate threshold was changed.

| Height | Cases | Before PASS | Before generation errors | After PASS | Satin | RUNNING fallback | Maximum 0.5mm-cell penetrations |
|---|---:|---:|---:|---:|---:|---:|---:|
| 15mm | 528 | 488 | 40 | 528 | 477 | 51 | 11 |
| 20mm | 528 | 487 | 41 | 528 | 475 | 53 | 8 |
| 30mm | 528 | 489 | 39 | 528 | 479 | 49 | 9 |

Row-order cycles: **69 → 0**, including fallback causes. Uncovered satin underpaths: **0**. Handakuten cases (90): **35 bean, 27 satin, 28 whole-object RUNNING fallback**. Across all voiced marks, 417 components use bean and 301 use satin in successfully generated satin objects.

| Remaining cause handled by RUNNING | Cases |
|---|---:|
| Ambiguous or connected voicing-mark geometry | 69 |
| Vector mark rings cannot be isolated within raster envelope | 36 |
| Incomplete satin routing | 36 |
| Centerline-to-satin conversion unavailable | 6 |
| Existing array-index exception with an index message | 2 |
| Existing generation exception without a message | 4 |

These are fallback cases, not silently missing strokes or claimed successful satin conversions. The 1.0mm choice is covered at the boundary in the unit tests; the sampler and classifier are not a replacement for sew-out testing.

### Hangul invariance

The complete before/after run covers **11,172 syllables × 3 heights = 33,516 inputs**. Bundled Noto Sans KR file SHA256: `194018e6b2b293a7964f037b25c0249ce1418bc9ab3c971060a03aa57861e252`; explicit weight 400 from `R.font.myembro_sans`.

```text
python3 -I tools/verification/kana-satin/compare-hangul.py /tmp/myembro-kana-dakuten/before /tmp/myembro-kana-dakuten/after
{"cases": 33516, "PES_sha256_comparisons": 32820, "preexisting_errors": 696, "changed": 0, "first_differences": []}
```

**32,820 actual PES byte hashes are identical.** The untouched baseline already fails to generate PES for 696 inputs: 608 incomplete satin, 76 unavailable centerline conversion, and 12 existing array-index exceptions. Every one retains exactly the same status and error in the new run. There is no PES hash to compare for those baseline failures; this is not a claim that all 33,516 inputs generate PES. The coordinator was asked to confirm how pre-existing failures should count toward the requested all-PES criterion; that criterion remains explicitly pending if every input must have a PES. No Hangul fix was folded into this branch.

Raw records and logs remain under `/tmp/myembro-kana-dakuten/`: `before/hashes.jsonl`, `after/hashes.jsonl`, `hangul-comparison.json`, `kana-before/rows.jsonl`, `kana-after-polygons/rows.jsonl`, `kana-summary-polygons.txt`. Baseline recording took 676.70s and after recording 1070.03s while other tests ran concurrently; these wall times are not a performance comparison.

```text
tools/verification/kana-satin/run.sh after "$PWD" /tmp/myembro-kana-dakuten/kana-after-polygons
BUILD SUCCESSFUL in 51s
32 actionable tasks: 5 executed, 27 up-to-date
python3 -I tools/verification/kana-satin/summarize.py /tmp/myembro-kana-dakuten/kana-before /tmp/myembro-kana-dakuten/kana-after-polygons
before PASS=1464 ERROR=120; after PASS=1584; cycles=0; uncovered_underpaths=0; peak_cell=11
```

The checked-in after harness asserts all 1,584 cases complete, no BLOCK/ERROR/unsupported cases, no cyclic fallback reason, and no uncovered underpath. Normal unit tests include the real measured `ご` cycle, 1.0mm/triple-bean selection, serialization/height changes, the native app preparation path and 30 frozen Hangul PES hashes.

Native app preparation (bundled Noto Sans KR, 20mm): `がぱ`, `かき`, `ご` select the kana satin backend; joined-mark `プ` selects RUNNING. The native app test fixes these measured outcomes, checks that kana never sets the Hangul data/flag, and verifies offline PES roundtrip. Kana requests the same explicit 400 bundled family through a kana-only guard; the existing Hangul font selection block is unchanged.

After the final kana-only polygon selection fix, all 13 compiled classes in the Hangul orchestration/data/component-order families remain byte-identical to the baseline (`HangulSatin*`, `CenterlineGlyph*`, `HangulComponentOrder*`, `CenterlineSatinData*`, `BinaryGlyphRaster*`, `RasterToSource*`). The full Hangul record therefore still exercises the unchanged implementation; no additional Hangul algorithm edit followed it.

Final whole-project verification after the polygon/counter regression and exact native app expectations:

```text
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew test lint --no-watch-fs --console=plain
BUILD SUCCESSFUL in 4m 34s
80 actionable tasks: 19 executed, 61 up-to-date
```

JUnit XML totals: engine 1034, app debug 490, app release 483, MCP 10; failures/errors/skipped all 0. Final log: `/tmp/myembro-kana-dakuten/test-lint-polygons.log`. `git diff --check` also passes. The additional `--no-watch-fs` disables only Gradle's file watcher while isolated native probes run; it does not skip any tests or lint tasks.
