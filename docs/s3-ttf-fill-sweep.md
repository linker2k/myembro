# S3 Korean TTF fill: reduced height sweep

Date: 2026-10-24. Public fonts only. Result files are in
[`s3-ttf-fill-sweep/`](s3-ttf-fill-sweep/); full local runtime and first failing PES examples
are under `/tmp/myembro-ttf-fill-sweep-reduced/`.

## Input selection

- All Korean outline fonts exposed by this checkout: 3 bundled TTFs + 30 pinned
  `kind=outline-ttf`, `scripts` containing `ko` catalog entries (33 faces, including
  the catalog's Noto Serif KR CFF/OTF). Each file's SHA-256 is checked before use.
- Intersect `Font.canDisplay` over modern Hangul U+AC00–U+D7A3 across all 33 fonts:
  2,350 common supported syllables. Missing glyphs must never count as successful tests.
- Rank by the strict writing-stroke sum of initial consonant, vowel and final
  consonant; descending count, ascending Unicode codepoint for ties. The tables
  are translated from [count-korean-stroke-js, fixed commit
  5f595640db4a059caf0d535fc72a4751a706e077](https://github.com/juunini/count-korean-stroke-js/blob/5f595640db4a059caf0d535fc72a4751a706e077/index.ts)
  (`strict=true`, MIT). The source was read; no upstream JavaScript was executed.
  Full attribution is in `THIRD_PARTY_NOTICES.md` and `tools/licenses/`.
- Top 30, scoring 13–16 strokes:
  `빪뺍뺐뼙뼜빱빴뺄뺌뺨뻤뼘뽑떫밟뵀빨빰빻뺏뻘뻠뼛뽈뽐뿔뿜쁩삡쐤`.
  This is a reproducible writing convention, not a universal handwriting count or
  a proof that these are the worst embroidery shapes.
- Height anchors use the current app's 5–90mm range: `5, 26.25, 47.5, 68.75, 90mm`
  (min, 25%, 50%, 75%, max). Coarse stage: 33 × 30 × 5 = **4,950 cases**.
- Only fonts with a failing *interior* anchor are refined. In those fonts, each
  adjacent PASS/BLOCK transition interval is sampled at integer mm; existing
  anchors are reused. BLOCK/BLOCK and PASS/PASS intervals are not expanded.
  This boundary-only refinement keeps the reduced scope bounded; it does not
  establish the behavior between two equally classified samples.

## Executable check

`KoreanTtfSweep` extracts each actual font outline with JVM AWT at 1000px and
flattens curves at 0.2px. Height reference is the ink height of `자`, as in the app.
It calls `LetteringEngine.fromContours(FILL, outline=true)` with default tatami
and underlay, writes PES v6, then calls
`SewoutCheck.checkPes(..., SewoutOptions(origin=APP_GENERATED))`.

`PASS` means a complete engine analysis with no BLOCKER. The worker's frozen
policy checks density, consecutive zero-length repeats, functional locks,
maximum stitch length, file validity and the default SE400/100mm sewing area.
Warnings and information do not become blockers in this runner. Generation
exceptions, incomplete analysis and suppressed blocker details are recorded as
failures. Failure-reason counts may overlap within one case.

AWT outlines and Android `Path` extraction have different implementations;
Android extraction, arbitrary multi-letter text and actual sewing are outside
this single-glyph engine sweep. Heights are nominal app heights; a glyph taller
than the reference may exceed the hoop even below 90mm.

## Reproduce

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 tools/sweep_korean_ttf.py prepare --font-cache /tmp/myembro-fontq-independent/fonts
python3 -u tools/sweep_korean_ttf.py run --workers 8
python3 -m unittest discover -s tools/tests -p 'test_*sweep.py' -v
./gradlew :engine:check
```

Preparation uses the existing public-font cache and checks every catalog hash;
it does not install or execute downloaded code. Missing or mismatched fonts
abort. The runtime classpath, font files and source identity are frozen under
`/tmp` before calculation, so another Gradle build cannot replace the running
classes. CSV checkpoints flush after each case and support resuming. Repreparing a root with
results is rejected; resuming validates every frozen classpath hash.

The abandoned full 567,600-case sweep was **never started**. Its 99-case pilot
was separate; its linear 53-hour estimate led to this reduced plan. Checkpoints
were resumed to apply boundary-only refinement and then fail-fast evaluation; the final wall
time includes earlier computation and both restarts.

## Fail-fast refinement

For each refined height the predicate is `all30(selectedGlyphsPass)`.
The first actual BLOCK is sufficient to exclude that font/height; later
characters at that height need not be generated. Conversely, a PASS height
always requires all 30 actual PES checks. This is logical short-circuiting,
with no assumed relationship between different heights. Characters with more
observed coarse blockers are tried first; this changes computation order only.

The coarse stage still evaluates all 4,950 combinations. The JSON separately
records executed PASS/BLOCK cases, omitted checks following a witnessed blocker,
and pending checks. Omitted combinations are **unknown**, not added to BLOCK
case counts. Height decisions can be complete without testing every character
of an already excluded height. Failure counts are observed cases, not a census
of every potentially failing combination.

The fail-fast optimization changed only the local runner. The original frozen
engine, writer and SewoutCheck bytecode hashes remain unchanged; the initial
and final runtime manifests are archived separately.

## Range interpretation

`allowed-height-ranges.json` contains passing/blocked sampled heights and the
widest successive PASS-sample interval for every font; ties choose the lower
start. Incomplete font profiles receive no range. BLOCK and missing results
split intervals. The table is a **sampled candidate**, not certification of all
continuous heights. The JSON keeps `deployment_enabled=false` and denies
untested inputs; this task does not activate new app slider limits.

See `summary.md`, `summary.json`, `failures.jsonl` and `timing.json` in the result
directory for measured counts, per-font ranges, failure cases and execution time.

## Measured result

- Coarse: 4950 cases, 4263 PASS / 687 BLOCK (about 9 minutes).
- Coarse + refinement: **375/375 font/height decisions**,
  **8888 actual cases = 7708 PASS + 1180 BLOCK**;
  2362 character checks omitted after a witnessed blocker.
- Eight workers; total wall **1405.844s (23.43 minutes)**,
  including warm-up and both checkpoint restarts. The total exceeded the 15-minute target.
- Observed failure reasons (overlapping): `{"DENSITY_HOTSPOT": 36, "HOOP_EXCEEDED": 1145, "MACHINE_AREA_EXCEEDED": 535}`.
- Latest `./gradlew :engine:check`: **BUILD SUCCESSFUL in 23s**; JUnit XML: 564 tests,
  zero failures/errors. `python3 -m unittest discover -s tools/tests -p 'test_*sweep.py' -v`:
  **Ran 11 tests; OK**. CI executes the reducer tests without running this extended sweep.
- The archive reconciles every raw CSV row, omitted count, failure reason and completed
  height decision, and revalidates the frozen engine hashes before writing its snapshot.

```text
FINAL COMPLETE 8888 PASS 7708 BLOCK 1180 wall_seconds 1405.844
```
