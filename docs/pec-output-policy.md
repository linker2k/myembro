# Current PEC output policy (2026-10-24)

Latest user decision: **discard the extra 2mm margin**. `HOOP_SAFETY_MARGIN_UNITS_0P1MM=1` reserves the last endpoint under P2; it is not an inset added to each side. A 100mm hoop allows coordinates 0..999, with maximum design width/height 99.9mm. The previous 20unit/96mm policy no longer applies.

`SewoutLimits` is the single definition of hoop endpoints, density, repetition, and stitch length. `exportGateHoop` exports Kotlin HoopSpec/constants as JSON, also read by Python checks.

- Counts only actually recorded STITCH/JUMP destinations, excluding the implicit origin. Uses integer checks for minimum x=y=0, maximum x<hoop width/maximum y<hoop height, and header dimensions=max−min. Also blocks an empty motion list.
- Editor placement applies the same rule to centered integer intervals. A 100mm hoop uses −500..499; an odd 1001unit span uses −500..500. No floating-point epsilon.
- Passes default/machine/custom HoopSpec identically. Intersects lettering's verified allowed interval with that hoop's height; editor properties and project/USB preparation also check actual placement.
- The default PES Writer uses require to check final-byte 0-length commands and SewoutCheck blocking reasons. Generated markers follow generated policy; original conversions follow existing import policy. Existing PES bytes are preserved, but origin/header/hoop are checked during transfer preparation.
- P8 is dx²+dy²≤120². Generated designs are blocked; imported designs receive warnings. Origin/header/hoop errors block both sources.
- Original-comparison-only `inkstitchCommands=true` is not used by the app. Official equivalence checks retain the frozen official stitch/command counts and tolerances for this profile. App 0-length cleanup is checked in separate regressions.

Directly compares documented P6 `half_stitch` with LockStitchParameters start/end defaults and InkLockStitch defaults. CI also runs AGENTS P1–P8 numeric checks and n−1/n/n+1 boundary checks.

## Composite designs

Fixed synthesis of stationary JUMPs in `TRIM → 색 변경/STOP → 실제 JUMP`. Preserves TRIM state until an actual nonzero JUMP and records the PEC trim bit with it. Actual needle coordinates are unchanged. A TRIM immediately before termination creates no motion, matching upstream PEC. Existing standalone PEC and original-comparison-profile behavior is preserved.

Multicolor outline/satin regressions save with the actual public PesWriter. Composite designs containing actually unlocked imported boundaries and originals with cell density 16 remain blocked. They are not made to pass by relaxing thresholds, removing body stitches, or generating custom locks. See [regression records](composite-output-regressions.md).

## Reproduction

```sh
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew check :app:assembleDebug :engine:exportGateHoop
python3 -m unittest discover -s tools/tests -p 'test_quality_gate_*.py'
```

After installing Python gate v7/P8 revisions, apply the same constants with `:engine:exportGateHoop -PgateHoopOutput=/tmp/myembro-quality-gate/hoop-spec.json` and `python3 tools/update_gate_viewport_policy.py`. Read the private corpus only through `tools/report_pec_viewport.py --corpus <local-dir> --output <report-stem>`. Do not commit originals or names.

The staging/s4-only photo integration patch is [photo-safety-margin.patch](integration/photo-safety-margin.patch), handled by the integrator. Size calculation uses the shared helper and therefore follows the latest 999 endpoint semantics. This branch has no photo screen.

Existing retest files `/tmp/myembro-sewtest/s1/S1SAMPL3.PES`, `/tmp/myembro-sewtest/s4/S4SAMP3.PES`(+PNG) are artifacts from the previous unit. S4 retained the existing 63.5mm width/70mm packing exception. They were not regenerated in this unit.
