# KRSAT2 → KRSAT3 connected-component routing comparison

Jumps belong to the destination component/glyph and include the initial move from the origin. Travel length is the sum of actual PEC JUMP lengths.
Uses the explicit component order from the before/after renderers.

| Glyph | Previous jumps | Updated jumps | Previous travel(mm) | Updated travel(mm) |
|---|---:|---:|---:|---:|
|Jeong|3|3|29.777|16.361|
|Min|3|3|37.829|27.932|

| Glyph·component(sewing order) | bbox(px) | Previous entry jumps | Updated entry jumps | Updated internal jumps |
|---|---|---:|---:|---:|
|Jeong·2|[15, 17, 83, 76]|1|1|0|
|Jeong·0|[76, 8, 110, 77]|1|1|0|
|Jeong·4|[33, 81, 110, 124]|1|1|0|
|Min·3|[138, 18, 192, 72]|1|1|0|
|Min·1|[217, 8, 227, 91]|1|1|0|
|Min·5|[153, 84, 231, 120]|1|1|0|

Total JUMPs 6→6, travel 67.606→44.293mm. Eliminates backtracking between glyphs; internal component JUMPs 0. Start/end locks for all 6 runs PASS (the departure origin of the initial move has no thread requiring a lock).
Gate PASS, 0-length commands: 0, origin(0,0), maximum per cell 5, maximum stitch 2.507987mm.

Does not recognize jamo stroke order or judge traditional stroke order. Components are 8-connected ink components; line/order rules follow each renderer commit's documentation.
Final acceptance requires user sewout. Connecting the app defaults is outside this sewout-sample generation unit.

## Changes and evidence

Based on the **2026-10-24 user-confirmed rule**. For two components of the same glyph,
`overlap=max(0,min(bottomA,bottomB)-max(topA,topB))`,
if `2*overlap >= min(heightA,heightB)`, treat them as on the same line and sew the component with smaller left first.
Otherwise sew the component with smaller top first. Includes 50%; checks using integer
multiplication without adding floating-point tolerance. Glyph index takes highest priority.

Same-line detection is pairwise and may be non-transitive. Rather than passing a non-transitive comparator
to JVM sort, resolves each pair's precedence constraints as a DAG and explicitly rejects contradictory layouts.
Does not arbitrarily add other tolerances or jamo recognition.
This applies user ordering constraints; satin/running/lock algorithms are unchanged.

Actual output component-order assertions:
- Jeong: component 2(ㅈ) → 0(ㅓ) → 4(ㅇ)
- Min: component 3(ㅁ) → 1(ㅣ) → 5(ㄴ)

The jamo names above are explanatory labels for the requested Jeong Min sample. The engine does not recognize jamo;
it uses only existing ink-component bboxes. Jeong's top two components overlap by 59px of the smaller height 59px;
Min's top two overlap by 54px of the smaller height 54px, so each pair is classified as the same line.

Additional regressions 4 cases: left→right top components with differing tops, exact 50%/below 50% boundary,
glyph-index priority/input-order independence, explicit rejection of contradictory pairwise constraints.
Existing connected-component/AutoTrace/satin/routing/PES regressions remain.

Final `/tmp/myembro-sewtest/krs/KRSAT3.PES`: 624 stitches·5 TRIM·one color Black 40wt,
26.9×14.4mm. SHA256
`c9cc6ce067b013c2e289e888df5a9dabaf3cb640d1c5d8f4c545315a914587d4`.
KRSAT1/2 were not overwritten. Same-stem `.png` is an actual decoded-PES rendering;
`-comparison.md/json`, `-routing.json`, and `-gate.json/log` were also preserved.
External gate v7 PASS: all 6 sewing-run start/end locks present, maximum per cell 5, 0-length commands: 0,
min(0,0)/max(269,144), header 269×144 match exactly.

Reproduction (use a new filename):

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSample -PhangulInput=/tmp/myembro-sewtest/krs2 -PhangulOutput=/tmp/myembro-sewtest/krs/NEWNAME -PstrokeWidthMm=1.13
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-sewtest/krs/NEWNAME-gate.json /tmp/myembro-sewtest/krs/NEWNAME.PES
# 기존 게이트 Python 환경으로:
python tools/hangul_satin/compare-components.py /tmp/myembro-sewtest/krs/KRSAT2.PES /tmp/myembro-sewtest/krs/NEWNAME.PES /tmp/myembro-sewtest/krs/NEWNAME-routing.json /tmp/myembro-sewtest/krs/NEWNAME-comparison --before-routing /tmp/myembro-sewtest/krs/KRSAT2-routing.json
```

The previous 9-path KRSAT1 comparison mode remains. Connecting app defaults is still the next unit;
this sample-generation change is not reported as completed app integration. Final acceptance requires user sewout.

Validation: `./gradlew check` BUILD SUCCESSFUL in 37s, engine 667·app Debug 205/Release 205
failures 0·Lint passed. Output `/tmp/myembro-port-out/letter/krsat3-check.log`.
