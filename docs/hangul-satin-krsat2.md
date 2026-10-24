# KRSAT1 → KRSAT2 connected-component routing comparison

Jumps belong to the destination component/glyph and include the initial move from the origin. Travel length is the sum of actual PEC JUMP lengths.
Uses the original9paths' path-index→ink-component ownership and the final6component order.

| Glyph | Previous jumps | Updated jumps | Previous travel(mm) | Updated travel(mm) |
|---|---:|---:|---:|---:|
|Jeong|6|3|58.928|29.777|
|Min|3|3|36.184|37.829|

| Glyph·component(sewing order) | bbox(px) | Previous entry jumps | Updated entry jumps | Updated internal jumps |
|---|---|---:|---:|---:|
|Jeong·0|[76, 8, 110, 77]|2|1|0|
|Jeong·2|[15, 17, 83, 76]|3|1|0|
|Jeong·4|[33, 81, 110, 124]|1|1|0|
|Min·1|[217, 8, 227, 91]|1|1|0|
|Min·3|[138, 18, 192, 72]|1|1|0|
|Min·5|[153, 84, 231, 120]|1|1|0|

Total JUMPs 9→6, travel 95.111→67.606mm. Eliminates backtracking between glyphs; internal component JUMPs 0. Start/end locks for all 6 runs PASS(the departure origin of the initial move has no thread requiring a lock).
Gate PASS, 0-length0, origin(0,0), maximum per cell5, maximum stitch2.507987mm.

Does not recognize jamo stroke order or judge traditional stroke order. Components are 8-connected ink components, ordered by glyph index·bbox top·left. No same-line tolerance.
Final acceptance requires user sewout. Connecting the app defaults is outside this sewout-sample generation unit.

## Upstream mapping, differences, and scope

`AutoSatin.kt`: Ink/Stitch v3.3.0
`lib/stitches/auto_satin.py:SatinSegment.break_up/center_line/to_satin,
build_graph/path_to_operations/collapse_sequential_segments,
RunningStitch.to_element/is_sequential/__add__`,
`lib/stitches/utils/autoroute.py:_add_unordered_jumps/possible_jumps/find_path`,
`lib/elements/satin_column.py:center_line/find_cut_points/split/_cut_rails/
_assign_rungs_to_split_rails/_add_rungs_if_necessary/_add_rung/reverse`.
Pinned commit `b0edd96311ece82ee48816dc93466274b12c2a9c`.
Checked LICENSE and the files' GPL-3.0-or-later notices; existing GPL3 distribution notices apply.
No Ink/Stitch code execution or dependency installation.

Upstream1mm center-line splitting → minimum connections between component nodes(k=1 MST) →
existing upstream find_path port → underpath on earlier visits/satin only on the final visit → sequential merging →
find cut points on both rails before splitting → redistribute/add rungs → existing Stroke/SatinColumn/S1.
No new nearest-distance greedy routing traversal was created. The figures above and cut/rung0.3px·1.1/3times,
running merge0.5px·precision0.00001 are upstream literals/defaults.

Differences/scope:
- This API is exclusively for **direction-aligned Sections returned by convert-to-satin**. General reverse/flip of
  oppositely directed raw SVG rails, DOM, arbitrary running-stroke input, and SVG attribute copying
  are outside this unit. Only default parameters were used in KRSAT2.
- Shapely/GEOS is replaced by existing JTS1.20; NetworkX k=1 minimum connections are replaced by per-component nearest
  node candidates and an insertion-stable Kruskal MST. Tie order for equally distant candidates and
  equivalence to official output for arbitrary self-intersecting rails are **not assessed**.
- Upstream split behavior that can discard very short rail fragments is recorded in `discardedSatins`.
  The sewout CLI refuses file generation if any are discarded. KRSAT2 has **0**.
- The user-confirmed order uses **ink-component bboxes**, rather than path start points.
  Uses 8-connectivity, matching AutoTrace pixel diagonal adjacency.
  Explicitly maps pixel ownership to font-layout glyph bboxes. Rejects ambiguous ownership or
  ink components without centerlines. No jamo naming, stroke-order inference, or nearest-glyph guessing.
- Distinguishes graph bridges inside components from actual PES JUMPs. The upstream planner's default
  collapse3mm or less uses running connections, so a graph bridge may have no actual JUMP.
  The **0-count check uses PEC bytes**.
- Does not enable force_lock_stitches per group because the upstream planner then creates a JUMP each time.
  Uses S1 default boundary/distance criteria and half_stitch to lock both ends of actual JUMPs.
- Preserves source trim commands and S1 PEC jump/trim rules at glyph/component boundaries(final5TRIM).
  Does not assume SE400 automatic thread cutting.

## Validation and artifacts

New analytical regressions6cases(8-connectivity·glyph-first/bbox order·ownership rejection3,
straight/branched underpath coverage order·3component PES round trip3). Existing graph7cases and
AutoTrace C golden10cases are also included in the full checks. Auto-route equivalence to
an executed official Ink/Stitch oracle is not assessed; these tests are not reported as matching official execution.

Final file `/tmp/myembro-sewtest/krs/KRSAT2.PES`: **624stitches·5TRIM·one color Black40wt**,
actual26.9×14.4mm. SHA256
`5ec454ec5c9abb8782999dac6d71231dac2694af67041987c96d492efb4fc597`.
The existing KRSAT1 SHA is unchanged. Development comparison candidates were preserved separately in
`/tmp/myembro-sewtest/krs2/`; the delivery target is **krs/KRSAT2.PES** above.

The same-stem `.png` is a decoded-PES needle rendering;
`-centerline.png/json` is the input centerline; `-routing.json` records glyph/component/path/underpath
provenance; `-comparison.md/json` contains before/after tables; `-gate.json/log` is the external v7 gate verdict.
Gate metrics/criteria were unchanged. max269/144, min0/0, header269/144 match exactly,
0-length needle/JUMP0, maximum per cell5, missing start/end/pre-/post-trim locks0.

## Reproduction

Run with a new filename. Input is public OFL Noto Sans KR400 'Jeong Min'15mm; no private
patterns/libraries are read or added as test fixtures.

```sh
python3 tools/hangul_satin/prepare-raster.py --out /tmp/myembro-sewtest/krs2
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSample -PhangulInput=/tmp/myembro-sewtest/krs2 -PhangulOutput=/tmp/myembro-sewtest/krs/NEWNAME -PstrokeWidthMm=1.13
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-sewtest/krs/NEWNAME-gate.json /tmp/myembro-sewtest/krs/NEWNAME.PES
# 게이트의 기존 pyembroidery Python 환경으로 실행:
python tools/hangul_satin/compare-components.py /tmp/myembro-sewtest/krs/KRSAT1.PES /tmp/myembro-sewtest/krs/NEWNAME.PES /tmp/myembro-sewtest/krs/NEWNAME-routing.json /tmp/myembro-sewtest/krs/NEWNAME-comparison
```

This unit covers the KRSAT2 sewout sample/engine connection. Connecting the default app Hangul path and identical
regeneration when saving/editing TextObject remains for the next unit.

Final validation(after functional changes): `./gradlew check` BUILD SUCCESSFUL in28s, engine666·app Debug205/Release205
failures0·Lint passed. Subsequently removed the previous decision-stage stroke-order API/tests3cases. `/tmp/myembro-port-out/letter/krsat2-final-check.log`.
Automatically checked that all actual7 underpath sections are covered by the **union** of the same original satin sections
sewn later(including coverage split across multiple satin segments).
`compare-components.py` asserts no uncovered underpaths, discarded satins, glyph-order deviations, internal component
JUMPs, missing locks, or 0-length/origin errors. Automated verdicts and user sewout approval are separate.

After removing the obsolete API, the current source also reports `./gradlew check` BUILD SUCCESSFUL in28s, engine663·app each205
failures0·Lint passed (`/tmp/myembro-port-out/letter/krsat2-check-current.log`).
