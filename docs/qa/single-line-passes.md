# Single-line lettering pass counts: Yeonwoo (2026-10-24)

Calculation unit 1. Baseline main `9b37790` (after v0.2.7). **Generation code/UI/defaults unchanged.** The coordinator is confirming the machine-test font and height with the user, so the cause of 3 passes in the actual file is not yet determined.

## Conditions and measurement definitions

- Entire `single-line-svg` catalog: **40 fonts × 10/15mm**. Checked every public local SVG's SHA-256 against the current catalog. Text `Yeonwoo`, letter spacing 1mm, default 2.5mm stitches, straight layout.
- Supplied the same SVG layout data as the app's `LoadedFont.glyph/advance/reference`/`layoutGlyphs` to `TextObject(fontKind=OUTLINE, singleLine=true)` to generate `localPattern()`. Current automatic single-line path is RUNNING. Also calculated comparison output with BEAN specified on the same path.
- Also compared bundled Ink/Stitch font **Sacramarif**, whose elements are all running for this phrase, with original settings unchanged. Did not mix other Ink/Stitch fonts containing satin with the 40 single-line SVG fonts.
- Excluded generated locks with S1 `GeneratedPatternProcessor.stripGeneratedLocks()`. Also excluded jumps/trims and counted only needle→needle segments. **Table percentages use the unique count of undirected segments with identical endpoints**. Reverse direction is the same segment. Included 4 or more passes too.
- Partial overlaps with different endpoints can be excluded from those percentages. Separately recorded collinear overlap-length distributions for source/output paths in `summary.json`. Segments close but not exactly collinear due to curve quantization at 0.1mm are not merged as the same line. Thus this is not a visual overlap ratio accounting for needle-hole/thread width. Decimal precision in collinearity calculations is diagnostic numeric representation, not sewing policy.

## Results and causes

- RUNNING at both heights: of 40 fonts, **35 have 100% of identical-endpoint body segments traversed 1 time**; 5 have some 2-pass segments. **0 fonts have 3 or more passes**.
- Hershey Sans 1-stroke/Hershey Script 1-stroke at both heights: RUNNING **1/2/3 passes = 100/0/0%**, BEAN **0/0/100%**. The current default automatic RUNNING path does not apply bean.
- BEAN multiplies source-overlapped sections too. Example: EMS Readability 10mm has RUNNING 1 pass 98.21%, 2 passes 1.79%; BEAN 3 passes 98.21%, **6 passes 1.79%**. The 3 passes of 'Bold' describe each originally single-line input segment, not a limit of 3 passes across all source duplicates.
- Sacramarif: 10mm identical endpoints 1 pass 99.26%, 2 passes 0.74%; 15mm 1 pass 98.95%, 2 passes 1.05%. By output collinear length, 2 passes account for **1.48%/1.58%** respectively. Original running paths include **13.37% with 2 passes** by collinear length. Source has `repeats=1`/unspecified and no bean repeat attribute. Different resampled endpoints/quantization make source-length distributions differ from output identical-endpoint distributions.
- SVG single-line paths execute original stroke order unchanged, without separate branch-search/automatic backtracking algorithms. The 2 passes above arise from overlap/backtracking in source SVG paths. Locks were also excluded as a cause of body duplication. Existing saved BEAN objects may retain a 3-pass setting unlike the current UI default.

Upstream evidence: Ink/Stitch v3.3.0 `lib/stitches/running_stitch.py:bean_stitch` @ `b0edd96311ece82ee48816dc93466274b12c2a9c`: repeats `[1]` means forward→back→forward per segment (3 total passes); `[0]` means no repeats. Explicit BEAN in current `StitchStyle.kt:withStitchStyle` has the same 3-pass behavior. The next UI unit calls worker's existing upstream `RunningStitch.beanStitch`/Stroke port, without creating custom backtracking optimization.

## Reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:measureSingleLinePasses \
  -PpassHeight=10 -PpassOutput=/tmp/myembro-port-out/letter/single-line-passes/10mm
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:measureSingleLinePasses \
  -PpassHeight=15 -PpassOutput=/tmp/myembro-port-out/letter/single-line-passes/15mm
python3 tools/summarize_single_line_passes.py /tmp/myembro-port-out/letter/single-line-passes
```

Default input SVG directory: `/tmp/myembro-fontq-independent/fonts`; other paths via `-PpassFonts=...`. All 40 current-catalog SVGs must be present there for the same results. Missing files are skipped; execution prints the measured font count. `passText` can change the phrase too. No PES/private files were read/generated.

Outputs: `10mm/measurements.json`, `15mm/measurements.json`, `*/embroidery.json`, body-segment CSV, `summary.json`, `table.md` under the above `/tmp` directory. Execution 10mm 2 seconds/15mm 2 seconds (including incremental build). `./gradlew :engine:test` **PASS**. Diagnostic task is opt-in; CI/app generation paths unchanged.

## Awaiting decisions

Machine-test font/height confirmation and Normal/Bold defaults await the coordinator's decision. UI scope is only the two-option selection for single-line fonts; a new algorithm removing existing source backtracking is outside this unit.

## Full table

| Font | Height mm | Mode | 1 pass % | 2 passes % | 3 passes % | 4 or more passes % | Cause |
|---|---:|---|---:|---:|---:|---:|---|
| hershey-sans-1 | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-sans-1 | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-script-1 | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-script-1 | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-med | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-med | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-gothic-english | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-gothic-english | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-readability | 10 | RUNNING | 98.21 | 1.79 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-readability | 10 | BEAN | 0.00 | 0.00 | 98.21 | 1.79 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-allure | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-allure | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-tech | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-tech | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-casual-hand | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-casual-hand | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-delight | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-delight | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| relief-singleline | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| relief-singleline | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-sans-med | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-sans-med | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-script-med | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-script-med | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-bold | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-bold | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-bold-italic | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-bold-italic | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-med-italic | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-med-italic | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-gothic-german | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-gothic-german | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-gothic-italian | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-gothic-italian | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-bird | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-bird | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-bird-swash-caps | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-bird-swash-caps | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-brush | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-brush | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-capitol | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-capitol | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-decorous-script | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-decorous-script | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-delight-swash-caps | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-delight-swash-caps | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-elfin | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-elfin | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-felix | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-felix | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-herculean | 10 | RUNNING | 97.96 | 2.04 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-herculean | 10 | BEAN | 0.00 | 0.00 | 97.96 | 2.04 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-invite | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-invite | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-league | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-league | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-little-princess | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-little-princess | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-misty-night | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-misty-night | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-neato | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-neato | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-nixish | 10 | RUNNING | 98.70 | 1.30 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-nixish | 10 | BEAN | 0.00 | 0.00 | 98.70 | 1.30 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-nixish-italic | 10 | RUNNING | 98.72 | 1.28 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-nixish-italic | 10 | BEAN | 0.00 | 0.00 | 98.72 | 1.28 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-osmotron | 10 | RUNNING | 98.61 | 1.39 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-osmotron | 10 | BEAN | 0.00 | 0.00 | 98.61 | 1.39 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-pancakes | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-pancakes | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-pepita | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-pepita | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-qwandry | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-qwandry | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-readability-italic | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-readability-italic | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-society | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-society | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-swiss | 10 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-swiss | 10 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| inkstitch-sacramarif | 10 | FONT_ORIGINAL | 99.26 | 0.74 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| hershey-sans-1 | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-sans-1 | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-script-1 | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-script-1 | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-med | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-med | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-gothic-english | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-gothic-english | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-readability | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-readability | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-allure | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-allure | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-tech | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-tech | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-casual-hand | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-casual-hand | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-delight | 15 | RUNNING | 97.47 | 2.53 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-delight | 15 | BEAN | 0.00 | 0.00 | 97.47 | 2.53 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| relief-singleline | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| relief-singleline | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-sans-med | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-sans-med | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-script-med | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-script-med | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-bold | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-bold | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-bold-italic | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-bold-italic | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-serif-med-italic | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-serif-med-italic | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-gothic-german | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-gothic-german | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| hershey-gothic-italian | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| hershey-gothic-italian | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-bird | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-bird | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-bird-swash-caps | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-bird-swash-caps | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-brush | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-brush | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-capitol | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-capitol | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-decorous-script | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-decorous-script | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-delight-swash-caps | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-delight-swash-caps | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-elfin | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-elfin | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-felix | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-felix | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-herculean | 15 | RUNNING | 98.51 | 1.49 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-herculean | 15 | BEAN | 0.00 | 0.00 | 98.51 | 1.49 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-invite | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-invite | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-league | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-league | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-little-princess | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-little-princess | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-misty-night | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-misty-night | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-neato | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-neato | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-nixish | 15 | RUNNING | 96.04 | 3.96 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-nixish | 15 | BEAN | 0.00 | 0.00 | 96.04 | 3.96 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-nixish-italic | 15 | RUNNING | 95.00 | 5.00 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-nixish-italic | 15 | BEAN | 0.00 | 0.00 | 95.00 | 5.00 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-osmotron | 15 | RUNNING | 98.99 | 1.01 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
| ems-osmotron | 15 | BEAN | 0.00 | 0.00 | 98.99 | 1.01 | bean 1 round trip (3 passes per segment); Source overlap/backtracking present |
| ems-pancakes | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-pancakes | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-pepita | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-pepita | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-qwandry | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-qwandry | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-readability-italic | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-readability-italic | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-society | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-society | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| ems-swiss | 15 | RUNNING | 100.00 | 0.00 | 0.00 | 0.00 | No bean; No identical straight-line overlaps in source |
| ems-swiss | 15 | BEAN | 0.00 | 0.00 | 100.00 | 0.00 | bean 1 round trip (3 passes per segment); No identical straight-line overlaps in source |
| inkstitch-sacramarif | 15 | FONT_ORIGINAL | 98.95 | 1.05 | 0.00 | 0.00 | No bean; Source overlap/backtracking present |
