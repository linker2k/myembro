# Headless agent scenario regressions

Ten scenario JSON files contain twelve variants. `:engine:test` runs all ten
JUnit methods in `AgentScenarioRegressionTest`; no emulator, network, downloads,
AWT font replacement, private designs or optional environment flag is needed.

Every case fixes success/rejection code, audited sewout verdict, needle count,
complete PES SHA-256, release flag, exact project equality and undo depth.
Successful artifacts use **the same bytes returned by executeScript/prepare_pes**.
No independently regenerated surrogate is used for Python cross-checking.
The initial baselines were measured with engine output and independently checked
by the existing Python quality gate (12/12 verdicts matched) before pinning.
Tests never update their expectations on failure.

| JSON | Variants |
|---|---|
| 01-logo-transform | Public bundled logo: move, rotate, recolor |
| 02-inkstitch-english | Bundled Ink/Stitch Medium `Ab` |
| 03-hershey-weights | Existing Hershey single-line normal / bold `Ab` |
| 04-ttf-hangul-satin | Bundled Noto Sans KR `정민`, 15mm, Android outline/raster snapshots |
| 05-emoji-heart | Existing red-heart pipeline at 20mm, prepare then add |
| 06-align-distribute | Three digitized letters, top alignment and horizontal distribution |
| 07-duplicate-order | Duplicate positioned letter, change sewing order |
| 08-hoop-boundary | Synthetic existing boundary-test geometry: 999 PASS / 1000 BLOCK |
| 09-height-rejected | Existing 60mm maximum exceeded: INVALID_ARGUMENTS, no publication |
| 10-mid-failure-rollback | Move + recolor + missing-object failure: exact original project retained |

The fixture envelope is test-host configuration, not another tool schema.
Each `script` uses the existing Project JSON and strict tool calls. Optional
`preparations` call the real dispatcher before the script; `$prepared.<id>` binds
only a returned opaque resultId. This mirrors a caller preparing an emoji then
submitting Add, without adding arbitrary geometry or a new mutation path.
Font revisions are literal pinned source revisions. The TTF resolver uses only
`font-snapshots/noto-sans-kr-satin.json.gz`, captured by Android on emulator-5556
at `agent/font-snapshots@4413c6a`. It replays the same CenterlineGlyph/layoutGlyphs
chain as AndroidFontSnapshotTest. See the snapshot README/manifest and OFL notice
beside that resource. Other data comes from the existing redistributable
logo/Fluent/font bundles and existing synthetic ToolDispatcherTest boundary input.
No new stitch algorithms or lock patterns are introduced.

## Rejected cases and audit artifacts

Rejected scripts must return no PES, preserve full ProjectJson state (not only
its summary), preserve revision/history, and create zero undo steps. Their audit
bytes are explicitly **diagnostic live-state** files, never dispatcher output:
height/mid-script failures audit the unchanged, valid original logo (PASS);
1000 audits the unchanged invalid boundary state (BLOCK). PolicyFixtureWriter
first asserts that the public writer rejects that invalid file, then creates
wire bytes only inside test code. This is not a production export bypass.
Thus `released=false` with `sewout=PASS` means the attempted script was rejected
and the original project is still sewable; it never means a failed candidate
was exported. Successful no-op boundary inspection retains zero undo steps.

## Reproduction and independent Python check

Use a NEW output directory every time:

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:test
./gradlew :engine:runAgentRegression \
  -PagentRegressionOutput=/tmp/myembro-agent-scenarios-NEW
python3 tools/check_agent_scenarios.py /tmp/myembro-agent-scenarios-NEW \
  --gate /tmp/myembro-quality-gate/quality-gate
```

The artifact task verifies the pinned JSON before producing observed.json and
`*.audit.pes` / `*.result.json`. The Python script verifies each PES hash, invokes
the independent Python gate on those exact files with explicit HoopSpec-derived
hoop dimensions, and compares all twelve verdicts. The 1000 case additionally
requires a PEC_HOOP reason. It writes python-crosscheck.json; no gate installation
or policy change occurs, and this check grants no release/user sewout approval.
A missing external Python gate is a failure, never a silent skip in this opt-in
cross-check. JVM CI has no dependency on `/tmp` tools.

For intentional baseline review only, `-PagentRegressionMeasure=true` writes
measurements to a new external directory without changing tracked JSON. Review
the intended outcomes, exact source changes and Python verdicts before manually
updating expected numeric/hash fields. Never accept a changed hash merely to
make CI green.
