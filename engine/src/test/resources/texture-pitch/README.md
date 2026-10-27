# Measured synthetic texture raster inputs

`results.tsv`: 108 rows produced by `:engine:sweepThreadTextureRaster`, floor rounding, default plot/stitch settings and seed 0, on 2026-10-27. Inputs are repository-owned mathematical pictures from `TextureSyntheticFixtures`, no private or external image data.

Normal CI recomputes preset selection from this frozen table but never regenerates it. Re-run the explicit Gradle sweep for extended measurement. See `docs/research/thread-texture-raster-pitch.md` for the original command, limits and all failure cases. Timings are observations, not golden assertions.
