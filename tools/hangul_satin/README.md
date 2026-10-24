# Explicit Korean centerline satin trial

This is a local trial runner, not the app's automatic lettering method.
It never executes Ink/Stitch or AutoTrace native code.

1. `python3 tools/hangul_satin/prepare-raster.py`
   reads the bundled OFL Noto Sans KR variable font, sets weight **400** explicitly,
   and writes the requested `정민` ink-height **15mm** raster and source hashes.
2. `JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSample`
   runs the existing Kotlin thinning and pixel-centerline ports. Default `trace`
   mode writes only `KRSAT1-centerline.{json,png}` and creates **no PES**.
3. After the trial's explicit constant stroke width is supplied, run the same task
   with `-PstrokeWidthMm=WIDTH_IN_MM`. There is intentionally no guessed default.
   The runner uses the existing Ink/Stitch `StrokeToSatin` and `SatinColumn` ports,
   the S1 half_stitch/P4 pipeline and the default PES P1–P8 checks.
   Width is not an automatic classification or a measured/validated font range.
4. Successful generation writes `/tmp/myembro-sewtest/krs/KRSAT1.PES`, `.png` and
   `.json`. The PNG is drawn from decoded PES. Checks require one black 40wt
   thread, <=60x60mm, origin (0,0), no zero needle/jump, exact header bounds and
   the normal output safety gate. No check is relaxed. Existing PES names are
   rejected; specify `-PhangulOutput=/tmp/.../NEWNAME` for subsequent trials.

Curve contract: the original ordered pixel paths are valid explicit LineString
inputs for convert-to-satin; no invented Bezier fitting/smoothing is applied.
Their Cartesian y coordinates are converted to the existing SVG y-down units.
This is not a claim that AutoTrace `fit.c` spline output is reproduced.

Width contract: AutoTrace's optional distance map gives local radius values;
`fit.c` applies neighborhood maxima and stores a weighted z coordinate. Its
`output-svg.c` does not serialize a per-path constant stroke-width. Ink/Stitch
`convert_path_to_satin` instead requires an explicit **constant** stroke_width.
No original conversion between those two widths has been established here, so
mean/median/minimum width is not substituted. The caller must specify the trial
width. See `docs/hangul-centerline.md` for source commits and pending validation.
