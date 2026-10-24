# Korean tatami physical trial

2026-10-24: public bundled Noto Sans KR (OFL), fontTools static weight700.
No private design/font data is included. W, the acceptable minimum stroke width,
is **unconfirmed**: no production threshold or route was changed.

Reproduce: `python3 tools/make_kr_sewtest.py --output /tmp/myembro-sewtest/kr-new`
(requires local fontTools/Pillow and the existing pyembroidery environment).
The generator refuses existing trial names. Never reuse names already sewn.
Copy **only KRSAMP1.PES** to the machine; parts/ contains generation inputs.
The requested one-file trial exceptionally permits90×90mm, rather than60×60mm.
Fill15/20/25mm occupies the left column; matching running outlines the right.
The right25mm item is lower to avoid overlap while preserving physical sizes.
Labels in overview.png are reference annotations and are not stitched.

Final independent command:
```
/tmp/myembro-quality-gate/quality-gate check --style fill --hoop-bounds-mm 0 0 100 100 --json /tmp/myembro-sewtest/kr/sampler-gate.json /tmp/myembro-sewtest/kr/KRSAMP1.PES
```
Output: `PASS 1/1 cases passed`. Encoded bounds(0,0)..(899,899),
PEC header899×899 (0.1mm units), zero motions0, one thread,5357 stitches.
Thus size89.9×89.9mm fits90mm and the100mm hoop with more than2mm size allowance.
Parts plus routing comparisons independently passed9/9. Short-stitch ratios are
reference metrics, not blocking conditions. Final acceptance requires user sewout.

JTS1.20 interior Voronoi vertices of boundary samples at0.1mm spacing measure
2×distance to continuous ink boundary. Sampled minima are corner/tip-sensitive,
not a certified stroke minimum. For15/20/25mm, minima are approximately
0.014/0.017/0.021mm; P10 widths0.578/0.700/0.896mm. W remains unknown.
JUnit StrokeWidthMeasurementTest checks rectangle widths, scaling, holes, empties.

Ink/Stitch v3.3.0 `lib/elements/fill_stitch.py` at
b0edd96311ece82ee48816dc93466274b12c2a9c supplies the comparison routing:
previous endpoint carried forward, polygon ordering from that endpoint, next
polygon nearest-point target, default underpath enabled; trim between characters.
Actual app baseline jumps7/9/9 become3/4/4 at15/20/25mm.
However sewn connections outside ink remain: counts7/4/8 become18/11/9;
lengths15.019/8.842/13.330mm become9.151/8.417/9.179mm.
Therefore reduced jump counts alone do not establish reduced visible thread.
Comparison is experimental, not automatically enabled in production.

Validation: `./gradlew check` BUILD SUCCESSFUL43s;
subsequent `:engine:check` BUILD SUCCESSFUL10s after trial instrumentation.
Machine-readable measurements, placement and independent final gate output are
in [measurements/kr-tatami-trial](measurements/kr-tatami-trial/).
