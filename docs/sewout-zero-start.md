# SE400 T3 safe output: remove 0-length commands and same-position trim restarts

User sewout results: T1PEDS·T3NOZRO normal, T2OURS skipped. Retains half_stitch locks.
Additional diagnosis: two sections of S23SAM2 are STITCH@P → TRIM → JUMP(0)@P → needle;
they trim/restart at the same position without actual travel, so the decision was to join them into continuous sewing.

## Application boundary

- `ThreadStartCleanup.clean`: removes 0-length JUMPs/needle stitches. Also removes TRIM if the first restart position after
  TRIM is identical and no travel to another position/color change/STOP intervenes. Does not recompute nonzero lock paths.
- `GeneratedPatternProcessor.forSewout`: shared **final sewout stage**. Cleans only an output copy without modifying
  editor originals/generation algorithms or their index/lock provenance. Removes index provenance from the cleaned copy.
- Default `PesWriter.write` / `writeV1` / `writeV6` apply this automatically. Editor/lettering/sampler
  all use this boundary. Project viewer checks also inspect the same final copy.
- Byte pass-through when reading/sharing existing PES and the raw PEC API remain unchanged.
  Explicit `inkstitchCommands=true` is an upstream-compatible profile for official-output comparison, unused by the app.
  Its behavior is unchanged. Use the default safe profile for machine output.

Removes only T3's motionless lock starting point, retaining half_stitch's nonzero back-and-forth motion.
Python assertions confirm that **nonzero needle coordinates/order after removing original 0-length needle stitches exactly match the regenerated files**.

## Retest files (local only)

| File | Stitches | Trims | 0-length JUMP/needle | Maximum consecutive | v6 gate |
|---|---:|---:|---:|---:|---|
| /tmp/myembro-sewtest/s1/S1SAMPL2.PES | 685 | 37 | 0 / 0 | 1 | PASS |
| /tmp/myembro-sewtest/s23/S23SAM3.PES | 4417 | 13 | 0 / 0 | 1 | PASS |
| /tmp/myembro-sewtest/s4/S4SAMP2.PES | 2436 | 1 | 0 / 0 | 1 | PASS |

PNG/layout JSON accompanies each file. Existing individual PES files were rearranged/serialized with our
SamplerCli/PesWriter to avoid mixing in new fonts/algorithms. Preserved S4's existing 70mm canvas and actual 63.5×30mm size.
`/tmp/myembro-sewtest/zero-start-clean-gate.{txt,json}` and `zero-start-clean-bytes.json` contain computation results.
S23SAM3 SHA-256: `427df7f2ae4bc53267a5de2e80de5c5ef2d784bd4f844b1f268faa601449f458`.

## Tests and staging integration

Engine tests verify command lists corresponding to literal T3 start bytes, preservation of nonzero half_stitch back-and-forth motion,
cleanup idempotency, same-point/distant TRIM distinction, and preservation of COLOR_CHANGE/STOP boundaries.
Existing export tests explicitly account for differences in 0-length commands between the source model and final PES.
Retains PES parser/limit/color/original-object/lock-generation tests and runs the full check.

Cherry-picking this commit into staging/s2s3 and staging/s4 automatically applies it to default PES output.
Do not add duplicate postprocessing in generators or change lock types.
