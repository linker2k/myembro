# Hangul satin order and routing — Unit 1

New direction after the user's KRSAT1 sewout (2026-10-24): satin by default for Hangul, complete
each character individually, original auto-route underpaths within characters, and trims between characters.
Jamo stroke order is deferred per the integrator's reply. Do not implement it without verified
jamo/stroke-order references.
**Current scope is the routing-graph core port, the user-confirmed stroke-order API, and baseline
measurements only. App defaults have not yet changed; no claim is made of KRSAT2.PES, before/after
improvement figures, or completed automatic jamo stroke ordering.**

## KRSAT1 actual PEC baseline

Input: the existing engine test file using public OFL Noto Sans KR400, ‘Jeongmin’ at 15mm,
and constant width 1.13mm. No personal files were read or bundled. SHA256
`c26ff1bb5fdbee8c46397c2fcaba1800aa27da3b98b9b0d49d13a340fd202e79`.
The actual needle-character sections are `정→민→정→민→정→민`, with 5 inter-character round-trip JUMPs.

| Arrival character | JUMP count | JUMP travel length (mm) | TRIM count at that character |
|---|---:|---:|---:|
| Jeong |6|58.927649|6|
| Min |3|36.183745|2|
| Total |9|95.111393|8|

The initial movement from the origin is included. JUMPs are attributed to the arrival character,
and TRIMs to the preceding position. Travel length is the sum of JUMP lengths, not running/satin
needle lengths. Classification uses the midpoint 133.5 of the largest empty needle-x interval
114..153 (PEC integer units) **only for this case, already known to contain two characters**.
It is not a general character/jamo-recognition rule. External gate v7 judged this file PASS, which
is consistent: character round trips/jump-thread appearance are not existing blocking criteria.

Output: `/tmp/myembro-port-out/letter/krsat1-routing-baseline{.json,-gate.json,-gate.log}`.
Reproduce with the existing gate Python environment:
`python tools/hangul_satin/measure-routing.py /tmp/myembro-sewtest/krs/KRSAT1.PES /tmp/myembro-port-out/letter/krsat1-routing-baseline.json`.

## Original core port

`stitches/utils/SatinRouteGraph.kt` corresponds to the ordering in Ink/Stitch v3.3.0
`lib/stitches/utils/autoroute.py:find_path/_get_shortest_path` and
`lib/stitches/auto_satin.py:path_to_operations`.
Commit `b0edd96311ece82ee48816dc93466274b12c2a9c`, GPL-3.0-or-later,
Copyright (c) Ink/Stitch contributors. Source attribution is retained at the top of the file.

1. Choose the unweighted shortest start→end path as the backbone.
2. Remove those edges, then traverse DFS branches out and back from each backbone node.
   As in the original, build the DFS-label list first, then remove graph edges.
3. On repeated visits to a satin section, mark **only the last visit as satin** and preceding
   visits as running underpaths. JUMPs are not converted to running.

Instead of NetworkX, use JVM insertion-ordered adjacency, bidirectional BFS, and an explicit DFS
stack. No added dependencies. After finding the backbone of a directed(preserve_order) graph,
copy it to an undirected graph for branch traversal, as in the original. No new numeric nearest-
neighbor greedy routing was created. The 7 analytic regressions cover a straight line, T-branch,
cycle/nontree, JUMP round trip, identical start/end, direction preservation, and disconnected input.
**Equivalence to the official Ink/Stitch execution oracle is undecided**; analytical tests are not
called a match to original execution. No original Python execution or dependency installation.

Differences/incomplete work: this API accepts only already-built graphs. Original `build_graph`
(1mm satin chunks), ordered/unordered jump connections, cutting both rails in
`SatinSegment.to_satin`, RunningStitch conversion/collapse, shape-difference trim decisions,
and DOM manipulation are not yet connected. Disconnected input explicitly fails rather than
creating fake automatic connections (the caller must complete original add_jumps under the
contract). For duplicate section IDs, the caller must supply the same ID for the same satin
and normalized start/end.

## Character/jamo ordering contract and evidence to confirm

- Preserve **character indices** in the TTF raster/extract stage and process characters in
  reading order. Do not output whole-text pixel-scan order unchanged.
- [Unicode16 §3.12.2](https://www.unicode.org/versions/Unicode16.0.0/core-spec/chapter-3/)
  provides the basis for decomposing precomposed syllables into L/V/(T) jamo. It does not label
  individual TTF-outline pieces with jamo/stroke order. fontTools confirmed that bundled Noto
  Sans KR uniC815/uniBBFC have flat glyf outlines, not composites, and therefore lack jamo-component metadata.
- Initial→medial→final jamo separation was the initial request, but was deferred by the integrator's
  reply. The latest decision is centerline-start y/x order below, not labeled traditional jamo
  stroke order. The Ministry of Education basic curriculum's
  [Korean-language achievement standard 6Korean03-01](https://www.nise.go.kr/ebook/src/viewer/download.php?host=main&no=1&site=20240226_094301)
  specifies teaching jamo stroke order, but is not evidence of an algorithm for decomposing
  glyphs into jamo/strokes or an applicable coordinate table. Jamo/stroke-order references and
  actual glyph mappings are unconfirmed.

Next unit: connect the character-index separation contract and original rail cutting/graph creation →
check whether running paths under columns are covered by subsequent satin → generate the new name
KRSAT2 and compare P1–P8/PNG/actual bytes before and after → connect app TextObject serialization/
off-thread generation. Do not generalize Noto ‘Jeongmin’ constant width 1.13mm to arbitrary TTFs.

Additional integrator decision: SE400 has no automatic thread cutting (confirmed by the user's
actual sewing). Keep trim codes as PE-Design does, but reduce jumps themselves. Include **locks
at both ends of every JUMP** in the next routing-integration checks. Retain original half_stitch
locks; this graph-port unit does not claim completed generation/output checks for them. Continue
including per-character JUMP counts in before/after tables. Confirmation of jamo stroke order is
not a prerequisite for app integration.

Final integrator stroke-order decision: without jamo separation, **centerline stroke-start y first,
then x on ties**; complete characters in reading order. `SatinStrokeOrder` accepts explicit
character indices and +y-down paths to create that order (3 analytic regressions). Do not invent
an arbitrary same-y tolerance; compare x only on exact ties. Do not arbitrarily reverse original
start directions to change the reference point. This is a simple user-defined order and does not
claim agreement with traditional stroke order for every jamo. The sorted result is input for
original graph integration with `preserve_order=true` in the next unit.

Verification: `./gradlew check` BUILD SUCCESSFUL in27s, engine 660·app Debug205/Release205,
0 failures·Lint passed. New routing 7 + stroke order 3 = 10 analytic regressions.
Output: `/tmp/myembro-port-out/letter/auto-route-order-check.log`.
This unit covers only the original graph core/ordering contract. Actual satin rail cutting/
connections, KRSAT2 PES generation, and changing the app's Hangul default remain outstanding.

## Unit 2 — connected-component KRSAT2

Per the latest user correction, use **connected-component bboxes** instead of path-start order.
The SatinStrokeOrder API/tests for the previous decision were removed; KRSAT2 uses only the
connected-component contract. Original rail cutting/underpath integration and actual output/
before-after measurements are recorded in the [KRSAT2 report](hangul-satin-krsat2.md).

## Unit 3 — row classification by 50% vertical overlap

User-confirmed rule: if the vertical ranges of two clusters overlap by at least 50% of the smaller
height, place them on the same row, left→right; otherwise top→bottom. KRSAT3 was generated in
Jeong jieut→eo→ieung / Min mieum→i→nieun order. Boundary regressions, preserved original scope,
and before/after byte measurements: [Unit 3 report](hangul-satin-krsat3.md).
