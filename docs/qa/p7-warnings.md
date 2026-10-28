# P7 density warnings — user decision 2026-10-28

Both generated and imported PES use the same policy: 0–15 penetrations in a 0.5mm
cell has no density finding, 16–30 is WARNING with the cell highlight, and 31+
is BLOCKER. Warnings allow saving/transferring. Basis: 334 PE-Design files, p99=15,
max=25; the 3 peaks above 15 were 16/23/25. Actual birdnest failure: 38.
`SewoutLimits` owns warning and block thresholds; `HoopConfigCli` exports them to
the independent Python gate. P1–P6/P8 are unchanged.

## Actual output, not raw coordinates

Texture generation does not reject a raw-coordinate density preflight. Adapter
results lazily encode/check actual PES only when a report/bytes is requested,
and cache the outcome. The app consumes the generated pattern and calls
`PesWriter.writeChecked`: encode, validate once, return bytes and the same report.
This retains warning cell locations. `PesOutputSafety.Rejected` retains typed
findings; the photo/SVG screens map count, density, hoop, repeated/zero, lock,
length, file and incomplete-check failures to existing localized messages.

`TextureNormalizedDensityTest` uses synthetic integer points with raw/wire peaks
18→12 and 15→17. Both save normally; the latter retains its density WARNING.
`P7DensityPolicyTest` checks 14/15/16 and 29/30/31/32 for both origins and both PES
writer versions, including non-null warning highlights and 31+ rejection.

## Warning-only flow audit

- Photo: warning results use the caution badge; the check dialog shows each density
  warning’s top-left x/y range and count. `PhotoViewModel.passed/canSend` use complete analysis and no BLOCKER;
  texture `canUse/canEdit` use the same condition plus encoded bytes.
- Viewer/USB: `sewoutNeedsConfirmation` tests blockers/incomplete analysis only;
  warning highlights remain visible in the existing sewing-check overlay/sheet.
- Block editor: `BlockApply.check` rejects placed/output blockers; warning-only
  writer/export validation returns normally and `BlockRunner` publishes Passed.
- Lettering: adding text uses the renderer's readiness, and saving/transferring
  follows the same editor/viewer/PES safety boundary.
- Dispatcher formerly required verdict PASS and rejected CAUTION. It now checks
  complete analysis, actual blockers and valid viewport. The original-PES path
  retains imported provenance, and output findings remain available to callers.

Policy-dependent output goldens were regenerated from main 2007a4f algorithms
with the new P7 guard: optional outlines at 16–30 may now remain, so former byte
hashes under the old density guard are intentionally obsolete. Synthetic inputs
and all other invariants remain fixed.

## Independent gate v8

Local tool: `/tmp/myembro-quality-gate`, previous active v7 archived under
`versions/v7-before-p7-20261028`. Boundary fixtures and reports are under
`/tmp/myembro-p7`. The gate prints WARN for 16–30 and blocks only above 30.
No private design is added to this change.

A pre-existing full-gate imported-lock discrepancy was observed for the frozen
`long_first` sample: Python also blocks its missing locks, while Kotlin warns
for imported locks. The same discrepancy exists in the archived v7 algorithm;
this P7-only change does not alter either lock policy. P7 boundary parity is
checked separately for both origins; the existing non-P7 Kotlin verdict is kept.

The unchanged legacy samples named `density30`/`density31` contain 29/30 actual
needle events after their stationary initial landing is removed. They therefore
now warn. New boundary fixtures explicitly assert actual wire counts 31/32 and
remain BLOCK; no 31-penetration density is accepted.
