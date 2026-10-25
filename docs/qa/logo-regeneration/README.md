# Bundled logo regeneration after deterministic ring ordering

Base: `agent/integration` at `3080238`. Branch: `fix/logo-regen`.
The bundled logo is our existing launcher artwork, not a personal or third-party
embroidery design. The input and design choices are unchanged.

## Source and existing generator

- Input: `app/src/main/res/drawable/ic_launcher_foreground.xml`, SHA256
  `99b7302772db9fc884cef2fae4e3f319c146aa0d347c314b520c35b18e0f8b76`.
- Existing generator: `LogoDesignTest.logoAt60mmPassesInvariantsAndGate` calls
  `VectorArt.vectorDrawableToSvg` → `parseSvg` → `render(widthMm=60.0, name="myembro")`
  → `PesWriter.write`.
- Existing source/process documentation: [image-to-design.md](../../image-to-design.md),
  “Common width decision rule” and the logo reproduction command; the test's
  older `docs/emoji-to-design.md` reference does not exist in this checkout.
- Existing stitches remain Ink/Stitch-port satin for the clamp bar and hoop ring,
  and tatami for the M. Source XML, dimensions, thread mapping, stitch algorithms,
  density, and safety thresholds are unchanged.

`f0dcdd5` canonicalizes Polygonizer ring starts/order. The previous bundled PES
came from the formerly nondeterministic order. This asset is regenerated from
that now deterministic path; it does not restore the old order or weaken the
byte equality assertion.

The test's `LOGO_OUT` export now runs **before** the bundled-byte assertion so an
outdated asset can actually be regenerated. Every assertion remains in place,
and the initial bootstrap still fails the old/new byte comparison as expected.
The supplemental `IMAGE_PROBE_LOGO_OUT` route from `VectorArtClassificationTest`
was checked too, but uses the name `Art`; the bundled asset is copied only from
the canonical `LogoDesignTest` output named `myembro`.

## Before / after

Decoded with the existing pyembroidery interpreter and independently checked by
the external bounded-PEC quality gate. Native coordinates are 0.1mm units.

| Property | Previous bundle | Regenerated bundle |
|---|---:|---:|
| PES bytes | 6,863 | 6,867 |
| Needle stitches | 2,808 | 2,810 |
| Color 1 needle stitches | 1,025 | 1,025 |
| Color 2 needle stitches | 1,783 | 1,785 |
| Width × height | 60.0 × 63.3mm | 60.0 × 63.3mm |
| Motion minimum (x,y) | (0,0) | (0,0) |
| Motion maximum (x,y) | (600,633) | (600,633) |
| PEC header width × height | 600 × 633 | 600 × 633 |
| Colors / color changes | 2 / 1 | 2 / 1 |
| PEC palette order | 26 → 49 | 26 → 49 |
| Quality gate | PASS | PASS |

Both palettes are **Light Brown 26 `#B27624` → Amber Red 49 `#B54B64`**.
The additional 2 penetrations are in the second (tatami M) color block; the first
satin color block's count is unchanged. No new color or color reorder is present.

- Previous PES SHA256: `db7327b352a3f426404e59ead131f2fe23b41626b663d0a4563b291d2e78e8ac`.
- Regenerated PES SHA256: `9e3a369ba8de95e00129ec1684da9d78aa66360b8759fe0c8aec0dbae0e6bc52`.
- [Decoded comparison](comparison.json), [palette](palette.json), and
  [quality gate report](quality-gate.json) retain the observations.

## Reproduction and verification

Run the existing canonical generator once to produce artifacts. If the bundled
asset is outdated, this first run fails only its final byte assertion; inspect
the generated PES, then replace the asset and rerun the full checks:

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
LOGO_OUT=/tmp/myembro-logo-regen/after ./gradlew :engine:test \
  --tests '*LogoDesignTest' --console=plain
cp /tmp/myembro-logo-regen/after/LOGO60.PES app/src/main/assets/designs/myembro_logo.pes
/tmp/myembro-quality-gate/quality-gate check \
  /tmp/myembro-logo-regen/before/LOGO60.PES \
  /tmp/myembro-logo-regen/after/LOGO60.PES \
  --style mixed --json /tmp/myembro-logo-regen/gate.json
./gradlew :engine:runAgentRegression \
  -PagentRegressionOutput=/tmp/myembro-logo-regen/scenarios \
  -PagentRegressionMeasure=true --console=plain
LOGO_OUT=/tmp/myembro-logo-regen/after ./gradlew check --console=plain
```

The previous bundle is preserved locally under `/tmp/myembro-logo-regen/before/`.
The generator also writes `LOGO60.png` (source beside decoded stitches), `logo.svg`,
`THREADS.md`, and `README.md` under `/tmp/myembro-logo-regen/after/`. The preview
was visually inspected: hoop, clamp bar, and M placement are retained.

The three existing agent scenarios that load the bundled logo also need updated
`stitchCount` and `pesSha256` observations: logo transform, rejected height, and
mid-script rollback. Their input scripts, success/failure requirements, rollback,
undo, release guard, and sewout expectations remain unchanged. All other scenario
expectations are checked against measurement and retained.

External gate output: `PASS 2/2 cases passed` (old and new logo). Its report retains
`release_ready=false`: actual logo sewout has not yet occurred, as stated in the
regeneration decision. The user explicitly retains this existing 2-color,
60×63.3mm built-in design; it is not a newly proposed single-color sewout sample.

### Ancillary stale MCP test expectation

The first full check after updating logo observations completed engine and app
checks but exposed an unrelated stale expectation in `McpHostTest.kt:15`:
`get_settings` was expected to return `NOT_IMPLEMENTED`, although it is already
implemented by `ToolDispatcher` and covered by `MoreToolsTest`. The MCP test now
asserts `ok=true` and rejects an unknown argument with `INVALID_ARGUMENTS`,
matching that existing engine contract. Only the test changes; the MCP host,
settings resolver, transport, schemas, and runtime behavior are unchanged.

### Final execution output

```text
LOGO size 60.0x63.3mm stitches 2810 colors 2 blockers 0 warnings []
quality-gate check --style mixed: PASS 2/2 cases passed
./gradlew check --console=plain
MCP_STDIO PASS: JSON-only stdout; exact 69 schemas; parse error; EOF exit
BUILD SUCCESSFUL in 4s
75 actionable tasks: 4 executed, 71 up-to-date
```

The final invocation reuses successful engine/app checks from the preceding
invocation and reruns the corrected MCP test; no task is excluded.

| Test task | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| engine | 852 | 0 | 0 | 0 |
| app Debug | 325 | 0 | 0 | 0 |
| app Release | 325 | 0 | 0 | 0 |
| MCP | 4 | 0 | 0 | 0 |

[Full final check output](check-output.txt), [gate stdout](gate-output.txt),
[JUnit totals and logo stdout](test-results.json), and the
[exact scenario expectation changes](scenario-changes.json) are retained here.
No push or main merge.
