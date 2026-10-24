# Korean centerline satin trial — KRSAT1

Explicit local trial, not the app's automatic lettering method. Ink/Stitch source is never
executed. For this trial only, the unmodified AutoTrace C reference supplies the original
**fit** output; its Kotlin fit port is not completed or bundled into the app.

```sh
python3 tools/hangul_satin/prepare-raster.py
python3 tools/hangul_satin/original-fit.py
JAVA_HOME=/home/gosh2/.jdks/jdk-17.0.20.1+1 ./gradlew :engine:hangulSatinSample -PstrokeWidthMm=1.13
/tmp/myembro-quality-gate/quality-gate check --text --style satin --json /tmp/myembro-sewtest/krs/KRSAT1-gate.json /tmp/myembro-sewtest/krs/KRSAT1.PES
```

The raster is bundled OFL Noto Sans KR variable font, explicitly weight **400**, text
`정민`, ink-height **15mm**. Width **1.13mm** is the coordinator's approved constant,
based on model GlyphStrokeWidths medians: 정1.126mm, 민1.125mm. No mean/median rule,
automatic satin/fill selection, or substitute smoothing algorithm is implemented.

`original-fit.py` verifies the clean source commit and uses the existing local C compiler,
GLib headers/runtime. It installs nothing. AutoTrace thin-image → pxl-outline →
`fit.c:fitted_splines` run unchanged, `new_fitting_opts` defaults plus `centerline=TRUE`,
`preserve_width=FALSE` (constant width is separately specified). Two executions must be
byte identical. Source/tool/binary/input/output hashes and build command are recorded in
`fit-provenance.json`. Noto trial inputs and outputs stay in /tmp, never test resources.

Kotlin reads the original line/cubic controls, follows output-svg.c's float y=height-y
and six-significant-digit C `%g` serialization, then uses the existing `SvgPath.flatten`
0.1 SVG-pixel tolerance (same as the font parser). This is curve subdivision, not smoothing.
It reuses StrokeToSatin → SatinColumn → S1 half_stitch/P4 → default PES writer.
Stale/modified fit inputs are rejected by hashes. Without fit.json, only trace mode is
allowed; no pixel-staircase PES fallback exists.

The writer requires P1–P8. The runner also checks a single black 40wt thread, <=60×60mm,
origin(0,0), no zero needle/jump, and exact header extents. Existing output PES names are
rejected: use `-PhangulOutput=/tmp/.../NEWNAME` for the next physical trial.

Outputs: `/tmp/myembro-sewtest/krs/KRSAT1.{PES,png,json}`, gate JSON/text, original
fit/provenance and fitted-centerline PNG. The main PNG is rendered from **decoded PES**.
The requested font ink-height is15mm; actual needle extents are26.8×14.4mm and are
reported separately. Automated gate PASS is not user sewout acceptance.
