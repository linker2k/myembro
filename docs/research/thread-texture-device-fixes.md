# Texture device regression fixes (2026-10-27)

Reported from the 5560 device UI integration (`22b5d07`); engine fix on `agent/thread-texture`. No app/UI installation or device timing claim is included here.

## P2 integer dimensions and pitch audit

The old `heightMm * 10 <= 999` test rejected a valid frame because `99.9 * 222 / 222 * 10` evaluates to `999.0000000000002` on the JVM. `ThreadTextureAdapter.frameUnits` now canonicalizes requested width with the existing `Units.pixelsToNative`/Math.round rule, checks the shared photo minimum/maximum as integer 0.1mm units, derives and rounds aspect height in native units, then compares against `SewoutLimits.hoopMaxYUnits0p1mm`. The canonical frame width also feeds the adapter scale. No epsilon or additional margin was introduced; final PEC bounds remain strict integers.

Boundary tests independently cover width and aspect height 998/999/1000: 998 and 999 accepted, 1000 rejected. A 99.9mm frame at fine pitch produces the expected 222×222 raster, generates a lavender-background circle, passes SewoutCheck and exports a valid PEC viewport. Requested frame width is not occupied-ink/header width after background removal.

The entire `engine/.../texture/` package was searched for comparisons and floor/ceil operations:

| Location | Audit result |
|---|---|
| Adapter width range | Double range comparison replaced by native integer bounds. |
| Adapter aspect height | Double/native comparison replaced by rounded native integer bounds. |
| Pixel budget `floor(frameMm / pitch)` | Frame is canonicalized to native units, then BigDecimal division with FLOOR produces an exact integer budget. Adjacent Double representations of 90.0/99.8/99.9/100.0 yield the same budget; noisy 99.9 yields 222, 90.0 yields 200 at 0.45mm/px. |
| Fine-pitch minimum | Decimal comparison plus exact membership in measured preset data. Membership deliberately rejects unmeasured preset values; no tolerance. |
| Original plotter angle/progress/luminance comparisons | Unchanged: these are algorithm decisions, not geometric hoop bounds. Original frozen trace oracle remains unchanged. |

## Shared page-background selection

`ColorRegionCleanup.selectBackground` contains the **same** preliminary Wu N+1 quantization, transparency filtering, border-label mode and stable tie order previously embedded in `ColorDrawingPipeline.trace`. The latter calls this helper and retains its existing foreground quantization/cleanup/render path. Fully transparent pixels are excluded; any transparency disables opaque border-background selection, matching its previous behavior.

The texture adapter selects the background RGB with this shared rule after plotting, maps it to the supplied Brother palette with the same line/thread distance, and skips lines assigned to that background thread. It flushes the current run at each skipped line so foreground pieces cannot be joined across the deleted background. It neither removes background from the original raster nor regenerates/reorders original plotting lines. No new brightness/chroma/background heuristic is used.

`Result.backgroundThread` exposes the selected exclusion for diagnostics. The original plot result remains available in full. All downstream runs use the existing running/trim/jump/half_stitch processing and mandatory SewoutCheck/PES gates. Background suppression is in the adapter only; source `SketchLines`, its defaults and all frozen oracle text are unchanged.

## Synthetic before/after measurement

One 40mm picture frame at the measured normal pitch (88×88 pixels): black disk on lavender, white or transparent background. Both versions adapt the **same original plot result**; the before version omits only the new thread exclusion. The lavender 99.9mm regression additionally regenerates the original plot independently and asserts exact line-list equality.

| Background | Original plot lines | Stitches before | Stitches after | Excluded lines | Peak / 0.5mm | Sewout + PEC |
|---|---:|---:|---:|---:|---:|---|
| lavender | 1876 | 3594 | 2997 | 154 | 6 | PASS |
| white | 1486 | 2505 | 2505 | 0 | 6 | PASS |
| transparent | 1486 | 2447 | 2447 | 0 | 6 | PASS |

White needed no removal in this fixture because the original plotter already assigned no lines to its white background thread; transparency likewise adds no background thread. These controls remain unchanged rather than requiring an artificial stitch-count reduction. No conclusion about all photos/fabrics is inferred from this table. Raw measurements: [thread-texture-device-background.tsv](thread-texture-device-background.tsv).

Tests: `TextureDeviceRegressionTest`, `TextureRasterPolicyTest`, existing adapter/oracle suites and full `:engine:test`. The initial focused run exposed an incorrect expectation that white must always lose stitches; the corrected test requires no increase and no retained background-thread runs, while lavender must strictly remove lines/stitches.

Final verification: `./gradlew :engine:test` — 992 tests, 0 failures/errors, `BUILD SUCCESSFUL in 1m 29s`. Original SketchOracle 10/10, device regressions 5/5, raster policy 3/3. Log: `/tmp/myembro-texture-device-engine-test.log`.
