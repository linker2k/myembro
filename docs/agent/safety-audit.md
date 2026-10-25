# Adversarial safety audit and policy follow-up

Baseline: `agent/mcp` at `66a90d0`; initial tests-only audit: `bacc3f1`.
The initial run had 12 tests / 7 failures. The manager then clarified the
save and placement contracts and requested per-item script approvals.
USB/PesExport production code is deliberately unchanged here: its High fix belongs
to `fix/export-gate`. The two original High assertions remain enabled and failing.

## Current contract

- `save_project` and `save_pes` have `needsUserConfirmation: "onOverwrite"`.
  A new filename saves without consent. Existing targets require a single-use
  token tied to normalized arguments, revision, payload digest and target version.
  Existing-file tests seed targets before no-token attempts. Separate new-name
  tests assert exactly 1 fake-store write per successful save on controller and MCP.
- Other protected tools retain `needsUserConfirmation: true`. The catalog validator
  checks the exact policy values and preserves conditional policy in the MCP
  metadata / trusted adapter registry without changing provider argument schemas.
- Off-hoop placement is permitted while editing. The 9999 native-unit coordinate
  is intentionally accepted by dispatcher and MCP staging, then committed.
  `prepare_pes` rejects the committed candidate with SEWOUT_BLOCKED, returns no
  bytes/artifact; save_pes rejects missing or stale artifacts and writes nothing.
  send_usb remains NOT_IMPLEMENTED, including after valid MCP consent, with no
  writes. This verifies current rejection, not a future USB service implementation.
  Saving the editable project is distinct from exporting a PES.
- Phone and headless scripts have **no blanket confirmed/allowRemoval boolean**.
  The trusted preview host passes only checked `ScriptApproval` items. Their
  zero-based call index, exact target IDs and SHA-256 scope bind the entire
  canonical script, transaction and base revision. A changed call, target, index,
  hash, transaction or revision invalidates an approval. Requests are preview
  metadata, not proof of user consent: never copy all requests automatically or
  accept approvals from model-controlled tool arguments.
- Only undoable `remove_objects` can be approved inside scripts. Preview returns
  `approvalRequests` and applies nothing. A missing item aborts the entire apply;
  the same transaction can be retried with the selected items. A successful script
  occupies one undo step. All other protected tools, including saves for new
  filenames, are forbidden in both script preview and apply; forged approvals
  also cannot authorize them. File/library deletion, transfer, overwrite, restore
  and settings use separate confirmation flows.

## Reproduction

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
python3 tools/check_agent_tools.py
./gradlew --offline --continue :engine:test --tests 'io.github.linker2k.myembro.engine.agent.*' :mcp:test :app:testDebugUnitTest --tests '*AdversarialPhoneSafetyTest' --tests '*AdversarialUsbSafetyTest' --tests '*EditorCommandScriptTest' --console=plain
python3 tools/safety-tests/summarize.py .
```

Safety subset: **15 tests, 2 failures, 0 errors, 0 skipped**;
`HIGH_FAILURES=2 OTHER_FAILURES=0 ERRORS=0`.
The broader regression run covers **63 tests (engine 46, MCP 10, app 7), 2 failures, 0 errors**; both failures are the same retained High assertions.
The Gradle and summary commands exit 1 because the original High failures remain.
No assertion was ignored or converted into an expected success.
JSON evidence is written to `/tmp/myembro-safety-audit/`.
The regression log is `/tmp/myembro-safety-policy-regression.log`.

| Safety test class | Tests | Failures |
|---|---:|---:|
| AdversarialSafetyTest | 6 | 1 (High) |
| AdversarialMcpSafetyTest | 4 | 0 |
| AdversarialPhoneSafetyTest | 4 | 0 |
| AdversarialUsbSafetyTest | 1 | 1 (High) |

## Retained High failure

`engine/.../io/PesExport.kt:19` checks viewport validity but returns a synthetic
1092-byte PES with imported-origin SewoutCheck BLOCK (40 penetrations in one cell).
`app/.../library/DesignFormats.kt:43` passes it through; the actual
`DirectUsbExporter.write` (`app/.../usb/DirectUsbExporter.kt:84`) causes **6 fake
block-device writes / 20992 bytes**, including FAT/directory metadata.
Its production fix is outside this branch's follow-up scope.

## Passing adversarial checks

- All 21 protected scopes: no-token attempts make zero fake-store writes and no
  project changes through raw kernel, bound controller, MCP and phone script.
  Conditional save scopes use existing targets; new names are tested separately.
- MCP forged, different-argument (or other-action for argumentless tools) and
  replayed tokens: all 21 reject without additional writes or published changes.
  Controller save token tests additionally cover the two implemented save tools.
  Only a valid approved overwrite writes once.
- Unknown fields: **69/69 tools rejected per route** on dispatcher, MCP and phone.
  NaN, infinity, 1e100, negative scale/height and blank IDs are rejected. Nonfinite
  values are direct API-boundary tests, not valid JSON wire literals.
- Phone item tests: preview exposes exact approval scopes; no/partial approval
  aborts atomically; altered scope, duplicate index and script edits reject;
  selected removals commit together and one undo restores the original project.
- Every other protected tool (20 tools × preview/apply × forged/no approval) is
  rejected before execution. There are zero fake-store writes or returned bytes.
- Agent prepare/check reject off-hoop, unsafe-density and corrupt-viewport inputs.
  A read-only asset check may return an OK response containing a BLOCK report;
  that response contains no PES bytes and makes no writes.

## Boundaries and fixtures

No personal corpus or uploaded design is committed. Dense PES is a synthetic
forbidden input generated with the explicit oracle writer profile; normal writer
settings are not weakened. Public bundled logo bytes are used for other fixtures.
The installed `/usr/sbin/mkfs.fat` formats a private temporary image; real exporter
code writes to an in-memory BlockDevice, never USB hardware. MCP storage is
replaced with a RecordingStore through test-only reflection. Shared fixtures are
compiled only into test source sets.

The USB test composes the same prepare/write functions as DirectUsbExporter.export;
it does not exercise Android USB permission/open or physical hardware. The SAF
output stream and Android lifecycle are not executed. Agent USB/restore/settings
stubs remain unimplemented. No production preview/apply screen is connected to
runCommandScript yet: this branch provides and tests its shared host API, not a
new Compose screen. Hosts must implement the actual checked-item UI using this
contract. Interactive MCP confirmation remains independent of script approvals.

No push or main merge is performed.
