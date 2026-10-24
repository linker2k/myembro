# Composite-output regressions and staging merge

- Cause: the PEC canonicalizer inserted a 0-length JUMP when the command immediately after TRIM was a color change/STOP. Preserved pending state to attach the TRIM bit to the actual JUMP after the color command. The original raw PEC format API is unchanged.
- TRIM immediately before termination creates no actual motion. Therefore satisfies P4 without generating a separate ending move/needle stitch.
- `CompositeTrimRegressionTest` compares literal expected commands/coordinates for COLOR_CHANGE/STOP×PES v1/v6. Default multicolor outline and satin must pass all public Writer checks.
- Actual original cell density 16 remains blocked by P7; asserts body stitches are identical before/after prepareForOutput. Unlocked imported boundaries are blocked too. Check conditions were not relaxed, nor body stitches arbitrarily thinned.
- `ExactPolicyTest` asserts that AGENTS P6's type, start/end parameters, and InkLockStitch default type match.

Removed the 2mm policy per the user's revision. The 1unit constant is an alias for P2's reservation of the last endpoint, not a separate inset. Allows 998/999, rejects 1000. Slider verification ranges/numeric input/placement/actual transfer checks use the same HoopSpec.

Merged `origin/staging/s2s3` f230c13 into this worker worktree. During conflicts, retained staging's simplified lettering UI, font catalog, tatami/sweep/playback code and applied only worker output boundaries/checks. Frozen counts/tolerances of the original official oracles are unchanged. Original comparison uses the explicit compatibility profile.

Validation commands/logs:

- `./gradlew check :app:assembleDebug :engine:exportGateHoop` → BUILD SUCCESSFUL (32s), `/tmp/myembro-staging-worker-final.log`.
- Multicolor/satin + TRIM/P6 regressions using the actual public Writer and APK → BUILD SUCCESSFUL (3s), `/tmp/myembro-composite-targeted.log`.
- Python integer-policy boundary tests 14 passed.

Before pushing, compare with `git diff origin/staging/s2s3..HEAD --stat`. Also check that playback strings/MainActivity/font assets/catalog match staging.

Final recheck: `:engine:test :app:assembleDebug` BUILD SUCCESSFUL (14s), engine 609 tests with 0 failures. App debug/release each 205 tests with 0 failures. `/tmp/myembro-worker-merge-final.log`.
