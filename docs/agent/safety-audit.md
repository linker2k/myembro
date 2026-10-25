# Adversarial safety audit

Baseline: `agent/mcp` at `66a90d0`. This branch adds tests only. Production implementations are unchanged. The deliberately failing assertions remain enabled, so this branch is **not green** and must not be treated as a merge approval.

## Reproduction

```sh
export JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1
./gradlew --offline --continue :engine:test --tests '*AdversarialSafetyTest' :mcp:test --tests '*AdversarialMcpSafetyTest' :app:testDebugUnitTest --tests '*AdversarialPhoneSafetyTest' --tests '*AdversarialUsbSafetyTest' --console=plain
python3 tools/safety-tests/summarize.py .
```

Measured on 2026-10-25: **12 tests, 7 failures, 0 errors, 0 skipped**. Both commands exit 1 because the safety expectations fail. Per-case JSON evidence is written to `/tmp/myembro-safety-audit/`; the Gradle run was captured in `/tmp/myembro-safety-final.log`. No private corpus or uploaded design is used. The dense synthetic fixture deliberately uses the oracle writer profile to construct a forbidden input; ordinary output settings are not weakened.

The USB test requires the existing `/usr/sbin/mkfs.fat`. It formats only a private temporary image and writes through an in-memory `BlockDevice`, never USB hardware. Shared fixtures are added only to test source sets. MCP tests replace the host's captured storage backend through test-only reflection because the host has no public injection seam.

## Failures

| Severity | Source | Measured evidence | Suggested follow-up (not implemented) |
|---|---|---|---|
| High | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/io/PesExport.kt:19`; `app/src/main/kotlin/io/github/linker2k/myembro/library/DesignFormats.kt:43`; `app/src/main/kotlin/io/github/linker2k/myembro/usb/DirectUsbExporter.kt:84` | A synthetic 1092-byte PES has a valid viewport but imported-origin SewoutCheck BLOCK (40 penetrations in one cell). PesExport returns all 1092 bytes. Passing the actual DesignFormats result into DirectUsbExporter.write causes **6 fake-device write calls / 20992 bytes**, including filesystem metadata. | Enforce both checks before release of PES bytes and before a USB mutation. |
| Medium: strict-policy mismatch | `engine/src/main/kotlin/io/github/linker2k/myembro/engine/agent/DocumentTools.kt:46` | `save_project` and `save_pes`, each annotated needsUserConfirmation, perform **1 fake-store write each without a token**, independently through controller and MCP. | Resolve whether the annotation requires consent for all writes or only replacement. The previous conditional-overwrite contract intentionally allows new names; this is **not an existing-file overwrite bypass**. |
| Medium: phone API policy gap | `app/src/main/kotlin/io/github/linker2k/myembro/ui/editor/EditorSession.kt:24`; `engine/src/main/kotlin/io/github/linker2k/myembro/engine/agent/ToolTurnController.kt:139` | `runCommandScript(confirmed=true)` executes remove_objects with no per-action token: project changes and 18835 PES bytes are returned. | Remove blanket consent if scripts must never execute these tools. This is a trusted-host boolean under the old API contract, not a model-supplied JSON field. No production call site or Apply button exists for runCommandScript yet, so an actual button exploit is **unconfirmed**. |
| Low: staging policy mismatch | `docs/agent/tools.json:437` | xMm=999.9 (9999 native 0.1mm units) is accepted by dispatcher and MCP staging. The phone script rejects final output with SEWOUT_BLOCKED; no PES bytes are released. | Decide whether staging must reject out-of-hoop transforms. This value lies inside the published ±10000mm schema, so acceptance is **not a schema-validation bypass**. |

There are 4 failure categories above but 7 failing tests: the same category is asserted at more than one boundary.

## Confirmation matrix

Cells show **fake-store write calls / result code** for valid typed arguments without a token. Each row starts with a fresh session and backend; controller and MCP save_pes receive a real PASS artifact; the phone script has no artifact-registration API and supplies a missing artifact ID. The remove_objects target exists. None of these no-token cases changes the project. Raw kernel dispatch also tests all 21: zero writes and zero project changes; save services are NOT_IMPLEMENTED at that lower boundary.

| Tool | Bound dispatcher | MCP | Phone script, confirmed=false |
|---|---|---|---|
| remove_objects | 0 / CONFIRMATION_REQUIRED | 0 / CONFIRMATION_REQUIRED | 0 / CONFIRMATION_REQUIRED |
| new_project | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| open_project | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| recover_project | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| discard_recovery | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| commit_import | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| delete_designs | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| import_font | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| delete_font | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| save_project | 1 / OK | 1 / OK | 0 / NOT_IMPLEMENTED |
| save_pes | 1 / OK | 1 / OK | 0 / NOT_IMPLEMENTED |
| share_pes | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| send_usb | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| resume_usb_after_delete | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| share_diagnostics | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| create_backup | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| restore_backup | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| save_custom_frame | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| delete_custom_frame | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| open_free_design_source | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |
| copy_diagnostics | 0 / NOT_IMPLEMENTED | 0 / CONFIRMATION_REQUIRED | 0 / NOT_IMPLEMENTED |

All 21 tools are also attempted in phone dry-run: zero fake writes, unchanged project/revision/history, no PES bytes. A read-only removal preview may return OK without applying it.

MCP tests exercise forged, argument-mismatched (or other-action for argumentless tools), and replayed tokens for all 21 tools. All negative calls reject and produce no extra writes or project changes. Unimplemented operations remain unimplemented even after valid confirmation. The two implemented save tools additionally exercise controller tokens directly: only the approved overwrite writes once; forged/mismatched/reused tokens do not write. Existing-file byte/version checks are enforced by the fake DocumentStore, not inferred from response text.

## Input and output coverage

- Additional root fields are rejected for **69/69 tools at each of dispatcher, MCP and phone script**, with zero writes. These are dispatcher/host API tests, not a stdio transport fuzz campaign.
- NaN, infinity, 1e100, negative scale, negative text height, empty object ID and empty transaction ID are rejected on all 3 paths. The 9999-unit exception is listed above. Nonfinite JsonPrimitive values are tested directly at the API boundary; they are not valid JSON wire literals.
- Agent controller prepare_pes and project sewout_check reject out-of-hoop placement, the dense input and a deliberately corrupted PEC header; no bytes or artifact are returned. A fabricated artifact cannot be saved. An approved asset sewout_check may successfully return a BLOCK report but returns no PES bytes and makes no writes.
- MCP blocked-placement prepare_pes returns no artifact and makes no writes. Phone script BLOCK inputs do not commit or release bytes. These pass while the lower existing PesExport/USB path fails.
- Agent send_usb/share_pes and other service stubs currently return NOT_IMPLEMENTED. Their zero side effects establish rejection today, **not safety of a future USB/restore/settings implementation**. There is no fake USB service plugged into those stubs. The separate USB test exercises the actual existing writer against a fake block device.
- The actual USB test composes the same preparation and write functions used by DirectUsbExporter.export; it does not simulate Android USB permission/open calls. The SAF exporter shares preparation (`UsbExporter.kt:97`) but its Android output stream and UI lifecycle were not executed. No claim of exhaustive SAF, chooser, backup or physical-device coverage is made.
- The phone Apply button has no production connection yet; tests cover EditorSession.runCommandScript. The default confirmed=false path is safe in these cases, whereas the trusted confirmed=true escape fails the requested stricter policy.

No implementation fix, push or main merge was performed.

## High-severity export boundary fix

`fix/export-gate` enforces the existing SewoutCheck BLOCK policy and exact
PecViewportCheck at PesExport preparation, project DesignFormats preparation,
and immediately before DirectUsbExporter writes. Imported PES retain the 30
penetrations/cell threshold and their exact original bytes. Generated origin and
selected HoopSpec travel with prepared results/payloads; no limits are enlarged.
Incomplete analysis also rejects output. USB batches are copied and validated in
full before the first mutation. Resume validates the frozen payloads before even
opening the device, so invalid data cannot cause space-recovery deletions.

The original engine-pes-export and USB adversarial assertions remain enabled.
Additional tests bypass preparation and submit dense, corrupt-header and malformed
payloads directly after a valid first payload: every case must produce zero writes
and an unchanged fake disk. Existing USB report/allocation tests now use a real
public PES fixture instead of arbitrary non-PES filler. Public imported-file tests
still compare prepared bytes against FAT12-remounted bytes for both export versions.
Density boundary tests cover imported 30/31 and generated 16. No private designs,
policy relaxation, disabled tests or real USB device is involved.

The audit's separate confirmation/staging policy mismatches are unchanged by this
High-severity fix. A full check still reports their deliberately failing assertions;
these results must not be described as a green overall audit.

Measured verification for this fix (2026-10-25):

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew --continue check
# BUILD FAILED in 4m 9s. Engine 844/2 failed; app debug 289/1;
# app release 289/1; MCP 9/2. All 6 failures are the pre-existing
# confirmation/staging assertions above; lint and checkStdio complete.
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew \
  :engine:test --tests '*AdversarialSafetyTest.publicPesExportPreparationCannotReturnSewoutBlockedBytes' \
  --tests '*PesExportSafetyTest' :app:testDebugUnitTest \
  --tests '*AdversarialUsbSafetyTest' --tests '*UsbTransferReportTest'
# BUILD SUCCESSFUL in 2s. Engine 3/3; USB 10/10; 0 failures/errors.
```

JUnit-captured evidence (shared `/tmp` reports can be overwritten by other audit
runs; the values below are from this worktree's test XML, not a reused report):

```text
SAFETY_engine-pes-export {"blockedInput":true,"returnedBytes":0}
SAFETY_usb-blocked {"inputVerdict":"BLOCK","viewportValid":true,"preparedBytes":0,"usbWriteCalls":0,"usbWrittenBytes":0}
```

The full check therefore remains **FAIL**, while the High-severity export/USB
regressions and normal public-PES round trips pass. No claim of overall audit
approval is made.
