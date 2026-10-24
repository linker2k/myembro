# KRSAMP7 — 정민, Noto Sans KR Bold700, fill only

One black40wt thread; top/middle/bottom=8/10/12mm; no running outlines or stitched labels.
Rows begin at y=0/11/24mm. Physical size is preserved.

| Font | height mm | 정 min mm | 민 min mm | 정민 min mm | 정 P10 mm | 민 P10 mm |
|---|---:|---:|---:|---:|---:|---:|
| Bold700 | 8 | 0.008686 | 0.081210 | 0.008686 | 0.287855 | 0.417621 |
| Bold700 | 10 | 0.010017 | 0.084593 | 0.010017 | 0.347314 | 0.489000 |
| Bold700 | 12 | 0.011378 | 0.087010 | 0.011378 | 0.453155 | 0.493100 |
| Regular400 reference (not stitched) | 15 | 0.006116 | 0.092818 | 0.006116 | 0.413248 | 0.661053 |

Minimum=sampled interior Voronoi diameter; boundary sampling0.1mm, same method as KRSAMP1.
Corners/tips affect minima; these values do not certify a minimum stroke width. W unknown.
Regular400 at15mm is a controlled reference for S23. The exact S23 effective font variation
was not recorded, so identity with that physical file is unconfirmed. No private S23 file used.

KRSAMP7.PES bounds=(0,0)..(222,360) header=222x360 zero_motions=0 threads=1 stitches=1976 max_stitch_mm=4.1012193308819755
PASS /tmp/myembro-sewtest/kr7/KRSAMP7.PES within five-condition checks
REFERENCE_ONLY short_lt03_ratio: 0.150810 > 0.108930; body_short_lt03_ratio: 0.151116 > 0.064180; body_tiny_lt027_ratio: 0.139959 > 0.056993
PASS 1/1 cases passed

Reproduce (new, unsewn output directory):
```
python3 tools/make_kr_small_trial.py --output /tmp/myembro-sewtest/kr7
```
The bundled OFL Noto Sans KR variable font is instantiated at700/400 locally.
PES and instantiated fonts remain in /tmp and are not committed.
`./gradlew :engine:check --console=plain`: BUILD SUCCESSFUL in11s.
