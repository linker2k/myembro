# Headless tool dispatcher: first executable slice

Base: `agent/commands` at `b892b6d`, merged with `agent/tools` at `688ffd8`.
No Android, provider SDK, network dependency, or new stitch algorithm is introduced.

## Implemented boundary

`ToolDispatcher.dispatch(project, revision, name, arguments, confirmed=false)` is a synchronous,
snapshot-in/snapshot-out **single-call kernel**, not the proposed session/turn/history controller.
It implements the 15 names in `ToolDispatcher.SUPPORTED`: get_project, the listed object transform,
mirror/center/align/distribute/duplicate/order/visibility/recolor/remove/frame edits, sewout_check,
and prepare_pes. All other catalog names return `NOT_IMPLEMENTED` **after** schema validation.
Unknown names return `UNKNOWN_TOOL`; invalid inputs return `INVALID_ARGUMENTS`. Command reducer
rejections retain their typed codes and original project. A rejected call never returns PES bytes.

The authoritative [tools.json](tools.json) is copied unchanged into the engine resources by Gradle.
`ToolSchema` validates the exact closed JSON Schema subset emitted by that catalog (types, closed
objects, required fields, arrays, enums, numeric bounds, regex). It rejects unsupported schema
keywords at construction; it does not claim to implement arbitrary Draft 2020-12 schemas.
`tools/check_agent_tools.py` separately meta-validates the catalog with jsonschema.
No coercion of string numbers/booleans, unknown nested keys, nonfinite values, invalid enum values,
or fixed-range violations is permitted. Dynamic IDs, thread indices, frame identity, duplicate
transform targets and reorder bounds are also checked before publication.

Value edits call the shared `ProjectCommand` reducer. Convenience alignment/distribution/mirroring
uses existing engine helpers, resolving to SetTransforms. Group/center preparation copies the existing
app `EditorSelection.transformGroup` behavior, including visible selection bounds, pivot, clamped
scales and translation; no preview-box approximation or new geometry algorithm is used. Sharing
that helper with the app and connecting this kernel to UI dispatch are future work.

`transactionId` is validated and echoed as correlation data only in this slice. There is no live
transaction store, retry receipt, undo history, begin/commit turn, or concurrent revision owner yet.
Those lifecycle tools deliberately remain NOT_IMPLEMENTED, and this kernel must not be exposed as
an unrestricted production agent service. The host must freeze/own project data, serialize publication,
compare revisions, provide scoped idempotency and bind confirmation to exact arguments before calling
this kernel. `confirmed` is a trusted host parameter, never accepted in the tool JSON. Removal without
it returns `CONFIRMATION_REQUIRED`. The scenario CLI does not grant removal consent.

Frames and approved PES assets are injected by the host. Defaults are the existing engine HoopSpec
presets; an unknown frame/machine fails explicitly. Assets are defensively copied; no filesystem path
or arbitrary URL is accepted by a tool. Text preparation/font registry binding is not implemented.
get_project reports IDs/order/types/visibility/transforms/colors and text settings without attempting
to synthesize an unsupported text font or pretending there is undo history.

## Check and PES release

For a project, export verifies the requested revision/frame, rejects coverage-only position layouts,
flattens using existing defaults and applies the existing final thread-start cleanup without recentering.
It checks **centered placement first**, so a writer's top-left normalization cannot hide an off-hoop edit.
The normal PesWriter (never the oracle profile) then encodes, including its existing output safety checks.
Actual PES bytes are checked by SewoutCheck and PecViewportCheck. The report includes verdict,
completion, findings, severities including suppressed details, and integer PEC/header extents.
Only an actual complete **PASS**, with valid viewport, releases bytes from prepare_pes; CAUTION and
BLOCK release none in this conservative scenario slice. A writer rejection returns SEWOUT_BLOCKED
without bypassing that writer just to obtain bytes. Existing source provenance is derived from the
pattern, never from a model argument. sewout_check does not return byte payloads.

For approved PES-asset checks, exactly one of projectRevision/pesAssetId is nonempty. Imported-file
policy is retained and a read-only check may report CAUTION/BLOCK; malformed/truncated assets return
an error without a crash. No threshold is relaxed. This is file-quality validation, not physical sewout
approval; no tool sends files or starts a sewing machine.

## JSON scenario runner

[scenarios/builtin-logo.json](scenarios/builtin-logo.json) exercises a bundled, redistributable logo:
move/rotate, recolor, generate PES, and check it. Input has exactly initialProject, calls, export.
initialProject is an inline ProjectJson object or the explicit built-in descriptor in the example.
The built-in resolver allows only the bundled logo and sample_heart_running.pes; arbitrary paths,
external asset downloads and personal fixtures are not read. Inline ProjectJson uses no file resolver.
`$revision` in a projectRevision/expectedRevision argument is expanded by the runner before validation.
Each accepted changed snapshot advances the local revision. Each call result and the final summary
are recorded. Calls are atomic individually; the entire call list is not a session-level transaction.
Any failed call prevents PES release, even if later calls succeed.

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :engine:runAgentScenario \
  -PagentScenario=docs/agent/scenarios/builtin-logo.json \
  -PagentOutput=/tmp/myembro-agent-scenario-NEW --console=plain
```

The output directory must not already exist, preventing overwrite and stale output confusion.
`results.json` contains each result, final state and final export diagnostics. `output.pes` is written
only if every call succeeded and final exact-byte checks passed. A blocked run exits nonzero and
has no output.pes. This local runner never performs USB, restore, deletion, or publication. Initial
scenario-configuration errors fail nonzero; tool rejections are recorded normally.

## Verification

```sh
python3 tools/check_agent_tools.py
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --offline :engine:test \
  --tests '*ToolDispatcherTest' --console=plain
```

Tests exercise every supported name with a normal and rejected call, concrete resulting geometry/
order/color/visibility, schema errors, unknown/not-implemented tools, typed rejections, host consent,
stale revisions, invalid placement before normalization, actual encoded 999/1000 endpoint policy,
and the built-in JSON-style scenario. Generated outputs and test logs remain local build or /tmp
artifacts; no personal designs or generated PES are committed.

Observed validation for this slice: full `:engine:check` finished BUILD SUCCESSFUL in 32s;
JUnit totals 773 tests, zero failures/errors/skips. The focused dispatcher suite contains eight
JUnit tests, including all 15 supported-name normal/reject pairs and all 54 unimplemented names.
The file-based logo scenario emitted complete PASS output; an off-hoop file-based scenario exited
nonzero, recorded its call results and left no output.pes. Local evidence:
`/tmp/myembro-dispatcher-final-check.log`, `/tmp/myembro-dispatcher-evidence.txt`,
`/tmp/myembro-dispatcher-blocked.log`. These are automated checks, not hardware approval.
